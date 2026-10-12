package com.lawrence.supportagent.feedback;

import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.feedback.port.AnswerFeedbackRepository;
import com.lawrence.supportagent.memory.UserMemoryContentPolicy;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import java.time.Instant;
import java.util.UUID;

/** 编排本人回答反馈的创建、更正、删除及管理员聚合权限。 */
public class AnswerFeedbackUseCase {
    private static final int MAX_NOTE_CHARACTERS = 300;
    private final AnswerFeedbackRepository repository;
    private final UserMemoryContentPolicy sensitiveText;
    private final TimeProvider time;

    /** 注入反馈存储、已有敏感文本校验策略和统一时间端口。 */
    public AnswerFeedbackUseCase(AnswerFeedbackRepository repository,
                                 UserMemoryContentPolicy sensitiveText, TimeProvider time) {
        this.repository = repository;
        this.sensitiveText = sensitiveText;
        this.time = time;
    }

    /** 查询本人对一次成功回答的当前反馈。 */
    public AnswerFeedback get(AuthenticatedUser actor, UUID runId) {
        requireSuccessfulOwnedRun(actor, runId);
        return repository.find(runId, actor.userId()).orElseThrow(this::notFound);
    }

    /** 首次提交或按预期版本更正反馈，拒绝无版本覆盖和并发丢失更新。 */
    public AnswerFeedback put(AuthenticatedUser actor, UUID runId, FeedbackRating rating,
                              FeedbackReason reason, String note, Long expectedVersion) {
        requireSuccessfulOwnedRun(actor, runId);
        if (rating == null || (rating == FeedbackRating.HELPFUL && reason != null)
                || (rating == FeedbackRating.NOT_HELPFUL && reason == null)
                || (expectedVersion != null && (expectedVersion < 0 || expectedVersion == Long.MAX_VALUE))) {
            throw new IllegalArgumentException("反馈评分、原因或版本不符合要求");
        }
        String safeNote = normalizeNote(note);
        Instant now = time.now();
        if (expectedVersion == null) {
            if (!repository.insert(new AnswerFeedback(runId, actor.userId(), rating,
                    reason, safeNote, 0, now, now))) throw conflict();
        } else if (!repository.update(new AnswerFeedback(runId, actor.userId(), rating,
                reason, safeNote, expectedVersion + 1, now, now), expectedVersion)) {
            throw conflict();
        }
        return repository.find(runId, actor.userId()).orElseThrow(this::notFound);
    }

    /** 按版本清空本人反馈内容，只留无内容版本标记阻止旧请求覆盖重建反馈。 */
    public void delete(AuthenticatedUser actor, UUID runId, long expectedVersion) {
        requireSuccessfulOwnedRun(actor, runId);
        if (expectedVersion < 0) throw new IllegalArgumentException("反馈版本不能为负数");
        if (!repository.delete(runId, actor.userId(), expectedVersion, time.now())) throw conflict();
    }

    /** 仅允许平台管理员读取没有个人说明的聚合计数。 */
    public FeedbackSummary summary(AuthenticatedUser actor) {
        if (actor == null) throw new ApplicationException(ErrorCode.AUTH_UNAUTHORIZED, "认证信息无效");
        if (!actor.administrator()) throw new ApplicationException(ErrorCode.AUTH_FORBIDDEN, "仅管理员可查看反馈聚合");
        return repository.summary();
    }

    /** 验证运行存在、成功且归属当前用户，统一隐藏他人和失败运行。 */
    private void requireSuccessfulOwnedRun(AuthenticatedUser actor, UUID runId) {
        if (actor == null) throw new ApplicationException(ErrorCode.AUTH_UNAUTHORIZED, "认证信息无效");
        if (runId == null || !repository.isSuccessfulOwnedRun(runId, actor.userId())) throw notFound();
    }

    /** 把空白说明归一为空，并复用已验证的凭据和个人信息防护。 */
    private String normalizeNote(String note) {
        if (note == null || note.isBlank()) return null;
        String normalized;
        try {
            normalized = sensitiveText.normalize(note).content();
        } catch (ApplicationException exception) {
            if (exception.errorCode() != ErrorCode.MEMORY_SENSITIVE_CONTENT) throw exception;
            throw new ApplicationException(ErrorCode.FEEDBACK_SENSITIVE_CONTENT,
                    "反馈说明包含禁止保存的敏感信息");
        }
        if (normalized.codePointCount(0, normalized.length()) > MAX_NOTE_CHARACTERS) {
            throw new IllegalArgumentException("反馈说明不能超过 300 个字符");
        }
        return normalized;
    }

    /** 返回不泄露运行归属或状态的不存在错误。 */
    private ApplicationException notFound() {
        return new ApplicationException(ErrorCode.FEEDBACK_NOT_FOUND, "反馈或可评价回答不存在");
    }

    /** 返回版本或重复创建冲突。 */
    private ApplicationException conflict() {
        return new ApplicationException(ErrorCode.FEEDBACK_VERSION_CONFLICT, "反馈已变化，请刷新后重试");
    }
}
