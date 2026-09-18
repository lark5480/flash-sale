package com.flashsale.common.constant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MQ 终态标记的编解码测试。
 * <p>
 * 标记值同时承担两件事：客户端按状态判断是否停止轮询、按原因决定下一步动作。
 * 因此「带原因的 FAILED 必须仍被判成 FAILED」是这条契约的关键 —— 漏掉它就会让
 * 客户端把限购失败当成仍在处理，一直转到超时。
 */
class RocketMQConstantsTest {

    @Test
    @DisplayName("失败原因随标记一起编码，读取时状态与原因各自解出")
    void failedMarker_roundTripsReasonAndStatus() {
        String marker = RocketMQConstants.failedMarker("已达每人限购数量");

        assertThat(marker).isEqualTo("FAILED:已达每人限购数量");
        assertThat(RocketMQConstants.statusOf(marker)).isEqualTo(RocketMQConstants.RESULT_FAILED);
        assertThat(RocketMQConstants.failReasonOf(marker)).isEqualTo("已达每人限购数量");
    }

    @Test
    @DisplayName("带原因的失败标记仍按 FAILED 判定，客户端不会因此继续轮询")
    void statusOf_failureWithReason_stillEqualsFailed() {
        assertThat(RocketMQConstants.statusOf(RocketMQConstants.failedMarker("库存不足")))
                .isEqualTo(RocketMQConstants.RESULT_FAILED);
    }

    @Test
    @DisplayName("无原因或原样的 FAILED 退化为纯终态，原因解出为 null")
    void failedMarker_blankReason_degradesToPlainFailed() {
        assertThat(RocketMQConstants.failedMarker(null)).isEqualTo(RocketMQConstants.RESULT_FAILED);
        assertThat(RocketMQConstants.failedMarker("  ")).isEqualTo(RocketMQConstants.RESULT_FAILED);

        assertThat(RocketMQConstants.failReasonOf(RocketMQConstants.RESULT_FAILED)).isNull();
        assertThat(RocketMQConstants.failReasonOf("FAILED:   ")).isNull();
    }

    @Test
    @DisplayName("标记缺失按 PROCESSING 处理，成功与在途状态原样透传")
    void statusOf_coversMissingMarkerAndTerminalStates() {
        assertThat(RocketMQConstants.statusOf(null)).isEqualTo(RocketMQConstants.RESULT_PROCESSING);
        assertThat(RocketMQConstants.statusOf(RocketMQConstants.RESULT_DONE)).isEqualTo(RocketMQConstants.RESULT_DONE);
        assertThat(RocketMQConstants.failReasonOf(RocketMQConstants.RESULT_DONE)).isNull();
    }
}
