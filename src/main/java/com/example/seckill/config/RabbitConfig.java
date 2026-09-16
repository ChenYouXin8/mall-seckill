package com.example.seckill.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 配置（文档 7.5 设计约束）：
 * - Exchange: seckill.order.direct（direct）
 * - Queue: seckill.order.queue；routingKey: seckill.order.create
 * - 死信队列: seckill.order.dlq（消费失败超过 3 次进入）
 */
@Configuration
public class RabbitConfig {

    public static final String ORDER_EXCHANGE = "seckill.order.direct";
    public static final String ORDER_QUEUE = "seckill.order.queue";
    public static final String ORDER_ROUTING_KEY = "seckill.order.create";
    public static final String ORDER_DLQ = "seckill.order.dlq";
    public static final String ORDER_DLQ_ROUTING_KEY = "seckill.order.dlq.key";

    @Bean
    public DirectExchange orderExchange() {
        return new DirectExchange(ORDER_EXCHANGE, true, false);
    }

    @Bean
    public Queue orderQueue() {
        return QueueBuilder.durable(ORDER_QUEUE)
                .withArgument("x-dead-letter-exchange", ORDER_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", ORDER_DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue orderDlq() {
        return QueueBuilder.durable(ORDER_DLQ).build();
    }

    @Bean
    public Binding orderBinding() {
        return BindingBuilder.bind(orderQueue()).to(orderExchange()).with(ORDER_ROUTING_KEY);
    }

    @Bean
    public Binding orderDlqBinding() {
        return BindingBuilder.bind(orderDlq()).to(orderExchange()).with(ORDER_DLQ_ROUTING_KEY);
    }

    /**
     * 消息体 JSON 序列化（文档 7.5：消息体 JSON）。
     */
    @Bean
    public Jackson2JsonMessageConverter jackson2JsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
