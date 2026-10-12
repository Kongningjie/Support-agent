package com.lawrence.supportagent.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.feedback.port.AnswerFeedbackRepository;
import com.lawrence.supportagent.memory.UserMemoryContentPolicy;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import com.lawrence.supportagent.user.UserRole;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 验证用户反馈的归属、成功状态、版本和说明安全边界。 */
@ExtendWith(MockitoExtension.class)
class AnswerFeedbackUseCaseTest {
    private static final UUID RUN = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");
    @Mock private AnswerFeedbackRepository repository;
    private AnswerFeedbackUseCase useCase;

    /** 用固定时间和真实敏感文本策略创建用例。 */
    @BeforeEach void setUp() {
        useCase = new AnswerFeedbackUseCase(repository, new UserMemoryContentPolicy(), () -> NOW);
    }

    /** 首次评价不允许覆盖已有记录，说明必须规范化。 */
    @Test void shouldCreateFeedbackWithNormalizedNote() {
        owned();
        when(repository.insert(any())).thenReturn(true);
        when(repository.find(RUN, USER)).thenReturn(Optional.of(feedback(0)));

        AnswerFeedback result = useCase.put(user(), RUN, FeedbackRating.NOT_HELPFUL,
                FeedbackReason.INCOMPLETE, "  缺少恢复步骤  ", null);

        assertThat(result.version()).isZero();
        verify(repository).insert(new AnswerFeedback(RUN, USER, FeedbackRating.NOT_HELPFUL,
                FeedbackReason.INCOMPLETE, "缺少恢复步骤", 0, NOW, NOW));
    }

    /** 他人、失败和不存在运行都不能通过运行标识探测反馈。 */
    @Test void shouldRejectRunNotSuccessfulOrNotOwned() {
        assertThatThrownBy(() -> useCase.put(user(), RUN, FeedbackRating.HELPFUL, null, null, null))
                .isInstanceOf(ApplicationException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FEEDBACK_NOT_FOUND);
        verify(repository, never()).insert(any());
    }

    /** 重复创建和旧版本更正都必须冲突，不能静默覆盖。 */
    @Test void shouldRejectDuplicateAndStaleUpdate() {
        owned();
        when(repository.insert(any())).thenReturn(false);
        assertThatThrownBy(() -> useCase.put(user(), RUN, FeedbackRating.HELPFUL, null, null, null))
                .isInstanceOf(ApplicationException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FEEDBACK_VERSION_CONFLICT);
        when(repository.update(any(), eq(2L))).thenReturn(false);
        assertThatThrownBy(() -> useCase.put(user(), RUN, FeedbackRating.HELPFUL, null, null, 2L))
                .isInstanceOf(ApplicationException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FEEDBACK_VERSION_CONFLICT);
    }

    /** 更正应使用当前版本并得到递增的新版本。 */
    @Test void shouldUpdateWithExpectedVersion() {
        owned();
        when(repository.update(any(), eq(0L))).thenReturn(true);
        when(repository.find(RUN, USER)).thenReturn(Optional.of(feedback(1)));

        assertThat(useCase.put(user(), RUN, FeedbackRating.HELPFUL, null, null, 0L).version()).isEqualTo(1);
        verify(repository).update(new AnswerFeedback(RUN, USER, FeedbackRating.HELPFUL,
                null, null, 1, NOW, NOW), 0L);
    }

    /** 评分原因必须成对合法，敏感说明不能保存。 */
    @Test void shouldRejectInvalidReasonAndSensitiveNote() {
        owned();
        assertThatThrownBy(() -> useCase.put(user(), RUN, FeedbackRating.NOT_HELPFUL, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> useCase.put(user(), RUN, FeedbackRating.HELPFUL,
                FeedbackReason.OTHER, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> useCase.put(user(), RUN, FeedbackRating.HELPFUL, null,
                "password=secret12345", null))
                .isInstanceOf(ApplicationException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FEEDBACK_SENSITIVE_CONTENT);
        assertThatThrownBy(() -> useCase.put(user(), RUN, FeedbackRating.HELPFUL, null,
                "字".repeat(301), null)).isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).insert(any());
    }

    /** 删除必须使用旧版本；旧版本或已删除均不可成功。 */
    @Test void shouldDeleteOnlyMatchingVersion() {
        owned();
        when(repository.delete(RUN, USER, 0, NOW)).thenReturn(true);
        useCase.delete(user(), RUN, 0);
        verify(repository).delete(RUN, USER, 0, NOW);
        assertThatThrownBy(() -> useCase.delete(user(), RUN, 1))
                .isInstanceOf(ApplicationException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FEEDBACK_VERSION_CONFLICT);
    }

    /** 普通用户无权看聚合，管理员只能得到分类计数。 */
    @Test void shouldRestrictSummaryToAdministrator() {
        assertThatThrownBy(() -> useCase.summary(user()))
                .isInstanceOf(ApplicationException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AUTH_FORBIDDEN);
        EnumMap<FeedbackRating, Long> ratings = new EnumMap<>(FeedbackRating.class);
        ratings.put(FeedbackRating.HELPFUL, 1L);
        when(repository.summary()).thenReturn(new FeedbackSummary(1, ratings,
                new EnumMap<>(FeedbackReason.class)));
        assertThat(useCase.summary(new AuthenticatedUser(USER, "admin", UserRole.ADMIN)).total()).isEqualTo(1);
    }

    /** 使 Mockito 中的一次成功运行属于当前用户。 */
    private void owned() {
        when(repository.isSuccessfulOwnedRun(RUN, USER)).thenReturn(true);
    }

    /** 创建固定版本的反馈返回值。 */
    private AnswerFeedback feedback(long version) {
        return new AnswerFeedback(RUN, USER, FeedbackRating.NOT_HELPFUL,
                FeedbackReason.INCOMPLETE, "缺少恢复步骤", version, NOW, NOW);
    }

    /** 创建普通认证用户。 */
    private AuthenticatedUser user() {
        return new AuthenticatedUser(USER, "user", UserRole.USER);
    }
}
