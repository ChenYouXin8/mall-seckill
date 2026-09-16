package com.example.seckill.redis;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Lua 脚本加载器（文档 7.2/7.4：扣减与释放锁必须走 Lua，禁止 GET+DECR / 直接 DEL）。
 */
@Slf4j
@Component
public class LuaScripts {

    /** 库存原子扣减（文档 7.2 原样）：KEYS[1]=stock key, ARGV[1]=扣减数量(1)。返回 1成功/0库存不足/-1不存在 */
    public static final String STOCK_DEDUCT_LUA =
            "if redis.call('exists', KEYS[1]) == 0 then\n" +
            "    return -1\n" +
            "end\n" +
            "local stock = tonumber(redis.call('get', KEYS[1]))\n" +
            "if stock < tonumber(ARGV[1]) then\n" +
            "    return 0\n" +
            "end\n" +
            "redis.call('decrby', KEYS[1], ARGV[1])\n" +
            "return 1";

    /** 分布式锁释放（文档 7.4：比对 token 才删，防止误删他人锁）：KEYS[1]=lock key, ARGV[1]=token。返回 1成功/0不匹配 */
    public static final String LOCK_RELEASE_LUA =
            "if redis.call('get', KEYS[1]) == ARGV[1] then\n" +
            "    return redis.call('del', KEYS[1])\n" +
            "end\n" +
            "return 0";

    /** 库存回补（回滚扣减/超时回补）：KEYS[1]=stock key。返回 1成功/0不存在 */
    public static final String STOCK_RESTORE_LUA =
            "if redis.call('exists', KEYS[1]) == 0 then\n" +
            "    return 0\n" +
            "end\n" +
            "redis.call('incrby', KEYS[1], 1)\n" +
            "return 1";

    /**
     * 令牌桶限流（文档 7.7 第二层：应用内按活动维度限流，自定义 Lua 实现）。
     * KEYS[1]=rate key（Hash: tokens/last），ARGV[1]=capacity，ARGV[2]=rate/s，ARGV[3]=now(ms)。
     * 返回 1=放行（扣 1 个 token）/ 0=限流。
     */
    public static final String RATE_LIMIT_LUA =
            "local t = redis.call('hget', KEYS[1], 'tokens')\n" +
            "local l = redis.call('hget', KEYS[1], 'last')\n" +
            "local capacity = tonumber(ARGV[1])\n" +
            "if t == false then\n" +
            "    t = capacity\n" +
            "    l = tonumber(ARGV[3])\n" +
            "else\n" +
            "    t = tonumber(t)\n" +
            "    l = tonumber(l)\n" +
            "end\n" +
            "local elapsed = tonumber(ARGV[3]) - l\n" +
            "if elapsed > 0 then\n" +
            "    local refill = elapsed * tonumber(ARGV[2]) / 1000\n" +
            "    if refill > 0 then\n" +
            "        t = math.min(capacity, t + refill)\n" +
            "        l = tonumber(ARGV[3])\n" +
            "    end\n" +
            "end\n" +
            "if t >= 1 then\n" +
            "    redis.call('hset', KEYS[1], 'tokens', t - 1, 'last', l)\n" +
            "    return 1\n" +
            "end\n" +
            "redis.call('hset', KEYS[1], 'tokens', t, 'last', l)\n" +
            "return 0";

    private DefaultRedisScript<Long> stockDeductScript;
    private DefaultRedisScript<Long> lockReleaseScript;
    private DefaultRedisScript<Long> stockRestoreScript;
    private DefaultRedisScript<Long> rateLimitScript;

    @PostConstruct
    void init() {
        stockDeductScript = buildScript(STOCK_DEDUCT_LUA);
        lockReleaseScript = buildScript(LOCK_RELEASE_LUA);
        stockRestoreScript = buildScript(STOCK_RESTORE_LUA);
        rateLimitScript = buildScript(RATE_LIMIT_LUA);
        log.info("Lua 脚本加载完成：库存扣减/释放锁/库存回补/令牌桶限流");
    }

    private DefaultRedisScript<Long> buildScript(String lua) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(lua);
        script.setResultType(Long.class);
        return script;
    }

    public DefaultRedisScript<Long> stockDeduct() {
        return stockDeductScript;
    }

    public DefaultRedisScript<Long> lockRelease() {
        return lockReleaseScript;
    }

    public DefaultRedisScript<Long> stockRestore() {
        return stockRestoreScript;
    }

    public DefaultRedisScript<Long> rateLimit() {
        return rateLimitScript;
    }
}
