package com.lawrence.supportagent.evaluation;

import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.chat.ConversationDetails;
import com.lawrence.supportagent.chat.ConversationLifecycleUseCase;
import com.lawrence.supportagent.evaluation.HumanEvaluationRepository.DatasetState;
import com.lawrence.supportagent.evaluation.HumanEvaluationRepository.FeedbackCandidate;
import com.lawrence.supportagent.evaluation.HumanEvaluationRepository.Review;
import com.lawrence.supportagent.evaluation.HumanEvaluationRepository.Sample;
import com.lawrence.supportagent.evaluation.HumanEvaluationRepository.Score;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import java.text.Normalizer;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** 编排管理员样本采集、双人盲评、分歧复核和独立数据集晋升。 */
public class HumanEvaluationUseCase {
    private static final UUID GLOBAL_SPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Pattern SENSITIVE = Pattern.compile("(?i)-----BEGIN .*PRIVATE KEY-----|\\bBearer\\s+\\S+|\\b(?:password|passwd|pwd|api[_-]?key|access[_-]?key|secret[_-]?key|token)\\s*[:=]\\s*\\S{4,}|(?<!\\d)1[3-9]\\d{9}(?!\\d)|(?<!\\d)\\d{17}[0-9Xx](?!\\d)|\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");
    private final HumanEvaluationRepository repository;
    private final AnswerEvaluationDatasetPort fixedCases;
    private final ConversationLifecycleUseCase conversations;
    private final TimeProvider time;

    /** 注入持久化、固定用例、现有会话权限边界和时钟。 */
    public HumanEvaluationUseCase(HumanEvaluationRepository repository, AnswerEvaluationDatasetPort fixedCases,
                                  ConversationLifecycleUseCase conversations, TimeProvider time) {
        this.repository = repository;
        this.fixedCases = fixedCases;
        this.conversations = conversations;
        this.time = time;
    }

    /** 分页返回无帮助反馈的最小候选元数据。 */
    public List<FeedbackCandidate> candidates(AuthenticatedUser actor, int page, int size) {
        requireAdmin(actor);
        return repository.feedbackCandidates(offset(page, size), size);
    }

    /** 返回固定回答用例的最小选择信息；实际回答仍由管理员显式提供。 */
    public List<FixedCandidate> fixedCandidates(AuthenticatedUser actor) {
        requireAdmin(actor);
        return fixedCases.load().cases().stream()
                .map(value -> new FixedCandidate(value.caseId(), value.category().name(), value.input()))
                .toList();
    }

    /** 在来源有效且人工确认脱敏后创建样本，绝不自动复制原始会话内容。 */
    public Sample create(AuthenticatedUser actor, String sourceType, String sourceRef, UUID spaceId,
                         String question, String answer, String evidence, boolean sanitizedConfirmed) {
        requireAdmin(actor);
        if (!sanitizedConfirmed || sourceType == null || sourceRef == null || spaceId == null) {
            throw new IllegalArgumentException("必须选择来源、空间并确认脱敏");
        }
        String normalizedRef = sourceRef.strip();
        if (normalizedRef.isBlank() || normalizedRef.length() > 100) throw new IllegalArgumentException("来源标识无效");
        if ("FIXED".equals(sourceType)) {
            String fixedRef = normalizedRef;
            if (!GLOBAL_SPACE.equals(spaceId) || fixedCases.load().cases().stream()
                    .noneMatch(value -> value.caseId().equals(fixedRef))) {
                throw new IllegalArgumentException("固定用例 ID 或空间无效");
            }
        } else if ("FEEDBACK".equals(sourceType)) {
            UUID runId;
            try { runId = UUID.fromString(normalizedRef); }
            catch (IllegalArgumentException exception) { throw new IllegalArgumentException("反馈运行 ID 无效", exception); }
            normalizedRef = runId.toString();
            UUID conversationId = repository.feedbackConversation(runId).orElseThrow(this::notFound);
            ConversationDetails details = conversations.details(actor, conversationId);
            if (!spaceId.equals(details.overview().spaceId()) || details.recentTurns().stream()
                    .noneMatch(turn -> runId.equals(turn.runId()))) {
                throw new IllegalArgumentException("反馈对应的回答已不可读取或空间不符");
            }
        } else {
            throw new IllegalArgumentException("样本来源类型无效");
        }
        Sample sample = new Sample(UUID.randomUUID(), sourceType, normalizedRef, spaceId,
                safe(question, 2000), safe(answer, 10000), safe(evidence, 10000), actor.userId(),
                HumanEvaluationRepository.ReviewStatus.FIRST_PENDING, DatasetState.NONE, 0, time.now());
        if (!repository.create(sample)) throw new ApplicationException(ErrorCode.COMMON_CONFLICT,
                "来源已变化或已创建评测样本，请刷新后重试");
        return sample;
    }

    /** 返回样本列表；评分详情通过独立接口按当前评审人盲评投影。 */
    public List<Sample> list(AuthenticatedUser actor, int page, int size) {
        requireAdmin(actor);
        return repository.list(offset(page, size), size);
    }

    /** 返回样本；未决阶段只展示当前管理员自己的评分。 */
    public Detail details(AuthenticatedUser actor, UUID sampleId) {
        requireAdmin(actor);
        Sample sample = repository.find(sampleId).orElseThrow(this::notFound);
        List<Review> all = repository.reviews(sampleId);
        List<Review> visible = sample.status() == HumanEvaluationRepository.ReviewStatus.DECIDED
                ? all : all.stream().filter(review -> review.reviewerId().equals(actor.userId())).toList();
        Score finalScore = sample.status() == HumanEvaluationRepository.ReviewStatus.DECIDED
                ? all.stream().filter(review -> "ARBITER".equals(review.role())).findFirst()
                        .orElseGet(() -> all.getFirst()).score() : null;
        return new Detail(sample, visible, finalScore);
    }

    /** 原子提交一次不可更改的盲评；同人不可担任第二或第三位评审。 */
    public Detail review(AuthenticatedUser actor, UUID sampleId, Score score, String reason) {
        requireAdmin(actor);
        validateScore(score);
        repository.submit(sampleId, actor.userId(), score, safe(reason, 1000), time.now());
        return details(actor, sampleId);
    }

    /** 在已形成结论后显式纳入开发集或单独审批晋升回归集。 */
    public Detail promote(AuthenticatedUser actor, UUID sampleId, DatasetState target) {
        requireAdmin(actor);
        if (target == null || target == DatasetState.NONE) throw new IllegalArgumentException("目标数据集无效");
        repository.promote(sampleId, actor.userId(), target, time.now());
        return details(actor, sampleId);
    }

    /** 校验五维评分范围并确保没有缺项。 */
    private void validateScore(Score score) {
        if (score == null || score.factual() < 0 || score.factual() > 2 || score.citation() < 0
                || score.citation() > 2 || score.completeness() < 0 || score.completeness() > 2
                || score.actionability() < 0 || score.actionability() > 2 || score.clarity() < 0
                || score.clarity() > 2) throw new IllegalArgumentException("五维评分都必须为 0～2");
    }

    /** 规范化并扫描已由管理员确认的文本，失败时不保存任何副本。 */
    private String safe(String value, int maximum) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("评测文本不能为空");
        String normalized = Normalizer.normalize(value.replace("\r\n", "\n").replace('\r', '\n'),
                Normalizer.Form.NFC).strip();
        if (normalized.codePointCount(0, normalized.length()) > maximum || SENSITIVE.matcher(normalized).find()
                || normalized.codePoints().anyMatch(cp -> (cp < 0x20 && cp != '\n' && cp != '\t')
                        || (cp >= 0x7f && cp <= 0x9f))) {
            throw new IllegalArgumentException("评测文本过长或包含敏感内容");
        }
        return normalized;
    }

    /** 将 1 起页码转换为安全偏移量。 */
    private int offset(int page, int size) {
        if (page < 1 || size < 1 || size > 100) throw new IllegalArgumentException("分页范围无效");
        try { return Math.multiplyExact(page - 1, size); }
        catch (ArithmeticException exception) { throw new IllegalArgumentException("分页偏移量过大", exception); }
    }

    /** 双层校验管理员身份，不依赖前端路由。 */
    private void requireAdmin(AuthenticatedUser actor) {
        if (actor == null) throw new ApplicationException(ErrorCode.AUTH_UNAUTHORIZED, "认证信息无效");
        if (!actor.administrator()) throw new ApplicationException(ErrorCode.AUTH_FORBIDDEN, "仅管理员可操作人工评测");
    }

    /** 返回不透露来源存在性的找不到错误。 */
    private ApplicationException notFound() {
        return new ApplicationException(ErrorCode.COMMON_VALIDATION_FAILED, "评测样本或来源不可用");
    }

    /** 按当前管理员可见性投影样本和评分。 */
    public record Detail(Sample sample, List<Review> reviews, Score finalScore) { }

    /** 固定回答评测用例的 ID、类别和问题，不包含模型回答。 */
    public record FixedCandidate(String caseId, String category, String question) { }
}
