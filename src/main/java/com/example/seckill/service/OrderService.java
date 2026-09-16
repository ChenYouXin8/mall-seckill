package com.example.seckill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.seckill.entity.Activity;
import com.example.seckill.entity.Order;
import com.example.seckill.mapper.ActivityMapper;
import com.example.seckill.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 场次与订单的查询/创建（M1 基础版：直连 DB）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderMapper orderMapper;
    private final ActivityMapper activityMapper;

    /**
     * 场次列表。
     */
    public List<Activity> listActivities() {
        return activityMapper.selectList(
                new LambdaQueryWrapper<Activity>().orderByDesc(Activity::getId));
    }

    /**
     * 查询场次。
     */
    public Activity getActivity(Long activityId) {
        return activityMapper.selectById(activityId);
    }

    /**
     * 幂等前置检查：同一用户是否已在该场次下单。
     */
    public boolean existsByUserAndActivity(Long userId, Long activityId) {
        Long count = orderMapper.selectCount(
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getUserId, userId)
                        .eq(Order::getActivityId, activityId));
        return count != null && count > 0;
    }

    /**
     * 新事务插入订单；唯一索引 uk_user_activity 冲突时返回 false（转 10002）。
     * REQUIRES_NEW 保证 DuplicateKeyException 回滚不污染外层事务。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryCreateOrder(Order order) {
        try {
            orderMapper.insert(order);
            return true;
        } catch (DuplicateKeyException e) {
            log.warn("订单插入唯一索引冲突: orderNo={}, userId={}, activityId={}, err={}",
                    order.getOrderNo(), order.getUserId(), order.getActivityId(), e.getMessage());
            return false;
        }
    }

    /**
     * 按订单号查询订单。
     */
    public Order getByOrderNo(String orderNo) {
        return orderMapper.selectOne(
                new LambdaQueryWrapper<Order>().eq(Order::getOrderNo, orderNo));
    }

    /**
     * 取消订单（扣库存失败时标记 status=2）。
     */
    @Transactional
    public void cancelOrder(Long orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order != null && order.getStatus() == 0) {
            Order upd = new Order();
            upd.setId(order.getId());
            upd.setStatus(2);
            orderMapper.updateById(upd);
        }
    }

    /**
     * 模拟支付（F6）：待支付 → 已支付，可重复调用（幂等）。
     *
     * @return 支付后的订单；订单不存在或状态不允许返回 null
     */
    @Transactional
    public Order payOrder(String orderNo) {
        Order order = getByOrderNo(orderNo);
        if (order == null) {
            return null;
        }
        // 幂等：已支付直接返回成功
        if (order.getStatus() == 1) {
            return order;
        }
        // 仅待支付可支付
        if (order.getStatus() != 0) {
            return null;
        }
        Order upd = new Order();
        upd.setId(order.getId());
        upd.setStatus(1);
        upd.setPayTime(LocalDateTime.now());
        orderMapper.updateById(upd);
        order.setStatus(1);
        order.setPayTime(LocalDateTime.now());
        return order;
    }
}
