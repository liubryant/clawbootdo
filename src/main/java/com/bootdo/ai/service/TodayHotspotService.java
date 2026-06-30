package com.bootdo.ai.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TodayHotspotService {
    private final JdbcTemplate jdbcTemplate;

    public TodayHotspotService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_dict WHERE type='ai_today_hotspot' AND del_flag='0'", Integer.class);
        if (count != null && count == 0) {
            insertSeed("美加墨世界杯冠军预测", "美加墨世界杯冠军预测", "美加墨世界杯冠军预测", 10);
            insertSeed("高考志愿热门专业分析", "高考志愿热门专业和大学分析", "高考志愿热门专业和大学分析", 20);
            insertSeed("办公文档生成", "一句话写竞品分析、会议纪要",
                    "帮我生成一份智能汽车行业办公文档助手模板，支持竞品分析、会议纪要和待办事项输出。", 30);
        }
        initializeMenu();
        // 清理上一版本误建的专用表；热点改为复用 BootDo 通用字典表。
        jdbcTemplate.execute("DROP TABLE IF EXISTS ai_today_hotspot");
    }

    public List<Map<String, Object>> listAll() {
        return query("");
    }

    public List<Map<String, Object>> listEnabled() {
        return query(" AND value='1'");
    }

    public void save(Long id, String title, String subtitle, String promptTemplate, int sortOrder) {
        if (id == null) {
            jdbcTemplate.update("INSERT INTO sys_dict " +
                            "(name,value,type,description,sort,parent_id,create_date,update_date,remarks,del_flag) " +
                            "VALUES (?,'1','ai_today_hotspot',?,?,0,NOW(),NOW(),?,'0')",
                    title, subtitle, sortOrder, promptTemplate);
        } else {
            int rows = jdbcTemplate.update("UPDATE sys_dict SET name=?,description=?,remarks=?," +
                            "sort=?,update_date=NOW() WHERE id=? AND type='ai_today_hotspot' AND del_flag='0'",
                    title, subtitle, promptTemplate, sortOrder, id);
            if (rows == 0) throw new IllegalArgumentException("今日热点不存在");
        }
    }

    public void setEnabled(Long id, int enabled) {
        int rows = jdbcTemplate.update("UPDATE sys_dict SET value=?,update_date=NOW() " +
                        "WHERE id=? AND type='ai_today_hotspot' AND del_flag='0'",
                enabled == 1 ? "1" : "0", id);
        if (rows == 0) throw new IllegalArgumentException("今日热点不存在");
    }

    public void remove(Long id) {
        int rows = jdbcTemplate.update("DELETE FROM sys_dict WHERE id=? AND type='ai_today_hotspot'", id);
        if (rows == 0) throw new IllegalArgumentException("今日热点不存在");
    }

    private void insertSeed(String title, String subtitle, String promptTemplate, int sortOrder) {
        jdbcTemplate.update("INSERT INTO sys_dict " +
                        "(name,value,type,description,sort,parent_id,create_date,update_date,remarks,del_flag) " +
                        "VALUES (?,'1','ai_today_hotspot',?,?,0,NOW(),NOW(),?,'0')",
                title, subtitle, sortOrder, promptTemplate);
    }

    private List<Map<String, Object>> query(String condition) {
        String sql = "SELECT id,name,description,remarks,value,sort,create_date,update_date " +
                "FROM sys_dict WHERE type='ai_today_hotspot' AND del_flag='0'" + condition +
                " ORDER BY sort ASC,id ASC";
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", rs.getLong("id"));
            item.put("title", rs.getString("name"));
            item.put("subtitle", rs.getString("description"));
            item.put("promptTemplate", rs.getString("remarks"));
            item.put("enabled", "1".equals(rs.getString("value")) ? 1 : 0);
            item.put("sortOrder", rs.getInt("sort"));
            item.put("gmtCreate", rs.getTimestamp("create_date"));
            item.put("gmtModified", rs.getTimestamp("update_date"));
            return item;
        });
    }

    private void initializeMenu() {
        jdbcTemplate.update("INSERT INTO sys_menu " +
                "(menu_id,parent_id,name,url,perms,type,icon,order_num,gmt_create,gmt_modified) " +
                "VALUES (215,200,'今日热点','ai/today-hotspot','ai:today-hotspot:view',1,'fa fa-fire',2,NOW(),NOW()) " +
                "ON DUPLICATE KEY UPDATE parent_id=VALUES(parent_id),name=VALUES(name),url=VALUES(url)," +
                "perms=VALUES(perms),type=VALUES(type),icon=VALUES(icon),order_num=VALUES(order_num),gmt_modified=NOW()");
        jdbcTemplate.update("INSERT INTO sys_role_menu (role_id,menu_id) " +
                "SELECT DISTINCT source.role_id,215 FROM sys_role_menu source " +
                "WHERE source.menu_id IN (200,201) AND NOT EXISTS " +
                "(SELECT 1 FROM sys_role_menu existing WHERE existing.role_id=source.role_id AND existing.menu_id=215)");
    }
}
