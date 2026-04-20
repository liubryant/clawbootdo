package com.bootdo.system.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.bootdo.common.controller.BaseController;

@Controller
public class AutoTestController extends BaseController {
	@GetMapping({ "/autotest" })
	String welcome(Model model) {

		// return "redirect:/blog";
		return "login";
	}
}
