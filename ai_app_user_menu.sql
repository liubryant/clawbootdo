INSERT INTO `sys_menu`
(`menu_id`, `parent_id`, `name`, `url`, `perms`, `type`, `icon`, `order_num`, `gmt_create`, `gmt_modified`)
VALUES
(210, 0, '用户信息', '', '', 0, 'fa fa-users', 9, NOW(), NULL)
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
(211, 210, '用户列表', 'ai/appuser', 'ai:appuser:appuser', 1, 'fa fa-address-book', 0, NOW(), NULL),
(212, 211, '刷新', '', 'ai:appuser:list', 2, '', 0, NOW(), NULL)
ON DUPLICATE KEY UPDATE
  `parent_id` = VALUES(`parent_id`),
  `name` = VALUES(`name`),
  `url` = VALUES(`url`),
  `perms` = VALUES(`perms`),
  `type` = VALUES(`type`),
  `icon` = VALUES(`icon`),
  `order_num` = VALUES(`order_num`);

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 1, 210 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `sys_role_menu` WHERE `role_id` = 1 AND `menu_id` = 210);

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 1, 211 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `sys_role_menu` WHERE `role_id` = 1 AND `menu_id` = 211);

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 1, 212 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `sys_role_menu` WHERE `role_id` = 1 AND `menu_id` = 212);
