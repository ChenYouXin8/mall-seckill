package com.example.seckill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.seckill.config.RabbitConfig;
import com.example.seckill.entity.Activity;
import com.example.seckill.entity.Order;
import com.example.seckill.entity.Stock;
import com.example.seckill.mapper.ActivityMapper;
import com.example.seckill.mapper.OrderMapper;
import com.example.seckill.mapper.StockMapper;
import com.example.seckill.redis.RedisStockService;
import com.example.seckill.util.SnowflakeIdWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3 削峰验证（文档 7.5 验收）：
 * 2000 并发抢 1000 件库存，入口瞬时打满，MQ 异步消费落库。
 * 记录 DB 侧最大瞬时写入速率（削峰后），验证削峰比与最终一致性。
 */
@SpringBootTest
class PeakingTest {

    private static final Logger log = LoggerFactory.getLogger(PeakingTest.class);

    @Autowired
    private SeckillService seckillService;
    @Autowired
    private ActivityMapper activityMapper;
    @Autowired
    private StockMapper stockMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private RedisStockService redisStockService;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private RabbitTemplate rabbitTemplate;

    private final SnowflakeIdWorker snowflake = new SnowflakeIdWorker();
    private final List<Long> createdActivities = new ArrayList<>();

    @AfterEach
    void cleanup() {
        // 支持保留数据排查：-Dseckill.keep.data=true
        if (Boolean.getBoolean("seckill.keep.data")) {
            return;
        }
        try {
            TimeUnit.SECONDS.sleep(3);
        } catch (InterruptedException ignored) {
        }
        for (Long activityId : createdActivities) {
            orderMapper.delete(new LambdaQueryWrapper<Order>().eq(Order::getActivityId, activityId));
            stockMapper.delete(new LambdaQueryWrapper<Stock>().eq(Stock::getActivityId, activityId));
            activityMapper.deleteById(activityId);
            redisTemplate.delete(redisStockService.stockKey(activityId, 1001L));
        }
        RabbitAdmin admin = new RabbitAdmin(rabbitTemplate);
        admin.purgeQueue(RabbitConfig.ORDER_QUEUE, false);
        admin.purgeQueue(RabbitConfig.ORDER_DLQ, false);
        createdActivities.clear();
    }

    /**
     * 文档 7.5 验收：入口 2000 并发（500 并发池），DB 侧最大瞬时写入峰值应远低于入口（削峰）。
     */
    @Test
    void asyncConsumeShavesPeak() throws Exception {
        int totalStock = 1000;
        int totalRequests = 2000;
        long activityId = snowflake.nextId();

        Activity activity = new Activity();
        activity.setId(activityId);
        activity.setTitle("M3-PEAK-" + activityId);
        activity.setStartTime(LocalDateTime.now().minusHours(1));
        activity.setEndTime(LocalDateTime.now().plusHours(1));
        activity.setStatus(1);
        activityMapper.insert(activity);

        Stock stock = new Stock();
        stock.setActivityId(activityId);
        stock.setProductId(1001L);
        stock.setTotalStock(totalStock);
        stock.setStock(totalStock);
        stock.setVersion(0);
        stockMapper.insert(stock);
        redisStockService.preheat(activityId, 1001L, totalStock);
        createdActivities.add(activityId);

        // 统计线程：每 200ms 采样 DB 订单数，计算每秒写入峰值
        AtomicLong peakPerSecond = new AtomicLong();
        AtomicLong lastCount = new AtomicLong();
        Thread sampler = new Thread(() -> {
            long prev = 0;
            long prevTime = System.nanoTime();
            while (true) {
                Long count = orderMapper.selectCount(new LambdaQueryWrapper<Order>()
                        .eq(Order::getActivityId, activityId));
                long cur = count == null ? 0 : count;
                long now = System.nanoTime();
                double seconds = (now - prevTime) / 1_000_000_000.0;
                if (seconds > 0) {
                    long rate = (long) ((cur - prev) / seconds);
                    peakPerSecond.accumulateAndGet(rate, Math::max);
                }
                prev = cur;
                prevTime = now;
                if (cur >= totalStock) {
                    lastCount.set(cur);
                    return;
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
        sampler.setDaemon(true);
        sampler.start();

        // 入口：2000 并发（500 并发池）
        long entryStart = System.currentTimeMillis();
        ExecutorService pool = Executors.newFixedThreadPool(500);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int i = 0; i < totalRequests; i++) {
            long userId = 8000000L + i;
            pool.submit(() -> {
                Map<String, Object> r = seckillService.seckill(activityId, 1001L, userId);
                if ((int) r.get("code") == 0) {
                    accepted.incrementAndGet();
                } else {
                    rejected.incrementAndGet();
                }
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(120, TimeUnit.SECONDS), "入口请求超时");
        long entryCostMs = System.currentTimeMillis() - entryStart;

        // 等待消费者落库完成
        sampler.join(120_000);
        long dbFinal = orderMapper.selectCount(new LambdaQueryWrapper<Order>()
                .eq(Order::getActivityId, activityId));
        // 诊断：队列积压与 Redis 余量
        Long queueDepth = null;
        try {
            RabbitAdmin admin = new RabbitAdmin(rabbitTemplate);
            queueDepth = (long) admin.getQueueInfo(RabbitConfig.ORDER_QUEUE).getMessageCount();
        } catch (Exception e) {
            log.warn("查询队列深度失败: {}", e.getMessage());
        }
        String redisRemaining = redisStockService.remaining(activityId, 1001L);

        long entryRate = totalRequests * 1000L / Math.max(entryCostMs, 1);
        log.info("========== 削峰数据 ==========");
        log.info("入口: {} 请求, 耗时 {}ms, 入口平均速率约 {} req/s", totalRequests, entryCostMs, entryRate);
        log.info("DB: 最终订单 {} 条, 峰值写入速率 {} 条/s, 削峰比约 {}:1",
                dbFinal, peakPerSecond.get(), Math.max(1, entryRate / Math.max(peakPerSecond.get(), 1)));
        log.info("队列积压: {}, Redis 余量: {}, 入口 accepted: {}", queueDepth, redisRemaining, accepted.get());

        // 诊断明细写文件（绕过 Maven 日志截断）
        Long dupOrderNo = orderMapper.selectCount(
                new QueryWrapper<Order>().select("order_no").groupBy("order_no").having("count(*)>1")
                        .eq("activity_id", activityId));
        Long dupUser = orderMapper.selectCount(
                new QueryWrapper<Order>().select("user_id").groupBy("user_id").having("count(*)>1")
                        .eq("activity_id", activityId));
        String diagnose = "activityId=" + activityId
                + "\ndbFinal=" + dbFinal + "\nqueueDepth=" + queueDepth
                + "\nredisRemaining=" + redisRemaining + "\naccepted=" + accepted.get()
                + "\ndupOrderNo=" + dupOrderNo + "\ndupUser=" + dupUser + "\n";
        try {
            java.nio.file.Files.writeString(
                    java.nio.file.Path.of("E:\\project\\mall-seckill\\peak-diagnose.txt"),
                    diagnose, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ex) {
            log.warn("写诊断文件失败: {}", ex.getMessage());
        }

        assertEquals(totalStock, dbFinal, "最终订单数必须等于总库存（不超卖）");
        assertEquals("0", redisStockService.remaining(activityId, 1001L), "Redis 余量必须为 0");
        // 削峰断言：DB 峰值写入 < 1000/s（文档 7.5 示例值），且明显低于入口速率
        assertTrue(peakPerSecond.get() < 1000,
                "DB 峰值写入速率应 < 1000/s，实际=" + peakPerSecond.get());
        assertTrue(entryRate > peakPerSecond.get(),
                "入口速率应高于 DB 写入速率（削峰生效）");
    }
}
