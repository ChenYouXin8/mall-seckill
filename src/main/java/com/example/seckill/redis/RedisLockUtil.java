package com.example.seckill.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 分布式锁（文档 7.4 / ADR-4）：
 * - 加锁：SET key token NX PX 3000（原子，禁止 SETNX+EXPIRE 两步）
 * - 释放：Lua 比对 token 才删（防止误删他人锁）
 */
@Component
@RequiredArgsConstructor
public class RedisLockUtil {

    public static final String LOCK_KEY_PREFIX = "lock:seckill:";

    private final StringRedisTemplate redisTemplate;
    private final LuaScripts luaScripts;

    /**
     * 加锁。
     *
     * @param key  锁 key（业务前缀+活动+用户）
     * @param ttlMs 过期毫秒
     * @return token（成功时非空），失败返回 null
     */
    public String lock(String key, long ttlMs) {
        String token = UUID.randomUUID().toString().replace("-", "");
        Boolean ok = redisTemplate.opsForValue()
                .setIfAbsent(key, token, ttlMs, TimeUnit.MILLISECONDS);
        return Boolean.TRUE.equals(ok) ? token : null;
    }

    /**
     * 释放锁（Lua 比对 token）。
     *
     * @return 1=成功释放，0=token 不匹配或锁不存在
     */
    public long unlock(String key, String token) {
        DefaultRedisScript<Long> script = luaScripts.lockRelease();
        Long result = redisTemplate.execute(script, Collections.singletonList(key), token);
        return result == null ? 0L : result;
    }

    public static String userLockKey(Long activityId, Long userId) {
        return LOCK_KEY_PREFIX + activityId + ":" + userId;
    }
}
