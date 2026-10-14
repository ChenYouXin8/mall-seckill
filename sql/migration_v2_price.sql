-- ============================================================
-- 迁移脚本 v2：为秒杀增加价格字段（2026-09-17）
-- 说明：init.sql 的 DDL 按《开发文档 v1.0》第 5 节禁止改动，
--       故新增字段走独立迁移脚本，可重复执行（幂等）。
-- 适用：MySQL 8 / 库 mall_seckill
-- ============================================================
USE mall_seckill;

-- 场次：秒杀价
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = 'mall_seckill' AND TABLE_NAME = 'seckill_activity' AND COLUMN_NAME = 'price');
SET @s := IF(@c = 0,
  'ALTER TABLE seckill_activity ADD COLUMN price DECIMAL(10,2) NULL COMMENT ''秒杀价(元)'' AFTER end_time',
  'SELECT ''seckill_activity.price exists''');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 订单：下单价格快照
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = 'mall_seckill' AND TABLE_NAME = 'seckill_order' AND COLUMN_NAME = 'price');
SET @s := IF(@c = 0,
  'ALTER TABLE seckill_order ADD COLUMN price DECIMAL(10,2) NULL COMMENT ''下单价格快照(元)'' AFTER product_id',
  'SELECT ''seckill_order.price exists''');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 给已有样例场次补一个演示价格（仅当为空时）
UPDATE seckill_activity SET price = 99.00 WHERE price IS NULL;
