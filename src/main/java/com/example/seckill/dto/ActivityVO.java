package com.example.seckill.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 场次列表视图对象：活动字段 + 首个商品的库存聚合（前端列表/卡片展示用）。
 */
@Data
public class ActivityVO {

    private Long id;

    private String title;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    /** 秒杀价（元） */
    private BigDecimal price;

    /** 0-未开始 1-进行中 2-已结束 3-已关闭 */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** 商品ID（取场次下首个商品） */
    private Long productId;

    /** 剩余库存 */
    private Integer stock;

    /** 总库存 */
    private Integer totalStock;

    /** 是否已预热（Redis 库存 key 是否存在） */
    private Boolean preheated;
}
