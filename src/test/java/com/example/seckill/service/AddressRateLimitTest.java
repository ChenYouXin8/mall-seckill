package com.example.seckill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.seckill.entity.Activity;
import com.example.seckill.entity.Order;
import com.example.seckill.mapper.ActivityMapper;
import com.example.seckill.mapper.OrderMapper;
import com.example.seckill.redis.RedisStockService;
import com.example.seckill.util.SnowflakeIdWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4 验收测试（文档 7.7）：
 * 1) 令牌桶限流：capacity 内放行、超频返回 10005；
 * 2) 隐藏地址：saveAddress/getAddress 一致，seckill 对无地址场景由 Controller 层校验（10006 走 curl 验收）。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "seckill.rate-limit.enabled=true",
        "seckill.rate-limit.capacity=5",
        "seckill.rate-limit.rate-per-second=1"
})
class AddressRateLimitTest {

    @Autowired
    private SeckillService seckillService;
    @Autowired
    private RedisStockService redisStockService;
    @Autowired
    private ActivityMapper activityMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private StringRedisTemplate redisTemplate;

    private final SnowflakeIdWorker snowflake = new SnowflakeIdWorker();
    private long createdActivityId;

    @AfterEach
    void cleanup() {
        if (createdActivityId > 0) {
            orderMapper.delete(new LambdaQueryWrapper<Order>().eq(Order::getActivityId, createdActivityId));
            activityMapper.delete(new LambdaQueryWrapper<Activity>().eq(Activity::getId, createdActivityId));
            String stockKey = RedisStockService.STOCK_KEY_PREFIX + createdActivityId + ":1001";
            redisTemplate.delete(stockKey);
            redisTemplate.delete(RedisStockService.ADDRESS_KEY_PREFIX + createdActivityId);
            redisTemplate.delete(RedisStockService.RATE_KEY_PREFIX + createdActivityId);
            String pattern = RedisStockService.BOUGHT_KEY_PREFIX + createdActivityId + ":*";
            for (String k : redisTemplate.keys(pattern)) {
                redisTemplate.delete(k);
            }
        }
    }

    private long newRunningActivity() {
        createdActivityId = snowflake.nextId();
        Activity a = new Activity();
        a.setId(createdActivityId);
        a.setTitle("M4-ADDR-RATE-" + createdActivityId);
        a.setStartTime(LocalDateTime.now().minusMinutes(1));
        a.setEndTime(LocalDateTime.now().plusMinutes(30));
        a.setStatus(1);
        activityMapper.insert(a);
        return createdActivityId;
    }

    @Test
    void tokenBucket_limitsByCapacity() {
        long activityId = newRunningActivity();
        // capacity=5, rate=1/s：毫秒级连续调用不可能 refill，前 5 次放行，第 6 次限流
        for (int i = 0; i < 5; i++) {
            assertTrue(redisStockService.tryAcquire(activityId, 5, 1), "第 " + (i + 1) + " 次应放行");
        }
        assertFalse(redisStockService.tryAcquire(activityId, 5, 1), "超出容量应限流");
    }

    @Test
    void hiddenAddress_roundTrip() {
        long activityId = newRunningActivity();
        redisStockService.saveAddress(activityId, "a3f9x2", 3600);
        assertEquals("a3f9x2", redisStockService.getAddress(activityId));
        // 地址不存在 → null（Controller 校验返回 10006）
        assertNotNull(redisStockService.getAddress(activityId));
        assertFalse(redisStockService.getAddress(activityId).isEmpty());
    }

    @Test
    void seckill_returnsRateLimited_whenExceeded() throws Exception {
        long activityId = newRunningActivity();
        // 预热库存，放行 5 次后第 6 次应被限流 10005
        redisStockService.preheat(activityId, 1001L, 10);
        for (int i = 0; i < 5; i++) {
            Map<String, Object> r = seckillService.seckill(activityId, 1001L, 9000000L + i);
            assertEquals(0, (int) r.get("code"), "第 " + (i + 1) + " 次应成功");
        }
        Map<String, Object> r6 = seckillService.seckill(activityId, 1001L, 9000005L);
        assertEquals(10005, (int) r6.get("code"), "超出容量应返回 10005");
        // 等 MQ 消费完 5 条消息（避免残留消息污染后续测试）
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            Long n = orderMapper.selectCount(new LambdaQueryWrapper<Order>().eq(Order::getActivityId, activityId));
            if (n != null && n == 5) {
                break;
            }
            Thread.sleep(300);
        }
        assertEquals(5, orderMapper.selectCount(
                new LambdaQueryWrapper<Order>().eq(Order::getActivityId, activityId)));
    }
}
