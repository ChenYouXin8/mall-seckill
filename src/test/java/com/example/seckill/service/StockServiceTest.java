package com.example.seckill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.seckill.entity.Activity;
import com.example.seckill.entity.Order;
import com.example.seckill.entity.Stock;
import com.example.seckill.mapper.ActivityMapper;
import com.example.seckill.mapper.OrderMapper;
import com.example.seckill.mapper.StockMapper;
import com.example.seckill.util.SnowflakeIdWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1 单元/集成测试（文档 10.1：StockService 乐观锁并发扣减不超卖、OrderService 唯一索引重复插入）。
 * 使用独立测试数据（activityId 取当前时间戳），测试后清理，不污染样例数据。
 */
@SpringBootTest
class StockServiceTest {

    @Autowired
    private StockService stockService;
    @Autowired
    private OrderService orderService;
    @Autowired
    private ActivityMapper activityMapper;
    @Autowired
    private StockMapper stockMapper;
    @Autowired
    private OrderMapper orderMapper;

    private final SnowflakeIdWorker snowflake = new SnowflakeIdWorker();
    private final List<Long> createdActivities = new ArrayList<>();

    private long newActivity(long total) {
        long activityId = snowflake.nextId();
        Activity activity = new Activity();
        activity.setId(activityId);
        activity.setTitle("M1-TEST-" + activityId);
        activity.setStartTime(LocalDateTime.now().minusHours(1));
        activity.setEndTime(LocalDateTime.now().plusHours(1));
        activity.setStatus(1);
        activityMapper.insert(activity);

        Stock stock = new Stock();
        stock.setActivityId(activityId);
        stock.setProductId(1001L);
        stock.setTotalStock((int) total);
        stock.setStock((int) total);
        stock.setVersion(0);
        stockMapper.insert(stock);

        createdActivities.add(activityId);
        return activityId;
    }

    @AfterEach
    void cleanup() {
        for (Long activityId : createdActivities) {
            orderMapper.delete(new LambdaQueryWrapper<Order>().eq(Order::getActivityId, activityId));
            stockMapper.delete(new LambdaQueryWrapper<Stock>().eq(Stock::getActivityId, activityId));
            activityMapper.deleteById(activityId);
        }
        createdActivities.clear();
    }

    /**
     * 10.1 用例：乐观锁并发扣减不超卖。
     * 10 件库存，20 线程并发各扣 1 次 → 成功恰为 10 次，最终 stock = 0。
     */
    @Test
    void concurrentDeductDoesNotOversell() throws InterruptedException {
        long activityId = newActivity(10);
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    if (stockService.deductStock(activityId, 1001L) == 1) {
                        success.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS), "线程未就绪");
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发扣减超时");
        pool.shutdown();

        Stock after = stockMapper.selectOne(new LambdaQueryWrapper<Stock>()
                .eq(Stock::getActivityId, activityId).eq(Stock::getProductId, 1001L));
        assertEquals(10, success.get(), "成功扣减数必须恰为总库存");
        assertEquals(0, after.getStock(), "最终剩余库存必须为 0，不得超卖");
    }

    /**
     * 10.1 用例：同一用户+场次重复插入被唯一索引拦截（转 10002 语义）。
     */
    @Test
    void duplicateInsertRejectedByUniqueIndex() {
        long activityId = newActivity(10);
        long userId = 900001L;

        Order first = buildOrder(activityId, userId);
        assertTrue(orderService.tryCreateOrder(first), "首次插入应成功");

        Order second = buildOrder(activityId, userId);
        assertFalse(orderService.tryCreateOrder(second), "重复插入应被唯一索引拦截");

        Long count = orderMapper.selectCount(new LambdaQueryWrapper<Order>()
                .eq(Order::getUserId, userId).eq(Order::getActivityId, activityId));
        assertEquals(1L, count, "同一用户+场次只允许 1 条订单");
    }

    private Order buildOrder(long activityId, long userId) {
        long id = snowflake.nextId();
        Order order = new Order();
        order.setId(id);
        order.setOrderNo(String.valueOf(id));
        order.setUserId(userId);
        order.setActivityId(activityId);
        order.setProductId(1001L);
        order.setStatus(0);
        order.setExpireTime(LocalDateTime.now().plusMinutes(2));
        return order;
    }
}
