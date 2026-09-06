package com.lawrence.supportagent.idempotency;

import java.util.function.LongFunction;
import java.util.function.Supplier;

/** 在基础设施事务中执行或复用具有外部幂等语义的写操作。 */
public interface IdempotentExecutor {
    /**
     * 执行首次操作，或按首次资源 ID 重查并复用成功结果。
     *
     * @param command 幂等键、请求哈希和租约配置
     * @param action 首次获得执行权后运行的业务写操作
     * @param replayLoader 成功重放时按资源 ID 读取最新等价响应
     * @param <T> 应用层返回类型
     * @return 首次执行或重放得到的结果
     */
    <T> T execute(IdempotencyCommand command, Supplier<IdempotentResource<T>> action,
                  LongFunction<T> replayLoader);
}
