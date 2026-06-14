ALTER TABLE `ai_app_user`
  ADD COLUMN `device_model` varchar(64) DEFAULT NULL COMMENT '手机型号' AFTER `password`,
  ADD COLUMN `os_version` varchar(32) DEFAULT NULL COMMENT 'iOS系统版本' AFTER `device_model`,
  ADD COLUMN `app_name` varchar(64) DEFAULT NULL COMMENT 'App名称' AFTER `os_version`,
  MODIFY COLUMN `password` varchar(128) DEFAULT NULL COMMENT '登录密码（AES可逆加密存储），仅使用过密码登录时才会设置';
