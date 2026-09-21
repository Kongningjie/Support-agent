package com.lawrence.supportagent.persistence.mapper;

import com.lawrence.supportagent.persistence.record.SecurityEventDO;
import org.apache.ibatis.annotations.Mapper;

/** 写入最小化账号安全审计事件。 */
@Mapper
public interface SecurityEventMapper {
    /** 插入一条安全事件并返回影响行数。 */
    int insert(SecurityEventDO event);
}
