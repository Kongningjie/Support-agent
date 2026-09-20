package com.lawrence.supportagent.auth;

import com.lawrence.supportagent.sharedkernel.OperatorId;
import com.lawrence.supportagent.sharedkernel.port.OperatorProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** 将当前认证用户转换为审计操作者，并为无请求上下文的系统任务保留稳定身份。 */
public class SecurityContextOperatorProvider implements OperatorProvider {
    private final OperatorId systemOperator;

    /** 使用配置的系统操作者创建身份提供器。 */
    public SecurityContextOperatorProvider(String systemOperatorId) {
        this.systemOperator = new OperatorId(systemOperatorId);
    }

    /** 优先返回当前认证用户 UUID，没有用户请求上下文时返回系统操作者。 */
    @Override
    public OperatorId currentOperator() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return new OperatorId(user.userId().toString());
        }
        return systemOperator;
    }
}
