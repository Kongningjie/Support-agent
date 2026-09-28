package com.lawrence.supportagent.evaluation;

import java.util.List;
import java.util.Objects;

/**
 * 一条不包含真实企业信息的固定知识空间隔离评测用例。
 *
 * @param caseId 稳定唯一用例编号
 * @param category 冻结场景类别
 * @param spaceCode 测试空间代码，仅允许固定虚构代码
 * @param userRole 测试用户空间角色或 NONE
 * @param input 测试输入或操作描述
 * @param expectedSources 允许出现的稳定虚构来源键
 * @param forbiddenSources 禁止进入候选、引用、答案或详情的来源键
 * @param forbiddenDisclosures 防枚举响应中禁止出现的虚构空间或资源信息
 * @param requiredExactTerms 允许来源 Top5 必须保留的精确词
 * @param expectedResult ALLOW、DENY、NOT_FOUND 或 NO_HIT
 */
public record KnowledgeSpaceEvaluationCase(
        String caseId,
        KnowledgeSpaceEvaluationCategory category,
        String spaceCode,
        String userRole,
        String input,
        List<String> expectedSources,
        List<String> forbiddenSources,
        List<String> forbiddenDisclosures,
        List<String> requiredExactTerms,
        String expectedResult) {
    /** 校验必填字段并冻结所有集合，防止运行中的评测基线被外部修改。 */
    public KnowledgeSpaceEvaluationCase {
        Objects.requireNonNull(caseId, "caseId must not be null");
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(spaceCode, "spaceCode must not be null");
        Objects.requireNonNull(userRole, "userRole must not be null");
        Objects.requireNonNull(input, "input must not be null");
        expectedSources = List.copyOf(expectedSources);
        forbiddenSources = List.copyOf(forbiddenSources);
        forbiddenDisclosures = List.copyOf(forbiddenDisclosures);
        requiredExactTerms = List.copyOf(requiredExactTerms);
        Objects.requireNonNull(expectedResult, "expectedResult must not be null");
    }
}
