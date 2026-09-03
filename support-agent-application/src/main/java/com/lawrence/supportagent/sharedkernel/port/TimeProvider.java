package com.lawrence.supportagent.sharedkernel.port;

import java.time.Instant;

/** 为应用用例提供可替换的统一 UTC 时间来源。 */
public interface TimeProvider {
    /** 返回当前 UTC 时间。 */
    Instant now();
}
