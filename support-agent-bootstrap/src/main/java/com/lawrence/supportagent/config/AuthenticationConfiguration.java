package com.lawrence.supportagent.config;

import com.lawrence.supportagent.auth.AuthenticationUseCase;
import com.lawrence.supportagent.auth.BCryptPasswordHashAdapter;
import com.lawrence.supportagent.auth.InitialAdminUseCase;
import com.lawrence.supportagent.auth.LocalAuthenticationAdapter;
import com.lawrence.supportagent.auth.RedisAccessTokenAdapter;
import com.lawrence.supportagent.auth.RedisLoginAttemptAdapter;
import com.lawrence.supportagent.auth.UserAdminUseCase;
import com.lawrence.supportagent.auth.port.AccessTokenPort;
import com.lawrence.supportagent.auth.port.AuthenticationPort;
import com.lawrence.supportagent.auth.port.LoginAttemptPort;
import com.lawrence.supportagent.auth.port.PasswordHashPort;
import com.lawrence.supportagent.auth.port.SecurityEventPort;
import com.lawrence.supportagent.auth.port.UserRepository;
import com.lawrence.supportagent.idempotency.IdempotentExecutor;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import com.lawrence.supportagent.sharedkernel.port.UuidGenerator;
import java.time.Duration;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 装配本地认证、账号安全用例、稳定认证端口和首次管理员引导。 */
@Configuration
public class AuthenticationConfiguration {
    /** 创建固定 strength 12 的 BCrypt 密码适配器。 */
    @Bean
    public PasswordHashPort passwordHashPort() {
        return new BCryptPasswordHashAdapter();
    }

    /** 创建 Redis 不透明 Token 适配器。 */
    @Bean
    public AccessTokenPort accessTokenPort(StringRedisTemplate redis) {
        return new RedisAccessTokenAdapter(redis);
    }

    /** 以本地不透明 Token 实现稳定认证端口。 */
    @Bean
    public AuthenticationPort authenticationPort(AccessTokenPort tokens) {
        return new LocalAuthenticationAdapter(tokens);
    }

    /** 创建 Redis 登录失败限流适配器。 */
    @Bean
    public LoginAttemptPort loginAttemptPort(StringRedisTemplate redis) {
        return new RedisLoginAttemptAdapter(redis);
    }

    /** 创建登录、注销和本人查询用例。 */
    @Bean
    public AuthenticationUseCase authenticationUseCase(UserRepository users,
                                                        PasswordHashPort passwords,
                                                        AccessTokenPort tokens,
                                                        LoginAttemptPort attempts,
                                                        SecurityEventPort events,
                                                        TimeProvider time,
                                                        @Value("${support-agent.auth.token-ttl:2h}")
                                                        Duration tokenTtl,
                                                        @Value("${support-agent.auth.maximum-active-tokens:5}")
                                                        int maximumActiveTokens) {
        return new AuthenticationUseCase(users, passwords, tokens, attempts, events, time,
                tokenTtl, maximumActiveTokens);
    }

    /** 创建管理员用户管理用例。 */
    @Bean
    public UserAdminUseCase userAdminUseCase(UserRepository users, PasswordHashPort passwords,
                                             AccessTokenPort tokens, UuidGenerator ids,
                                             TimeProvider time, IdempotentExecutor idempotency,
                                             LoginAttemptPort attempts, SecurityEventPort events) {
        return new UserAdminUseCase(users, passwords, tokens, ids, time, idempotency,
                attempts, events);
    }

    /** 创建首次管理员引导用例。 */
    @Bean
    public InitialAdminUseCase initialAdminUseCase(UserRepository users, PasswordHashPort passwords,
                                                   UuidGenerator ids, TimeProvider time) {
        return new InitialAdminUseCase(users, passwords, ids, time);
    }

    /** 在数据库迁移后按显式环境变量引导唯一初始管理员。 */
    @Bean
    public ApplicationRunner initialAdminRunner(
            InitialAdminUseCase bootstrap, UserRepository users, Environment environment,
            @Value("${support-agent.auth.bootstrap-admin.enabled:false}") boolean enabled,
            @Value("${support-agent.auth.bootstrap-admin.username:}") String username,
            @Value("${support-agent.auth.bootstrap-admin.display-name:}") String displayName,
            @Value("${support-agent.auth.bootstrap-admin.password:}") String password) {
        return new ApplicationRunner() {
            /** 在应用完成装配后执行空表检查和可选引导。 */
            @Override public void run(ApplicationArguments arguments) {
                if (users.count() > 0) {
                    return;
                }
                if (!enabled) {
                    if (Arrays.asList(environment.getActiveProfiles()).contains("prod")) {
                        throw new IllegalStateException("生产环境用户表为空时必须启用初始管理员引导");
                    }
                    return;
                }
                bootstrap.bootstrap(true, username, displayName, password);
            }
        };
    }
}
