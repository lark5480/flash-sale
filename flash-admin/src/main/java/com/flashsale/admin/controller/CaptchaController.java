package com.flashsale.admin.controller;

import com.flashsale.common.result.ResultVO;
import com.flashsale.service.CaptchaService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/admin/auth")
public class CaptchaController {

    private final CaptchaService captchaService;

    public CaptchaController(CaptchaService captchaService) {
        this.captchaService = captchaService;
    }

    @GetMapping("/captcha")
    public ResultVO<Map<String, String>> getCaptcha() {
        return ResultVO.success(captchaService.generate());
    }
}
