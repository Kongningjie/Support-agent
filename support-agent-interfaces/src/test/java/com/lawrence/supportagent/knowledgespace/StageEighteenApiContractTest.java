package com.lawrence.supportagent.knowledgespace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.lawrence.supportagent.knowledge.ManagedDocumentController;
import com.lawrence.supportagent.resolvedcase.ResolvedCaseController;
import com.lawrence.supportagent.ticket.TicketController;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

/** 锁定阶段 18 空间接口路径和既有资源最小空间摘要契约。 */
class StageEighteenApiContractTest {
    /** 所有既有资源详情和摘要必须返回同一类型的最小空间摘要。 */
    @Test
    void shouldExposeSpaceSummaryOnExistingResources() {
        assertEquals(KnowledgeSpaceSummary.class,
                components(ManagedDocumentController.DocumentResponse.class).get("space"));
        assertEquals(UUID.class,
                components(ManagedDocumentController.DocumentResponse.class).get("spaceId"));
        assertEquals(KnowledgeSpaceSummary.class,
                components(ManagedDocumentController.DocumentSummaryResponse.class).get("space"));
        assertEquals(KnowledgeSpaceSummary.class,
                components(TicketController.TicketResponse.class).get("space"));
        assertEquals(UUID.class,
                components(TicketController.TicketResponse.class).get("spaceId"));
        assertEquals(KnowledgeSpaceSummary.class,
                components(TicketController.TicketSummaryResponse.class).get("space"));
        assertEquals(KnowledgeSpaceSummary.class,
                components(ResolvedCaseController.CaseResponse.class).get("space"));
        assertEquals(UUID.class,
                components(ResolvedCaseController.CaseResponse.class).get("spaceId"));
        assertEquals(KnowledgeSpaceSummary.class,
                components(ResolvedCaseController.CaseSummaryResponse.class).get("space"));
    }

    /** 空间发现、治理和成员管理九个动作必须具有明确 HTTP 映射。 */
    @Test
    void shouldExposeFrozenKnowledgeSpaceActions() throws Exception {
        assertNotNull(annotation("list", GetMapping.class));
        assertNotNull(annotation("details", GetMapping.class));
        assertNotNull(annotation("create", PostMapping.class));
        assertNotNull(annotation("update", PatchMapping.class));
        assertNotNull(annotation("disable", PostMapping.class));
        assertNotNull(annotation("enable", PostMapping.class));
        assertNotNull(annotation("members", GetMapping.class));
        assertNotNull(annotation("putMember", PutMapping.class));
        assertNotNull(annotation("revokeMember", DeleteMapping.class));
    }

    /** 返回指定控制器方法上的 HTTP 映射注解。 */
    private <T extends java.lang.annotation.Annotation> T annotation(
            String methodName, Class<T> annotationType) {
        Method method = Arrays.stream(KnowledgeSpaceController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst().orElseThrow();
        return method.getAnnotation(annotationType);
    }

    /** 返回响应 Record 的字段名和 Java 类型。 */
    private Map<String, Class<?>> components(Class<?> type) {
        return Arrays.stream(type.getRecordComponents()).collect(Collectors.toUnmodifiableMap(
                RecordComponent::getName, RecordComponent::getType));
    }
}
