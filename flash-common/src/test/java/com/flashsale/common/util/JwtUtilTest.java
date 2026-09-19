package com.flashsale.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

/**
 * {@link JwtUtil} 签名密钥注入与启动校验测试。
 * <p>
 * 一旦 {@code @Value} 带上默认值，「忘配 JWT_SECRET」就退化成「用仓库里公开的那把 key 签发」，
 * 任何读者都能离线伪造 {@code role=ADMIN} 的 Token 绕过网关与 api 两道鉴权。因此两件事都要钉住：
 * 注入方式不许带默认值，且缺失/过短时必须在启动阶段失败（密钥只在签发验签时才被读取，
 * 不校验的话应用能正常起来，第一个登录请求才炸）。
 */
class JwtUtilTest {

    @Test
    @DisplayName("jwt.secret 不允许带默认值")
    void secretInjectionMustNotHaveFallback() throws Exception {
        Field field = JwtUtil.class.getDeclaredField("secret");
        Value annotation = field.getAnnotation(Value.class);

        assertThat(annotation).as("JwtUtil.secret 必须仍由配置注入").isNotNull();
        assertThat(annotation.value())
                .as("带默认值的签名密钥等于公开密钥")
                .isEqualTo("${jwt.secret}");
    }

    @Test
    @DisplayName("密钥缺失或为空白时启动即失败")
    void validateSecret_rejectsMissingAndBlank() throws Exception {
        assertThatThrownBy(() -> validateWith(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt.secret 未配置");
        assertThatThrownBy(() -> validateWith("   "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt.secret 未配置");
    }

    @Test
    @DisplayName("短于 32 字节的密钥启动即失败，够长则通过")
    void validateSecret_enforcesHs256KeyLength() throws Exception {
        assertThatThrownBy(() -> validateWith("too-short-secret"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("长度不足");

        assertThatCode(() -> validateWith("0123456789abcdef0123456789abcdef"))
                .as("32 字节刚好达标")
                .doesNotThrowAnyException();
    }

    private void validateWith(String value) throws Exception {
        JwtUtil jwtUtil = new JwtUtil();
        Field field = JwtUtil.class.getDeclaredField("secret");
        field.setAccessible(true);
        field.set(jwtUtil, value);

        Method method = JwtUtil.class.getDeclaredMethod("validateSecret");
        method.setAccessible(true);
        try {
            method.invoke(jwtUtil);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }
}
