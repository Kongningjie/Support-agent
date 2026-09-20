package com.lawrence.supportagent.auth;

import com.lawrence.supportagent.auth.port.PasswordHashPort;
import com.lawrence.supportagent.auth.port.UserRepository;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import com.lawrence.supportagent.sharedkernel.port.UuidGenerator;
import com.lawrence.supportagent.user.UserAccount;
import com.lawrence.supportagent.user.UserRole;

/** 仅在空用户表上执行一次环境变量初始管理员引导。 */
public class InitialAdminUseCase {
    private final UserRepository users;
    private final PasswordHashPort passwords;
    private final UuidGenerator ids;
    private final TimeProvider time;

    /** 注入首次管理员所需的持久化、哈希、标识和时间端口。 */
    public InitialAdminUseCase(UserRepository users, PasswordHashPort passwords,
                               UuidGenerator ids, TimeProvider time) {
        this.users = users;
        this.passwords = passwords;
        this.ids = ids;
        this.time = time;
    }

    /** 在用户表为空时按显式配置创建管理员；非空时不读取或校验密码。 */
    public boolean bootstrap(boolean enabled, String username, String displayName, String password) {
        if (users.count() > 0) {
            return false;
        }
        if (!enabled) {
            return false;
        }
        UserAccount account = UserAccount.create(ids.generate(), username, displayName,
                passwords.hash(PasswordPolicy.validate(password)), UserRole.ADMIN,
                "SYSTEM_BOOTSTRAP", time.now());
        users.save(account);
        return true;
    }
}
