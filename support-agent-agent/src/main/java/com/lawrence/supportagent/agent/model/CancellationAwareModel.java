package com.lawrence.supportagent.agent.model;

import com.lawrence.supportagent.chat.ChatExecutionControl;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import reactor.core.publisher.Flux;

/** 当前工单 Agent 的模型围栏，防止工具错误处理后在已取消请求中再发起下一轮推理。 */
final class CancellationAwareModel implements Model {
    private final Model delegate;
    private final ChatExecutionControl control;

    /** 包装共享模型但不取得其关闭权。 */
    CancellationAwareModel(Model delegate, ChatExecutionControl control) {
        this.delegate = delegate;
        this.control = control;
    }

    /** 每次实际订阅前检查，包括 Agent 自动进行的后续推理。 */
    @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.defer(() -> { control.checkCancelled(); return delegate.stream(messages, tools, options); });
    }
    /** 保留底层模型名称。 */
    @Override public String getModelName() { return delegate.getModelName(); }
    /** 保留原生结构化输出能力。 */
    @Override public boolean supportsNativeStructuredOutput() { return delegate.supportsNativeStructuredOutput(); }
    /** 保留工具与结构化输出组合能力。 */
    @Override public boolean supportsNativeStructuredOutputWithTools() { return delegate.supportsNativeStructuredOutputWithTools(); }
    /** 保留上下文窗口约束。 */
    @Override public int getContextWindowSize() { return delegate.getContextWindowSize(); }
}
