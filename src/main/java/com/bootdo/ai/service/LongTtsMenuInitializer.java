package com.bootdo.ai.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import javax.annotation.PostConstruct;

@Component
public class LongTtsMenuInitializer {
    private final JdbcTemplate jdbcTemplate;
    public LongTtsMenuInitializer(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    @PostConstruct
    public void initialize() {
        try {
            Integer exists = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='sys_menu'", Integer.class);
            if (exists == null || exists == 0) return;
            jdbcTemplate.update("INSERT INTO sys_menu (menu_id,parent_id,name,url,perms,type,icon,order_num,gmt_create,gmt_modified) " +
                    "VALUES (214,0,'AI模型','','',0,'fa fa-microphone',10,NOW(),NULL) " +
                    "ON DUPLICATE KEY UPDATE parent_id=VALUES(parent_id),name=VALUES(name),url=VALUES(url),perms=VALUES(perms),type=VALUES(type),icon=VALUES(icon),order_num=VALUES(order_num)");
            jdbcTemplate.update("INSERT INTO sys_menu (menu_id,parent_id,name,url,perms,type,icon,order_num,gmt_create,gmt_modified) " +
                    "VALUES (216,214,'长语音生成','ai/tts','ai:tts:view',1,'fa fa-volume-up',0,NOW(),NULL) " +
                    "ON DUPLICATE KEY UPDATE parent_id=VALUES(parent_id),name=VALUES(name),url=VALUES(url),perms=VALUES(perms),type=VALUES(type),icon=VALUES(icon),order_num=VALUES(order_num)");
            jdbcTemplate.update("INSERT INTO sys_role_menu(role_id,menu_id) SELECT 1,214 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id=1 AND menu_id=214)");
            jdbcTemplate.update("INSERT INTO sys_role_menu(role_id,menu_id) SELECT 1,216 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id=1 AND menu_id=216)");
        } catch (Exception ignored) { }
    }
}
