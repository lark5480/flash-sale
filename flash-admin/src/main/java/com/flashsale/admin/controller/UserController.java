package com.flashsale.admin.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.flashsale.common.result.ResultVO;
import com.flashsale.model.vo.UserVO;
import com.flashsale.service.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/list")
    public ResultVO<List<UserVO>> list(@RequestParam(defaultValue = "1") long page,
                                        @RequestParam(defaultValue = "10") long size) {
        IPage<com.flashsale.model.entity.User> userPage = userService.listUsers(page, size);
        List<UserVO> records = userPage.getRecords().stream().map(UserVO::from).toList();
        return ResultVO.success(records);
    }

    @PutMapping("/{id}/status")
    public ResultVO<Void> updateStatus(@PathVariable Long id, @RequestBody Map<String, Integer> body) {
        userService.updateStatus(id, body.get("status"));
        return ResultVO.success();
    }
}
