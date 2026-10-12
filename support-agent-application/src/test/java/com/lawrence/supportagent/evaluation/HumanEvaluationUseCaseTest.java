package com.lawrence.supportagent.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.chat.ChatIntent;
import com.lawrence.supportagent.chat.ConversationDetails;
import com.lawrence.supportagent.chat.ConversationLifecycleUseCase;
import com.lawrence.supportagent.chat.ConversationLifecycleStatus;
import com.lawrence.supportagent.chat.ConversationOverview;
import com.lawrence.supportagent.chat.ConversationTurnView;
import com.lawrence.supportagent.evaluation.HumanEvaluationRepository.DatasetState;
import com.lawrence.supportagent.evaluation.HumanEvaluationRepository.Review;
import com.lawrence.supportagent.evaluation.HumanEvaluationRepository.ReviewStatus;
import com.lawrence.supportagent.evaluation.HumanEvaluationRepository.Sample;
import com.lawrence.supportagent.evaluation.HumanEvaluationRepository.Score;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.user.UserRole;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 验证人工评测权限、固定来源、敏感副本和未决盲评可见性。 */
@ExtendWith(MockitoExtension.class)
class HumanEvaluationUseCaseTest {
    private static final UUID ADMIN_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID SAMPLE_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID GLOBAL_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");
    @Mock private HumanEvaluationRepository repository;
    @Mock private AnswerEvaluationDatasetPort fixedCases;
    @Mock private ConversationLifecycleUseCase conversations;
    private HumanEvaluationUseCase useCase;

    /** 使用固定 UTC 时钟装配不依赖 Spring 的应用用例。 */
    @BeforeEach void setUp() {
        useCase = new HumanEvaluationUseCase(repository, fixedCases, conversations, () -> NOW);
    }

    /** 仅管理员可依据真实固定用例创建脱敏副本。 */
    @Test void shouldRequireAdminAndConfirmedFixedCase() {
        AnswerEvaluationCase fixed = new AnswerEvaluationCase("A8-GROUND-001",
                AnswerEvaluationCategory.GROUNDED, "原问题", List.of(), List.of(), List.of(), List.of(),
                List.of(), false, AnswerEvaluationSchemaType.GROUNDED_ANSWER);
        when(fixedCases.load()).thenReturn(new AnswerEvaluationDatasetSnapshot("1", "hash", List.of(fixed)));
        when(repository.create(any())).thenReturn(true);
        Sample result = useCase.create(admin(), "FIXED", "A8-GROUND-001", GLOBAL_ID,
                "  问题  ", "  回答  ", "  证据  ", true);
        assertThat(result.question()).isEqualTo("问题");
        assertThat(result.status()).isEqualTo(ReviewStatus.FIRST_PENDING);
        assertThatThrownBy(() -> useCase.create(user(), "FIXED", "A8-GROUND-001", GLOBAL_ID,
                "问题", "回答", "证据", true)).isInstanceOf(ApplicationException.class);
        assertThatThrownBy(() -> useCase.create(admin(), "FIXED", "A8-GROUND-001", GLOBAL_ID,
                "问题", "回答", "证据", false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> useCase.create(admin(), "FIXED", "A8-GROUND-001", GLOBAL_ID,
                "问题", "api_key=secret123456789", "证据", true)).isInstanceOf(IllegalArgumentException.class);
    }

    /** 反馈样本必须仍有有效反馈、可读会话轮次及一致的空间，不复制原回答。 */
    @Test void shouldRequireLiveFeedbackTurnAndMatchingSpace() {
        UUID runId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        when(repository.feedbackConversation(runId)).thenReturn(Optional.of(conversationId));
        when(conversations.details(admin(), conversationId)).thenReturn(new ConversationDetails(
                new ConversationOverview(conversationId, spaceId, ConversationLifecycleStatus.IDLE,
                        1, 0, 0, NOW, NOW.plusSeconds(3600)),
                List.of(new ConversationTurnView(UUID.randomUUID(), runId, "原问题", "原回答",
                        ChatIntent.GREETING, null, List.of(), NOW, 1))));
        when(repository.create(any())).thenReturn(true);

        Sample result = useCase.create(admin(), "FEEDBACK", runId.toString().toUpperCase(), spaceId,
                "脱敏问题", "脱敏回答", "脱敏证据", true);
        assertThat(result.sourceRef()).isEqualTo(runId.toString());
        assertThat(result.answer()).isEqualTo("脱敏回答");
        assertThatThrownBy(() -> useCase.create(admin(), "FEEDBACK", runId.toString(), GLOBAL_ID,
                "脱敏问题", "脱敏回答", "脱敏证据", true)).isInstanceOf(IllegalArgumentException.class);
    }

    /** 未形成结论前只看到自己的分数，形成结论后显示复核最终分。 */
    @Test void shouldHideOtherReviewUntilDecision() {
        Review mine = new Review(ADMIN_ID, "FIRST", new Score(2, 2, 2, 2, 2), "理由一", NOW);
        Review other = new Review(OTHER_ID, "SECOND", new Score(0, 1, 1, 1, 2), "理由二", NOW);
        when(repository.find(SAMPLE_ID)).thenReturn(Optional.of(sample(ReviewStatus.ARBITRATION_PENDING)));
        when(repository.reviews(SAMPLE_ID)).thenReturn(List.of(mine, other));
        HumanEvaluationUseCase.Detail pending = useCase.details(admin(), SAMPLE_ID);
        assertThat(pending.reviews()).containsExactly(mine);
        assertThat(pending.finalScore()).isNull();

        Review arbiter = new Review(UUID.randomUUID(), "ARBITER", new Score(1, 1, 1, 1, 1), "终审", NOW);
        when(repository.find(SAMPLE_ID)).thenReturn(Optional.of(sample(ReviewStatus.DECIDED)));
        when(repository.reviews(SAMPLE_ID)).thenReturn(List.of(mine, other, arbiter));
        HumanEvaluationUseCase.Detail decided = useCase.details(admin(), SAMPLE_ID);
        assertThat(decided.reviews()).hasSize(3);
        assertThat(decided.finalScore()).isEqualTo(arbiter.score());
    }

    /** 未确认或越界评分不得交给持久化端口。 */
    @Test void shouldRejectInvalidScoreBeforeWrite() {
        assertThatThrownBy(() -> useCase.review(admin(), SAMPLE_ID,
                new Score(3, 2, 2, 2, 2), "原因"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).submit(any(), any(), any(), any(), any());
    }

    /** 构造带固定内容但不保存个人信息的样本。 */
    private Sample sample(ReviewStatus status) {
        return new Sample(SAMPLE_ID, "FIXED", "A8-GROUND-001", GLOBAL_ID,
                "问题", "回答", "证据", ADMIN_ID, status, DatasetState.NONE, 0, NOW);
    }

    /** 构造管理员身份。 */
    private AuthenticatedUser admin() { return new AuthenticatedUser(ADMIN_ID, "admin", UserRole.ADMIN); }

    /** 构造普通用户身份。 */
    private AuthenticatedUser user() { return new AuthenticatedUser(OTHER_ID, "user", UserRole.USER); }
}
