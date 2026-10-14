package com.example.seckill.mq;

import com.example.seckill.config.RabbitConfig;
import com.example.seckill.config.SeckillMetrics;
import com.example.seckill.entity.Activity;
import com.example.seckill.entity.Order;
import com.example.seckill.redis.RedisStockService;
import com.example.seckill.service.OrderService;
import com.example.seckill.service.StockService;
import com.example.seckill.util.ErrorCode;
import com.example.seckill.util.SnowflakeIdWorker;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 秒杀订单消费者（文档 7.5）：
 * - 手动 ack；处理失败重投，超过 3 次进入死信队列 seckill.order.dlq
 * - 流程：幂等检查 → 插入订单（唯一索引，冲突回滚 Redis 库存并 ack）→ 乐观锁扣库存（失败取消订单写 FAILED）→ 写结果缓存
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderConsumer {

    private static final String RESULT_KEY_PREFIX = "seckill:result:";
    private static final int MAX_RETRY = 3;

    private final OrderService orderService;
    private final StockService stockService;
    private final RedisStockService redisStockService;
    private final SeckillMetrics metrics;
    private final SnowflakeIdWorker snowflakeIdWorker;

    @org.springframework.amqp.rabbit.annotation.RabbitListener(queues = RabbitConfig.ORDER_QUEUE)
    public void onMessage(OrderMessage message,
                          Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                          @Header(name = "x-death", required = false) List<Map<String, Object>> xDeath)
            throws IOException {
        long start = System.currentTimeMillis();
        try {
            log.info("消费者收到消息: orderNo={}, userId={}, activityId={}, productId={}, xDeath={}",
                    message.getOrderNo(), message.getUserId(), message.getActivityId(), message.getProductId(),
                    xDeath == null ? 0 : xDeath.size());
            process(message);
            channel.basicAck(deliveryTag, false);
            metrics.recordConsume(System.currentTimeMillis() - start);
        } catch (DuplicateOrderException e) {
            // 唯一索引冲突：区分消息重投与业务重复
            // - 该 orderNo 已落库（消息重投）→ 直接 ack，不回滚 Redis（库存已由首次处理扣减）
            // - 该 orderNo 未落库（业务重复，同用户同活动第二次抢购）→ 回滚 Redis 扣减并 ack
            Order existing = orderService.getByOrderNo(message.getOrderNo());
            if (existing != null) {
                log.warn("消费者消息重投(订单已存在 id={}), 直接 ack: orderNo={}", existing.getId(), message.getOrderNo());
            } else {
                redisStockService.restore(message.getActivityId(), message.getProductId());
                metrics.restoreCount.incrementAndGet();
                log.warn("消费者重复订单(DB无此orderNo)，回滚 Redis 库存并 ack: orderNo={}, userId={}", message.getOrderNo(), message.getUserId());
            }
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            int retries = xDeath == null ? 0 : xDeath.size();
            if (retries >= MAX_RETRY) {
                log.error("消费失败超过 {} 次，进入死信队列: orderNo={}, err={}", MAX_RETRY, message.getOrderNo(), e.getMessage(), e);
                channel.basicNack(deliveryTag, false, false);
            } else {
                log.warn("消费失败，重投(第 {} 次): orderNo={}, err={}", retries + 1, message.getOrderNo(), e.getMessage(), e);
                channel.basicNack(deliveryTag, false, true);
            }
        }
    }

    private void process(OrderMessage msg) {
        // 1. 插入订单（唯一索引 uk_user_activity 兜底）
        // 注意：入口已写"已购标记"用于防重复请求，不能作为消费者的幂等依据；
        // 消费者幂等由 DB 唯一索引保证（消息重投/业务重复时 tryCreateOrder 冲突）。
        long orderId = snowflakeIdWorker.nextId();
        Order order = new Order();
        order.setId(orderId);
        order.setOrderNo(msg.getOrderNo());
        order.setUserId(msg.getUserId());
        order.setActivityId(msg.getActivityId());
        order.setProductId(msg.getProductId());
        // 价格快照：取场次当前秒杀价，锁价于下单时刻
        Activity activity = orderService.getActivity(msg.getActivityId());
        if (activity != null) {
            order.setPrice(activity.getPrice());
        }
        order.setStatus(0);
        order.setExpireTime(LocalDateTime.now().plusMinutes(2));
        if (!orderService.tryCreateOrder(order)) {
            throw new DuplicateOrderException("唯一索引冲突: " + msg.getOrderNo());
        }

        // 3. 乐观锁扣减库存（DB 兜底，文档 7.3）
        int affected = stockService.deductStock(msg.getActivityId(), msg.getProductId());
        if (affected == 0) {
            // Redis 预减与 DB 不一致（如绕过 Redis 直打 DB）：订单取消 + 结果 FAILED
            orderService.cancelOrder(order.getId());
            writeResult(msg.getOrderNo(), "FAILED");
            log.warn("DB 乐观锁扣减失败，订单取消: orderNo={}, activityId={}", msg.getOrderNo(), msg.getActivityId());
            return;
        }

        // 4. 写已购标记 + 结果缓存
        redisStockService.markBought(msg.getActivityId(), msg.getUserId());
        writeResult(msg.getOrderNo(), "PENDING");
        metrics.orderSuccess.incrementAndGet();
        log.info("下单落库成功: orderNo={}, userId={}, activityId={}, productId={}",
                msg.getOrderNo(), msg.getUserId(), msg.getActivityId(), msg.getProductId());
    }

    private void writeResult(String orderNo, String status) {
        Map<String, Object> data = new HashMap<>();
        data.put("orderNo", orderNo);
        data.put("status", status);
        redisStockService.writeResult(orderNo, data);
    }

    /** 唯一索引冲突（重复下单） */
    static class DuplicateOrderException extends RuntimeException {
        DuplicateOrderException(String msg) {
            super(msg);
        }
    }
}
