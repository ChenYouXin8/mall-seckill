package com.example.seckill.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Redis 库存服务（文档 7.1 预热 / 7.2 Lua 扣减 / 3.3 Key 设计）。
 * Redis 是流量入口闸门，DB 是最终事实（文档 7.3）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisStockService {

    public static final String STOCK_KEY_PREFIX = "seckill:stock:";
    public static final String BOUGHT_KEY_PREFIX = "seckill:bought:";
    public static final String RESULT_KEY_PREFIX = "seckill:result:";
    public static final String COMPENSATE_KEY_PREFIX = "seckill:compensate:";

    private final StringRedisTemplate redisTemplate;
    private final LuaScripts luaScripts;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 预热库存（F2）：SET NX 幂等——仅当 key 不存在时写入 totalStock，重复预热不翻倍、不覆盖进行中的扣减。
     *
     * @return true=本次写入成功；false=已存在（跳过）
     */
    public boolean preheat(Long activityId, Long productId, int totalStock) {
        String key = stockKey(activityId, productId);
        Boolean ok = redisTemplate.opsForValue().setIfAbsent(key, String.valueOf(totalStock));
        log.info("预热库存: key={}, total={}, 写入={}", key, totalStock, Boolean.TRUE.equals(ok));
        return Boolean.TRUE.equals(ok);
    }

    /**
     * Lua 原子扣减（文档 7.2）。
     *
     * @return 1=成功 / 0=库存不足 / -1=未预热（key 不存在）
     */
    public long deduct(Long activityId, Long productId) {
        DefaultRedisScript<Long> script = luaScripts.stockDeduct();
        Long result = redisTemplate.execute(script, Collections.singletonList(stockKey(activityId, productId)), "1");
        return result == null ? -1L : result;
    }

    /**
     * 回滚扣减 / 超时回补（INCRBY +1）。
     */
    public long restore(Long activityId, Long productId) {
        DefaultRedisScript<Long> script = luaScripts.stockRestore();
        Long result = redisTemplate.execute(script, Collections.singletonList(stockKey(activityId, productId)));
        return result == null ? 0L : result;
    }

    /**
     * 已购标记（幂等前置检查，TTL=活动结束由调用方控制）。
     */
    public void markBought(Long activityId, Long userId) {
        String key = boughtKey(activityId, userId);
        redisTemplate.opsForValue().set(key, "1");
    }

    public boolean hasBought(Long activityId, Long userId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(boughtKey(activityId, userId)));
    }

    public String stockKey(Long activityId, Long productId) {
        return STOCK_KEY_PREFIX + activityId + ":" + productId;
    }

    public String boughtKey(Long activityId, Long userId) {
        return BOUGHT_KEY_PREFIX + activityId + ":" + userId;
    }

    /**
     * 剩余库存（验收用）。
     */
    public String remaining(Long activityId, Long productId) {
        return redisTemplate.opsForValue().get(stockKey(activityId, productId));
    }

    /**
     * 设置已购标记过期时间（活动结束时清理）。
     */
    public void expireBought(Long activityId, Long userId, long ttlSeconds) {
        redisTemplate.expire(boughtKey(activityId, userId), ttlSeconds, TimeUnit.SECONDS);
    }

    /**
     * 写下单结果缓存（文档 3.3：seckill:result:{orderNo}，TTL 10min）。
     */
    public void writeResult(String orderNo, Object data) {
        try {
            redisTemplate.opsForValue().set(RESULT_KEY_PREFIX + orderNo,
                    objectMapper.writeValueAsString(data), 10, TimeUnit.MINUTES);
        } catch (JsonProcessingException e) {
            log.error("结果缓存序列化失败: orderNo={}", orderNo, e);
        }
    }

    /**
     * 读下单结果缓存（轮询查询用，文档 7.6：查询量小，缓存穿透回 DB 可接受）。
     */
    public String readResult(String orderNo) {
        return redisTemplate.opsForValue().get(RESULT_KEY_PREFIX + orderNo);
    }

    /**
     * 删除结果缓存（缓存一致性：先更新 DB 再删缓存，文档 7.6）。
     */
    public void deleteResult(String orderNo) {
        redisTemplate.delete(RESULT_KEY_PREFIX + orderNo);
    }

    /**
     * 补偿计数 +1（超时回补时 Redis 失败，记录待补偿数量，文档 7.8）。
     */
    public void incrCompensate(Long activityId, Long productId) {
        redisTemplate.opsForValue().increment(COMPENSATE_KEY_PREFIX + activityId + ":" + productId);
    }

    /**
     * 取走并清空补偿计数（返回待回补数量）。
     */
    public long drainCompensate(Long activityId, Long productId) {
        String key = COMPENSATE_KEY_PREFIX + activityId + ":" + productId;
        String val = redisTemplate.opsForValue().get(key);
        if (val == null) {
            return 0;
        }
        redisTemplate.delete(key);
        try {
            return Long.parseLong(val);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
