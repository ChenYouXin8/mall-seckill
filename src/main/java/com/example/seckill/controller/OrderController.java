package com.example.seckill.controller;

import com.example.seckill.entity.Order;
import com.example.seckill.service.OrderService;
import com.example.seckill.util.ErrorCode;
import com.example.seckill.util.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 订单接口（文档 6.1 契约）。
 */
@RestController
@RequestMapping("/api/seckill/v1/order")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /**
     * GET /api/seckill/v1/order/result?orderNo= 轮询下单结果（F5）。
     * M1：直连 DB 查询（M3 改为读结果缓存）。
     * 返回三态：PENDING 排队中 / SUCCESS 成功 / FAILED 失败。
     */
    @GetMapping("/result")
    public Result<Map<String, Object>> result(@RequestParam String orderNo) {
        Order order = orderService.getByOrderNo(orderNo);
        if (order == null) {
            return Result.fail(ErrorCode.ORDER_INVALID, "订单不存在");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("orderNo", order.getOrderNo());
        data.put("status", statusText(order.getStatus()));
        data.put("orderStatus", order.getStatus());
        return Result.ok(data);
    }

    private String statusText(Integer status) {
        if (status == null) {
            return "FAILED";
        }
        // 0-待支付 1-已支付 2-已取消 3-超时关闭
        return switch (status) {
            case 0 -> "PENDING";
            case 1 -> "SUCCESS";
            default -> "FAILED";
        };
    }
}
