package com.example.seckill.service;

import com.example.seckill.mapper.StockMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 库存服务：DB 乐观锁扣减/回补（文档 7.3 / 7.8）。
 */
@Service
@RequiredArgsConstructor
public class StockService {

    private final StockMapper stockMapper;

    /**
     * 乐观锁扣减库存（WHERE stock > 0 兜底，杜绝超卖）。
     *
     * @return 受影响行数，1=扣减成功，0=库存不足
     */
    public int deductStock(Long activityId, Long productId) {
        return stockMapper.deductStock(activityId, productId);
    }

    /**
     * 回补库存（超时关单后调用）。
     */
    public int restoreStock(Long activityId, Long productId) {
        return stockMapper.restoreStock(activityId, productId);
    }
}
