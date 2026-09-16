package com.example.seckill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2/M3 验收测试（文档 7.2/10.1）：
 * 并发 5000 线程抢 10 件库存 → Lua 扣减成功恰 10 次、Redis 余量 0、MQ 消费后 DB 订单恰 10 条。
 * 说明：M4 起 SeckillService 内按活动维度令牌桶限流，本测试关注防超卖本身，故关闭限流。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {"seckill.rate-limit.enabled=false"})
class SeckillConcurrencyTest {

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

    private long newActivityWithStock(int total) {
        long activityId = snowflake.nextId();
        Activity activity = new Activity();
        activity.setId(activityId);
        activity.setTitle("M2-CONC-" + activityId);
        activity.setStartTime(LocalDateTime.now().minusHours(1));
        activity.setEndTime(LocalDateTime.now().plusHours(1));
        activity.setStatus(1);
        activityMapper.insert(activity);

        Stock stock = new Stock();
        stock.setActivityId(activityId);
        stock.setProductId(1001L);
        stock.setTotalStock(total);
        stock.setStock(total);
        stock.setVersion(0);
        stockMapper.insert(stock);

        redisStockService.preheat(activityId, 1001L, total);
        createdActivities.add(activityId);
        return activityId;
    }

    @AfterEach
    void cleanup() {
        // 先等待消费者消费完剩余消息，再清理，避免消息积压重新落库
        try {
            TimeUnit.SECONDS.sleep(5);
        } catch (InterruptedException ignored) {
        }
        for (Long activityId : createdActivities) {
            orderMapper.delete(new LambdaQueryWrapper<Order>().eq(Order::getActivityId, activityId));
            stockMapper.delete(new LambdaQueryWrapper<Stock>().eq(Stock::getActivityId, activityId));
            activityMapper.deleteById(activityId);
            redisTemplate.delete(redisStockService.stockKey(activityId, 1001L));
            for (int i = 0; i < 5000; i++) {
                redisTemplate.delete(redisStockService.boughtKey(activityId, 1000000L + i));
            }
        }
        // 清空队列，防止残留消息影响其他测试
        RabbitAdmin admin = new RabbitAdmin(rabbitTemplate);
        admin.purgeQueue(RabbitConfig.ORDER_QUEUE, false);
        admin.purgeQueue(RabbitConfig.ORDER_DLQ, false);
        createdActivities.clear();
    }

    /**
     * 文档 7.2 验收：并发 5000 线程抢 10 件库存。
     * 期望：成功恰 10（Lua 返回 1 恰 10 次）、库存不足 4990、Redis 余量 0、MQ 消费后 DB 订单恰 10。
     */
    @Test
    void concurrent5000Seckill10Stock() throws InterruptedException {
        long activityId = newActivityWithStock(10);
        int threads = 5000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger stockNotEnough = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        java.util.concurrent.ConcurrentHashMap<Long, Integer> callCount = new java.util.concurrent.ConcurrentHashMap<>();

        for (int i = 0; i < threads; i++) {
            long userId = 1000000L + i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    callCount.merge(userId, 1, Integer::sum);
                    Map<String, Object> r = seckillService.seckill(activityId, 1001L, userId);
                    int code = (int) r.get("code");
                    if (code == 0) {
                        success.incrementAndGet();
                    } else if (code == 10001) {
                        stockNotEnough.incrementAndGet();
                    } else {
                        other.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(ready.await(30, TimeUnit.SECONDS), "5000 线程未就绪");
        start.countDown();
        assertTrue(done.await(180, TimeUnit.SECONDS), "并发抢购超时");
        pool.shutdown();

        // 诊断：每个 userId 必须恰好调用 1 次（排除任务重复提交）
        assertEquals(threads, callCount.size(), "每个 userId 应恰好调用 1 次");

        // 入口层断言
        assertEquals(10, success.get(), "Lua 扣减成功次数必须恰为 10（不得超卖）");
        assertEquals(4990, stockNotEnough.get(), "其余必须全部为库存不足");
        assertEquals(0, other.get(), "不应有其他错误码");
        assertEquals("0", redisStockService.remaining(activityId, 1001L), "Redis 余量必须为 0");

        // 等待 MQ 消费者落库完成（最长 60s）
        long deadline = System.currentTimeMillis() + 60_000;
        Long orderCount = 0L;
        while (System.currentTimeMillis() < deadline) {
            orderCount = orderMapper.selectCount(new LambdaQueryWrapper<Order>()
                    .eq(Order::getActivityId, activityId));
            if (orderCount != null && orderCount == 10) {
                break;
            }
            TimeUnit.MILLISECONDS.sleep(200);
        }
        assertEquals(10L, orderCount, "MQ 消费后 DB 订单数必须恰为 10");
    }
}
