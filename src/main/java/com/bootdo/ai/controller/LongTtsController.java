package com.bootdo.ai.controller;

import com.bootdo.ai.service.LongTtsService;
import com.bootdo.common.utils.R;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;

@Controller
@RequestMapping("/ai/tts")
public class LongTtsController {
    private final LongTtsService service;
    public LongTtsController(LongTtsService service) { this.service = service; }

    @GetMapping
    public String index() { return "ai/tts/index"; }

    @PostMapping("/generate")
    @ResponseBody
    public R generate(@RequestParam("voiceFile") MultipartFile voiceFile,
                      @RequestParam(value = "engine", defaultValue = "sglang") String engine,
                      @RequestParam("referenceText") String referenceText,
                      @RequestParam("text") String text) {
        try {
            return R.ok().put("job", service.submit(voiceFile, referenceText, text, engine));
        } catch (IllegalArgumentException e) {
            return R.error(e.getMessage());
        } catch (Exception e) {
            return R.error("创建任务失败：" + e.getMessage());
        }
    }

    @GetMapping("/status/{id}")
    @ResponseBody
    public R status(@PathVariable("id") String id) {
        LongTtsService.Job job = service.get(id);
        return job == null ? R.error("任务不存在或服务已重启") : R.ok().put("job", job);
    }

    @GetMapping("/download/{id}")
    public ResponseEntity<FileSystemResource> download(@PathVariable("id") String id) {
        File file = service.output(id);
        if (file == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=ai-long-voice-" + id + ".mp3")
                .contentLength(file.length())
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .body(new FileSystemResource(file));
    }
}
