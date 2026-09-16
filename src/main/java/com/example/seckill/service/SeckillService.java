package com.example.seckill.service;

import com.example.seckill.entity.Activity;
import com.example.seckill.entity.Order;
import com.example.seckill.redis.RedisLockUtil;
import com.example.seckill.redis.RedisStockService;
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
 * 秒杀核心服务（M2：Redis 预热 + Lua 原子扣减 + 用户维度分布式锁 + 已购标记幂等，见文档 11 节 M2）。
 * 调用顺序严格遵循文档 7.4：加锁 → Lua 扣减 → 落单；任何失败回滚 Redis 扣减并释放锁。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillService {

    private final OrderService orderService;
    private final RedisStockService redisStockService;
    private final RedisLockUtil redisLockUtil;
    private final SnowflakeIdWorker snowflakeIdWorker = new SnowflakeIdWorker();

    @Value("${seckill.pay-expire-minutes:2}")
    private int payExpireMinutes;

    /**
     * M2 同步抢购：状态校验 → 已购标记 → 分布式锁 → Lua 扣减 → 插单（唯一索引兜底）→ 已购标记。
     * 插单失败回滚 Redis 库存（INCRBY 加回）。
     */
    public Map<String, Object> seckill(Long activityId, Long productId, Long userId) {
        Map<String, Object> result = new HashMap<>();

        Activity activity = orderService.getActivity(activityId);
        if (activity == null) {
            result.put("code", ErrorCode.SYSTEM_ERROR);
            result.put("message", "场次不存在");
            return result;
        }
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

        // 幂等前置检查：已购标记（Redis）
        if (redisStockService.hasBought(activityId, userId)) {
            result.put("code", ErrorCode.DUPLICATE_ORDER);
            result.put("message", "重复抢购");
            return result;
        }

        // 用户维度防连点锁（文档 7.4：SET NX PX，3s）
        String lockKey = RedisLockUtil.userLockKey(activityId, userId);
        String token = redisLockUtil.lock(lockKey, 3000);
        if (token == null) {
            result.put("code", ErrorCode.RATE_LIMITED);
            result.put("message", "请求过于频繁");
            return result;
        }

        try {
            // Lua 原子扣减（文档 7.2）：1 成功 / 0 库存不足 / -1 未预热
            long deduct = redisStockService.deduct(activityId, productId);
            if (deduct == 0) {
                result.put("code", ErrorCode.STOCK_NOT_ENOUGH);
                result.put("message", "库存不足");
                return result;
            }
            if (deduct == -1) {
                log.warn("库存未预热: activityId={}, productId={}", activityId, productId);
                result.put("code", ErrorCode.SYSTEM_ERROR);
                result.put("message", "库存未预热，请先调用预热接口");
                return result;
            }

            // 插入订单（唯一索引 uk_user_activity 兜底，文档 7.4/ADR-3）
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
                // 并发重复：回滚 Redis 扣减（文档 7.4：失败必须回滚）
                redisStockService.restore(activityId, productId);
                log.warn("重复抢购被唯一索引拦截并回滚库存: userId={}, activityId={}", userId, activityId);
                result.put("code", ErrorCode.DUPLICATE_ORDER);
                result.put("message", "重复抢购");
                return result;
            }

            // 写已购标记（幂等，TTL 活动结束）
            redisStockService.markBought(activityId, userId);

            log.info("抢购成功(Lua预减): orderNo={}, userId={}, activityId={}", orderNo, userId, activityId);
            result.put("code", ErrorCode.SUCCESS);
            result.put("message", "排队中");
            Map<String, Object> data = new HashMap<>();
            data.put("orderNo", orderNo);
            data.put("status", "PENDING");
            result.put("data", data);
            return result;
        } finally {
            redisLockUtil.unlock(lockKey, token);
        }
    }
}
