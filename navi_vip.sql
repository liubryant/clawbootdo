CREATE TABLE IF NOT EXISTS `navi_vip_product` (
  `id` varchar(32) NOT NULL,
  `name` varchar(64) NOT NULL,
  `price` decimal(10,2) NOT NULL,
  `duration_days` int NOT NULL,
  `description` varchar(255) DEFAULT NULL,
  `enabled` tinyint NOT NULL DEFAULT 1,
  `sort_order` int NOT NULL DEFAULT 0,
  `gmt_create` datetime NOT NULL,
  `gmt_modified` datetime NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `navi_vip_order` (
  `id` varchar(32) NOT NULL,
  `user_id` bigint NOT NULL,
  `product_id` varchar(32) NOT NULL,
  `amount` decimal(10,2) NOT NULL,
  `status` varchar(20) NOT NULL,
  `mock_order` tinyint NOT NULL DEFAULT 0,
  `pay_channel` varchar(10) DEFAULT NULL,
  `wx_transaction_id` varchar(64) DEFAULT NULL,
  `gmt_create` datetime NOT NULL,
  `gmt_modified` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_navi_vip_order_user` (`user_id`),
  KEY `idx_navi_vip_order_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `navi_vip_membership` (
  `user_id` bigint NOT NULL,
  `expires_at` datetime NOT NULL,
  `gmt_modified` datetime NOT NULL,
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
