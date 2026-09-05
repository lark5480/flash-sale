package com.flashsale.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * LocalDateTime 多格式反序列化测试。
 * <p>
 * 后端统一输出 `yyyy-MM-dd HH:mm:ss`，但前端组件与第三方客户端会提交 ISO-8601 风格时间，
 * 反序列化器必须同时吃下两种写法，否则接口直接 400。
 */
class FlexibleLocalDateTimeDeserializerTest {

    private static final LocalDateTime EXPECTED = LocalDateTime.of(2026, 12, 31, 0, 0, 0);

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule()
                    .addDeserializer(LocalDateTime.class, new FlexibleLocalDateTimeDeserializer()));

    @Test
    @DisplayName("后端标准格式：yyyy-MM-dd HH:mm:ss")
    void deserializeStandardFormat() throws Exception {
        assertThat(read("\"2026-12-31 00:00:00\"")).isEqualTo(EXPECTED);
    }

    @Test
    @DisplayName("ISO 本地时间：yyyy-MM-ddTHH:mm:ss")
    void deserializeIsoLocalDateTime() throws Exception {
        assertThat(read("\"2026-12-31T00:00:00\"")).isEqualTo(EXPECTED);
    }

    @Test
    @DisplayName("ISO 带毫秒与纯日期")
    void deserializeIsoWithFractionAndDateOnly() throws Exception {
        assertThat(read("\"2026-12-31T00:00:00.000\"")).isEqualTo(EXPECTED);
        assertThat(read("\"2026-12-31\"")).isEqualTo(EXPECTED);
    }

    @Test
    @DisplayName("带时区偏移时忽略偏移，按字面本地时间解析")
    void deserializeWithOffset() throws Exception {
        assertThat(read("\"2026-12-31T00:00:00+08:00\"")).isEqualTo(EXPECTED);
        assertThat(read("\"2026-12-31T00:00:00Z\"")).isEqualTo(EXPECTED);
    }

    @Test
    @DisplayName("空串反序列化为 null")
    void deserializeBlankAsNull() throws Exception {
        assertThat(read("\"\"")).isNull();
        assertThat(read("null")).isNull();
    }

    @Test
    @DisplayName("无法识别的格式抛出解析异常")
    void deserializeIllegalFormat() {
        assertThatThrownBy(() -> read("\"2026/12/31 00:00:00\""))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("无法解析为 LocalDateTime");
    }

    private LocalDateTime read(String json) throws Exception {
        return objectMapper.readValue(json, LocalDateTime.class);
    }
}
