package com.bootdo.ai.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/** 时光睡眠 Apple 订阅使用独立数据表，避免与 Navi、AgentClaw 和其他支付渠道串权益。 */
@Component
public class SleepVipSchemaInitializer {
    private final JdbcTemplate jdbcTemplate;

    public SleepVipSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS sleep_apple_entitlement (" +
                "original_transaction_id varchar(64) NOT NULL, guest_user_id bigint NOT NULL," +
                "latest_transaction_id varchar(64) NOT NULL, apple_product_id varchar(128) NOT NULL," +
                "expires_at datetime NOT NULL, environment varchar(20) DEFAULT NULL," +
                "gmt_create datetime NOT NULL, gmt_modified datetime NOT NULL," +
                "PRIMARY KEY (original_transaction_id), UNIQUE KEY uk_sleep_apple_guest (guest_user_id)," +
                "KEY idx_sleep_apple_latest_tx (latest_transaction_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS sleep_apple_account_binding (" +
                "user_id bigint NOT NULL, original_transaction_id varchar(64) NOT NULL," +
                "gmt_create datetime NOT NULL, gmt_modified datetime NOT NULL," +
                "PRIMARY KEY (user_id), UNIQUE KEY uk_sleep_apple_original (original_transaction_id))" +
                " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS sleep_apple_order (" +
                "transaction_id varchar(64) NOT NULL, original_transaction_id varchar(64) NOT NULL," +
                "user_id bigint DEFAULT NULL, guest_user_id bigint NOT NULL," +
                "apple_product_id varchar(128) NOT NULL, expires_at datetime NOT NULL," +
                "environment varchar(20) DEFAULT NULL, gmt_create datetime NOT NULL," +
                "PRIMARY KEY (transaction_id), KEY idx_sleep_order_original (original_transaction_id)," +
                "KEY idx_sleep_order_user (user_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }
}
