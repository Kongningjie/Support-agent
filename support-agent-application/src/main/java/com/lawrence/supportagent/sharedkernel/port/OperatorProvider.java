package com.lawrence.supportagent.sharedkernel.port;

import com.lawrence.supportagent.sharedkernel.OperatorId;

/** 提供当前受信任操作者，不读取客户端伪造身份。 */
public interface OperatorProvider {
    /** 返回当前服务端确认的操作者。 */
    OperatorId currentOperator();
}
