-- ============================================
-- Flash Sale 秒杀系统 — 建表 DDL
-- 数据库: flash_sale
-- ============================================

CREATE DATABASE IF NOT EXISTS `flash_sale`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE `flash_sale`;

-- 用户表
CREATE TABLE IF NOT EXISTS `user` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `username` VARCHAR(50) NOT NULL,
    `password` VARCHAR(255) NOT NULL,
    `phone` VARCHAR(20) DEFAULT NULL,
    `email` VARCHAR(100) DEFAULT NULL,
    `role` VARCHAR(20) NOT NULL DEFAULT 'USER',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '1=启用 0=禁用',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '0=正常 1=已删除',
    UNIQUE KEY `uk_username` (`username`),
    KEY `idx_role` (`role`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- 商品表
CREATE TABLE IF NOT EXISTS `item` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `name` VARCHAR(200) NOT NULL,
    `description` TEXT DEFAULT NULL,
    `price` DECIMAL(10,2) NOT NULL COMMENT '原价',
    `image` VARCHAR(500) DEFAULT NULL,
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '1=上架 0=下架',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted` TINYINT NOT NULL DEFAULT 0,
    KEY `idx_name` (`name`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商品表';

-- 秒杀活动表
CREATE TABLE IF NOT EXISTS `flash_sale` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `item_id` BIGINT NOT NULL COMMENT '商品ID',
    `flash_price` DECIMAL(10,2) NOT NULL COMMENT '秒杀价',
    `stock` INT NOT NULL DEFAULT 0 COMMENT '秒杀库存',
    `limit_per_user` INT NOT NULL DEFAULT 1 COMMENT '每人限购数量',
    `start_time` DATETIME NOT NULL COMMENT '开始时间',
    `end_time` DATETIME NOT NULL COMMENT '结束时间',
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0=待开始 1=进行中 2=已结束 3=已取消',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted` TINYINT NOT NULL DEFAULT 0,
    KEY `idx_item_id` (`item_id`),
    KEY `idx_status` (`status`),
    KEY `idx_start_time` (`start_time`),
    KEY `idx_end_time` (`end_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='秒杀活动表';

-- 秒杀订单表
CREATE TABLE IF NOT EXISTS `flash_order` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `item_id` BIGINT NOT NULL COMMENT '商品ID',
    `flash_sale_id` BIGINT NOT NULL COMMENT '秒杀活动ID',
    `flash_price` DECIMAL(10,2) NOT NULL COMMENT '成交价',
    `message_key` VARCHAR(64) DEFAULT NULL COMMENT 'MQ 消息幂等键，DB 级去重兜底',
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0=待支付 1=已支付 2=已取消 3=已退款',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted` TINYINT NOT NULL DEFAULT 0,
    UNIQUE KEY `uk_message_key` (`message_key`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_flash_sale_id` (`flash_sale_id`),
    KEY `idx_status` (`status`),
    KEY `idx_user_flash` (`user_id`, `flash_sale_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='秒杀订单表';

-- admin 用户由应用启动时自动初始化（密码: admin123）

-- ============================================
-- 初始化数据
-- ============================================

-- 商品
INSERT IGNORE INTO `item` (`id`, `name`, `description`, `price`, `image`, `status`) VALUES
(1, 'iPhone 17 Pro', 'A18 Pro 芯片，钛金属设计，4800 万像素', 8999.00, '/images/iPhone17pro.png', 1),
(2, 'iPhone 16 Pro', 'A17 Pro 芯片，动作按钮，USB-C 接口', 6999.00, NULL, 1),
(3, 'AirPods Pro 3', '自适应降噪，空间音频，USB-C', 1899.00, NULL, 1),
(4, 'HUAWEI Mate 80 Pro', '麒麟 9100，卫星通信，XMAGE 影像', 6000.00, '/images/mate80pro.png', 1),
(5, 'OPPO Find X9 Ultra', '骁龙 8 Elite Gen 5，7050mAh，100W 快充，10 倍光变', 7999.00, '/images/oppofindx9ultra.png', 1),
(6, 'Xiaomi 17 Pro', '6.3 英寸 1.5K 小直屏，妙享背屏，192g 轻薄机身', 5599.00, '/images/xiaomi17pro.png', 1),
(7, 'iPhone 17', 'A19 处理器，120Hz ProMotion，4800 万超广角', 5999.00, '/images/iPhone17.png', 1);

-- 秒杀活动（时间覆盖全年的进行中活动，方便开发调试）
INSERT IGNORE INTO `flash_sale` (`id`, `item_id`, `flash_price`, `stock`, `limit_per_user`, `start_time`, `end_time`, `status`) VALUES
(1, 1, 899.00,  100, 1, '2026-06-15 00:00:00', '2026-12-31 23:59:59', 1),
(2, 2, 4999.00, 50,  1, '2026-06-01 00:00:00', '2026-09-30 00:00:00', 1),
(3, 3, 999.00,  200, 2, '2026-06-01 00:00:00', '2026-08-31 00:00:00', 1),
(4, 4, 5500.00, 100, 1, '2026-06-14 00:00:00', '2026-07-22 00:00:00', 1),
(5, 5, 5999.00, 80,  1, '2026-06-14 00:00:00', '2026-08-22 00:00:00', 1),
(6, 6, 3999.00, 60,  1, '2026-06-14 00:00:00', '2026-08-22 00:00:00', 1),
(7, 7, 4999.00, 120, 1, '2026-06-14 00:00:00', '2026-08-22 00:00:00', 1);
