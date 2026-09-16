package com.example.seckill.mq;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 异步下单消息体（文档 7.5 契约 JSON）：
 * { "orderNo": "...", "userId": 1001, "activityId": 1, "productId": 1001, "timestamp": 1726000000000 }
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OrderMessage {

    private String orderNo;
    private Long userId;
    private Long activityId;
    private Long productId;
    private Long timestamp;
}
