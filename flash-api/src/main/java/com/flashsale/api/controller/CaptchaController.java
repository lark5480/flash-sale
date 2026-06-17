package com.flashsale.api.controller;

import com.flashsale.common.result.ResultVO;
import com.flashsale.service.CaptchaService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class CaptchaController {

    private final CaptchaService captchaService;

    public CaptchaController(CaptchaService captchaService) {
        this.captchaService = captchaService;
    }

    /**
     * 获取图形验证码
     * <p>
     * 返回 captchaId（客户端提交时带上）和 svg（内联渲染）
     */
    @GetMapping("/captcha")
    public ResultVO<Map<String, String>> getCaptcha() {
        return ResultVO.success(captchaService.generate());
    }
}
