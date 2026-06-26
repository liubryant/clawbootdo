package com.bootdo.ai.controller;

import com.alibaba.fastjson.JSON;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import javax.annotation.PostConstruct;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sounds")
public class SoundResourceController {
    private final ResourceLoader resourceLoader;
    private volatile Map<String, SoundEntry> soundIndex = Collections.emptyMap();

    @Value("${iossleep.soundRoot:classpath:/static/sleep_sounds/}")
    private String soundRoot;

    public SoundResourceController(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    public void loadSoundIndex() throws Exception {
        Resource manifest = resourceLoader.getResource(soundRootPath() + "manifest.json");
        if (!manifest.exists()) {
            manifest = resourceLoader.getResource(soundRootPath() + "sounds_server_manifest.json");
        }
        if (!manifest.exists()) {
            soundIndex = Collections.emptyMap();
            return;
        }

        try (InputStream inputStream = manifest.getInputStream()) {
            String json = readUtf8(inputStream);
            List<SoundEntry> entries = JSON.parseArray(json, SoundEntry.class);
            Map<String, SoundEntry> index = new LinkedHashMap<>();
            for (SoundEntry entry : entries) {
                index.put(entry.getId(), entry);
            }
            soundIndex = index;
        }
    }

    @GetMapping
    public List<SoundEntry> list() {
        return Collections.unmodifiableList(new java.util.ArrayList<>(soundIndex.values()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SoundEntry> detail(@PathVariable("id") String id) {
        SoundEntry entry = soundIndex.get(id);
        if (entry == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(entry);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable("id") String id) throws Exception {
        SoundEntry entry = soundIndex.get(id);
        if (entry == null) {
            return ResponseEntity.notFound().build();
        }

        Resource audio = resourceLoader.getResource(soundRootPath() + entry.getRelativePath());
        if (!audio.exists()) {
            return ResponseEntity.notFound().build();
        }

        StreamingResponseBody body = outputStream -> {
            try (InputStream inputStream = audio.getInputStream()) {
                StreamUtils.copy(inputStream, outputStream);
            }
        };

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(entry.getContentType()));
        headers.setContentLength(entry.getSizeBytes());
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + entry.getId() + fileExtension(entry.getAudioFile()) + "\"");
        headers.add(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000");
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    private String fileExtension(String fileName) {
        int dotIndex = fileName == null ? -1 : fileName.lastIndexOf('.');
        return dotIndex >= 0 ? fileName.substring(dotIndex) : "";
    }

    private String soundRootPath() {
        return soundRoot.endsWith("/") ? soundRoot : soundRoot + "/";
    }

    private String readUtf8(InputStream inputStream) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        StreamUtils.copy(inputStream, outputStream);
        return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
    }

    public static class SoundEntry {
        private String id;
        private Integer index;
        private String directory;
        private String title;
        private String audioFile;
        private String relativePath;
        private Long sizeBytes;
        private String contentType;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public Integer getIndex() {
            return index;
        }

        public void setIndex(Integer index) {
            this.index = index;
        }

        public String getDirectory() {
            return directory;
        }

        public void setDirectory(String directory) {
            this.directory = directory;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getAudioFile() {
            return audioFile;
        }

        public void setAudioFile(String audioFile) {
            this.audioFile = audioFile;
        }

        public String getRelativePath() {
            return relativePath;
        }

        public void setRelativePath(String relativePath) {
            this.relativePath = relativePath;
        }

        public Long getSizeBytes() {
            return sizeBytes;
        }

        public void setSizeBytes(Long sizeBytes) {
            this.sizeBytes = sizeBytes;
        }

        public String getContentType() {
            return contentType;
        }

        public void setContentType(String contentType) {
            this.contentType = contentType;
        }
    }
}
