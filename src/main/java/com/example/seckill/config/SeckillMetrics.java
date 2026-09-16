package com.example.seckill.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 可观测性埋点（文档 7.9）：MVP 用 AtomicLong 计数 + 日志输出，不引入外部监控。
 */
@Slf4j
@Component
public class SeckillMetrics {

    /** Lua 扣减成功计数 */
    public final AtomicLong deductSuccess = new AtomicLong();
    /** Lua 扣减失败（库存不足）计数 */
    public final AtomicLong deductFail = new AtomicLong();
    /** MQ 消费耗时累计（毫秒） */
    public final AtomicLong consumeCostMs = new AtomicLong();
    /** 消费次数 */
    public final AtomicLong consumeCount = new AtomicLong();
    /** 下单成功（落库）计数 */
    public final AtomicLong orderSuccess = new AtomicLong();
    /** 库存回补计数 */
    public final AtomicLong restoreCount = new AtomicLong();

    public void recordConsume(long costMs) {
        consumeCostMs.addAndGet(costMs);
        consumeCount.incrementAndGet();
    }

    /** 平均消费耗时（毫秒） */
    public double avgConsumeMs() {
        long n = consumeCount.get();
        return n == 0 ? 0 : consumeCostMs.get() / (double) n;
    }

    public void logSnapshot() {
        log.info("[SeckillMetrics] 扣减成功={}, 扣减失败={}, 下单成功={}, 回补={}, " +
                        "MQ消费次数={}, 平均消费耗时={}ms",
                deductSuccess.get(), deductFail.get(), orderSuccess.get(), restoreCount.get(),
                consumeCount.get(), String.format("%.2f", avgConsumeMs()));
    }
}
