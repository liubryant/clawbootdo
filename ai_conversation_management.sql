CREATE TABLE IF NOT EXISTS `ai_conversation_log` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `device_name` varchar(128) DEFAULT NULL COMMENT '请求设备名称',
  `request_ip` varchar(64) DEFAULT NULL COMMENT '请求IP',
  `conversation_type` varchar(20) DEFAULT NULL COMMENT '对话类型：TEXT/IMAGE/VIDEO',
  `conversation_content` mediumtext COMMENT '对话内容',
  `conversation_result` mediumtext COMMENT '对话结果',
  `model` varchar(128) DEFAULT NULL COMMENT '模型',
  `stream` tinyint(1) DEFAULT NULL COMMENT '是否流式',
  `success` tinyint(1) DEFAULT NULL COMMENT '是否成功',
  `elapsed_ms` int(11) DEFAULT NULL COMMENT '耗时毫秒',
  `gmt_create` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_ai_conversation_type` (`conversation_type`),
  KEY `idx_ai_conversation_device` (`device_name`),
  KEY `idx_ai_conversation_create` (`gmt_create`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI对话日志';

INSERT INTO `sys_menu`
(`menu_id`, `parent_id`, `name`, `url`, `perms`, `type`, `icon`, `order_num`, `gmt_create`, `gmt_modified`)
VALUES
(200, 0, '对话管理', '', '', 0, 'fa fa-comments', 8, NOW(), NULL)
ON DUPLICATE KEY UPDATE
  `parent_id` = VALUES(`parent_id`),
  `name` = VALUES(`name`),
  `url` = VALUES(`url`),
  `perms` = VALUES(`perms`),
  `type` = VALUES(`type`),
  `icon` = VALUES(`icon`),
  `order_num` = VALUES(`order_num`);

INSERT INTO `sys_menu`
(`menu_id`, `parent_id`, `name`, `url`, `perms`, `type`, `icon`, `order_num`, `gmt_create`, `gmt_modified`)
VALUES
(201, 200, '对话列表', 'ai/conversation', 'ai:conversation:conversation', 1, 'fa fa-commenting', 0, NOW(), NULL),
(202, 201, '刷新', '', 'ai:conversation:list', 2, '', 0, NOW(), NULL),
(203, 201, '删除', '', 'ai:conversation:remove', 2, '', 0, NOW(), NULL),
(204, 201, '批量删除', '', 'ai:conversation:batchRemove', 2, '', 0, NOW(), NULL)
ON DUPLICATE KEY UPDATE
  `parent_id` = VALUES(`parent_id`),
  `name` = VALUES(`name`),
  `url` = VALUES(`url`),
  `perms` = VALUES(`perms`),
  `type` = VALUES(`type`),
  `icon` = VALUES(`icon`),
  `order_num` = VALUES(`order_num`);

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 1, 200 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `sys_role_menu` WHERE `role_id` = 1 AND `menu_id` = 200);

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 1, 201 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `sys_role_menu` WHERE `role_id` = 1 AND `menu_id` = 201);

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 1, 202 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `sys_role_menu` WHERE `role_id` = 1 AND `menu_id` = 202);

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 1, 203 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `sys_role_menu` WHERE `role_id` = 1 AND `menu_id` = 203);

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 1, 204 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `sys_role_menu` WHERE `role_id` = 1 AND `menu_id` = 204);
