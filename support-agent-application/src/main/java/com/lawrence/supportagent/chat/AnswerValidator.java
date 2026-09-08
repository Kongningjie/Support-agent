package com.lawrence.supportagent.chat;

import com.lawrence.supportagent.knowledge.DocumentContentPolicy;
import com.lawrence.supportagent.knowledge.ExactTerm;
import com.lawrence.supportagent.knowledge.ExactTermExtractor;
import com.lawrence.supportagent.retrieval.RetrievalEvidence;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 确定性验证引用、敏感信息及精确技术值是否由相应证据支持。 */
public class AnswerValidator {
    private static final Pattern CITATION = Pattern.compile("\\[S(\\d+)]");
    private static final Pattern PORT = Pattern.compile("(?i)(?:端口|port|:)[\\s:=]*(\\d{2,5})");
    private final ExactTermExtractor extractor;
    private final DocumentContentPolicy contentPolicy;

    /** 注入阶段三复用的精确词和敏感内容策略。 */
    public AnswerValidator(ExactTermExtractor extractor, DocumentContentPolicy contentPolicy) {
        this.extractor = extractor;
        this.contentPolicy = contentPolicy;
    }

    /** 返回空列表表示通过，否则返回可安全交给模型的规则编号。 */
    public List<String> validate(String answer, List<RetrievalEvidence> evidence) {
        if (answer == null || answer.isBlank() || answer.length() > 8000) {
            return List.of("ANSWER_LENGTH_INVALID");
        }
        try {
            contentPolicy.normalizeDirectText(answer);
        } catch (RuntimeException exception) {
            return List.of("ANSWER_SENSITIVE_CONTENT");
        }
        Map<String, RetrievalEvidence> byCitation = new HashMap<>();
        for (int index = 0; index < evidence.size(); index++) {
            byCitation.put("S" + (index + 1), evidence.get(index));
        }
        Matcher allCitations = CITATION.matcher(answer);
        boolean found = false;
        while (allCitations.find()) {
            found = true;
            if (!byCitation.containsKey("S" + allCitations.group(1))) {
                return List.of("UNKNOWN_CITATION");
            }
        }
        if (!found) {
            return List.of("CITATION_REQUIRED");
        }
        for (String segment : answer.split("(?m)(?<=[。！？.!?])\\s+|\\R(?=\\s*(?:[-*]|\\d+[.)]))")) {
            List<ExactTerm> terms = extractor.extract(segment);
            Matcher ports = PORT.matcher(segment);
            if (!terms.isEmpty() || ports.find()) {
                List<RetrievalEvidence> cited = citedEvidence(segment, byCitation);
                if (cited.isEmpty() || !terms.stream().allMatch(term -> cited.stream()
                        .anyMatch(item -> supports(item, term))) || !portsSupported(segment, cited)) {
                    return List.of("EXACT_VALUE_NOT_SUPPORTED");
                }
            }
        }
        return List.of();
    }

    /** 返回当前答案片段实际声明的证据。 */
    private List<RetrievalEvidence> citedEvidence(String segment,
                                                   Map<String, RetrievalEvidence> evidence) {
        java.util.ArrayList<RetrievalEvidence> values = new java.util.ArrayList<>();
        Matcher matcher = CITATION.matcher(segment);
        while (matcher.find()) {
            RetrievalEvidence value = evidence.get("S" + matcher.group(1));
            if (value != null) values.add(value);
        }
        return values;
    }

    /** 判断精确技术词是否原样存在于引用证据的精确字段或正文。 */
    private boolean supports(RetrievalEvidence evidence, ExactTerm term) {
        return evidence.exactTerms().stream().anyMatch(candidate -> candidate.type() == term.type()
                && candidate.normalizedValue().equals(term.normalizedValue()))
                || evidence.content().contains(term.displayValue());
    }

    /** 检查片段中出现的所有端口号都在引用证据正文中存在。 */
    private boolean portsSupported(String segment, List<RetrievalEvidence> evidence) {
        Matcher matcher = PORT.matcher(segment);
        while (matcher.find()) {
            String port = matcher.group(1);
            if (evidence.stream().noneMatch(value -> value.content().contains(port))) return false;
        }
        return true;
    }
}
