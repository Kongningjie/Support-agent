package com.lawrence.supportagent;

import com.lawrence.supportagent.agent.model.DashScopeChatModelAdapter;
import com.lawrence.supportagent.chat.ChatExecutionControl;
import com.lawrence.supportagent.chat.RedisConversationStoreAdapter;
import com.lawrence.supportagent.chat.port.ConversationStorePort;
import com.lawrence.supportagent.model.ChatModelPort;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.mockito.Mockito;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;

/** 仅测试源集内的浏览器联调进程：真实后端/存储和合成上游，不生成生产假模型接口。 */
public final class ChatBrowserTestServer {
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static final AtomicReference<ChatExecutionControl> CONTROL = new AtomicReference<>();
    private static volatile String modelBase;
    private static volatile Path modelState;

    /** 启动临时容器与本地上游；写就绪端口，检测停止文件后有序回收全部本进程资源。 */
    public static void main(String[] args) throws Exception {
        Path ready = Path.of(args[0]);
        Path stop = Path.of(args[1]);
        modelState = Path.of(args[2]);
        Files.writeString(modelState, "0");
        try (var mysql = new MySQLContainer("mysql:8.4.11").withDatabaseName("support_agent")
                     .withUsername("support_agent").withPassword("browser_test_password");
             var redis = new GenericContainer<>("redis:7.4.7").withExposedPorts(6379);
             ServerSocket upstream = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
             var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            mysql.start(); redis.start();
            modelBase = "http://127.0.0.1:" + upstream.getLocalPort();
            upstream.setSoTimeout(1000);
            var peer = threads.submit(() -> {
                while (!Files.exists(stop)) {
                    try (Socket socket = upstream.accept()) {
                        serveModel(socket);
                    } catch (java.net.SocketTimeoutException ignored) {
                        // 有界 accept 以便正常停止，不伪造模型成功结果。
                    } catch (java.io.IOException exception) {
                        if (!Files.exists(stop)) throw new IllegalStateException("本地模型连接异常", exception);
                    }
                }
            });
            var properties = Map.<String, Object>ofEntries(
                    Map.entry("server.port", "0"), Map.entry("server.address", "127.0.0.1"),
                    Map.entry("spring.datasource.url", mysql.getJdbcUrl()),
                    Map.entry("spring.datasource.username", mysql.getUsername()),
                    Map.entry("spring.datasource.password", mysql.getPassword()),
                    Map.entry("spring.data.redis.url", "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379)),
                    Map.entry("support-agent.dashscope.api-key", ""),
                    Map.entry("support-agent.async-task.enabled", "false"),
                    Map.entry("support-agent.elasticsearch.url", "http://127.0.0.1:1"),
                    Map.entry("support-agent.auth.bootstrap-admin.enabled", "true"),
                    Map.entry("support-agent.auth.bootstrap-admin.username", "browser-admin"),
                    Map.entry("support-agent.auth.bootstrap-admin.display-name", "Browser Test"),
                    Map.entry("support-agent.auth.bootstrap-admin.password", "browser_test_password"));
            // 命令行属性优先于 application.yml，避免本机环境指向真实数据库或模型。
            String[] overrides = properties.entrySet().stream().map(entry -> "--" + entry.getKey() + "=" + entry.getValue()).toArray(String[]::new);
            try (var context = new SpringApplicationBuilder(BrowserApplication.class, Fixtures.class).run(overrides)) {
                Files.writeString(ready, context.getEnvironment().getProperty("local.server.port"));
                long deadline = System.nanoTime() + Duration.ofMinutes(10).toNanos();
                while (!Files.exists(stop) && System.nanoTime() < deadline && !peer.isDone()) TimeUnit.MILLISECONDS.sleep(200);
                if (!Files.exists(stop)) Files.createFile(stop);
                long cancellationDeadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
                while (CONTROL.get() != null && !CONTROL.get().isCancelled() && System.nanoTime() < cancellationDeadline) TimeUnit.MILLISECONDS.sleep(100);
                peer.get(35, TimeUnit.SECONDS);
            } finally {
                // 启动或验证异常也必须终止 accept 循环，不能让执行器关闭无限等待。
                if (!Files.exists(stop)) Files.createFile(stop);
            }
            if (REQUESTS.get() != 3) throw new IllegalStateException("模型实际请求次数与预期不符");
        }
        Files.writeString(ready.resolveSibling("complete"), "PASS");
    }

    /** 第一请求持续等待 EOF 验证真正取消，其余请求返回合法 OpenAI 流。 */
    private static void serveModel(Socket socket) throws java.io.IOException {
        socket.setSoTimeout(60000);
        InputStream input = socket.getInputStream();
        int length = 0;
        String line;
        while (!(line = readLine(input)).isEmpty()) {
            if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
        }
        input.readNBytes(length);
        int count = REQUESTS.incrementAndGet();
        Files.writeString(modelState, Integer.toString(count));
        if (count == 1) {
            if (input.read() != -1) throw new IllegalStateException("上游连接未被主动关闭");
            return;
        }
        String json = new ObjectMapper().writeValueAsString(Map.of("id", "fixture", "object", "chat.completion.chunk", "created", 0,
                "model", "fixture", "choices", java.util.List.of(Map.of("index", 0, "delta", Map.of("role", "assistant", "content", "你好，请描述需要处理的技术问题。")))));
        byte[] body = ("data: " + json + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
        var output = socket.getOutputStream();
        output.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(body); output.flush();
    }

    /** 仅解析合成本地 HTTP 请求头，不记录请求正文或凭据。 */
    private static String readLine(InputStream input) throws java.io.IOException {
        StringBuilder text = new StringBuilder();
        int next;
        while ((next = input.read()) != -1 && next != '\n') if (next != '\r') text.append((char) next);
        if (next == -1) throw new java.io.EOFException("请求头未完成");
        return text.toString();
    }

    /** 使用相同生产配置和组件，仅排除测试源集的其他测试配置，避免扩大扫描范围。 */
    @TestConfiguration
    @org.springframework.boot.SpringBootConfiguration
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration(exclude = org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class)
    @org.springframework.scheduling.annotation.EnableScheduling
    @org.springframework.context.annotation.ComponentScan(basePackages = "com.lawrence.supportagent", excludeFilters = {
            @org.springframework.context.annotation.ComponentScan.Filter(type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE, classes = SupportAgentApplication.class),
            @org.springframework.context.annotation.ComponentScan.Filter(type = org.springframework.context.annotation.FilterType.ANNOTATION, classes = TestConfiguration.class),
            @org.springframework.context.annotation.ComponentScan.Filter(type = org.springframework.context.annotation.FilterType.REGEX, pattern = ".*(Test|IT)(\\$.*)?$")
    })
    static class BrowserApplication { }

    /** 仅装配测试替身边界；业务、认证、权限、Redis Lua、MySQL 及 SSE 均为实际实现。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        /** 使用实际适配器访问本地模型，观测同一个请求控制供丢失提交响应实验使用。 */
        @Bean static BeanPostProcessor fixtures() {
            return new BeanPostProcessor() {
                /** 仅替换聊天端口地址与提交响应边界，不改变实际用例。 */
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if ("chatModelPort".equals(name)) {
                        ChatModelPort model = Mockito.mock(ChatModelPort.class);
                        var adapter = new DashScopeChatModelAdapter("local-fixture-key", "fixture", modelBase);
                        Mockito.when(model.greeting(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any())).thenAnswer(call -> {
                            CONTROL.set(call.getArgument(4));
                            return adapter.greeting(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3), call.getArgument(4));
                        });
                        return model;
                    }
                    if (bean instanceof RedisConversationStoreAdapter) {
                        ConversationStorePort store = Mockito.spy((RedisConversationStoreAdapter) bean);
                        Mockito.doAnswer(call -> {
                            var turn = call.getArgument(3, ConversationStorePort.CompletedTurn.class);
                            Object saved = call.callRealMethod();
                            if ("谢谢".equals(turn.userMessage())) {
                                ChatExecutionControl control = CONTROL.get();
                                try (var registration = control.register(() -> { })) {
                                    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
                                    while (!control.isCancelled() && System.nanoTime() < deadline) TimeUnit.MILLISECONDS.sleep(25);
                                    if (!control.isCancelled()) throw new IllegalStateException("浏览器未在提交后断连");
                                }
                                throw new IllegalStateException("合成提交响应丢失");
                            }
                            return saved;
                        }).when(store).complete(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
                        return store;
                    }
                    return bean;
                }
            };
        }
    }
}
