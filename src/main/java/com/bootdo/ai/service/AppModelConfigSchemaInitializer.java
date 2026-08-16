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
        jdbcTemplate.update("INSERT IGNORE INTO ai_app_model_config " +
                        "(app_code,config_type,ai_provider,ai_base_url,ai_api_key,ai_model,enabled,note,gmt_create,gmt_modified) " +
                        "VALUES ('inspireplanet','VIDEO_TEXT','doubao','https://ark.cn-beijing.volces.com/api/v3',''," +
                        "'doubao-seedance-1-5-pro-251215',1,'豆包文生视频（支持后台独立配置）',NOW(),NOW())");
        // 首次升级时沿用同一应用已配置的豆包 Key，管理员之后仍可在文生视频区域独立修改。
        jdbcTemplate.update("UPDATE ai_app_model_config video " +
                "JOIN ai_app_model_config text_config ON text_config.app_code=video.app_code AND text_config.config_type='TEXT' " +
                "SET video.ai_api_key=text_config.ai_api_key, video.gmt_modified=NOW() " +
                "WHERE video.app_code='inspireplanet' AND video.config_type='VIDEO_TEXT' " +
                "AND (video.ai_api_key IS NULL OR video.ai_api_key='') AND text_config.ai_api_key<>''");
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
