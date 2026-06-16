package com.flashsale.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.flashsale.model.dto.LoginDTO;
import com.flashsale.model.dto.RegisterDTO;
import com.flashsale.model.entity.User;
import com.flashsale.model.vo.LoginVO;
import com.flashsale.model.vo.UserVO;

public interface UserService {

    UserVO register(RegisterDTO registerDTO);

    LoginVO login(LoginDTO loginDTO);

    UserVO getUserById(Long userId);

    UserVO getUserByUsername(String username);

    IPage<User> listUsers(long page, long size);

    void updateStatus(Long userId, Integer status);

    /**
     * 使用 refreshToken 刷新 accessToken
     */
    LoginVO refreshToken(String refreshToken);
}
