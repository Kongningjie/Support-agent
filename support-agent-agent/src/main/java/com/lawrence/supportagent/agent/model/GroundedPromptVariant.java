package com.lawrence.supportagent.agent.model;

/** 阶段 8 用于同一模型回答 A/B 的知识回答 Prompt 版本。 */
public enum GroundedPromptVariant {
    /** 阶段 4 起使用的完整基线 Prompt。 */ ORIGINAL("/prompts/grounded-answer.md"),
    /** 仅合并重复文字且保留全部安全语义的压缩 Prompt。 */ COMPACT("/prompts/grounded-answer-compact.md");

    private final String resource;

    /** 绑定枚举与只读类路径 Prompt。 */
    GroundedPromptVariant(String resource) { this.resource = resource; }

    /** 返回该版本对应的类路径资源。 */
    public String resource() { return resource; }
}
