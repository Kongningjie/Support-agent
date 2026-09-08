package com.lawrence.supportagent.chat;

/**
 * 保存规则或模型产生的受控意图结果。
 *
 * @param intent 最终路由意图
 * @param confidence 置信度，范围零到一
 * @param standaloneQuery 仅供检索使用的独立问题
 * @param ticketNo 合法工单号，非工单意图时为空
 * @param reasonCode 稳定识别原因码
 */
public record IntentDecision(ChatIntent intent, double confidence, String standaloneQuery,
                             String ticketNo, String reasonCode) { }
