package com.example.seckill.controller;

import com.example.seckill.entity.Activity;
import com.example.seckill.redis.RedisStockService;
import com.example.seckill.service.OrderService;
import com.example.seckill.service.SeckillService;
import com.example.seckill.util.ErrorCode;
import com.example.seckill.util.Result;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
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
    private final SeckillService seckillService;
    private final RedisStockService redisStockService;

    @Value("${seckill.address-ttl-seconds:7200}")
    private long addressTtlSeconds;

    private static final char[] SECRET_CHARS =
            "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * GET /api/seckill/v1/activities 场次列表（无需鉴权）。
     */
    @GetMapping("/activities")
    public Result<List<Activity>> activities() {
        return Result.ok(orderService.listActivities());
    }

    /**
     * GET /api/seckill/v1/address/{activityId} 获取秒杀随机地址（文档 7.7 / 6.1 契约）。
     * 活动未开始返回 10003；已结束返回 10004。地址写入 Redis，抢购必须携带。
     */
    @GetMapping("/address/{activityId}")
    public Result<Map<String, Object>> address(@PathVariable Long activityId) {
        if (activityId == null || activityId <= 0) {
            return Result.fail(400, "参数错误");
        }
        Activity activity = orderService.getActivity(activityId);
        if (activity == null) {
            return Result.fail(ErrorCode.SYSTEM_ERROR, "场次不存在");
        }
        if (activity.getStatus() == 0) {
            return Result.fail(ErrorCode.NOT_STARTED, "秒杀未开始");
        }
        if (activity.getStatus() != 1) {
            return Result.fail(ErrorCode.ENDED, "秒杀已结束");
        }

        String secret = generateSecret();
        redisStockService.saveAddress(activityId, secret, addressTtlSeconds);
        Map<String, Object> data = new HashMap<>();
        data.put("address", secret);
        data.put("expiresInSeconds", addressTtlSeconds);
        return Result.ok(data);
    }

    /**
     * POST /api/seckill/v1/{secretPath}/order 抢购。
     * M4：校验 secretPath 与 Redis 中隐藏地址一致（文档 7.7），否则 10006。
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

        // 隐藏地址校验（文档 7.7：无地址/不匹配 → 10006）
        String expected = redisStockService.getAddress(activityId);
        if (expected == null || !expected.equals(secretPath)) {
            return Result.fail(ErrorCode.INVALID_ADDRESS, "非法秒杀地址");
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

    private String generateSecret() {
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            sb.append(SECRET_CHARS[RANDOM.nextInt(SECRET_CHARS.length)]);
        }
        return sb.toString();
    }
}
