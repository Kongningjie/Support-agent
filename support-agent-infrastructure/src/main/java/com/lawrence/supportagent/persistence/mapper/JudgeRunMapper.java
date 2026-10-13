package com.lawrence.supportagent.persistence.mapper;
import com.lawrence.supportagent.persistence.record.JudgeRunDO;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
/** Judge SQL 操作，不在数据库事务内调用模型。 */
@Mapper
public interface JudgeRunMapper {
    /** 锁定人工样本行，使多进程占用按样本串行化。 */
    String lockSample(@Param("id") String id);
    /** 按操作者和 Key 查幂等结果。 */
    JudgeRunDO find(@Param("actor") String actor, @Param("key") String key);
    /** 释放已到期占用，但不自动执行新调用。 */
    int expire(@Param("sample") String sample, @Param("now") Instant now);
    /** 插入运行占用。 */
    int insert(JudgeRunDO value);
    /** 仅在租约有效时完成，返回影响行数。 */
    int complete(@Param("id") String id, @Param("json") String json,
                 @Param("status") String status, @Param("now") Instant now);
    /** 读取样本最近二十次运行。 */
    List<JudgeRunDO> list(@Param("sample") String sample);
    /** 聚合输入仅允许人工已决样本，最大1001条。 */
    List<JudgeRunDO> calibrationRuns();
}
