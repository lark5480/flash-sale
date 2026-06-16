package com.flashsale.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flashsale.common.exception.BusinessException;
import com.flashsale.common.result.ResultCode;
import com.flashsale.common.util.JwtUtil;
import com.flashsale.common.util.PasswordUtil;
import com.flashsale.mapper.UserMapper;
import com.flashsale.model.dto.LoginDTO;
import com.flashsale.model.dto.RegisterDTO;
import com.flashsale.model.entity.User;
import com.flashsale.model.enums.UserRoleEnum;
import com.flashsale.model.vo.LoginVO;
import com.flashsale.model.vo.UserVO;
import com.flashsale.service.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final PasswordUtil passwordUtil;
    private final JwtUtil jwtUtil;

    @Value("${jwt.expiration:1800000}")
    private long expiration;

    public UserServiceImpl(UserMapper userMapper, PasswordUtil passwordUtil, JwtUtil jwtUtil) {
        this.userMapper = userMapper;
        this.passwordUtil = passwordUtil;
        this.jwtUtil = jwtUtil;
    }

    @Override
    public UserVO register(RegisterDTO dto) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, dto.getUsername());
        if (userMapper.selectCount(wrapper) > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "username already exists");
        }
        User user = new User();
        user.setUsername(dto.getUsername());
        user.setPassword(passwordUtil.encode(dto.getPassword()));
        user.setPhone(dto.getPhone());
        user.setEmail(dto.getEmail());
        user.setRole(UserRoleEnum.USER.getRole());
        user.setStatus(1);
        userMapper.insert(user);
        return UserVO.from(user);
    }

    @Override
    public LoginVO login(LoginDTO dto) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, dto.getUsername());
        User user = userMapper.selectOne(wrapper);
        if (user == null || !passwordUtil.matches(dto.getPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "invalid username or password");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException(ResultCode.FORBIDDEN, "account disabled");
        }
        String accessToken = jwtUtil.generateToken(user.getId(), user.getRole());
        String refreshToken = jwtUtil.generateRefreshToken(user.getId());
        return new LoginVO(accessToken, refreshToken, expiration);
    }

    @Override
    public UserVO getUserById(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "user not found");
        }
        return UserVO.from(user);
    }

    @Override
    public UserVO getUserByUsername(String username) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, username);
        User user = userMapper.selectOne(wrapper);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "user not found");
        }
        return UserVO.from(user);
    }

    @Override
    public IPage<User> listUsers(long page, long size) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(User::getCreateTime);
        return userMapper.selectPage(new Page<>(page, size), wrapper);
    }

    @Override
    public void updateStatus(Long userId, Integer status) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "user not found");
        }
        user.setStatus(status);
        userMapper.updateById(user);
    }

    @Override
    public LoginVO refreshToken(String refreshToken) {
        // 解析并校验 refreshToken
        Long userId;
        try {
            userId = jwtUtil.getUserId(refreshToken);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "invalid refresh token");
        }
        if (jwtUtil.isTokenExpired(refreshToken)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "refresh token expired");
        }

        // 查询用户状态
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "user not found");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException(ResultCode.FORBIDDEN, "account disabled");
        }

        // 颁发新 accessToken
        String newAccessToken = jwtUtil.generateToken(user.getId(), user.getRole());
        return new LoginVO(newAccessToken, refreshToken, expiration);
    }
}
