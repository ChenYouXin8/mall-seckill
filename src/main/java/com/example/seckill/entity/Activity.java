package com.example.seckill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 秒杀场次实体（对应表 seckill_activity，DDL 见文档第 5 节）。
 */
@Data
@TableName("seckill_activity")
public class Activity {

    /** 场次ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 标题 */
    private String title;

    /** 开始时间 */
    private LocalDateTime startTime;

    /** 结束时间 */
    private LocalDateTime endTime;

    /** 秒杀价（元） */
    private BigDecimal price;

    /** 0-未开始 1-进行中 2-已结束 3-已关闭 */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
