package com.flashsale.common.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 兼容多种格式的 LocalDateTime 反序列化器。
 *
 * <p>后端统一以 {@code yyyy-MM-dd HH:mm:ss} 序列化 LocalDateTime，但前端组件（如 el-date-picker
 * 未配置 value-format 时）、第三方客户端或 curl 可能提交 ISO-8601 风格时间。为避免硬绑定单一格式
 * 导致 400 错误，这里按顺序尝试以下格式，任一命中即成功：</p>
 *
 * <ol>
 *   <li>{@code yyyy-MM-dd HH:mm:ss} — 后端标准输出格式</li>
 *   <li>{@code yyyy-MM-dd HH:mm} — 前端部分组件截断秒位后的格式</li>
 *   <li>{@code ISO_LOCAL_DATE_TIME} — {@code 2026-12-31T00:00:00[.SSS]}</li>
 *   <li>{@code yyyy-MM-dd} — 纯日期，按当天 00:00 处理</li>
 *   <li>{@code ISO_OFFSET_DATE_TIME} — {@code 2026-12-31T00:00:00+08:00} 或 {@code ...Z}（偏移部分忽略）</li>
 *   <li>{@code ISO_INSTANT} — {@code 2026-12-31T00:00:00Z}</li>
 * </ol>
 *
 * <p>空串与空白串一律反序列化为 {@code null}。</p>
 */
public class FlexibleLocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {

    private static final DateTimeFormatter DATE_ONLY_FORMATTER = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE)
            .parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
            .parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0)
            .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
            .toFormatter();

    private static final List<DateTimeFormatter> FORMATTERS = Collections.unmodifiableList(Arrays.asList(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DATE_ONLY_FORMATTER,
            DateTimeFormatter.ISO_OFFSET_DATE_TIME,
            DateTimeFormatter.ISO_INSTANT
    ));

    @Override
    public LocalDateTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        String text = parser.getValueAsString();
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        String value = text.trim();
        for (DateTimeFormatter formatter : FORMATTERS) {
            try {
                return LocalDateTime.parse(value, formatter);
            } catch (DateTimeParseException ignored) {
                // 继续尝试下一种格式
            }
        }
        throw context.weirdStringException(value, LocalDateTime.class,
                "无法解析为 LocalDateTime，支持的格式示例：2026-12-31 00:00:00 / 2026-12-31T00:00:00");
    }
}
