package com.example.seckill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 秒杀订单实体（对应表 seckill_order，雪花ID主键，用户+场次唯一）。
 */
@Data
@TableName("seckill_order")
public class Order {

    /** 雪花ID */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 订单号 */
    private String orderNo;

    private Long userId;

    private Long activityId;

    private Long productId;

    /** 下单价格快照（元） */
    private BigDecimal price;

    /** 0-待支付 1-已支付 2-已取消 3-超时关闭 */
    private Integer status;

    /** 支付时间 */
    private LocalDateTime payTime;

    /** 支付截止时间 */
    private LocalDateTime expireTime;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
