package com.lawrence.supportagent.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 验证阶段 10 中英文混合 Token 保守估算规则。 */
class ConservativeTokenEstimatorTest {
    private final ConservativeTokenEstimator estimator = new ConservativeTokenEstimator();

    /** CJK 字符应逐字符计数并增加百分之十五安全余量。 */
    @Test
    void shouldEstimateCjkCharactersConservatively() {
        assertThat(estimator.estimate("中文测试")).isEqualTo(5);
    }

    /** 非 CJK 内容应按每四字符一 Token 向上取整后增加余量。 */
    @Test
    void shouldEstimateLatinCharactersInGroupsOfFour() {
        assertThat(estimator.estimate("abcdefgh")).isEqualTo(3);
    }

    /** 空内容不得虚增会话预算。 */
    @Test
    void shouldReturnZeroForEmptyContent() {
        assertThat(estimator.estimate("")).isZero();
        assertThat(estimator.estimate((String) null)).isZero();
    }

    /** 中英文交替时必须分别折算每个连续非 CJK 片段，避免合并后低估。 */
    @Test
    void shouldRoundEachNonCjkRunIndependently() {
        assertThat(estimator.estimate("a中b")).isEqualTo(4);
    }
}
