package com.example.seckill.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.seckill.config.SeckillMetrics;
import com.example.seckill.entity.Order;
import com.example.seckill.mapper.OrderMapper;
import com.example.seckill.mapper.StockMapper;
import com.example.seckill.redis.RedisStockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 支付超时回补定时任务（文档 7.8）：
 * - 每 30s 扫描 status=0 AND expire_time < NOW() 的订单，批量关闭（status=3）并回补库存
 * - 回补顺序：先 DB 后 Redis；Redis 失败记录补偿计数，由下次任务重试
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderExpireTask {

    private final OrderMapper orderMapper;
    private final StockMapper stockMapper;
    private final RedisStockService redisStockService;
    private final SeckillMetrics metrics;

    @Scheduled(fixedDelay = 30000, initialDelay = 10000)
    public void closeExpiredOrders() {
        try {
            // 1. 先处理上一轮 Redis 回补失败遗留的补偿计数
            handleCompensate();
            // 2. 扫描超时订单
            List<Order> expired = orderMapper.selectList(
                    new LambdaQueryWrapper<Order>()
                            .eq(Order::getStatus, 0)
                            .lt(Order::getExpireTime, LocalDateTime.now())
                            .last("LIMIT 500"));
            int closed = 0;
            for (Order order : expired) {
                if (closeAndRestore(order)) {
                    closed++;
                }
            }
            if (closed > 0) {
                log.info("超时关单回补完成: closed={}", closed);
            }
        } catch (Exception e) {
            log.error("超时回补任务异常", e);
        }
    }

    /**
     * 关闭订单并回补库存（先 DB 后 Redis，独立于抢购事务）。
     *
     * @return true=本次关闭成功
     */
    private boolean closeAndRestore(Order order) {
        // 乐观关闭：仅 status=0 可关闭，避免重复处理
        int affected = orderMapper.closeExpired(order.getId());
        if (affected == 0) {
            return false;
        }
        // DB 回补库存（乐观锁 +1）
        int dbRestored = stockMapper.restoreStock(order.getActivityId(), order.getProductId());
        if (dbRestored == 0) {
            log.error("DB 库存回补失败: activityId={}, productId={}", order.getActivityId(), order.getProductId());
            return true;
        }
        // Redis 回补（INCRBY）；失败记录补偿计数由下次任务重试
        long redisRestored = redisStockService.restore(order.getActivityId(), order.getProductId());
        if (redisRestored == 0) {
            redisStockService.incrCompensate(order.getActivityId(), order.getProductId());
            log.warn("Redis 库存回补失败，记录补偿: orderNo={}, activityId={}, productId={}",
                    order.getOrderNo(), order.getActivityId(), order.getProductId());
        } else {
            metrics.restoreCount.incrementAndGet();
        }
        log.info("超时关单: orderNo={}, userId={}, 库存已回补", order.getOrderNo(), order.getUserId());
        return true;
    }

    /**
     * 处理补偿计数：Redis 回补失败的遗留（文档 7.8：由下次任务重试）。
     */
    private void handleCompensate() {
        // 通过库存表找到所有活动+商品组合
        List<com.example.seckill.entity.Stock> stocks = stockMapper.selectList(null);
        for (com.example.seckill.entity.Stock stock : stocks) {
            long pending = redisStockService.drainCompensate(stock.getActivityId(), stock.getProductId());
            if (pending > 0) {
                for (long i = 0; i < pending; i++) {
                    long restored = redisStockService.restore(stock.getActivityId(), stock.getProductId());
                    if (restored == 0) {
                        redisStockService.incrCompensate(stock.getActivityId(), stock.getProductId());
                        log.warn("补偿重试仍失败: activityId={}, productId={}", stock.getActivityId(), stock.getProductId());
                        break;
                    }
                    metrics.restoreCount.incrementAndGet();
                }
                log.info("补偿完成: activityId={}, productId={}, count={}",
                        stock.getActivityId(), stock.getProductId(), pending);
            }
        }
    }
}
