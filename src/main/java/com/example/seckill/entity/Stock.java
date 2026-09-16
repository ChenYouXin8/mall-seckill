package com.example.seckill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 秒杀库存实体（对应表 seckill_stock，含乐观锁版本号）。
 */
@Data
@TableName("seckill_stock")
public class Stock {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 场次ID */
    private Long activityId;

    /** 商品ID */
    private Long productId;

    /** 总库存 */
    private Integer totalStock;

    /** 剩余库存 */
    private Integer stock;

    /** 乐观锁版本号 */
    private Integer version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
