package com.flashsale.common.result;

import static org.assertj.core.api.Assertions.assertThat;

import com.flashsale.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 错误码消息口径测试。
 * <p>
 * 前端弹窗直接展示 {@code ResultVO.msg}（{@code request.js} 与 {@code e.response.data.msg} 两条路径都是），
 * 而 {@code new BusinessException(ResultCode.X)} 只带枚举时消息就取自枚举 —— 枚举写英文，用户就会看到英文。
 */
class ResultCodeTest {

    private static boolean containsHan(String text) {
        return text.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
    }

    @Test
    @DisplayName("除 SUCCESS 外的错误码消息必须是中文（会进前端弹窗）")
    void errorCodes_carryChineseMessage() {
        for (ResultCode rc : ResultCode.values()) {
            if (rc == ResultCode.SUCCESS) {
                continue;
            }
            assertThat(containsHan(rc.getMsg()))
                    .as("错误码 %s 的消息应为中文，实际为：%s", rc.name(), rc.getMsg())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("只带错误码的 BusinessException 暴露中文消息")
    void businessException_withoutExplicitMsg_usesChineseDefault() {
        assertThat(new BusinessException(ResultCode.FLASH_REPEAT).getMessage())
                .isEqualTo(ResultCode.FLASH_REPEAT.getMsg())
                .doesNotContain("repeat purchase");
    }
}
