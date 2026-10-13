package com.lawrence.supportagent.config;

import com.lawrence.supportagent.agent.model.DashScopeJudgeAdapter;
import com.lawrence.supportagent.evaluation.HumanEvaluationUseCase;
import com.lawrence.supportagent.evaluation.JudgeModelPort;
import com.lawrence.supportagent.evaluation.JudgeRunRepository;
import com.lawrence.supportagent.evaluation.JudgeUseCase;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 装配默认关闭的独立 Judge，不回退为普通回答模型。 */
@Configuration
public class JudgeConfiguration {
    /** 开关、模型和密钥同时有效时才创建真实适配器。 */
    @Bean public JudgeModelPort judgeModelPort(SupportAgentProperties properties,
            @Value("${support-agent.judge.enabled:false}") boolean enabled,
            @Value("${support-agent.judge.model:}") String model) {
        var config=properties.dashscope();
        if (!enabled || model.isBlank() || config.apiKey()==null || config.apiKey().isBlank()) {
            return new JudgeModelPort() {
                /** 未配置的能力不提供模型名称。 */
                @Override public String modelName() { return ""; }
                /** 未配置调用拒绝执行，不生成固定评分。 */
                @Override public Result evaluate(com.lawrence.supportagent.evaluation.HumanEvaluationRepository.Sample sample) {
                    throw new IllegalStateException("Judge 未配置");
                }
            };
        }
        if (model.length()>100 || !model.matches("[A-Za-z0-9._-]+")) throw new IllegalArgumentException("Judge 模型名称无效");
        return new DashScopeJudgeAdapter(config.apiKey(),model,config.baseUrl());
    }
    /** 注入独立模型和已脱敏人工样本边界。 */
    @Bean public JudgeUseCase judgeUseCase(HumanEvaluationUseCase humans, JudgeRunRepository runs,
                                         JudgeModelPort model, TimeProvider time) {
        return new JudgeUseCase(humans,runs,model,time);
    }
}
