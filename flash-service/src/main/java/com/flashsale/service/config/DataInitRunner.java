package com.flashsale.service.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flashsale.common.util.PasswordUtil;
import com.flashsale.mapper.UserMapper;
import com.flashsale.model.entity.User;
import com.flashsale.model.enums.UserRoleEnum;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class DataInitRunner implements CommandLineRunner {

    private final UserMapper userMapper;
    private final PasswordUtil passwordUtil;

    public DataInitRunner(UserMapper userMapper, PasswordUtil passwordUtil) {
        this.userMapper = userMapper;
        this.passwordUtil = passwordUtil;
    }

    @Override
    public void run(String... args) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, "admin");
        if (userMapper.selectCount(wrapper) == 0) {
            User admin = new User();
            admin.setUsername("admin");
            admin.setPassword(passwordUtil.encode("admin123"));
            admin.setRole(UserRoleEnum.ADMIN.getRole());
            admin.setStatus(1);
            userMapper.insert(admin);
        }
    }
}
