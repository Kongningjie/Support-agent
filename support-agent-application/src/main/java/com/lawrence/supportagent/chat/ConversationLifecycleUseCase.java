package com.lawrence.supportagent.chat;

import com.lawrence.supportagent.auth.AuthenticatedUser;
import com.lawrence.supportagent.chat.port.ConversationStorePort;
import com.lawrence.supportagent.chat.port.ConversationStorePort.CompletedTurn;
import com.lawrence.supportagent.chat.port.ConversationStorePort.LifecycleSnapshot;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import java.util.List;
import java.util.UUID;

/** 编排会话列表、详情、重置和删除，并在应用层传递认证与资源归属边界。 */
public class ConversationLifecycleUseCase {
    private static final int MAXIMUM_PAGE_SIZE = 100;
    private static final int RECENT_DETAIL_TURNS = 20;
    private final ConversationStorePort conversations;
    private final TimeProvider time;

    /** 注入会话存储端口和统一时间端口。 */
    public ConversationLifecycleUseCase(ConversationStorePort conversations, TimeProvider time) {
        this.conversations = conversations;
        this.time = time;
    }

    /** 查询当前认证用户自己的会话，并按最近访问时间倒序分页。 */
    public ConversationPage list(AuthenticatedUser actor, int page, int size) {
        requireActor(actor);
        if (page < 1 || size < 1 || size > MAXIMUM_PAGE_SIZE) {
            throw new IllegalArgumentException("会话分页参数不符合要求");
        }
        int offset;
        try {
            offset = Math.multiplyExact(page - 1, size);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("会话分页偏移量过大", exception);
        }
        var result = conversations.listLifecycle(actor.userId(), offset, size, time.now());
        List<ConversationOverview> items = result.items().stream().map(this::overview).toList();
        int totalPages = result.totalElements() == 0 ? 0
                : Math.toIntExact((result.totalElements() + size - 1) / size);
        return new ConversationPage(items, page, size, result.totalElements(), totalPages);
    }

    /** 查询所有者自己的会话；管理员可按明确 ID 查看其他用户会话。 */
    public ConversationDetails details(AuthenticatedUser actor, UUID conversationId) {
        requireActor(actor);
        if (conversationId == null) throw new IllegalArgumentException("会话 ID 不能为空");
        LifecycleSnapshot snapshot = conversations.lifecycleDetails(actor.userId(), actor.administrator(),
                conversationId, RECENT_DETAIL_TURNS, time.now());
        return new ConversationDetails(overview(snapshot), snapshot.turns().stream().map(this::turn).toList());
    }

    /** 仅允许所有者在版本一致且没有活动运行时重置会话。 */
    public ConversationOverview reset(AuthenticatedUser actor, UUID conversationId, long expectedVersion) {
        requireActor(actor);
        validateWrite(conversationId, expectedVersion);
        return overview(conversations.reset(actor.userId(), conversationId, expectedVersion, time.now()));
    }

    /** 仅允许所有者在版本一致且没有活动运行时永久删除 Redis 会话。 */
    public void delete(AuthenticatedUser actor, UUID conversationId, long expectedVersion) {
        requireActor(actor);
        validateWrite(conversationId, expectedVersion);
        conversations.delete(actor.userId(), conversationId, expectedVersion, time.now());
    }

    /** 把存储快照投影为不包含所有者和内部状态的公开元数据。 */
    private ConversationOverview overview(LifecycleSnapshot snapshot) {
        return new ConversationOverview(snapshot.conversationId(), snapshot.status(), snapshot.version(),
                snapshot.generation(), snapshot.summaryVersion(), snapshot.lastAccessAt(), snapshot.expiresAt());
    }

    /** 把完整成功轮次裁剪为用户可见字段。 */
    private ConversationTurnView turn(CompletedTurn turn) {
        return new ConversationTurnView(turn.turnId(), turn.userMessage(), turn.answer(), turn.intent(),
                turn.retrievalStatus(), turn.citations(), turn.completedAt(), turn.conversationVersion());
    }

    /** 拒绝缺失认证上下文。 */
    private void requireActor(AuthenticatedUser actor) {
        if (actor == null) throw new IllegalArgumentException("认证用户不能为空");
    }

    /** 校验生命周期写操作使用有效 ID 和非负预期版本。 */
    private void validateWrite(UUID conversationId, long expectedVersion) {
        if (conversationId == null || expectedVersion < 0) {
            throw new IllegalArgumentException("会话 ID 或预期版本不符合要求");
        }
    }
}
