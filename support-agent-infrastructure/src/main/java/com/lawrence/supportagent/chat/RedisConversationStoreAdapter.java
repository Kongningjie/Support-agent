package com.lawrence.supportagent.chat;

import com.lawrence.supportagent.chat.port.ConversationStorePort;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import tools.jackson.databind.ObjectMapper;

/** 使用 Redis Lua 原子维护会话生命周期、运行围栏、消息幂等、摘要和工单建议。 */
public class RedisConversationStoreAdapter implements ConversationStorePort {
    private static final Duration DEFAULT_CONVERSATION_TTL = Duration.ofDays(7);
    private static final Duration DEFAULT_SUGGESTION_TTL = Duration.ofHours(24);
    private static final Duration DEFAULT_RUN_LEASE = Duration.ofMinutes(3);
    private static final Duration DEFAULT_SUGGESTION_LEASE = Duration.ofMinutes(3);
    private static final String PREFIX = "support-agent:chat:";
    private static final int LIFECYCLE_FIELD_COUNT = 8;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Duration conversationTtl;
    private final Duration suggestionTtl;
    private final Duration runLease;
    private final Duration suggestionLease;

    /** 注入字符串 Redis 客户端和统一 JSON 序列化器。 */
    public RedisConversationStoreAdapter(StringRedisTemplate redis, ObjectMapper mapper) {
        this(redis, mapper, DEFAULT_CONVERSATION_TTL, DEFAULT_SUGGESTION_TTL,
                DEFAULT_RUN_LEASE, DEFAULT_SUGGESTION_LEASE);
    }

    /** 注入 Redis 客户端以及会话、建议和租约的显式生命周期配置。 */
    public RedisConversationStoreAdapter(StringRedisTemplate redis, ObjectMapper mapper,
                                         Duration conversationTtl, Duration suggestionTtl,
                                         Duration runLease, Duration suggestionLease) {
        validateDurations(conversationTtl, suggestionTtl, runLease, suggestionLease);
        this.redis = redis;
        this.mapper = mapper;
        this.conversationTtl = conversationTtl;
        this.suggestionTtl = suggestionTtl;
        this.runLease = runLease;
        this.suggestionLease = suggestionLease;
    }

    /** {@inheritDoc} */
    @Override
    public BeginResult begin(UUID ownerUserId, UUID conversationId, UUID clientMessageId, String message,
                             Long expectedVersion, UUID runId, Instant now) {
        if (ownerUserId == null) throw new IllegalArgumentException("会话所有者不能为空");
        UUID actualConversationId = conversationId == null ? UUID.randomUUID() : conversationId;
        String script = """
                local exists=redis.call('EXISTS',KEYS[1])
                if exists==0 then
                  if ARGV[4]~='-' then return {'EXPIRED'} end
                  redis.call('HSET',KEYS[1],'version','0','generation','0','summaryVersion','0',
                    'lastSequence','0','ownerUserId',ARGV[1],'createdAt',ARGV[5],'lastAccessAt',ARGV[5],'status','IDLE')
                elseif redis.call('HGET',KEYS[1],'ownerUserId')~=ARGV[1] then return {'OWNER'}
                elseif ARGV[4]=='-' or (redis.call('HGET',KEYS[1],'version') or '0')~=ARGV[4] then
                  return {'VERSION',redis.call('HGET',KEYS[1],'version') or '0'}
                end
                local generation=redis.call('HGET',KEYS[1],'generation') or '0'
                local messageKey=ARGV[9]..generation..':'..ARGV[8]
                local oldHash=redis.call('HGET',messageKey,'requestHash')
                if oldHash and oldHash~=ARGV[3] then return {'MESSAGE_REUSED'} end
                if redis.call('HGET',messageKey,'status')=='COMPLETED' then
                  redis.call('HSET',KEYS[1],'lastAccessAt',ARGV[5])
                  redis.call('ZADD',KEYS[2],ARGV[5],ARGV[10])
                  redis.call('EXPIRE',KEYS[1],ARGV[7]); redis.call('EXPIRE',messageKey,ARGV[7])
                  redis.call('EXPIRE',KEYS[2],ARGV[7]); redis.call('EXPIRE',KEYS[3],ARGV[7]); redis.call('EXPIRE',KEYS[4],ARGV[7])
                  return {'REPLAY',redis.call('HGET',KEYS[1],'version') or '0',redis.call('HGET',messageKey,'result')}
                end
                local active=redis.call('HGET',KEYS[1],'activeRunId')
                local lease=tonumber(redis.call('HGET',KEYS[1],'runLeaseUntil') or '0')
                if active and lease>tonumber(ARGV[5]) then return {'BUSY'} end
                local interrupted=''; if active then interrupted=active end
                redis.call('HSET',KEYS[1],'status','RUNNING','activeRunId',ARGV[2],
                  'runLeaseUntil',ARGV[6],'lastAccessAt',ARGV[5],'activeMessageKey',messageKey)
                redis.call('HSET',messageKey,'requestHash',ARGV[3],'status','PROCESSING','runId',ARGV[2])
                redis.call('SADD',KEYS[4],messageKey)
                redis.call('ZADD',KEYS[2],ARGV[5],ARGV[10])
                redis.call('EXPIRE',KEYS[1],ARGV[7]); redis.call('EXPIRE',messageKey,ARGV[7])
                redis.call('EXPIRE',KEYS[2],ARGV[7]); redis.call('EXPIRE',KEYS[3],ARGV[7]); redis.call('EXPIRE',KEYS[4],ARGV[7])
                return {'ACQUIRED',redis.call('HGET',KEYS[1],'version'),interrupted}
                """;
        List<?> result = executeList(script, List.of(conversationKey(actualConversationId),
                        userIndexKey(ownerUserId), turnsKey(actualConversationId), auxiliaryKey(actualConversationId)),
                ownerUserId.toString(), runId.toString(), sha256(message.trim()),
                expectedVersion == null ? "-" : expectedVersion.toString(), Long.toString(now.toEpochMilli()),
                Long.toString(now.plus(runLease).toEpochMilli()), Long.toString(conversationTtl.toSeconds()),
                clientMessageId.toString(), messageKeyPrefix(actualConversationId), actualConversationId.toString());
        return beginResult(actualConversationId, result);
    }

    /** 把 Lua 开始结果转换为稳定领域结果或应用异常。 */
    private BeginResult beginResult(UUID conversationId, List<?> result) {
        String status = string(result, 0);
        if ("MESSAGE_REUSED".equals(status)) throw error(ErrorCode.CHAT_MESSAGE_ID_REUSED, "clientMessageId 已用于其他消息");
        if ("OWNER".equals(status) || "EXPIRED".equals(status)) throw notFound();
        if ("VERSION".equals(status)) throw versionConflict();
        if ("BUSY".equals(status)) throw busy();
        if ("REPLAY".equals(status)) {
            return new BeginResult(BeginStatus.REPLAY, conversationId,
                    Long.parseLong(string(result, 1)), read(string(result, 2), CompletedTurn.class), null);
        }
        UUID interrupted = string(result, 2).isBlank() ? null : UUID.fromString(string(result, 2));
        return new BeginResult(BeginStatus.ACQUIRED, conversationId,
                Long.parseLong(string(result, 1)), null, interrupted);
    }

    /** {@inheritDoc} */
    @Override
    public boolean renew(UUID ownerUserId, UUID conversationId, UUID runId, Instant now) {
        String script = "if redis.call('HGET',KEYS[1],'ownerUserId')~=ARGV[1] or "
                + "redis.call('HGET',KEYS[1],'activeRunId')~=ARGV[2] then return 0 end "
                + "redis.call('HSET',KEYS[1],'runLeaseUntil',ARGV[3]); return 1";
        Long result = redis.execute(new DefaultRedisScript<>(script, Long.class),
                List.of(conversationKey(conversationId)), ownerUserId.toString(), runId.toString(),
                Long.toString(now.plus(runLease).toEpochMilli()));
        return Long.valueOf(1).equals(result);
    }

    /** {@inheritDoc} */
    @Override
    public long nextSequence(UUID ownerUserId, UUID conversationId, UUID runId) {
        String script = "if redis.call('HGET',KEYS[1],'ownerUserId')~=ARGV[1] or "
                + "redis.call('HGET',KEYS[1],'activeRunId')~=ARGV[2] then return -1 end "
                + "return redis.call('HINCRBY',KEYS[1],'lastSequence',1)";
        Long result = redis.execute(new DefaultRedisScript<>(script, Long.class),
                List.of(conversationKey(conversationId)), ownerUserId.toString(), runId.toString());
        if (result == null || result < 0) throw busy();
        return result;
    }

    /** {@inheritDoc} */
    @Override
    public long nextReplaySequence(UUID ownerUserId, UUID conversationId) {
        String script = "if redis.call('HGET',KEYS[1],'ownerUserId')~=ARGV[1] then return -1 end "
                + "return redis.call('HINCRBY',KEYS[1],'lastSequence',1)";
        Long result = redis.execute(new DefaultRedisScript<>(script, Long.class),
                List.of(conversationKey(conversationId)), ownerUserId.toString());
        if (result == null) throw error(ErrorCode.DEPENDENCY_UNAVAILABLE, "无法分配会话事件序号");
        if (result < 0) throw notFound();
        return result;
    }

    /** {@inheritDoc} */
    @Override
    public CompletedTurn complete(UUID ownerUserId, UUID conversationId, UUID runId, CompletedTurn turn,
                                  String serializedAgentState, Instant now) {
        String script = """
                if redis.call('HGET',KEYS[1],'ownerUserId')~=ARGV[1] or redis.call('HGET',KEYS[1],'activeRunId')~=ARGV[2] then return 0 end
                local generation=redis.call('HGET',KEYS[1],'generation') or '0'
                local messageKey=ARGV[13]..generation..':'..ARGV[11]
                redis.call('RPUSH',KEYS[3],ARGV[3])
                redis.call('HSET',KEYS[1],'version',ARGV[4],'status','IDLE','lastAccessAt',ARGV[5])
                redis.call('HDEL',KEYS[1],'activeRunId','runLeaseUntil','activeMessageKey')
                if ARGV[6]~='' then redis.call('HSET',KEYS[1],'agentState',ARGV[6]) end
                redis.call('HSET',messageKey,'status','COMPLETED','result',ARGV[3])
                redis.call('SADD',KEYS[4],messageKey)
                if ARGV[7]=='1' then
                  local suggestionKey=ARGV[14]..generation..':'..ARGV[12]
                  redis.call('HSET',suggestionKey,'status','AVAILABLE','context',ARGV[8],'sourceTurnId',ARGV[9])
                  redis.call('SADD',KEYS[4],suggestionKey); redis.call('EXPIRE',suggestionKey,ARGV[16])
                end
                redis.call('ZADD',KEYS[2],ARGV[5],ARGV[15])
                redis.call('EXPIRE',KEYS[1],ARGV[10]); redis.call('EXPIRE',messageKey,ARGV[10])
                redis.call('EXPIRE',KEYS[2],ARGV[10]); redis.call('EXPIRE',KEYS[3],ARGV[10]); redis.call('EXPIRE',KEYS[4],ARGV[10])
                return 1
                """;
        Long result = redis.execute(new DefaultRedisScript<>(script, Long.class),
                List.of(conversationKey(conversationId), userIndexKey(ownerUserId),
                        turnsKey(conversationId), auxiliaryKey(conversationId)),
                ownerUserId.toString(), runId.toString(), write(turn), Long.toString(turn.conversationVersion()),
                Long.toString(now.toEpochMilli()), serializedAgentState == null ? "" : serializedAgentState,
                turn.suggestionId() == null ? "0" : "1", nullToEmpty(turn.suggestionContext()),
                turn.turnId().toString(), Long.toString(conversationTtl.toSeconds()),
                turn.clientMessageId().toString(), turn.suggestionId() == null ? "" : turn.suggestionId().toString(),
                messageKeyPrefix(conversationId), suggestionKeyPrefix(conversationId), conversationId.toString(),
                Long.toString(suggestionTtl.toSeconds()));
        if (!Long.valueOf(1).equals(result)) throw busy();
        return turn;
    }

    /** {@inheritDoc} */
    @Override
    public void fail(UUID ownerUserId, UUID conversationId, UUID runId, Instant now) {
        String script = """
                if redis.call('HGET',KEYS[1],'ownerUserId')==ARGV[1] and redis.call('HGET',KEYS[1],'activeRunId')==ARGV[2] then
                  local messageKey=redis.call('HGET',KEYS[1],'activeMessageKey')
                  redis.call('HSET',KEYS[1],'status','IDLE','lastAccessAt',ARGV[3])
                  redis.call('HDEL',KEYS[1],'activeRunId','runLeaseUntil','activeMessageKey')
                  if messageKey then redis.call('HSET',messageKey,'status','FAILED') end
                  redis.call('ZADD',KEYS[2],ARGV[3],ARGV[5]); redis.call('EXPIRE',KEYS[1],ARGV[4]); redis.call('EXPIRE',KEYS[2],ARGV[4])
                  return 1
                end
                return 0
                """;
        redis.execute(new DefaultRedisScript<>(script, Long.class),
                List.of(conversationKey(conversationId), userIndexKey(ownerUserId)), ownerUserId.toString(),
                runId.toString(), Long.toString(now.toEpochMilli()), Long.toString(conversationTtl.toSeconds()),
                conversationId.toString());
    }

    /** {@inheritDoc} */
    @Override
    public SuggestionClaim claimSuggestion(UUID ownerUserId, UUID conversationId,
                                           UUID suggestionId, Instant now) {
        UUID claimId = UUID.randomUUID();
        String script = """
                if redis.call('HGET',KEYS[1],'ownerUserId')~=ARGV[1] then return {'NOT_FOUND'} end
                local generation=redis.call('HGET',KEYS[1],'generation') or '0'
                local suggestionKey=ARGV[6]..generation..':'..ARGV[5]
                if redis.call('EXISTS',suggestionKey)==0 then return {'NOT_FOUND'} end
                local status=redis.call('HGET',suggestionKey,'status')
                if status=='CONSUMED' then return {'CONSUMED','','',redis.call('HGET',suggestionKey,'sourceTurnId'),redis.call('HGET',suggestionKey,'ticketNo')} end
                local lease=tonumber(redis.call('HGET',suggestionKey,'leaseUntil') or '0')
                if status=='PROCESSING' and lease>tonumber(ARGV[2]) then return {'PROCESSING'} end
                redis.call('HSET',suggestionKey,'status','PROCESSING','claimId',ARGV[3],'leaseUntil',ARGV[4])
                return {'CLAIMED',ARGV[3],redis.call('HGET',suggestionKey,'context'),redis.call('HGET',suggestionKey,'sourceTurnId'),''}
                """;
        List<?> result = executeList(script, List.of(conversationKey(conversationId)), ownerUserId.toString(),
                Long.toString(now.toEpochMilli()), claimId.toString(), Long.toString(now.plus(suggestionLease).toEpochMilli()),
                suggestionId.toString(), suggestionKeyPrefix(conversationId));
        String status = string(result, 0);
        if ("NOT_FOUND".equals(status)) throw error(ErrorCode.TICKET_SUGGESTION_NOT_FOUND, "工单建议不存在或已过期");
        if ("PROCESSING".equals(status)) throw error(ErrorCode.TICKET_SUGGESTION_IN_PROGRESS, "工单建议正在处理中");
        return new SuggestionClaim(status, string(result, 1).isBlank() ? null : UUID.fromString(string(result, 1)),
                string(result, 2), UUID.fromString(string(result, 3)), string(result, 4));
    }

    /** {@inheritDoc} */
    @Override
    public void consumeSuggestion(UUID ownerUserId, UUID conversationId, UUID suggestionId, UUID claimId,
                                  String ticketNo, Instant now) {
        String script = """
                if redis.call('HGET',KEYS[1],'ownerUserId')~=ARGV[1] then return 0 end
                local generation=redis.call('HGET',KEYS[1],'generation') or '0'
                local suggestionKey=ARGV[6]..generation..':'..ARGV[5]
                if redis.call('HGET',suggestionKey,'claimId')~=ARGV[2] then return 0 end
                redis.call('HSET',suggestionKey,'status','CONSUMED','ticketNo',ARGV[3],'consumedAt',ARGV[4])
                redis.call('HDEL',suggestionKey,'claimId','leaseUntil','context'); return 1
                """;
        Long result = redis.execute(new DefaultRedisScript<>(script, Long.class),
                List.of(conversationKey(conversationId)), ownerUserId.toString(), claimId.toString(), ticketNo,
                Long.toString(now.toEpochMilli()), suggestionId.toString(), suggestionKeyPrefix(conversationId));
        if (!Long.valueOf(1).equals(result)) {
            throw error(ErrorCode.TICKET_SUGGESTION_IN_PROGRESS, "工单建议消费租约已经失效");
        }
    }

    /** {@inheritDoc} */
    @Override
    public List<String> recentContext(UUID ownerUserId, UUID conversationId,
                                      int maximumTurns, int maximumCharacters) {
        String script = "if redis.call('HGET',KEYS[1],'ownerUserId')~=ARGV[1] then return {'OWNER'} end "
                + "local result={'OK'}; local turns=redis.call('LRANGE',KEYS[2],-tonumber(ARGV[2]),-1); "
                + "for _,turn in ipairs(turns) do table.insert(result,turn) end; return result";
        List<?> raw = executeList(script, List.of(conversationKey(conversationId), turnsKey(conversationId)),
                ownerUserId.toString(), Integer.toString(maximumTurns));
        if ("OWNER".equals(string(raw, 0))) throw notFound();
        List<String> reversed = new ArrayList<>();
        int characters = 0;
        for (int index = raw.size() - 1; index >= 1; index--) {
            CompletedTurn turn = read(string(raw, index), CompletedTurn.class);
            String value = "用户：" + turn.userMessage() + "\n助手：" + turn.answer();
            if (characters + value.length() > maximumCharacters) break;
            reversed.add(value);
            characters += value.length();
        }
        Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    /** {@inheritDoc} */
    @Override
    public MemorySnapshot memorySnapshot(UUID ownerUserId, UUID conversationId) {
        String script = """
                if redis.call('EXISTS',KEYS[1])==0 or redis.call('HGET',KEYS[1],'ownerUserId')~=ARGV[1] then return {'EXPIRED'} end
                local result={redis.call('HGET',KEYS[1],'version') or '0',
                  redis.call('HGET',KEYS[1],'generation') or '0',redis.call('HGET',KEYS[1],'summaryVersion') or '0',
                  redis.call('HGET',KEYS[1],'summary') or ''}
                local turns=redis.call('LRANGE',KEYS[2],0,-1)
                for _,turn in ipairs(turns) do table.insert(result,turn) end
                return result
                """;
        List<?> values = executeList(script,
                List.of(conversationKey(conversationId), turnsKey(conversationId)), ownerUserId.toString());
        if ("EXPIRED".equals(string(values, 0))) throw notFound();
        List<CompletedTurn> turns = new ArrayList<>();
        for (int index = 4; index < values.size(); index++) turns.add(read(string(values, index), CompletedTurn.class));
        String summaryJson = string(values, 3);
        ConversationSummary summary = summaryJson.isBlank() ? null : read(summaryJson, ConversationSummary.class);
        return new MemorySnapshot(conversationId, Long.parseLong(string(values, 0)),
                Long.parseLong(string(values, 1)), Long.parseLong(string(values, 2)), summary, turns);
    }

    /** {@inheritDoc} */
    @Override
    public boolean commitSummary(UUID ownerUserId, UUID conversationId, long expectedSummaryVersion,
                                 long expectedGeneration, ConversationSummary summary,
                                 int recentFullTurns, Instant now) {
        String script = """
                if redis.call('HGET',KEYS[1],'ownerUserId')~=ARGV[1] then return 0 end
                if tonumber(redis.call('HGET',KEYS[1],'generation') or '0')~=tonumber(ARGV[3]) then return 0 end
                local current=tonumber(redis.call('HGET',KEYS[1],'summaryVersion') or '0')
                if current~=tonumber(ARGV[2]) then return 0 end
                local conversationVersion=tonumber(redis.call('HGET',KEYS[1],'version') or '-1')
                if conversationVersion<tonumber(ARGV[4]) then return 0 end
                local oldCovered=tonumber(redis.call('HGET',KEYS[1],'summaryCoveredThroughVersion') or '0')
                if oldCovered>=tonumber(ARGV[4]) then return 0 end
                local turns=redis.call('LRANGE',KEYS[3],0,-1); local keep={}; local recent=tonumber(ARGV[6])
                for index,turn in ipairs(turns) do
                  local ok,value=pcall(cjson.decode,turn); if not ok or not value.conversationVersion then return -2 end
                  if value.conversationVersion>tonumber(ARGV[4]) or index>#turns-recent then table.insert(keep,turn) end
                end
                redis.call('HSET',KEYS[1],'summary',ARGV[5],'summaryVersion',tostring(current+1),
                  'summaryCoveredThroughVersion',ARGV[4],'lastAccessAt',ARGV[7])
                redis.call('DEL',KEYS[3]); if #keep>0 then redis.call('RPUSH',KEYS[3],unpack(keep)) end
                redis.call('ZADD',KEYS[2],ARGV[7],ARGV[9])
                redis.call('EXPIRE',KEYS[1],ARGV[8]); redis.call('EXPIRE',KEYS[2],ARGV[8]); redis.call('EXPIRE',KEYS[3],ARGV[8])
                return 1
                """;
        Long result = redis.execute(new DefaultRedisScript<>(script, Long.class),
                List.of(conversationKey(conversationId), userIndexKey(ownerUserId), turnsKey(conversationId)),
                ownerUserId.toString(), Long.toString(expectedSummaryVersion), Long.toString(expectedGeneration),
                Long.toString(summary.coveredThroughVersion()), write(summary), Integer.toString(recentFullTurns),
                Long.toString(now.toEpochMilli()), Long.toString(conversationTtl.toSeconds()), conversationId.toString());
        if (Long.valueOf(-2).equals(result)) throw error(ErrorCode.COMMON_INTERNAL_ERROR, "会话轮次数据无法用于摘要提交");
        return Long.valueOf(1).equals(result);
    }

    /** {@inheritDoc} */
    @Override
    public LifecyclePage listLifecycle(UUID ownerUserId, int offset, int limit, Instant now) {
        String script = """
                local ids=redis.call('ZRANGE',KEYS[1],0,-1)
                for _,id in ipairs(ids) do
                  local conversationKey=ARGV[5]..'{'..id..'}'
                  if redis.call('EXISTS',conversationKey)==0 or redis.call('HGET',conversationKey,'ownerUserId')~=ARGV[1] then
                    redis.call('ZREM',KEYS[1],id)
                  end
                end
                local total=redis.call('ZCARD',KEYS[1]); local result={tostring(total)}
                local page=redis.call('ZREVRANGE',KEYS[1],tonumber(ARGV[2]),tonumber(ARGV[2])+tonumber(ARGV[3])-1)
                for _,id in ipairs(page) do
                  local key=ARGV[5]..'{'..id..'}'; local owner=redis.call('HGET',key,'ownerUserId')
                  local lease=tonumber(redis.call('HGET',key,'runLeaseUntil') or '0')
                  local active=redis.call('HGET',key,'activeRunId'); local status='IDLE'
                  if active and lease>tonumber(ARGV[4]) then status='RUNNING' end
                  local accessed=redis.call('HGET',key,'lastAccessAt') or redis.call('HGET',key,'createdAt') or ARGV[4]
                  local ttl=redis.call('PTTL',key); local expires=tonumber(ARGV[4]); if ttl>0 then expires=expires+ttl end
                  table.insert(result,id); table.insert(result,owner); table.insert(result,status)
                  table.insert(result,redis.call('HGET',key,'version') or '0')
                  table.insert(result,redis.call('HGET',key,'generation') or '0')
                  table.insert(result,redis.call('HGET',key,'summaryVersion') or '0')
                  table.insert(result,accessed); table.insert(result,tostring(expires))
                end
                return result
                """;
        List<?> values = executeList(script, List.of(userIndexKey(ownerUserId)), ownerUserId.toString(),
                Integer.toString(offset), Integer.toString(limit), Long.toString(now.toEpochMilli()),
                PREFIX + "conversation:");
        List<LifecycleSnapshot> items = new ArrayList<>();
        for (int index = 1; index + LIFECYCLE_FIELD_COUNT - 1 < values.size(); index += LIFECYCLE_FIELD_COUNT) {
            items.add(lifecycle(values, index, List.of()));
        }
        return new LifecyclePage(items, Long.parseLong(string(values, 0)));
    }

    /** {@inheritDoc} */
    @Override
    public LifecycleSnapshot lifecycleDetails(UUID requesterUserId, boolean administrator,
                                              UUID conversationId, int recentTurnLimit, Instant now) {
        String script = """
                if redis.call('EXISTS',KEYS[1])==0 then return {'NOT_FOUND'} end
                local owner=redis.call('HGET',KEYS[1],'ownerUserId')
                if not owner or (owner~=ARGV[1] and ARGV[2]~='1') then return {'NOT_FOUND'} end
                local lease=tonumber(redis.call('HGET',KEYS[1],'runLeaseUntil') or '0')
                local active=redis.call('HGET',KEYS[1],'activeRunId'); local status='IDLE'
                if active and lease>tonumber(ARGV[3]) then status='RUNNING' end
                local accessed=redis.call('HGET',KEYS[1],'lastAccessAt') or redis.call('HGET',KEYS[1],'createdAt') or ARGV[3]
                local ttl=redis.call('PTTL',KEYS[1]); local expires=tonumber(ARGV[3]); if ttl>0 then expires=expires+ttl end
                local result={'OK',owner,status,redis.call('HGET',KEYS[1],'version') or '0',
                  redis.call('HGET',KEYS[1],'generation') or '0',redis.call('HGET',KEYS[1],'summaryVersion') or '0',
                  accessed,tostring(expires)}
                local turns=redis.call('LRANGE',KEYS[2],-tonumber(ARGV[4]),-1)
                for _,turn in ipairs(turns) do table.insert(result,turn) end
                return result
                """;
        List<?> values = executeList(script, List.of(conversationKey(conversationId), turnsKey(conversationId)),
                requesterUserId.toString(), administrator ? "1" : "0", Long.toString(now.toEpochMilli()),
                Integer.toString(recentTurnLimit));
        if ("NOT_FOUND".equals(string(values, 0))) throw notFound();
        List<CompletedTurn> turns = new ArrayList<>();
        for (int index = 8; index < values.size(); index++) turns.add(read(string(values, index), CompletedTurn.class));
        return lifecycle(conversationId, values, 1, turns);
    }

    /** {@inheritDoc} */
    @Override
    public LifecycleSnapshot reset(UUID ownerUserId, UUID conversationId, long expectedVersion, Instant now) {
        String script = """
                if redis.call('EXISTS',KEYS[1])==0 or redis.call('HGET',KEYS[1],'ownerUserId')~=ARGV[1] then return {'NOT_FOUND'} end
                if (redis.call('HGET',KEYS[1],'version') or '0')~=ARGV[2] then return {'VERSION'} end
                local lease=tonumber(redis.call('HGET',KEYS[1],'runLeaseUntil') or '0')
                if redis.call('HGET',KEYS[1],'activeRunId') and lease>tonumber(ARGV[3]) then return {'BUSY'} end
                local members=redis.call('SMEMBERS',KEYS[4]); for _,key in ipairs(members) do redis.call('DEL',key) end
                local activeMessage=redis.call('HGET',KEYS[1],'activeMessageKey'); if activeMessage then redis.call('DEL',activeMessage) end
                redis.call('DEL',KEYS[3],KEYS[4])
                local generation=tonumber(redis.call('HGET',KEYS[1],'generation') or '0')+1
                redis.call('HSET',KEYS[1],'version','0','generation',tostring(generation),'summaryVersion','0',
                  'lastSequence','0','status','IDLE','lastAccessAt',ARGV[3])
                redis.call('HDEL',KEYS[1],'summary','summaryCoveredThroughVersion','agentState','activeRunId','runLeaseUntil','activeMessageKey')
                redis.call('ZADD',KEYS[2],ARGV[3],ARGV[5]); redis.call('EXPIRE',KEYS[1],ARGV[4]); redis.call('EXPIRE',KEYS[2],ARGV[4])
                local expires=tonumber(ARGV[3])+tonumber(ARGV[4])*1000
                return {'OK',ARGV[1],'IDLE','0',tostring(generation),'0',ARGV[3],tostring(expires)}
                """;
        List<?> values = executeList(script, List.of(conversationKey(conversationId), userIndexKey(ownerUserId),
                        turnsKey(conversationId), auxiliaryKey(conversationId)), ownerUserId.toString(),
                Long.toString(expectedVersion), Long.toString(now.toEpochMilli()),
                Long.toString(conversationTtl.toSeconds()), conversationId.toString());
        lifecycleWriteResult(values);
        return lifecycle(conversationId, values, 1, List.of());
    }

    /** {@inheritDoc} */
    @Override
    public void delete(UUID ownerUserId, UUID conversationId, long expectedVersion, Instant now) {
        String script = """
                if redis.call('EXISTS',KEYS[1])==0 or redis.call('HGET',KEYS[1],'ownerUserId')~=ARGV[1] then return 'NOT_FOUND' end
                if (redis.call('HGET',KEYS[1],'version') or '0')~=ARGV[2] then return 'VERSION' end
                local lease=tonumber(redis.call('HGET',KEYS[1],'runLeaseUntil') or '0')
                if redis.call('HGET',KEYS[1],'activeRunId') and lease>tonumber(ARGV[3]) then return 'BUSY' end
                local members=redis.call('SMEMBERS',KEYS[4]); for _,key in ipairs(members) do redis.call('DEL',key) end
                local activeMessage=redis.call('HGET',KEYS[1],'activeMessageKey'); if activeMessage then redis.call('DEL',activeMessage) end
                redis.call('DEL',KEYS[1],KEYS[3],KEYS[4]); redis.call('ZREM',KEYS[2],ARGV[4])
                if redis.call('ZCARD',KEYS[2])==0 then redis.call('DEL',KEYS[2]) end
                return 'OK'
                """;
        String result = redis.execute(new DefaultRedisScript<>(script, String.class),
                List.of(conversationKey(conversationId), userIndexKey(ownerUserId), turnsKey(conversationId),
                        auxiliaryKey(conversationId)), ownerUserId.toString(), Long.toString(expectedVersion),
                Long.toString(now.toEpochMilli()), conversationId.toString());
        if ("NOT_FOUND".equals(result)) throw notFound();
        if ("VERSION".equals(result)) throw versionConflict();
        if ("BUSY".equals(result)) throw busy();
        if (!"OK".equals(result)) throw error(ErrorCode.DEPENDENCY_UNAVAILABLE, "Redis 会话删除失败");
    }

    /** 把重置脚本的稳定状态转换为异常。 */
    private void lifecycleWriteResult(List<?> values) {
        String status = string(values, 0);
        if ("NOT_FOUND".equals(status)) throw notFound();
        if ("VERSION".equals(status)) throw versionConflict();
        if ("BUSY".equals(status)) throw busy();
    }

    /** 从分页脚本的扁平字段构造生命周期快照。 */
    private LifecycleSnapshot lifecycle(List<?> values, int start, List<CompletedTurn> turns) {
        UUID conversationId = UUID.fromString(string(values, start));
        return lifecycle(conversationId, values, start + 1, turns);
    }

    /** 从不包含会话 ID 的连续字段构造生命周期快照。 */
    private LifecycleSnapshot lifecycle(UUID conversationId, List<?> values, int start, List<CompletedTurn> turns) {
        return new LifecycleSnapshot(conversationId, UUID.fromString(string(values, start)),
                ConversationLifecycleStatus.valueOf(string(values, start + 1)),
                Long.parseLong(string(values, start + 2)), Long.parseLong(string(values, start + 3)),
                Long.parseLong(string(values, start + 4)), instant(string(values, start + 5)),
                instant(string(values, start + 6)), turns);
    }

    /** 把 Redis 毫秒时间戳转换为 UTC 时间。 */
    private Instant instant(String value) {
        return Instant.ofEpochMilli(Long.parseLong(value));
    }

    /** 执行返回多值数组的 Lua 脚本。 */
    private List<?> executeList(String source, List<String> keys, String... arguments) {
        List<?> result = redis.execute(new DefaultRedisScript<>(source, List.class), keys, (Object[]) arguments);
        if (result == null || result.isEmpty()) throw error(ErrorCode.DEPENDENCY_UNAVAILABLE, "Redis 会话操作失败");
        return result;
    }

    /** 把脚本返回值稳定转换为字符串。 */
    private String string(List<?> values, int index) {
        return index >= values.size() || values.get(index) == null ? "" : values.get(index).toString();
    }

    /** 序列化 Redis JSON 值。 */
    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (RuntimeException exception) {
            throw error(ErrorCode.COMMON_INTERNAL_ERROR, "会话序列化失败");
        }
    }

    /** 反序列化 Redis JSON 值。 */
    private <T> T read(String value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (RuntimeException exception) {
            throw error(ErrorCode.COMMON_INTERNAL_ERROR, "会话数据无法读取");
        }
    }

    /** 使用 SHA-256 判断相同消息编号是否承载完全相同的规范化输入。 */
    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 不支持 SHA-256", exception);
        }
    }

    /** 校验 Redis 生命周期均为正值且租约短于对应数据保留时间。 */
    private void validateDurations(Duration conversation, Duration suggestion, Duration run, Duration claim) {
        if (!positive(conversation) || !positive(suggestion) || !positive(run) || !positive(claim)
                || run.compareTo(conversation) >= 0 || claim.compareTo(suggestion) >= 0) {
            throw new IllegalArgumentException("Redis 会话或租约时长配置不合法");
        }
    }

    /** 判断时长存在且严格大于零。 */
    private boolean positive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }

    /** 把可空字符串转换为空字符串，避免 Redis 参数拒绝空引用。 */
    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 创建应用异常。 */
    private ApplicationException error(ErrorCode code, String message) {
        return new ApplicationException(code, message);
    }

    /** 创建不泄露资源是否属于其他用户的会话不存在异常。 */
    private ApplicationException notFound() {
        return error(ErrorCode.CHAT_CONVERSATION_EXPIRED, "会话不存在或已经过期");
    }

    /** 创建稳定的会话版本冲突异常。 */
    private ApplicationException versionConflict() {
        return error(ErrorCode.CHAT_VERSION_CONFLICT, "会话版本已变化，请使用最新版本重试");
    }

    /** 创建稳定的活动运行冲突异常。 */
    private ApplicationException busy() {
        return error(ErrorCode.CHAT_CONVERSATION_BUSY, "该会话正在处理另一条消息");
    }

    /** 返回会话 Hash 键。 */
    private String conversationKey(UUID id) {
        return PREFIX + "conversation:{" + id + "}";
    }

    /** 返回用户会话最近访问有序索引键。 */
    private String userIndexKey(UUID ownerUserId) {
        return PREFIX + "user-conversations:{" + ownerUserId + "}";
    }

    /** 返回当前会话全部动态消息和建议键的索引。 */
    private String auxiliaryKey(UUID id) {
        return PREFIX + "members:{" + id + "}";
    }

    /** 返回按代次隔离的客户端消息键前缀。 */
    private String messageKeyPrefix(UUID id) {
        return PREFIX + "message:{" + id + "}:";
    }

    /** 返回最近轮次 List 键。 */
    private String turnsKey(UUID id) {
        return PREFIX + "turns:{" + id + "}";
    }

    /** 返回按代次隔离的工单建议键前缀。 */
    private String suggestionKeyPrefix(UUID id) {
        return PREFIX + "suggestion:{" + id + "}:";
    }
}
