package com.bootdo.ai.controller;

import com.bootdo.ai.service.MindfulnessContentService;
import com.bootdo.common.utils.R;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

@Controller
@RequestMapping("/ai/mindfulness")
public class MindfulnessController {
    private final MindfulnessContentService service;

    public MindfulnessController(MindfulnessContentService service) {
        this.service = service;
    }

    @GetMapping
    public String index() {
        return "ai/mindfulness/mindfulness";
    }

    @GetMapping("/list")
    @ResponseBody
    public R list() {
        return R.ok().put("data", service.listAll());
    }

    @PostMapping("/upload")
    @ResponseBody
    public R upload(@RequestParam("title") String title,
                    @RequestParam(value = "sortOrder", defaultValue = "0") int sortOrder,
                    @RequestParam("audio") MultipartFile audio) {
        try {
            service.save(title, sortOrder, audio);
            return R.ok("上传成功");
        } catch (IllegalArgumentException e) {
            return R.error(e.getMessage());
        } catch (Exception e) {
            return R.error("上传失败，请检查磁盘空间或文件格式");
        }
    }

    @PostMapping("/toggle")
    @ResponseBody
    public R toggle(@RequestParam("id") long id, @RequestParam("enabled") int enabled) {
        service.setEnabled(id, enabled == 1);
        return R.ok(enabled == 1 ? "已启用" : "已禁用");
    }

    @PostMapping("/remove")
    @ResponseBody
    public R remove(@RequestParam("id") long id) {
        try {
            service.remove(id);
            return R.ok("删除成功");
        } catch (Exception e) {
            return R.error("删除失败");
        }
    }
}
