package com.example.seckill.mq;

import com.example.seckill.config.RabbitConfig;
import com.example.seckill.config.SeckillMetrics;
import com.example.seckill.redis.RedisStockService;
import com.example.seckill.util.SnowflakeIdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 秒杀订单消息生产者（文档 7.5）：
 * - publisher confirms（配置 correlated）
 * - 发送失败重试 3 次（指数退避），仍失败 → 回滚 Redis 库存 + 记录失败日志
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillProducer {

    private final RabbitTemplate rabbitTemplate;
    private final RedisStockService redisStockService;
    private final SeckillMetrics metrics;
    private final SnowflakeIdWorker snowflakeIdWorker;

    @Value("${seckill.mq.confirm-timeout-ms:3000}")
    private long confirmTimeoutMs;

    /**
     * 发送下单消息。
     *
     * @return true=发送成功（broker 确认）；false=重试 3 次仍失败（调用方需回滚 Redis 库存）
     */
    public boolean send(OrderMessage message) {
        Exception lastError = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                sendOnce(message);
                return true;
            } catch (Exception e) {
                lastError = e;
                log.warn("MQ 发送失败第 {} 次: orderNo={}, err={}", attempt, message.getOrderNo(), e.getMessage());
                if (attempt < 3) {
                    try {
                        // 指数退避：500ms / 1000ms
                        TimeUnit.MILLISECONDS.sleep(500L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        log.error("MQ 发送重试 3 次仍失败，需回滚 Redis 库存: orderNo={}", message.getOrderNo(), lastError);
        return false;
    }

    private void sendOnce(OrderMessage message) throws Exception {
        String correlationId = UUID.randomUUID().toString();
        CorrelationData correlationData = new CorrelationData(correlationId);
        // 使用 CompletableFuture 等待 broker confirm（correlated 模式）
        CompletableFuture<CorrelationData.Confirm> future = new CompletableFuture<>();
        correlationData.getFuture().whenComplete((confirm, ex) -> {
            if (ex != null) {
                future.completeExceptionally(ex);
            } else {
                future.complete(confirm);
            }
        });

        rabbitTemplate.convertAndSend(
                RabbitConfig.ORDER_EXCHANGE,
                RabbitConfig.ORDER_ROUTING_KEY,
                message,
                correlationData);

        CorrelationData.Confirm confirm;
        try {
            confirm = future.get(confirmTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new AmqpException("MQ confirm 超时", e);
        }
        if (confirm == null || !confirm.isAck()) {
            throw new AmqpException("MQ broker 返回 nack");
        }
    }

    /**
     * 发送失败时回滚 Redis 库存（INCRBY 加回，文档 7.4/7.5）。
     */
    public void rollbackRedisStock(Long activityId, Long productId) {
        long restored = redisStockService.restore(activityId, productId);
        metrics.restoreCount.incrementAndGet();
        log.warn("MQ 发送失败，已回滚 Redis 库存: activityId={}, productId={}, 回补={}",
                activityId, productId, restored);
    }
}
