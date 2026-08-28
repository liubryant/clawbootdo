package com.bootdo.ai.controller;

import com.bootdo.ai.service.MindfulnessContentService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/mindfulness")
public class MindfulnessPublicController {
    private final MindfulnessContentService service;

    public MindfulnessPublicController(MindfulnessContentService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return service.listEnabled();
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable("id") long id) throws Exception {
        Map<String, Object> item = service.findEnabled(id);
        if (item == null) return ResponseEntity.notFound().build();
        Path path = service.resolveAudio(String.valueOf(item.get("fileName")));
        if (!Files.isRegularFile(path)) return ResponseEntity.notFound().build();
        InputStream inputStream = Files.newInputStream(path);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(String.valueOf(item.get("contentType"))))
                .contentLength(Files.size(path))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"mindfulness-" + id + fileExtension(path) + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                .body(new InputStreamResource(inputStream));
    }

    private String fileExtension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot);
    }
}
