package com.lawrence.supportagent.evaluation;

import com.lawrence.supportagent.persistence.mapper.JudgeRunMapper;
import com.lawrence.supportagent.persistence.record.JudgeRunDO;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;

/** Judge durable 占用及结果存储；模型调用由应用层在事务之外完成。 */
@Repository
public class MySqlJudgeRunRepository implements JudgeRunRepository {
    private final JudgeRunMapper mapper;
    private final ObjectMapper json;
    /** 注入 SQL Mapper 与统一 JSON 序列化器。 */
    public MySqlJudgeRunRepository(JudgeRunMapper mapper, ObjectMapper json) { this.mapper=mapper; this.json=json; }
    /** {@inheritDoc} */
    @Override public Optional<Run> find(UUID actorId, UUID key) {
        return Optional.ofNullable(mapper.find(actorId.toString(), key.toString())).map(this::run);
    }
    /** {@inheritDoc} */
    @Transactional
    @Override public void claim(Run value) {
        if (mapper.lockSample(value.sampleId().toString()) == null) throw conflict();
        mapper.expire(value.sampleId().toString(), value.startedAt());
        var row=new JudgeRunDO();
        row.runId=value.id().toString(); row.sampleId=value.sampleId().toString();
        row.actorId=value.actorId().toString(); row.requestKey=value.key().toString();
        row.modelName=value.model(); row.promptVersion=value.promptVersion(); row.status=value.status();
        row.estimatedInputTokens=value.estimatedInputTokens(); row.startedAt=value.startedAt();
        row.expiresAt=value.expiresAt(); row.attemptsJson="[]";
        try { if (mapper.insert(row)!=1) throw conflict(); }
        catch (DuplicateKeyException exception) { throw conflict(); }
    }
    /** {@inheritDoc} */
    @Override public void complete(UUID runId, List<Attempt> attempts, Instant now) {
        String status=attempts.stream().anyMatch(item->item.result()!=null) ? "COMPLETED" : "FAILED";
        if (mapper.complete(runId.toString(),json.writeValueAsString(attempts),status,now)!=1) throw conflict();
    }
    /** {@inheritDoc} */
    @Override public List<Run> list(UUID sampleId) { return mapper.list(sampleId.toString()).stream().map(this::run).toList(); }
    /** {@inheritDoc} */
    @Override public List<Run> calibrationRuns() { return mapper.calibrationRuns().stream().map(this::run).toList(); }
    /** 转换持久化字段，不把原始 JSON 返回接口。 */
    private Run run(JudgeRunDO value) {
        List<Attempt> attempts=json.readValue(value.attemptsJson,new TypeReference<List<Attempt>>() { });
        return new Run(UUID.fromString(value.runId),UUID.fromString(value.sampleId),UUID.fromString(value.actorId),
                UUID.fromString(value.requestKey),value.modelName,value.promptVersion,value.status,
                value.estimatedInputTokens,value.startedAt,value.expiresAt,List.copyOf(attempts));
    }
    /** 返回占用或租约冲突，不泄露 SQL 信息。 */
    private ApplicationException conflict() { return new ApplicationException(ErrorCode.COMMON_CONFLICT,"评测请求重复、样本忙或执行租约已过期，请查询结果"); }
}
