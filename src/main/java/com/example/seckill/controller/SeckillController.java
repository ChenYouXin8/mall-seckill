package com.example.seckill.controller;

import com.example.seckill.entity.Activity;
import com.example.seckill.service.OrderService;
import com.example.seckill.util.Result;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 秒杀接口（文档 6.1 契约）。
 */
@Slf4j
@RestController
@RequestMapping("/api/seckill/v1")
@RequiredArgsConstructor
public class SeckillController {

    private final OrderService orderService;
    private final com.example.seckill.service.SeckillService seckillService;

    /**
     * GET /api/seckill/v1/activities 场次列表（无需鉴权）。
     */
    @GetMapping("/activities")
    public Result<List<Activity>> activities() {
        return Result.ok(orderService.listActivities());
    }

    /**
     * POST /api/seckill/v1/{secretPath}/order 抢购。
     * M1：secretPath 暂不校验内容（M4 接入隐藏地址校验），请求体固定 {activityId, productId}。
     */
    @PostMapping("/{secretPath}/order")
    public Result<Map<String, Object>> order(@PathVariable String secretPath,
                                             @RequestBody Map<String, Object> body,
                                             HttpServletRequest request) {
        Long activityId = body.get("activityId") == null ? null : Long.valueOf(body.get("activityId").toString());
        Long productId = body.get("productId") == null ? null : Long.valueOf(body.get("productId").toString());
        Long userId = (Long) request.getAttribute("userId");

        if (activityId == null || productId == null || activityId <= 0 || productId <= 0) {
            return Result.fail(400, "参数错误");
        }

        Map<String, Object> r = seckillService.seckill(activityId, productId, userId);
        int code = (int) r.get("code");
        if (code == 0) {
            Map<String, Object> data = new HashMap<>();
            data.put("orderNo", ((Map<?, ?>) r.get("data")).get("orderNo"));
            data.put("status", "PENDING");
            return Result.ok("排队中", data);
        }
        return Result.fail(code, String.valueOf(r.get("message")));
    }
}
