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
        addPayChannelColumnIfMissing();
        if ("mock".equalsIgnoreCase(mode)) {
            jdbcTemplate.update("INSERT IGNORE INTO navi_vip_product " +
                            "(id,name,price,duration_days,description,enabled,sort_order,gmt_create,gmt_modified) " +
                            "VALUES (?,?,?,?,?,1,0,NOW(),NOW())",
                    "local_month", "本地测试月卡", new java.math.BigDecimal("0.01"), 30, "仅用于局域网联调，不调用微信");
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
