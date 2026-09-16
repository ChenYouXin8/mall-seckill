package com.example.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.seckill.entity.Stock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface StockMapper extends BaseMapper<Stock> {

    /**
     * DB 乐观锁扣减库存（防超卖第二层兜底，文档 7.3）。
     * 受影响行数 = 1 才算扣减成功。
     */
    @Update("UPDATE seckill_stock SET stock = stock - 1, version = version + 1 " +
            "WHERE activity_id = #{activityId} AND product_id = #{productId} AND stock > 0")
    int deductStock(@Param("activityId") Long activityId, @Param("productId") Long productId);

    /**
     * 超时回补库存（文档 7.8）：乐观锁 +1。
     */
    @Update("UPDATE seckill_stock SET stock = stock + 1, version = version + 1 " +
            "WHERE activity_id = #{activityId} AND product_id = #{productId}")
    int restoreStock(@Param("activityId") Long activityId, @Param("productId") Long productId);
}
