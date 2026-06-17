package com.flashsale.admin.controller;

import com.flashsale.common.annotation.RateLimit;
import com.flashsale.common.result.ResultCode;
import com.flashsale.common.result.ResultVO;
import com.flashsale.model.dto.LoginDTO;
import com.flashsale.model.vo.LoginVO;
import com.flashsale.model.vo.UserVO;
import com.flashsale.service.CaptchaService;
import com.flashsale.service.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/auth")
public class AuthController {

    private final UserService userService;
    private final CaptchaService captchaService;

    public AuthController(UserService userService, CaptchaService captchaService) {
        this.userService = userService;
        this.captchaService = captchaService;
    }

    @RateLimit(key = "admin-login", permits = 3, windowSeconds = 60)
    @PostMapping("/login")
    public ResultVO<LoginVO> login(@Valid @RequestBody LoginDTO loginDTO) {
        if (!captchaService.validate(loginDTO.getCaptchaId(), loginDTO.getCaptchaAnswer())) {
            return ResultVO.fail(ResultCode.CAPTCHA_ERROR, "验证码错误或已过期");
        }
        UserVO userVO = userService.getUserByUsername(loginDTO.getUsername());
        if (!"ADMIN".equals(userVO.getRole())) {
            return ResultVO.fail(403, "admin access required");
        }
        LoginVO loginVO = userService.login(loginDTO);
        return ResultVO.success(loginVO);
    }
}
