package com.lawrence.supportagent.sharedkernel.port;

import java.util.UUID;

/** 为应用用例提供可替换的 UUID 生成能力。 */
public interface UuidGenerator {
    /** 生成一个新的随机 UUID。 */
    UUID generate();
}
