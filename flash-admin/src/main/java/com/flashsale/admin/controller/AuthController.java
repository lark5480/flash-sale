package com.flashsale.admin.controller;

import com.flashsale.common.result.ResultVO;
import com.flashsale.model.dto.LoginDTO;
import com.flashsale.model.vo.LoginVO;
import com.flashsale.model.vo.UserVO;
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

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/login")
    public ResultVO<LoginVO> login(@Valid @RequestBody LoginDTO loginDTO) {
        UserVO userVO = userService.getUserByUsername(loginDTO.getUsername());
        if (!"ADMIN".equals(userVO.getRole())) {
            return ResultVO.fail(403, "admin access required");
        }
        LoginVO loginVO = userService.login(loginDTO);
        return ResultVO.success(loginVO);
    }
}
