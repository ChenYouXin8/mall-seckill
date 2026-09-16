package com.example.seckill.util;

/**
 * 统一错误码表（文档 6.3 节，禁止改动）。
 */
public final class ErrorCode {

    /** 成功 */
    public static final int SUCCESS = 0;
    /** 参数错误 */
    public static final int BAD_REQUEST = 400;
    /** 未认证 */
    public static final int UNAUTHORIZED = 401;
    /** 库存不足 */
    public static final int STOCK_NOT_ENOUGH = 10001;
    /** 重复抢购（一人一场一单） */
    public static final int DUPLICATE_ORDER = 10002;
    /** 秒杀未开始 */
    public static final int NOT_STARTED = 10003;
    /** 秒杀已结束 */
    public static final int ENDED = 10004;
    /** 请求过于频繁（限流） */
    public static final int RATE_LIMITED = 10005;
    /** 非法秒杀地址 */
    public static final int INVALID_ADDRESS = 10006;
    /** 订单不存在或状态不允许 */
    public static final int ORDER_INVALID = 10007;
    /** 系统异常 */
    public static final int SYSTEM_ERROR = 500;

    private ErrorCode() {
    }
}
