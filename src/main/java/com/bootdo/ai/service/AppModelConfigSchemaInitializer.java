package com.bootdo.ai.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Component
public class AppModelConfigSchemaInitializer {
    private final JdbcTemplate jdbcTemplate;

    public AppModelConfigSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS ai_app_model_config (" +
                "id BIGINT NOT NULL AUTO_INCREMENT, app_code VARCHAR(50) NOT NULL, config_type VARCHAR(20) NOT NULL," +
                "ai_provider VARCHAR(50) NOT NULL DEFAULT '', ai_base_url VARCHAR(300) NOT NULL DEFAULT ''," +
                "ai_api_key VARCHAR(400) NOT NULL DEFAULT '', ai_model VARCHAR(100) NOT NULL DEFAULT ''," +
                "enabled TINYINT NOT NULL DEFAULT 1, note VARCHAR(200) DEFAULT NULL," +
                "gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                "PRIMARY KEY(id), UNIQUE KEY uq_app_type(app_code, config_type)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用独立AI模型配置'");
        jdbcTemplate.update("INSERT IGNORE INTO ai_app_model_config " +
                        "(app_code,config_type,ai_provider,ai_base_url,ai_api_key,ai_model,enabled,gmt_create,gmt_modified) " +
                        "VALUES ('inspireplanet','TEXT','doubao','https://ark.cn-beijing.volces.com/api/v3','','doubao-seed-evolving',1,NOW(),NOW())");
        ensureMenus();
    }

    private void ensureMenus() {
        try {
            jdbcTemplate.update("INSERT IGNORE INTO sys_menu (menu_id,parent_id,name,url,perms,type,icon,order_num,gmt_create,gmt_modified) " +
                    "VALUES (220,0,'灵感星球','','',0,'fa fa-star',9,NOW(),NULL)");
            jdbcTemplate.update("INSERT IGNORE INTO sys_menu (menu_id,parent_id,name,url,perms,type,icon,order_num,gmt_create,gmt_modified) " +
                    "VALUES (221,220,'模型管理','inspireplanet/model-config','inspireplanet:model-config:view',1,'fa fa-cogs',1,NOW(),NULL)");
            jdbcTemplate.update("INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,220)");
            jdbcTemplate.update("INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,221)");
        } catch (Exception ignored) {
            // 菜单初始化失败不能影响已有业务启动。
        }
    }
}
