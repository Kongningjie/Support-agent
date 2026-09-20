package com.lawrence.supportagent.agent.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 从固定 JSONL 资源加载阶段 10 中文多轮摘要验收数据。 */
final class ConversationSummaryEvaluationDataset {
    static final String RESOURCE = "/evaluation/conversation-summary-cases.jsonl";
    static final String VERSION = "conversation-summary-eval-v1";
    private final ObjectMapper mapper;

    /** 注入与生产结构化响应一致的 Jackson 3 解析器。 */
    ConversationSummaryEvaluationDataset(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 加载全部非空 JSONL 行并返回内容哈希稳定的不可变快照。 */
    Snapshot load() {
        String content = new String(bytes(), StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        List<Case> cases = new ArrayList<>();
        content.lines().filter(line -> !line.isBlank())
                .forEach(line -> cases.add(parse(line)));
        return new Snapshot(VERSION, sha256(content.getBytes(StandardCharsets.UTF_8)), cases);
    }

    /** 将单行 JSON 转换为字段含义明确的评测用例。 */
    private Case parse(String line) {
        JsonNode node = mapper.readTree(line);
        List<Turn> turns = new ArrayList<>();
        node.path("turns").forEach(turn -> turns.add(new Turn(turn.path("version").asLong(),
                turn.path("user").stringValue(), turn.path("assistant").stringValue())));
        JsonNode entity = node.path("expectedEntity");
        ExpectedEntity expected = entity.isNull() || entity.isMissingNode() ? null
                : new ExpectedEntity(entity.path("type").stringValue(),
                entity.path("normalizedValue").stringValue(),
                entity.path("sourceTurnVersion").asLong());
        return new Case(node.path("caseId").stringValue(), node.path("category").stringValue(),
                node.path("online").asBoolean(), turns, strings(node.path("requiredPhrases")),
                strings(node.path("forbiddenPhrases")), expected);
    }

    /** 读取字符串数组。 */
    private List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        node.forEach(item -> values.add(item.stringValue()));
        return List.copyOf(values);
    }

    /** 读取固定类路径资源字节，缺失时立即使测试失败。 */
    private byte[] bytes() {
        try (var input = ConversationSummaryEvaluationDataset.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("缺少会话摘要评测数据");
            }
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取会话摘要评测数据", exception);
        }
    }

    /** 计算固定数据集小写 SHA-256。 */
    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 不支持 SHA-256", exception);
        }
    }

    /** @param version 数据集版本 @param sha256 原始 JSONL 内容哈希 @param cases 全部固定用例 */
    record Snapshot(String version, String sha256, List<Case> cases) {
        /** 复制用例，防止评测执行时污染锁定快照。 */
        Snapshot {
            cases = List.copyOf(cases);
        }
    }

    /**
     * @param caseId 稳定用例编号
     * @param category 冻结用例分类
     * @param online 是否需要真实摘要模型
     * @param turns 原始成功轮次
     * @param requiredPhrases 摘要必须保留的事实短语
     * @param forbiddenPhrases 摘要不得继续保留的过期事实短语
     * @param expectedEntity 必须精确保留的关键实体，可为空
     */
    record Case(String caseId, String category, boolean online, List<Turn> turns,
                List<String> requiredPhrases, List<String> forbiddenPhrases,
                ExpectedEntity expectedEntity) {
        /** 复制所有列表以保持用例不可变。 */
        Case {
            turns = List.copyOf(turns);
            requiredPhrases = List.copyOf(requiredPhrases);
            forbiddenPhrases = List.copyOf(forbiddenPhrases);
        }
    }

    /** @param version 成功会话版本 @param user 用户原文 @param assistant 安全完整回答 */
    record Turn(long version, String user, String assistant) { }

    /** @param type 精确词类型 @param normalizedValue 规范值 @param sourceTurnVersion 来源轮次版本 */
    record ExpectedEntity(String type, String normalizedValue, long sourceTurnVersion) { }
}
