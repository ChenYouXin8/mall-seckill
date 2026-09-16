package com.example.seckill.redis;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2 单元测试（文档 10.1）：
 * - LuaScripts：脚本可加载；扣减/不足/不存在三分支（经 RedisStockService 验证）
 * - RedisLockUtil：加锁互斥；持锁者释放成功；他人释放失败
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RedisLockUtilTest {

    @Autowired
    private RedisLockUtil redisLockUtil;
    @Autowired
    private RedisStockService redisStockService;
    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void lockIsExclusive() {
        String key = "lock:test:exclusive";
        String token1 = redisLockUtil.lock(key, 5000);
        assertNotNull(token1, "第一次加锁应成功");

        String token2 = redisLockUtil.lock(key, 5000);
        assertNull(token2, "持锁期间再次加锁应失败（互斥）");

        redisLockUtil.unlock(key, token1);
    }

    @Test
    void onlyOwnerCanRelease() {
        String key = "lock:test:release";
        String owner = redisLockUtil.lock(key, 5000);
        assertNotNull(owner);

        // 他人（错误 token）释放失败
        long wrong = redisLockUtil.unlock(key, "wrong-token");
        assertEquals(0, wrong, "错误 token 释放应失败");

        // 持锁者释放成功
        long right = redisLockUtil.unlock(key, owner);
        assertEquals(1, right, "持锁者释放应成功");

        // 释放后他人可加锁
        String again = redisLockUtil.lock(key, 5000);
        assertNotNull(again, "释放后应可重新加锁");
        redisLockUtil.unlock(key, again);
    }

    @Test
    void deductThreeBranches() {
        Long activityId = 9000001L;
        Long productId = 1001L;

        // 不存在（未预热）→ -1
        assertEquals(-1L, redisStockService.deduct(activityId, productId), "未预热应返回 -1");

        // 预热 5 件：首次写入、重复预热跳过（不翻倍）
        assertTrue(redisStockService.preheat(activityId, productId, 5), "首次预热应写入");
        assertFalse(redisStockService.preheat(activityId, productId, 5), "重复预热应跳过");
        assertEquals("5", redisStockService.remaining(activityId, productId), "预热后余量应为总库存");

        // 扣减 5 次成功，第 6 次不足
        for (int i = 0; i < 5; i++) {
            assertEquals(1L, redisStockService.deduct(activityId, productId), "第 " + (i + 1) + " 次扣减应成功");
        }
        assertEquals(0L, redisStockService.deduct(activityId, productId), "库存耗尽后应返回 0");
        assertEquals("0", redisStockService.remaining(activityId, productId), "余量应为 0");

        // 回补 1 件
        assertEquals(1L, redisStockService.restore(activityId, productId), "回补应成功");
        assertEquals("1", redisStockService.remaining(activityId, productId), "回补后余量应为 1");

        // 清理测试 key
        redisTemplate.delete(redisStockService.stockKey(activityId, productId));
    }
}
