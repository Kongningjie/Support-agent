package com.lawrence.supportagent.ticket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 验证工单聚合的一期状态机。 */
class TicketTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** 验证草稿可提交并由开放状态解决。 */
    @Test
    void shouldSubmitAndResolveTicket() {
        Ticket resolved = Ticket.draft(null, null, OWNER, "无法启动", "启动时报错", null, "dev-operator", NOW)
                .submit("dev-operator", NOW.plusSeconds(1))
                .resolve("配置缺失", "补充配置", "dev-operator", NOW.plusSeconds(2));
        assertEquals(TicketStatus.RESOLVED, resolved.status());
        assertEquals(2, resolved.version());
    }

    /** 验证草稿不能跳过开放状态直接解决。 */
    @Test
    void shouldRejectResolvingDraft() {
        Ticket draft = Ticket.draft(null, null, OWNER, "无法启动", "启动时报错", null, "dev-operator", NOW);
        assertThrows(IllegalStateException.class,
                () -> draft.resolve("根因", "方案", "dev-operator", NOW));
    }

    /** 验证草稿修改以及草稿、开放工单的关闭路径都会递增版本。 */
    @Test
    void shouldReviseAndCloseAllowedTickets() {
        Ticket revised = Ticket.draft(null, null, OWNER, "旧标题", "旧描述", null,
                "creator", NOW).reviseDraft("新标题", "新描述", "已重试", "editor",
                NOW.plusSeconds(1));
        Ticket closedDraft = revised.close("无需处理", "closer", NOW.plusSeconds(2));
        Ticket closedOpen = Ticket.draft(null, null, OWNER, "标题", "描述", null, "creator", NOW)
                .submit("submitter", NOW.plusSeconds(1))
                .close("无法复现", "closer", NOW.plusSeconds(2));

        assertEquals(2, closedDraft.version());
        assertEquals(TicketStatus.CLOSED, closedOpen.status());
        assertNotNull(closedOpen.closedAt());
    }

    /** 验证终态工单不能再次提交、解决或关闭。 */
    @Test
    void shouldRejectTransitionsFromTerminalStatus() {
        Ticket resolved = Ticket.draft(null, null, OWNER, "标题", "描述", null, "creator", NOW)
                .submit("submitter", NOW).resolve("根因", "方案", "resolver", NOW);

        assertThrows(IllegalStateException.class, () -> resolved.submit("operator", NOW));
        assertThrows(IllegalStateException.class, () -> resolved.close("关闭", "operator", NOW));
    }

    /** 验证创建工单时标题、问题描述和审计字段必须有效。 */
    @Test
    void shouldRejectMissingRequiredFields() {
        assertThrows(IllegalArgumentException.class,
                () -> Ticket.draft(null, null, OWNER, " ", "描述", null, "operator", NOW));
        assertThrows(IllegalArgumentException.class,
                () -> Ticket.draft(null, null, OWNER, "标题", null, null, "operator", NOW));
    }
}
