-- AgentClaw 今日热点：复用 sys_dict，不新增业务表。
INSERT INTO `sys_dict` (`name`,`value`,`type`,`description`,`sort`,`parent_id`,`create_date`,`update_date`,`remarks`,`del_flag`)
SELECT '美加墨世界杯冠军预测','1','ai_today_hotspot','美加墨世界杯冠军预测',10,0,NOW(),NOW(),'美加墨世界杯冠军预测','0'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_dict` WHERE `type`='ai_today_hotspot' AND `sort`=10 AND `del_flag`='0');

INSERT INTO `sys_dict` (`name`,`value`,`type`,`description`,`sort`,`parent_id`,`create_date`,`update_date`,`remarks`,`del_flag`)
SELECT '高考志愿热门专业分析','1','ai_today_hotspot','高考志愿热门专业和大学分析',20,0,NOW(),NOW(),'高考志愿热门专业和大学分析','0'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_dict` WHERE `type`='ai_today_hotspot' AND `sort`=20 AND `del_flag`='0');

INSERT INTO `sys_dict` (`name`,`value`,`type`,`description`,`sort`,`parent_id`,`create_date`,`update_date`,`remarks`,`del_flag`)
SELECT '办公文档生成','1','ai_today_hotspot','一句话写竞品分析、会议纪要',30,0,NOW(),NOW(),'帮我生成一份智能汽车行业办公文档助手模板，支持竞品分析、会议纪要和待办事项输出。','0'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_dict` WHERE `type`='ai_today_hotspot' AND `sort`=30 AND `del_flag`='0');

INSERT INTO `sys_menu`
(`menu_id`,`parent_id`,`name`,`url`,`perms`,`type`,`icon`,`order_num`,`gmt_create`,`gmt_modified`)
VALUES (215,200,'今日热点','ai/today-hotspot','ai:today-hotspot:view',1,'fa fa-fire',2,NOW(),NOW())
ON DUPLICATE KEY UPDATE `parent_id`=VALUES(`parent_id`),`name`=VALUES(`name`),`url`=VALUES(`url`),
`perms`=VALUES(`perms`),`type`=VALUES(`type`),`icon`=VALUES(`icon`),`order_num`=VALUES(`order_num`),`gmt_modified`=NOW();

-- 继承“对话管理/对话列表”的角色，不绑定某一个具体用户。
INSERT INTO `sys_role_menu` (`role_id`,`menu_id`)
SELECT DISTINCT source.`role_id`,215 FROM `sys_role_menu` source
WHERE source.`menu_id` IN (200,201)
AND NOT EXISTS (SELECT 1 FROM `sys_role_menu` existing WHERE existing.`role_id`=source.`role_id` AND existing.`menu_id`=215);
