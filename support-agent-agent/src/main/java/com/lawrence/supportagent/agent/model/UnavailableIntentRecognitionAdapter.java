package com.lawrence.supportagent.agent.model;

import com.lawrence.supportagent.chat.IntentDecision;
import com.lawrence.supportagent.model.IntentRecognitionPort;
import java.util.List;

/** 在密钥缺失时让应用层按既定规则降级为 SUPPORT_QUERY。 */
public class UnavailableIntentRecognitionAdapter implements IntentRecognitionPort {
    /** {@inheritDoc} */
    @Override public IntentDecision recognize(String message, List<String> recentTurns) {
        throw new IllegalStateException("当前环境未配置 DashScope 密钥");
    }
}
