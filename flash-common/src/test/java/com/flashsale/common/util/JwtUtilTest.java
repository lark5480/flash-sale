package com.flashsale.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

/**
 * {@link JwtUtil} 签名密钥注入方式测试。
 * <p>
 * 一旦 {@code @Value} 带上默认值，「忘配 JWT_SECRET」就会退化成「用仓库里公开的那把 key 签发」，
 * 任何读者都能离线伪造 {@code role=ADMIN} 的 Token 绕过网关与 api 两道鉴权。缺失必须让启动失败，
 * 所以这条注入方式本身就是要钉住的不变式。
 */
class JwtUtilTest {

    @Test
    @DisplayName("jwt.secret 不允许带默认值，未注入时必须启动失败")
    void secretInjectionMustNotHaveFallback() throws Exception {
        Field field = JwtUtil.class.getDeclaredField("secret");
        Value annotation = field.getAnnotation(Value.class);

        assertThat(annotation).as("JwtUtil.secret 必须仍由配置注入").isNotNull();
        assertThat(annotation.value())
                .as("带默认值的签名密钥等于公开密钥")
                .isEqualTo("${jwt.secret}");
    }
}
