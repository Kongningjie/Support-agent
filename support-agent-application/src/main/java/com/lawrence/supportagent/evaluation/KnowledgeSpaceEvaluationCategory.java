package com.lawrence.supportagent.evaluation;

/** 阶段 20 固定空间隔离评测的五类冻结场景。 */
public enum KnowledgeSpaceEvaluationCategory {
    /** GLOBAL 与一个当前空间共同召回。 */ GLOBAL_AND_ACTIVE,
    /** 两个受限空间存在相同或冲突知识。 */ RESTRICTED_CONFLICT,
    /** 撤权、降级、禁用或停用后的即时拒绝。 */ PERMISSION_CHANGE,
    /** 文档、工单、案例和引用的空间继承。 */ RESOURCE_INHERITANCE,
    /** 防枚举和困难正常访问。 */ ENUMERATION_AND_NORMAL
}
