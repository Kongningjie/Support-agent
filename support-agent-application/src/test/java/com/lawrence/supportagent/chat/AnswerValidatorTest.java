package com.lawrence.supportagent.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.lawrence.supportagent.knowledge.DocumentContentPolicy;
import com.lawrence.supportagent.knowledge.ExactTermExtractor;
import com.lawrence.supportagent.retrieval.RetrievalEvidence;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 验证模型完整答案的引用、精确值和敏感信息门禁。 */
class AnswerValidatorTest {
    private final AnswerValidator validator = new AnswerValidator(
            new ExactTermExtractor(), new DocumentContentPolicy());

    /** 引用不存在的临时来源编号必须失败。 */
    @Test
    void shouldRejectUnknownCitation() {
        assertThat(validator.validate("请执行检查。[S2]", List.of(evidence())))
                .containsExactly("UNKNOWN_CITATION");
    }

    /** 引用证据不含精确端口时不得输出该端口。 */
    @Test
    void shouldRejectUnsupportedPort() {
        assertThat(validator.validate("请检查端口 3306。[S1]", List.of(evidence())))
                .containsExactly("EXACT_VALUE_NOT_SUPPORTED");
    }

    /** 疑似密钥不得随模型答案发送给客户端。 */
    @Test
    void shouldRejectSensitiveCredential() {
        assertThat(validator.validate("api_key=abcdefghijklmnop [S1]", List.of(evidence())))
                .containsExactly("ANSWER_SENSITIVE_CONTENT");
    }

    /** 创建不含精确值的单条证据。 */
    private RetrievalEvidence evidence() {
        return new RetrievalEvidence("chunk-1", "MANAGED_DOCUMENT", 1, 1, "标题", "章节",
                "检查数据库连接", List.of(), Set.of(), 1, 1, 0.1, 0.9);
    }
}
