package com.lawrence.supportagent.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** 锁定阶段 3 文档正文边界和字符串 ID 对外契约。 */
class StageThreeApiContractTest {
    /** 验证详情包含正文，而分页摘要不会泄露完整正文。 */
    @Test
    void shouldExposeContentOnlyInDetails() {
        Map<String, Class<?>> details = components(ManagedDocumentController.DocumentResponse.class);
        Map<String, Class<?>> summary = components(
                ManagedDocumentController.DocumentSummaryResponse.class);

        assertTrue(details.containsKey("rawContent"));
        assertFalse(summary.containsKey("rawContent"));
        assertFalse(summary.containsKey("contentHash"));
    }

    /** 验证文档和异步任务 ID 均以字符串返回，避免前端整数精度损失。 */
    @Test
    void shouldExposeIdentifiersAsStrings() {
        Map<String, Class<?>> details = components(ManagedDocumentController.DocumentResponse.class);
        Map<String, Class<?>> action = components(ManagedDocumentController.ActionResponse.class);

        assertEquals(String.class, details.get("documentId"));
        assertEquals(String.class, action.get("taskId"));
    }

    /** 返回响应 Record 的字段名和 Java 类型。 */
    private Map<String, Class<?>> components(Class<?> type) {
        return Arrays.stream(type.getRecordComponents()).collect(Collectors.toUnmodifiableMap(
                RecordComponent::getName, RecordComponent::getType));
    }
}
