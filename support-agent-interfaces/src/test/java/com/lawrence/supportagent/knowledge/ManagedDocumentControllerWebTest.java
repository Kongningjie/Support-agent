package com.lawrence.supportagent.knowledge;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lawrence.supportagent.knowledgespace.KnowledgeSpaceSummary;
import com.lawrence.supportagent.sharedkernel.api.ApiResponseFactory;
import com.lawrence.supportagent.sharedkernel.api.GlobalExceptionHandler;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 验证浏览器标准 FormData 能绑定文件上传的标量字段和文件正文。 */
class ManagedDocumentControllerWebTest {
    private static final UUID GLOBAL_SPACE_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000001");
    private ManagedDocumentCommandUseCase commands;
    private MockMvc mockMvc;

    /** 使用真实 MVC 参数绑定、校验和异常处理器装配控制器。 */
    @BeforeEach
    void setUp() {
        TimeProvider timeProvider = () -> Instant.parse("2026-09-28T00:00:00Z");
        commands = mock(ManagedDocumentCommandUseCase.class);
        ManagedDocumentController controller = new ManagedDocumentController(commands,
                mock(ManagedDocumentQueryUseCase.class), new ApiResponseFactory(timeProvider));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler(timeProvider)).build();
    }

    /** 验证 FormData 字符串字段无需伪装成 JSON Part 即可完成上传。 */
    @Test
    void shouldBindBrowserFormDataScalarsAndFile() throws Exception {
        byte[] content = "# F4 文件上传\n\n固定测试内容。".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "f4-upload.md",
                "text/markdown", content);
        when(commands.createFile(any(), eq(GLOBAL_SPACE_ID), eq("F4 上传知识"),
                eq("f4-upload.md"), eq("text/markdown"), eq(content), eq("f4-upload-key")))
                .thenReturn(details());

        mockMvc.perform(multipart("/api/v1/knowledge/documents/files")
                        .file(file)
                        .param("spaceId", GLOBAL_SPACE_ID.toString())
                        .param("title", "F4 上传知识")
                        .param("idempotencyKey", "f4-upload-key")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.documentId").value("42"))
                .andExpect(jsonPath("$.data.spaceId").value(GLOBAL_SPACE_ID.toString()));

        verify(commands).createFile(any(), eq(GLOBAL_SPACE_ID), eq("F4 上传知识"),
                eq("f4-upload.md"), eq("text/markdown"), eq(content), eq("f4-upload-key"));
    }

    /** 创建文件上传接口成功响应所需的固定应用层详情。 */
    private ManagedDocumentDetails details() {
        Instant now = Instant.parse("2026-09-28T00:00:00Z");
        return new ManagedDocumentDetails(42L, GLOBAL_SPACE_ID,
                new KnowledgeSpaceSummary(GLOBAL_SPACE_ID, "GLOBAL", "企业公共空间"),
                "F4 上传知识", DocumentInputType.MARKDOWN_FILE, "f4-upload.md",
                "text/markdown", "# F4 文件上传", "hash",
                ManagedDocumentStatus.DRAFT, 0L, null, null, now, now, null, null);
    }
}
