package com.flashsale.common.util;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 密码工具类 —— BCrypt 加密与校验.
 * <p>
 * BCrypt 内置盐值，每次 encode 结果不同，通过 {@link #matches(String, String)} 比对明文与密文.
 * Spring Security 默认实现，强度为 10 轮.
 */
@Component
public class PasswordUtil {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /**
     * 加密明文密码.
     *
     * @param rawPassword 明文
     * @return BCrypt 密文（60 字符，$2a$ 前缀）
     */
    public String encode(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    /**
     * 校验明文与密文是否匹配.
     *
     * @param rawPassword     明文
     * @param encodedPassword 已加密的密文
     * @return true 表示匹配
     */
    public boolean matches(String rawPassword, String encodedPassword) {
        return encoder.matches(rawPassword, encodedPassword);
    }
}
