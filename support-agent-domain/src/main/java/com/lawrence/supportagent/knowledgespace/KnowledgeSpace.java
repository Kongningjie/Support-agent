package com.lawrence.supportagent.knowledgespace;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** 知识空间聚合，维护系统空间不变量、可见性、状态和乐观锁版本。 */
public record KnowledgeSpace(Long id, UUID spaceId, String code, String name, String description,
                             KnowledgeSpaceVisibility visibility, KnowledgeSpaceStatus status,
                             boolean systemSpace, long version, String createdBy,
                             Instant createdAt, String updatedBy, Instant updatedAt) {
    /** 企业公共空间的固定公开 UUID。 */
    public static final UUID GLOBAL_SPACE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    /** 企业公共空间的固定代码。 */
    public static final String GLOBAL_CODE = "GLOBAL";
    private static final Pattern CODE_PATTERN = Pattern.compile("[A-Z][A-Z0-9_-]{1,63}");

    /** 校验空间标识、展示字段、状态、版本和审计字段。 */
    public KnowledgeSpace {
        if (spaceId == null || visibility == null || status == null || version < 0
                || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("空间标识、可见性、状态、版本和时间不能为空");
        }
        code = normalizeCode(code);
        name = required(name, "空间名称", 100);
        description = optional(description, 500);
        createdBy = required(createdBy, "创建人", 64);
        updatedBy = required(updatedBy, "更新人", 64);
        if (systemSpace && (!GLOBAL_SPACE_ID.equals(spaceId) || !GLOBAL_CODE.equals(code)
                || visibility != KnowledgeSpaceVisibility.ENTERPRISE
                || status != KnowledgeSpaceStatus.ACTIVE)) {
            throw new IllegalArgumentException("系统空间必须保持固定 GLOBAL 不变量");
        }
        if (!systemSpace && GLOBAL_CODE.equals(code)) {
            throw new IllegalArgumentException("GLOBAL 是系统保留空间代码");
        }
    }

    /** 创建默认受限且活动的普通知识空间。 */
    public static KnowledgeSpace create(UUID spaceId, String code, String name, String description,
                                        String operator, Instant now) {
        return new KnowledgeSpace(null, spaceId, code, name, description,
                KnowledgeSpaceVisibility.RESTRICTED, KnowledgeSpaceStatus.ACTIVE, false, 0,
                operator, now, operator, now);
    }

    /** 修改普通空间的名称、说明与可见性，不允许改变代码。 */
    public KnowledgeSpace revise(String newName, String newDescription,
                                 KnowledgeSpaceVisibility newVisibility,
                                 String operator, Instant now) {
        requireMutable();
        if (newVisibility == null) {
            throw new IllegalArgumentException("空间可见性不能为空");
        }
        return new KnowledgeSpace(id, spaceId, code, newName, newDescription, newVisibility,
                status, false, version + 1, createdBy, createdAt, operator, now);
    }

    /** 停用普通活动空间并保留全部历史事实。 */
    public KnowledgeSpace disable(String operator, Instant now) {
        requireMutable();
        if (status != KnowledgeSpaceStatus.ACTIVE) {
            throw new IllegalStateException("只有活动空间可以停用");
        }
        return changeStatus(KnowledgeSpaceStatus.DISABLED, operator, now);
    }

    /** 重新启用已停用的普通空间。 */
    public KnowledgeSpace enable(String operator, Instant now) {
        requireMutable();
        if (status != KnowledgeSpaceStatus.DISABLED) {
            throw new IllegalStateException("只有停用空间可以启用");
        }
        return changeStatus(KnowledgeSpaceStatus.ACTIVE, operator, now);
    }

    /** 创建状态发生变化的新聚合快照。 */
    private KnowledgeSpace changeStatus(KnowledgeSpaceStatus newStatus, String operator, Instant now) {
        return new KnowledgeSpace(id, spaceId, code, name, description, visibility, newStatus,
                false, version + 1, createdBy, createdAt, operator, now);
    }

    /** 拒绝任何修改系统 GLOBAL 空间不变量的操作。 */
    private void requireMutable() {
        if (systemSpace) {
            throw new IllegalStateException("GLOBAL 系统空间不可修改");
        }
    }

    /** 规范化并校验稳定空间代码。 */
    public static String normalizeCode(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("空间代码不能为空");
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        if (!CODE_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("空间代码必须匹配 [A-Z][A-Z0-9_-]{1,63}");
        }
        return normalized;
    }

    /** 校验必填展示文本。 */
    private static String required(String value, String field, int maximumLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        String normalized = value.strip();
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + "不能超过 " + maximumLength + " 个字符");
        }
        return normalized;
    }

    /** 规范化可空说明文本。 */
    private static String optional(String value, int maximumLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException("空间说明不能超过 " + maximumLength + " 个字符");
        }
        return normalized;
    }
}
