package com.bootdo.ai.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class MindfulnessContentService {
    private static final long MAX_AUDIO_SIZE = 30L * 1024L * 1024L;
    private static final List<String> ALLOWED_EXTENSIONS = Arrays.asList("mp3", "m4a", "aac", "wav", "ogg");

    private final JdbcTemplate jdbcTemplate;

    @Value("${bootdo.uploadPath:/var/uploaded_files/}")
    private String uploadRoot;

    public MindfulnessContentService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS hossleep_mindfulness (" +
                "id bigint NOT NULL AUTO_INCREMENT," +
                "title varchar(100) NOT NULL," +
                "file_name varchar(120) NOT NULL," +
                "original_name varchar(255) DEFAULT NULL," +
                "content_type varchar(80) NOT NULL," +
                "size_bytes bigint NOT NULL," +
                "enabled tinyint NOT NULL DEFAULT 1," +
                "sort_order int NOT NULL DEFAULT 0," +
                "gmt_create datetime NOT NULL," +
                "gmt_modified datetime NOT NULL," +
                "PRIMARY KEY (id), KEY idx_hossleep_mindfulness_enabled_sort (enabled,sort_order,id)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='时光睡眠正念音频'");
        ensureMenu();
        try {
            Files.createDirectories(storageDirectory());
        } catch (IOException e) {
            throw new IllegalStateException("无法创建正念音频目录: " + storageDirectory(), e);
        }
    }

    public List<Map<String, Object>> listAll() {
        return jdbcTemplate.queryForList("SELECT id,title,original_name AS originalName,content_type AS contentType," +
                "size_bytes AS sizeBytes,enabled,sort_order AS sortOrder,gmt_create AS gmtCreate " +
                "FROM hossleep_mindfulness ORDER BY sort_order ASC,id DESC");
    }

    public List<Map<String, Object>> listEnabled() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT id,title,content_type AS contentType," +
                "size_bytes AS sizeBytes,sort_order AS sortOrder FROM hossleep_mindfulness " +
                "WHERE enabled=1 ORDER BY sort_order ASC,id DESC");
        for (Map<String, Object> row : rows) {
            row.put("downloadUrl", "/api/mindfulness/" + row.get("id") + "/download");
        }
        return rows;
    }

    public Map<String, Object> findEnabled(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT id,title,file_name AS fileName," +
                "original_name AS originalName,content_type AS contentType,size_bytes AS sizeBytes " +
                "FROM hossleep_mindfulness WHERE id=? AND enabled=1", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void save(String title, int sortOrder, MultipartFile audio) throws IOException {
        String normalizedTitle = title == null ? "" : title.trim();
        if (normalizedTitle.isEmpty() || normalizedTitle.length() > 100) {
            throw new IllegalArgumentException("标题不能为空且不能超过100个字符");
        }
        if (audio == null || audio.isEmpty()) {
            throw new IllegalArgumentException("请选择音频文件");
        }
        if (audio.getSize() > MAX_AUDIO_SIZE) {
            throw new IllegalArgumentException("音频文件不能超过30MB");
        }
        String originalName = StringUtils.cleanPath(audio.getOriginalFilename() == null ? "audio.mp3" : audio.getOriginalFilename());
        String extension = extensionOf(originalName);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("仅支持 MP3、M4A、AAC、WAV、OGG 音频");
        }
        String storedName = UUID.randomUUID().toString().replace("-", "") + "." + extension;
        Path destination = storageDirectory().resolve(storedName).normalize();
        if (!destination.getParent().equals(storageDirectory())) {
            throw new IllegalArgumentException("文件名不合法");
        }
        try (InputStream inputStream = audio.getInputStream()) {
            Files.copy(inputStream, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        String contentType = contentType(extension, audio.getContentType());
        try {
            jdbcTemplate.update("INSERT INTO hossleep_mindfulness " +
                    "(title,file_name,original_name,content_type,size_bytes,enabled,sort_order,gmt_create,gmt_modified) " +
                    "VALUES (?,?,?,?,?,1,?,NOW(),NOW())", normalizedTitle, storedName, originalName, contentType,
                    audio.getSize(), sortOrder);
        } catch (RuntimeException e) {
            Files.deleteIfExists(destination);
            throw e;
        }
    }

    public void setEnabled(long id, boolean enabled) {
        jdbcTemplate.update("UPDATE hossleep_mindfulness SET enabled=?,gmt_modified=NOW() WHERE id=?", enabled ? 1 : 0, id);
    }

    public void remove(long id) throws IOException {
        List<String> names = jdbcTemplate.query("SELECT file_name FROM hossleep_mindfulness WHERE id=?",
                new Object[]{id}, (rs, rowNum) -> rs.getString(1));
        if (names.isEmpty()) return;
        jdbcTemplate.update("DELETE FROM hossleep_mindfulness WHERE id=?", id);
        Files.deleteIfExists(resolveAudio(names.get(0)));
    }

    public Path resolveAudio(String fileName) {
        Path resolved = storageDirectory().resolve(fileName).normalize();
        if (!resolved.getParent().equals(storageDirectory())) {
            throw new IllegalArgumentException("文件路径不合法");
        }
        return resolved;
    }

    private Path storageDirectory() {
        return new File(uploadRoot, "hossleep/mindfulness").toPath().toAbsolutePath().normalize();
    }

    private String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String contentType(String extension, String provided) {
        if ("mp3".equals(extension)) return "audio/mpeg";
        if ("m4a".equals(extension)) return "audio/mp4";
        if ("aac".equals(extension)) return "audio/aac";
        if ("wav".equals(extension)) return "audio/wav";
        if ("ogg".equals(extension)) return "audio/ogg";
        return provided == null ? "application/octet-stream" : provided;
    }

    private void ensureMenu() {
        try {
            jdbcTemplate.update("INSERT INTO sys_menu (menu_id,parent_id,name,url,perms,type,icon,order_num,gmt_create,gmt_modified) " +
                    "VALUES (217,214,'正念列表','ai/mindfulness','ai:mindfulness:view',1,'fa fa-headphones',1,NOW(),NULL) " +
                    "ON DUPLICATE KEY UPDATE parent_id=VALUES(parent_id),name=VALUES(name),url=VALUES(url)," +
                    "perms=VALUES(perms),type=VALUES(type),icon=VALUES(icon),order_num=VALUES(order_num)");
            jdbcTemplate.update("INSERT INTO sys_role_menu(role_id,menu_id) SELECT 1,217 FROM DUAL " +
                    "WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id=1 AND menu_id=217)");
        } catch (Exception ignored) {
        }
    }
}
