package com.bootdo.ai.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Component
public class NaviVipSchemaInitializer {
    private final JdbcTemplate jdbcTemplate;

    @Value("${navi.vip.mode:disabled}")
    private String mode;

    public NaviVipSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        if ("disabled".equalsIgnoreCase(mode)) {
            return;
        }
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS navi_vip_product (" +
                "id varchar(32) NOT NULL, name varchar(64) NOT NULL, price decimal(10,2) NOT NULL," +
                "duration_days int NOT NULL, description varchar(255) DEFAULT NULL, enabled tinyint NOT NULL DEFAULT 1," +
                "sort_order int NOT NULL DEFAULT 0, gmt_create datetime NOT NULL, gmt_modified datetime NOT NULL," +
                "PRIMARY KEY (id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS navi_vip_order (" +
                "id varchar(32) NOT NULL, user_id bigint NOT NULL, product_id varchar(32) NOT NULL," +
                "amount decimal(10,2) NOT NULL, status varchar(20) NOT NULL, mock_order tinyint NOT NULL DEFAULT 0," +
                "wx_transaction_id varchar(64) DEFAULT NULL, gmt_create datetime NOT NULL, gmt_modified datetime NOT NULL," +
                "PRIMARY KEY (id), KEY idx_navi_vip_order_user (user_id), KEY idx_navi_vip_order_status (status))" +
                " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS navi_vip_membership (" +
                "user_id bigint NOT NULL, expires_at datetime NOT NULL, gmt_modified datetime NOT NULL," +
                "PRIMARY KEY (user_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS navi_apple_entitlement (" +
                "original_transaction_id varchar(64) NOT NULL, guest_user_id bigint NOT NULL," +
                "latest_transaction_id varchar(64) NOT NULL, apple_product_id varchar(128) NOT NULL," +
                "expires_at datetime NOT NULL, environment varchar(20) DEFAULT NULL," +
                "gmt_create datetime NOT NULL, gmt_modified datetime NOT NULL," +
                "PRIMARY KEY (original_transaction_id), UNIQUE KEY uk_apple_guest_user (guest_user_id)," +
                "KEY idx_apple_latest_tx (latest_transaction_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS navi_apple_account_binding (" +
                "user_id bigint NOT NULL, original_transaction_id varchar(64) NOT NULL," +
                "gmt_create datetime NOT NULL, gmt_modified datetime NOT NULL," +
                "PRIMARY KEY (user_id), KEY idx_apple_binding_original (original_transaction_id))" +
                " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        addPayChannelColumnIfMissing();
        initQuotaConfig();
        ensureProductMenuEntry();
        if ("mock".equalsIgnoreCase(mode)) {
            jdbcTemplate.update("INSERT IGNORE INTO navi_vip_product " +
                            "(id,name,price,duration_days,description,enabled,sort_order,gmt_create,gmt_modified) " +
                            "VALUES (?,?,?,?,?,1,0,NOW(),NOW())",
                    "local_month", "本地测试月卡", new java.math.BigDecimal("0.01"), 30, "仅用于局域网联调，不调用微信");
        }
    }

    private void initQuotaConfig() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS ai_quota_config (" +
                "id int NOT NULL AUTO_INCREMENT," +
                "app_name varchar(50) NOT NULL DEFAULT 'agentclaw'," +
                "free_video_daily int NOT NULL DEFAULT 1," +
                "free_image_daily int NOT NULL DEFAULT 10," +
                "vip_video_daily int NOT NULL DEFAULT 5," +
                "vip_image_daily int NOT NULL DEFAULT 50," +
                "vip_remind_days int NOT NULL DEFAULT 3," +
                "gmt_create datetime NOT NULL," +
                "gmt_modified datetime NOT NULL," +
                "PRIMARY KEY (id)," +
                "UNIQUE KEY uk_quota_app_name (app_name)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbcTemplate.update("INSERT IGNORE INTO ai_quota_config " +
                "(app_name,free_video_daily,free_image_daily,vip_video_daily,vip_image_daily,vip_remind_days,gmt_create,gmt_modified) " +
                "VALUES (?,?,?,?,?,?,NOW(),NOW())",
                "agentclaw", 1, 10, 5, 50, 3);
    }

    /**
     * 在“对话管理”下注册商品列表，并授权给超级管理员。
     * 全部语句均为幂等操作，菜单初始化失败不会影响支付服务启动。
     */
    private void ensureProductMenuEntry() {
        try {
            Integer tableExists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables " +
                            "WHERE table_schema=DATABASE() AND table_name='sys_menu'",
                    Integer.class);
            if (tableExists == null || tableExists == 0) return;

            jdbcTemplate.update(
                    "INSERT IGNORE INTO sys_menu " +
                            "(menu_id,parent_id,name,url,perms,type,icon,order_num,gmt_create,gmt_modified) " +
                            "VALUES (200,0,'对话管理','','',0,'fa fa-comments',8,NOW(),NULL)");
            jdbcTemplate.update(
                    "INSERT IGNORE INTO sys_menu " +
                            "(menu_id,parent_id,name,url,perms,type,icon,order_num,gmt_create,gmt_modified) " +
                            "VALUES (218,200,'商品列表','ai/vip-product','ai:vip-product:view',1,'fa fa-shopping-cart',5,NOW(),NULL)");
            jdbcTemplate.update(
                    "INSERT INTO sys_role_menu(role_id,menu_id) " +
                            "SELECT 1,200 FROM DUAL WHERE NOT EXISTS " +
                            "(SELECT 1 FROM sys_role_menu WHERE role_id=1 AND menu_id=200)");
            jdbcTemplate.update(
                    "INSERT INTO sys_role_menu(role_id,menu_id) " +
                            "SELECT 1,218 FROM DUAL WHERE NOT EXISTS " +
                            "(SELECT 1 FROM sys_role_menu WHERE role_id=1 AND menu_id=218)");
        } catch (Exception ignored) {
            // 后台菜单异常不能阻断支付接口启动。
        }
    }

    /**
     * navi_vip_order 表上线时没有 pay_channel 列，这里做一次幂等的补列，
     * 避免重复执行 ALTER TABLE 报错。
     */
    private void addPayChannelColumnIfMissing() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns " +
                        "WHERE table_schema=DATABASE() AND table_name='navi_vip_order' AND column_name='pay_channel'",
                Integer.class);
        if (count == null || count == 0) {
            jdbcTemplate.execute("ALTER TABLE navi_vip_order ADD COLUMN pay_channel varchar(10) DEFAULT NULL AFTER mock_order");
        }
    }
}
