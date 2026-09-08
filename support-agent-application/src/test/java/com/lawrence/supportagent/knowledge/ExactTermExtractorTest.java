package com.lawrence.supportagent.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** 验证一期八类精确技术词和命令位置约束。 */
class ExactTermExtractorTest {
    private final ExactTermExtractor extractor = new ExactTermExtractor();

    /** 验证八类技术词均可从受控示例中提取。 */
    @Test
    void shouldExtractAllSupportedTypes() {
        String content = "ERROR_CODE_42 版本 v4.1.0 配置 spring.datasource.url "
                + "路径 C:\\apps\\agent\\config.yml 工单 T000000000001 "
                + "类 com.example.support.AgentService 地址 https://example.test/api/v1/tickets\n"
                + "执行命令：\nPS> mvn test";

        Set<ExactTermType> types = extractor.extract(content).stream()
                .map(ExactTerm::type).collect(Collectors.toSet());

        assertEquals(Set.of(ExactTermType.values()), types);
    }

    /** 验证普通叙述中的命令形文字不会被误判为 COMMAND。 */
    @Test
    void shouldOnlyExtractCommandsFromAllowedLocations() {
        List<ExactTerm> terms = extractor.extract("建议使用 mvn test 完成验证，但这里不是命令块。");

        assertTrue(terms.stream().noneMatch(term -> term.type() == ExactTermType.COMMAND));
    }
}
