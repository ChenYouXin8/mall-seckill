package com.example.seckill.controller;

import com.example.seckill.entity.Activity;
import com.example.seckill.entity.Stock;
import com.example.seckill.mapper.ActivityMapper;
import com.example.seckill.mapper.StockMapper;
import com.example.seckill.util.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 管理端接口（文档 6.1 契约）。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final ActivityMapper activityMapper;
    private final StockMapper stockMapper;

    /**
     * POST /api/admin/seckill/activities 创建场次（F1）。
     * 请求体：{ "title": "...", "startTime": "2026-09-16 10:00:00", "endTime": "...", "stocks": [{"productId":1001,"totalStock":10}] }
     */
    @PostMapping("/seckill/activities")
    @Transactional
    public Result<Map<String, Object>> createActivity(@RequestBody Map<String, Object> body) {
        String title = body.get("title") == null ? null : body.get("title").toString();
        String startTimeStr = body.get("startTime") == null ? null : body.get("startTime").toString();
        String endTimeStr = body.get("endTime") == null ? null : body.get("endTime").toString();
        List<?> stocksRaw = body.get("stocks") == null ? List.of() : (List<?>) body.get("stocks");

        if (title == null || title.isBlank() || startTimeStr == null || endTimeStr == null || stocksRaw.isEmpty()) {
            return Result.fail(400, "参数错误");
        }
        LocalDateTime startTime = LocalDateTime.parse(startTimeStr.trim().replace(' ', 'T'));
        LocalDateTime endTime = LocalDateTime.parse(endTimeStr.trim().replace(' ', 'T'));
        if (!startTime.isBefore(endTime)) {
            return Result.fail(400, "时间非法：startTime 必须早于 endTime");
        }

        Activity activity = new Activity();
        activity.setTitle(title);
        activity.setStartTime(startTime);
        activity.setEndTime(endTime);
        // 0-未开始 1-进行中 2-已结束 3-已关闭；按当前时间自动计算状态
        LocalDateTime now = LocalDateTime.now();
        activity.setStatus(now.isBefore(startTime) ? 0 : (now.isBefore(endTime) ? 1 : 2));
        activityMapper.insert(activity);

        for (Object raw : stocksRaw) {
            @SuppressWarnings("unchecked")
            Map<String, Object> item = (Map<String, Object>) raw;
            long productId = Long.parseLong(item.get("productId").toString());
            int totalStock = Integer.parseInt(item.get("totalStock").toString());
            if (totalStock <= 0) {
                throw new IllegalArgumentException("totalStock 必须 > 0");
            }
            Stock stock = new Stock();
            stock.setActivityId(activity.getId());
            stock.setProductId(productId);
            stock.setTotalStock(totalStock);
            stock.setStock(totalStock);
            stock.setVersion(0);
            stockMapper.insert(stock);
        }

        log.info("创建场次成功: id={}, title={}, start={}, end={}", activity.getId(), title, startTime, endTime);
        return Result.ok(Map.of("activityId", activity.getId()));
    }

    /**
     * POST /api/admin/seckill/activities/{id}/preheat 预热库存到 Redis（F2）。
     * M1 暂以 DB 为准返回快照（Redis 接入见 M2 里程碑）。
     */
    @PostMapping("/seckill/activities/{id}/preheat")
    public Result<Map<String, Object>> preheat(@PathVariable Long id) {
        List<Stock> stocks = stockMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Stock>()
                        .eq(Stock::getActivityId, id));
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("activityId", id);
        data.put("items", stocks.stream().map(s -> Map.of(
                "productId", s.getProductId(),
                "totalStock", s.getTotalStock())).toList());
        log.info("预热接口(M1占位): activityId={}, items={}", id, stocks.size());
        return Result.ok("预热成功(M1:DB快照,Redis待M2)", data);
    }
}
