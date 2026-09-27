package com.lawrence.supportagent.knowledgespace;

/** 空间成员角色，按 READER、EDITOR、MANAGER 形成固定包含关系。 */
public enum SpaceRole {
    /** 可读取并选择空间。 */
    READER(1),
    /** 包含读取权限，可创建和修改知识草稿。 */
    EDITOR(2),
    /** 包含编辑权限，可治理成员、发布和归档知识。 */
    MANAGER(3);

    private final int level;

    /** 使用稳定权限等级创建角色枚举。 */
    SpaceRole(int level) {
        this.level = level;
    }

    /** 判断当前角色是否包含指定最低角色。 */
    public boolean includes(SpaceRole required) {
        return required != null && level >= required.level;
    }
}
