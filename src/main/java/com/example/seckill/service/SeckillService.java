package com.example.seckill.service;

import com.example.seckill.entity.Activity;
import com.example.seckill.entity.Order;
import com.example.seckill.util.ErrorCode;
import com.example.seckill.util.SnowflakeIdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 秒杀核心服务（M1：抢购直连 DB + 乐观锁，见文档 11 节 M1）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillService {

    private final OrderService orderService;
    private final StockService stockService;
    private final SnowflakeIdWorker snowflakeIdWorker = new SnowflakeIdWorker();

    @Value("${seckill.pay-expire-minutes:2}")
    private int payExpireMinutes;

    /**
     * M1 同步抢购：状态校验 → 幂等 → 插单（唯一索引兜底）→ 乐观锁扣库存。
     *
     * @return Map 包含 code / orderNo / status；业务失败时 code 为文档错误码
     */
    public Map<String, Object> seckill(Long activityId, Long productId, Long userId) {
        Map<String, Object> result = new HashMap<>();

        Activity activity = orderService.getActivity(activityId);
        if (activity == null) {
            result.put("code", ErrorCode.SYSTEM_ERROR);
            result.put("message", "场次不存在");
            return result;
        }
        // 状态校验：0-未开始 1-进行中 2-已结束 3-已关闭
        if (activity.getStatus() == 0) {
            result.put("code", ErrorCode.NOT_STARTED);
            result.put("message", "秒杀未开始");
            return result;
        }
        if (activity.getStatus() != 1) {
            result.put("code", ErrorCode.ENDED);
            result.put("message", "秒杀已结束");
            return result;
        }

        // 幂等前置检查（DB 唯一索引为最终防线）
        if (orderService.existsByUserAndActivity(userId, activityId)) {
            result.put("code", ErrorCode.DUPLICATE_ORDER);
            result.put("message", "重复抢购");
            return result;
        }

        // 插入订单（新事务，唯一索引冲突转 10002）
        long orderId = snowflakeIdWorker.nextId();
        String orderNo = String.valueOf(orderId);
        Order order = new Order();
        order.setId(orderId);
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setActivityId(activityId);
        order.setProductId(productId);
        order.setStatus(0);
        order.setExpireTime(LocalDateTime.now().plusMinutes(payExpireMinutes));

        if (!orderService.tryCreateOrder(order)) {
            log.warn("重复抢购被唯一索引拦截: userId={}, activityId={}", userId, activityId);
            result.put("code", ErrorCode.DUPLICATE_ORDER);
            result.put("message", "重复抢购");
            return result;
        }

        // 乐观锁扣减库存（第二层兜底）
        int affected = stockService.deductStock(activityId, productId);
        if (affected == 0) {
            log.warn("库存不足，取消订单: userId={}, activityId={}, productId={}", userId, activityId, productId);
            orderService.cancelOrder(order.getId());
            result.put("code", ErrorCode.STOCK_NOT_ENOUGH);
            result.put("message", "库存不足");
            return result;
        }

        log.info("抢购成功(直连DB): orderNo={}, userId={}, activityId={}", orderNo, userId, activityId);
        result.put("code", ErrorCode.SUCCESS);
        result.put("message", "排队中");
        Map<String, Object> data = new HashMap<>();
        data.put("orderNo", orderNo);
        data.put("status", "PENDING");
        result.put("data", data);
        return result;
    }
}
