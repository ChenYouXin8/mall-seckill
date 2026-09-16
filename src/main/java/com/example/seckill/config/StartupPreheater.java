package com.example.seckill.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.seckill.entity.Activity;
import com.example.seckill.entity.Stock;
import com.example.seckill.mapper.ActivityMapper;
import com.example.seckill.mapper.StockMapper;
import com.example.seckill.redis.RedisStockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动自动预热（文档 7.1）：应用启动时扫描 status=1 的活动，将其库存预热到 Redis。
 * 幂等：Redis 已有 key 时跳过（SET NX），不影响进行中的扣减。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupPreheater implements ApplicationRunner {

    private final ActivityMapper activityMapper;
    private final StockMapper stockMapper;
    private final RedisStockService redisStockService;

    @Override
    public void run(ApplicationArguments args) {
        List<Activity> activities = activityMapper.selectList(
                new LambdaQueryWrapper<Activity>().eq(Activity::getStatus, 1));
        int total = 0;
        for (Activity activity : activities) {
            List<Stock> stocks = stockMapper.selectList(
                    new LambdaQueryWrapper<Stock>().eq(Stock::getActivityId, activity.getId()));
            for (Stock stock : stocks) {
                if (redisStockService.preheat(stock.getActivityId(), stock.getProductId(), stock.getTotalStock())) {
                    total++;
                }
            }
        }
        log.info("启动自动预热完成: 活动数={}, 新写入库存key数={}", activities.size(), total);
    }
}
