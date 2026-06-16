package com.flashsale.api.controller;

import com.flashsale.common.result.ResultVO;
import com.flashsale.model.dto.LoginDTO;
import com.flashsale.model.dto.RegisterDTO;
import com.flashsale.model.vo.LoginVO;
import com.flashsale.model.vo.UserVO;
import com.flashsale.service.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    public ResultVO<UserVO> register(@Valid @RequestBody RegisterDTO registerDTO) {
        UserVO userVO = userService.register(registerDTO);
        return ResultVO.success(userVO);
    }

    @PostMapping("/login")
    public ResultVO<LoginVO> login(@Valid @RequestBody LoginDTO loginDTO) {
        LoginVO loginVO = userService.login(loginDTO);
        return ResultVO.success(loginVO);
    }

    @PostMapping("/refresh")
    public ResultVO<LoginVO> refresh(@RequestParam String refreshToken) {
        LoginVO loginVO = userService.refreshToken(refreshToken);
        return ResultVO.success(loginVO);
    }
}
