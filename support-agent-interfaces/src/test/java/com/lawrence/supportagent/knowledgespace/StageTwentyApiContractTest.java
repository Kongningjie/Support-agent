package com.lawrence.supportagent.knowledgespace;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.lawrence.supportagent.chat.ChatStreamRequest;
import com.lawrence.supportagent.knowledge.ManagedDocumentController;
import com.lawrence.supportagent.ticket.TicketController;
import io.swagger.v3.oas.annotations.media.Schema;
import java.lang.reflect.RecordComponent;
import org.junit.jupiter.api.Test;

/** 锁定阶段 20 新会话、手工工单和知识草稿的显式空间契约。 */
class StageTwentyApiContractTest {
    /** 三个正式客户端入口的 OpenAPI 空间字段都必须声明为必填。 */
    @Test
    void shouldDeclareExplicitSpaceOnAllCreationContracts() {
        assertRequired(ChatStreamRequest.class);
        assertRequired(TicketController.CreateDraftRequest.class);
        assertRequired(ManagedDocumentController.CreateTextRequest.class);
    }

    /** 查找 spaceId Record 字段并校验 OpenAPI 必填模式。 */
    private void assertRequired(Class<?> requestType) {
        RecordComponent component = java.util.Arrays.stream(requestType.getRecordComponents())
                .filter(value -> value.getName().equals("spaceId"))
                .findFirst().orElseThrow();
        Schema schema = component.getAccessor().getAnnotation(Schema.class);
        assertEquals(Schema.RequiredMode.REQUIRED, schema.requiredMode(), requestType.getName());
    }
}
