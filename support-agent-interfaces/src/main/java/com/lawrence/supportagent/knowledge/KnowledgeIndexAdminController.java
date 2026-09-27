package com.lawrence.supportagent.knowledge;

import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.sharedkernel.api.ApiResponseFactory;
import com.lawrence.supportagent.sharedkernel.api.ApiResult;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 暴露仅平台管理员可执行的知识索引全量重建入口。 */
@RestController
@RequestMapping("/api/v1/admin/knowledge-index")
public class KnowledgeIndexAdminController {
    private final KnowledgeIndexRebuildUseCase rebuilds;
    private final ApiResponseFactory responses;

    /** 注入全量重建用例和统一响应工厂。 */
    public KnowledgeIndexAdminController(KnowledgeIndexRebuildUseCase rebuilds,
                                         ApiResponseFactory responses) {
        this.rebuilds = rebuilds;
        this.responses = responses;
    }

    /** 同步重建配置的新物理索引，完整核对后原子切换业务别名。 */
    @Operation(summary = "全量重建并切换知识索引")
    @PostMapping("/rebuild")
    public ApiResult<KnowledgeIndexRebuildResult> rebuild(
            @AuthenticationPrincipal AuthenticatedUser actor,
            HttpServletRequest request) {
        return responses.success(rebuilds.rebuild(actor), request);
    }
}
