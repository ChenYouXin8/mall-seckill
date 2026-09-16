package com.example.seckill.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.seckill.config.RabbitConfig;
import com.example.seckill.entity.Activity;
import com.example.seckill.entity.Order;
import com.example.seckill.entity.Stock;
import com.example.seckill.mapper.ActivityMapper;
import com.example.seckill.mapper.OrderMapper;
import com.example.seckill.mapper.StockMapper;
import com.example.seckill.redis.RedisStockService;
import com.example.seckill.service.OrderService;
import com.example.seckill.util.SnowflakeIdWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * M3 测试（文档 10.1 / 7.8）：超时订单关闭 + 库存回补正确。
 * 制造 1 条超时订单 → 执行回补任务 → 订单关闭、Redis 与 DB 库存各 +1。
 */
@SpringBootTest
class OrderExpireTaskTest {

    @Autowired
    private OrderExpireTask orderExpireTask;
    @Autowired
    private ActivityMapper activityMapper;
    @Autowired
    private StockMapper stockMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderService orderService;
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
     * 文档 7.8 验收：制造 1 条超时订单（expire_time 已过），任务执行后：
     * 订单 status=3、DB 库存 +1、Redis 库存 +1。
     */
    @Test
    void expiredOrderClosedAndStockRestored() throws InterruptedException {
        // 准备：活动 + 库存 5 + 预热 + 一条待支付且已超时的订单
        long activityId = snowflake.nextId();
        Activity activity = new Activity();
        activity.setId(activityId);
        activity.setTitle("M3-EXPIRE-" + activityId);
        activity.setStartTime(LocalDateTime.now().minusHours(1));
        activity.setEndTime(LocalDateTime.now().plusHours(1));
        activity.setStatus(1);
        activityMapper.insert(activity);

        Stock stock = new Stock();
        stock.setActivityId(activityId);
        stock.setProductId(1001L);
        stock.setTotalStock(5);
        stock.setStock(5);
        stock.setVersion(0);
        stockMapper.insert(stock);

        redisStockService.preheat(activityId, 1001L, 5);

        // 模拟一条已扣库存的超时订单（DB 库存 5→4，Redis 5→4）
        Order order = new Order();
        order.setId(snowflake.nextId());
        order.setOrderNo(String.valueOf(order.getId()));
        order.setUserId(700001L);
        order.setActivityId(activityId);
        order.setProductId(1001L);
        order.setStatus(0);
        order.setExpireTime(LocalDateTime.now().minusMinutes(1)); // 已超时
        orderService.tryCreateOrder(order);
        stockMapper.deductStock(activityId, 1001L);
        redisStockService.deduct(activityId, 1001L);

        createdActivities.add(activityId);

        // 执行回补任务（等待消费者不干扰：此订单不经 MQ）
        orderExpireTask.closeExpiredOrders();
        // 任务内 handleCompensate 也执行，无需等待；sleep 供可能的重试路径
        Thread.sleep(500);

        // 断言：订单已关闭
        Order after = orderMapper.selectById(order.getId());
        assertEquals(3, after.getStatus(), "超时订单应关闭为 status=3");
        // DB 库存回补 4→5
        Stock afterStock = stockMapper.selectOne(new LambdaQueryWrapper<Stock>()
                .eq(Stock::getActivityId, activityId).eq(Stock::getProductId, 1001L));
        assertEquals(5, afterStock.getStock(), "DB 库存应回补为 5");
        // Redis 库存回补 4→5
        assertEquals("5", redisStockService.remaining(activityId, 1001L), "Redis 库存应回补为 5");
    }
}
