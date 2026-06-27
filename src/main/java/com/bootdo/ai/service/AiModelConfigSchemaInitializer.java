package com.bootdo.ai.service;

import com.bootdo.ai.config.AiProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * 自动建表并写入 4 种接口类型的默认配置行。
 * 默认 ai_api_key 为空串，回退到 application.yml 本地配置。
 */
@Component
public class AiModelConfigSchemaInitializer {

    private final JdbcTemplate jdbcTemplate;
    private final AiProperties aiProperties;

    public AiModelConfigSchemaInitializer(JdbcTemplate jdbcTemplate, AiProperties aiProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.aiProperties = aiProperties;
    }

    @PostConstruct
    public void initialize() {
        ensureMenuEntry();
        jdbcTemplate.execute(
            "CREATE TABLE IF NOT EXISTS ai_model_config (" +
            "  id           BIGINT        NOT NULL AUTO_INCREMENT," +
            "  config_type  VARCHAR(20)   NOT NULL COMMENT 'TEXT|IMAGE|VIDEO|IMAGE_EDIT'," +
            "  ai_provider  VARCHAR(50)   NOT NULL DEFAULT ''," +
            "  ai_base_url  VARCHAR(300)  NOT NULL DEFAULT ''," +
            "  ai_api_key   VARCHAR(400)  NOT NULL DEFAULT ''," +
            "  ai_model     VARCHAR(100)  NOT NULL DEFAULT ''," +
            "  enabled      TINYINT       NOT NULL DEFAULT 1," +
            "  note         VARCHAR(200)  DEFAULT NULL," +
            "  gmt_create   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP," +
            "  gmt_modified DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP," +
            "  PRIMARY KEY (id)," +
            "  UNIQUE KEY uq_config_type (config_type)" +
            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI模型接口配置'"
        );

        String glmBase   = safe(aiProperties.getGlm().getBaseUrl(),  "https://open.bigmodel.cn/api/paas/v4");
        String glmKey    = safe(aiProperties.getGlm().getApiKey(),   "");
        String sfBase    = aiProperties.getSiliconflow() == null ? "https://api.siliconflow.cn/v1"
                         : safe(aiProperties.getSiliconflow().getBaseUrl(), "https://api.siliconflow.cn/v1");
        String sfKey     = aiProperties.getSiliconflow() == null ? ""
                         : safe(aiProperties.getSiliconflow().getApiKey(), "");
        String textModel = safe(aiProperties.getGlm().getModel(),       "glm-4.7");
        String imgModel  = safe(aiProperties.getGlm().getImageModel(),  "glm-image");
        String vidModel  = safe(aiProperties.getGlm().getVideoModel(),  "cogvideox-3");
        String ieModel   = aiProperties.getSiliconflow() == null ? "Qwen/Qwen-Image-Edit-2509"
                         : safe(aiProperties.getSiliconflow().getImageEditModel(), "Qwen/Qwen-Image-Edit-2509");
        // IMAGE_EDIT prefers SiliconFlow key; fall back to GLM key if not set
        String ieKey = sfKey.isEmpty() ? glmKey : sfKey;

        String sql = "INSERT IGNORE INTO ai_model_config " +
                     "(config_type, ai_provider, ai_base_url, ai_api_key, ai_model, enabled, gmt_create, gmt_modified) " +
                     "VALUES (?, ?, ?, ?, ?, 1, NOW(), NOW())";

        jdbcTemplate.update(sql, "TEXT",       "glm",         glmBase, glmKey, textModel);
        jdbcTemplate.update(sql, "IMAGE",      "glm",         glmBase, glmKey, imgModel);
        jdbcTemplate.update(sql, "VIDEO",      "glm",         glmBase, glmKey, vidModel);
        jdbcTemplate.update(sql, "IMAGE_EDIT", "siliconflow", sfBase,  ieKey,  ieModel);
    }

    /**
     * 在「对话管理」(menu_id=200) 下追加「模型管理」菜单，幂等执行。
     * 不影响任何现有菜单数据。
     */
    private void ensureMenuEntry() {
        try {
            // 如果 sys_menu 表不存在（全新库）则跳过，避免报错
            Integer tableExists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables " +
                "WHERE table_schema = DATABASE() AND table_name = 'sys_menu'",
                Integer.class);
            if (tableExists == null || tableExists == 0) return;

            // 插入「对话管理」父菜单（如果不存在）
            jdbcTemplate.update(
                "INSERT IGNORE INTO sys_menu " +
                "(menu_id, parent_id, name, url, perms, type, icon, order_num, gmt_create, gmt_modified) " +
                "VALUES (200, 0, '对话管理', '', '', 0, 'fa fa-comments', 8, NOW(), NULL)");

            // 插入「模型管理」子菜单
            jdbcTemplate.update(
                "INSERT IGNORE INTO sys_menu " +
                "(menu_id, parent_id, name, url, perms, type, icon, order_num, gmt_create, gmt_modified) " +
                "VALUES (213, 200, '模型管理', 'ai/model-config', 'ai:model-config:view', 1, 'fa fa-cogs', 1, NOW(), NULL)");

            // 授权给 role_id=1（超级管理员）
            jdbcTemplate.update(
                "INSERT IGNORE INTO sys_role_menu (role_id, menu_id) " +
                "SELECT 1, 200 FROM DUAL " +
                "WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 200)");
            jdbcTemplate.update(
                "INSERT IGNORE INTO sys_role_menu (role_id, menu_id) " +
                "SELECT 1, 213 FROM DUAL " +
                "WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 213)");
        } catch (Exception e) {
            // 菜单写入失败不阻断启动
        }
    }

    private String safe(String value, String defaultValue) {
        return (value == null || value.trim().isEmpty()) ? defaultValue : value.trim();
    }
}
