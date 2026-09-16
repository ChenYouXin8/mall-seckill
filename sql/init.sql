-- ============================================================
-- mall-seckill 初始化脚本（DDL 严格按《开发文档 v1.0》第 5 节，禁止改动）
-- 适用：Docker Compose 初始化 / 本地手工执行
-- ============================================================
CREATE DATABASE IF NOT EXISTS mall_seckill DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE mall_seckill;

-- 秒杀场次
CREATE TABLE IF NOT EXISTS seckill_activity (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '场次ID',
    title       VARCHAR(128) NOT NULL COMMENT '标题',
    start_time  DATETIME     NOT NULL COMMENT '开始时间',
    end_time    DATETIME     NOT NULL COMMENT '结束时间',
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '0-未开始 1-进行中 2-已结束 3-已关闭',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_status_time (status, start_time)
) ENGINE=InnoDB COMMENT='秒杀场次';

-- 秒杀库存（含版本号，供乐观锁）
CREATE TABLE IF NOT EXISTS seckill_stock (
    id           BIGINT   NOT NULL AUTO_INCREMENT,
    activity_id  BIGINT   NOT NULL,
    product_id   BIGINT   NOT NULL,
    total_stock  INT      NOT NULL COMMENT '总库存',
    stock        INT      NOT NULL COMMENT '剩余库存',
    version      INT      NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_activity_product (activity_id, product_id)
) ENGINE=InnoDB COMMENT='秒杀库存';

-- 秒杀订单（雪花ID作为主键；用户+场次唯一索引保证一人一场一单）
CREATE TABLE IF NOT EXISTS seckill_order (
    id           BIGINT      NOT NULL COMMENT '雪花ID',
    order_no     VARCHAR(32) NOT NULL COMMENT '订单号',
    user_id      BIGINT      NOT NULL,
    activity_id  BIGINT      NOT NULL,
    product_id   BIGINT      NOT NULL,
    status       TINYINT     NOT NULL DEFAULT 0 COMMENT '0-待支付 1-已支付 2-已取消 3-超时关闭',
    pay_time     DATETIME    NULL,
    expire_time  DATETIME    NULL COMMENT '支付截止时间',
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    UNIQUE KEY uk_user_activity (user_id, activity_id),
    KEY idx_status_expire (status, expire_time)
) ENGINE=InnoDB COMMENT='秒杀订单';

-- 初始化样例数据（一个场次，10 件库存）
INSERT INTO seckill_activity (title, start_time, end_time, status)
SELECT '测试秒杀场次', DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_ADD(NOW(), INTERVAL 23 HOUR), 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM seckill_activity WHERE title = '测试秒杀场次');

INSERT INTO seckill_stock (activity_id, product_id, total_stock, stock)
SELECT a.id, 1001, 10, 10 FROM seckill_activity a WHERE a.title = '测试秒杀场次'
  AND NOT EXISTS (SELECT 1 FROM seckill_stock s WHERE s.activity_id = a.id AND s.product_id = 1001);
