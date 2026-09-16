package com.example.seckill.util;

import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Enumeration;

/**
 * 雪花算法 ID 生成器（文档 9.1 目录中 util/SnowflakeIdWorker）。
 * 64 位：1bit 符号位 + 41bit 毫秒时间戳 + 10bit 机器位 + 12bit 序列号。
 */
public class SnowflakeIdWorker {

    /** 起始时间戳：2024-01-01 00:00:00 UTC */
    private static final long EPOCH = 1704067200000L;
    private static final long SEQUENCE_BITS = 12L;
    private static final long WORKER_BITS = 10L;
    private static final long SEQUENCE_MASK = (1L << SEQUENCE_BITS) - 1;
    private static final long WORKER_SHIFT = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_BITS;

    private final long workerId;
    private long sequence = 0L;
    private long lastTimestamp = -1L;

    public SnowflakeIdWorker(long workerId) {
        if (workerId < 0 || workerId > (1L << WORKER_BITS) - 1) {
            throw new IllegalArgumentException("workerId 超出范围: " + workerId);
        }
        this.workerId = workerId;
    }

    public SnowflakeIdWorker() {
        this.workerId = resolveWorkerId();
    }

    /**
     * 从本机网卡 MAC 派生 10bit workerId，单机部署固定即可。
     */
    private static long resolveWorkerId() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                byte[] mac = ni.getHardwareAddress();
                if (mac != null && mac.length > 0) {
                    long hash = 0;
                    for (byte b : mac) {
                        hash = (hash << 8) ^ (b & 0xFF);
                    }
                    return Math.abs(hash) % (1L << WORKER_BITS);
                }
            }
        } catch (Exception ignored) {
            // 回退到随机 workerId
        }
        return new SecureRandom().nextInt(1 << WORKER_BITS);
    }

    public synchronized long nextId() {
        long timestamp = Instant.now().toEpochMilli();
        if (timestamp < lastTimestamp) {
            // 时钟回拨：等待追上（单机演示场景足够）
            long offset = lastTimestamp - timestamp;
            if (offset > 5000) {
                throw new IllegalStateException("时钟回拨过大: " + offset + "ms");
            }
            while (timestamp < lastTimestamp) {
                timestamp = Instant.now().toEpochMilli();
            }
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                timestamp = waitNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;
        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                | (workerId << WORKER_SHIFT)
                | sequence;
    }

    private long waitNextMillis(long lastTimestamp) {
        long timestamp = Instant.now().toEpochMilli();
        while (timestamp <= lastTimestamp) {
            timestamp = Instant.now().toEpochMilli();
        }
        return timestamp;
    }
}
