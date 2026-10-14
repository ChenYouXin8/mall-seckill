package com.example.seckill.controller;

import com.example.seckill.entity.Order;
import com.example.seckill.redis.RedisStockService;
import com.example.seckill.service.OrderService;
import com.example.seckill.util.ErrorCode;
import com.example.seckill.util.Result;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单接口（文档 6.1 契约）。
 * M3：结果查询优先读 Redis 缓存（seckill:result:{orderNo}），穿透回 DB；模拟支付 + 缓存一致性。
 */
@Slf4j
@RestController
@RequestMapping("/api/seckill/v1/order")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final RedisStockService redisStockService;
    private final ObjectMapper objectMapper;

    /**
     * GET /api/seckill/v1/order/result?orderNo= 轮询下单结果（F5）。
     * 返回三态：PENDING 排队中 / SUCCESS 成功 / FAILED 失败（不落库压力，读缓存）。
     */
    @GetMapping("/result")
    public Result<Map<String, Object>> result(@RequestParam String orderNo) {
        // 1. 读结果缓存
        String cached = redisStockService.readResult(orderNo);
        if (cached != null) {
            try {
                Map<String, Object> data = objectMapper.readValue(cached, new TypeReference<>() {
                });
                return Result.ok(data);
            } catch (Exception e) {
                log.warn("结果缓存解析失败，回退 DB: orderNo={}", orderNo, e);
            }
        }
        // 2. 缓存穿透回 DB（查询量小，可接受，文档 7.6）
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

    /**
     * POST /api/seckill/v1/order/pay 模拟支付（F6）。
     * 请求体：{ "orderNo": "..." }；可重复调用（幂等）。
     */
    @PostMapping("/pay")
    public Result<Map<String, Object>> pay(@RequestBody Map<String, Object> body) {
        String orderNo = body.get("orderNo") == null ? null : body.get("orderNo").toString();
        if (orderNo == null || orderNo.isBlank()) {
            return Result.fail(400, "参数错误");
        }
        Order order = orderService.payOrder(orderNo);
        if (order == null) {
            return Result.fail(ErrorCode.ORDER_INVALID, "订单不存在或状态不允许");
        }
        // 缓存一致性：先更新 DB，再删结果缓存（文档 7.6）
        redisStockService.deleteResult(orderNo);
        log.info("模拟支付成功: orderNo={}, status={}", orderNo, order.getStatus());

        Map<String, Object> data = new HashMap<>();
        data.put("orderNo", order.getOrderNo());
        data.put("status", statusText(order.getStatus()));
        return Result.ok("支付成功", data);
    }

    /**
     * GET /api/seckill/v1/order/list 订单列表（管理端）。
     * 不带 userId 参数返回全部（管理端订单页用）；带 userId 则仅返回该用户订单。
     */
    @GetMapping("/list")
    public Result<List<Order>> list(@RequestParam(required = false) Long userId,
                                    @RequestParam(required = false, defaultValue = "200") Integer limit) {
        return Result.ok(orderService.listOrders(userId, limit == null ? 200 : limit));
    }

    /**
     * POST /api/seckill/v1/order/cancel 管理端关单。
     * 请求体：{ "orderNo": "..." }；仅待支付可关，关闭后回补库存。
     */
    @PostMapping("/cancel")
    public Result<Map<String, Object>> cancel(@RequestBody Map<String, Object> body) {
        String orderNo = body.get("orderNo") == null ? null : body.get("orderNo").toString();
        if (orderNo == null || orderNo.isBlank()) {
            return Result.fail(400, "参数错误");
        }
        Order order = orderService.cancelByOrderNo(orderNo);
        if (order == null) {
            return Result.fail(ErrorCode.ORDER_INVALID, "订单不存在或状态不允许");
        }
        redisStockService.deleteResult(orderNo);
        Map<String, Object> data = new HashMap<>();
        data.put("orderNo", order.getOrderNo());
        data.put("status", statusText(order.getStatus()));
        data.put("orderStatus", order.getStatus());
        return Result.ok("订单已关闭", data);
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
