package com.lawrence.supportagent.chat;

import com.lawrence.supportagent.chat.port.ConversationStorePort;
import com.lawrence.supportagent.chat.ConversationSummary;
import com.lawrence.supportagent.sharedkernel.error.ApplicationException;
import com.lawrence.supportagent.sharedkernel.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import tools.jackson.databind.ObjectMapper;

/** 使用 Redis Lua 原子维护会话运行围栏、版本、幂等结果和工单建议。 */
public class RedisConversationStoreAdapter implements ConversationStorePort {
    private static final Duration DEFAULT_CONVERSATION_TTL = Duration.ofDays(7);
    private static final Duration DEFAULT_SUGGESTION_TTL = Duration.ofHours(24);
    private static final Duration DEFAULT_RUN_LEASE = Duration.ofMinutes(3);
    private static final Duration DEFAULT_SUGGESTION_LEASE = Duration.ofMinutes(3);
    private static final String PREFIX = "support-agent:chat:";
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

    /**
     * {@inheritDoc}
     * 在调用意图模型、检索和 Chat 模型前，原子取得指定会话的执行权。
     */
    @Override
    public BeginResult begin(UUID ownerUserId, UUID conversationId, UUID clientMessageId, String message,
                             Long expectedVersion, UUID runId, Instant now) {
        if (ownerUserId == null) throw new IllegalArgumentException("会话所有者不能为空");
        UUID actualConversationId = conversationId == null ? UUID.randomUUID() : conversationId;
        String hash = sha256(message.trim());
        String script = """
                if redis.call('EXISTS',KEYS[1])==1 and redis.call('HGET',KEYS[1],'ownerUserId')~=ARGV[1] then return {'OWNER'} end
                local oldHash=redis.call('HGET',KEYS[2],'requestHash')
                if oldHash and oldHash~=ARGV[3] then return {'MESSAGE_REUSED'} end
                if redis.call('HGET',KEYS[2],'status')=='COMPLETED' then
                  if redis.call('HGET',KEYS[1],'ownerUserId')~=ARGV[1] then return {'OWNER'} end
                  return {'REPLAY',redis.call('HGET',KEYS[1],'version') or '0',redis.call('HGET',KEYS[2],'result')}
                end
                local exists=redis.call('EXISTS',KEYS[1])
                if exists==0 then
                  if ARGV[4]~='-' then return {'EXPIRED'} end
                  redis.call('HSET',KEYS[1],'version','0','lastSequence','0','ownerUserId',ARGV[1],'createdAt',ARGV[5])
                else
                  if redis.call('HGET',KEYS[1],'ownerUserId')~=ARGV[1] then return {'OWNER'} end
                  local version=redis.call('HGET',KEYS[1],'version') or '0'
                  if ARGV[4]=='-' or version~=ARGV[4] then return {'VERSION',version} end
                end
                local owner=redis.call('HGET',KEYS[1],'activeRunId')
                local lease=tonumber(redis.call('HGET',KEYS[1],'runLeaseUntil') or '0')
                if owner and lease>tonumber(ARGV[5]) then return {'BUSY'} end
                local interrupted=''
                if owner then interrupted=owner end
                redis.call('HSET',KEYS[1],'status','RUNNING','activeRunId',ARGV[2],
                  'runLeaseUntil',ARGV[6],'lastAccessAt',ARGV[5],'activeMessageKey',KEYS[2])
                redis.call('HSET',KEYS[2],'requestHash',ARGV[3],'status','PROCESSING','runId',ARGV[2])
                redis.call('EXPIRE',KEYS[1],ARGV[7]); redis.call('EXPIRE',KEYS[2],ARGV[7])
                return {'ACQUIRED',redis.call('HGET',KEYS[1],'version'),interrupted}
                """;
        /*
         * ARGV[1] = 当前公开用户 UUID
         * ARGV[2] = 当前 runId
         * ARGV[3] = 当前消息的 SHA-256
         * ARGV[4] = 客户端传入的会话版本
         * ARGV[5] = 当前时间
         * ARGV[6] = 租约过期时间
         * ARGV[7] = Redis TTL
         */
        List<?> result = executeList(script, List.of(conversationKey(actualConversationId),
                        messageKey(actualConversationId, clientMessageId)), ownerUserId.toString(), runId.toString(), hash,
                expectedVersion == null ? "-" : expectedVersion.toString(), Long.toString(now.toEpochMilli()),
                Long.toString(now.plus(runLease).toEpochMilli()),
                Long.toString(conversationTtl.toSeconds()));
        String status = string(result, 0);
        if ("MESSAGE_REUSED".equals(status)) throw error(ErrorCode.CHAT_MESSAGE_ID_REUSED, "clientMessageId 已用于其他消息");
        if ("OWNER".equals(status)) throw error(ErrorCode.CHAT_CONVERSATION_EXPIRED, "会话不存在或已经过期");
        if ("EXPIRED".equals(status)) throw error(ErrorCode.CHAT_CONVERSATION_EXPIRED, "会话不存在或已经过期");
        if ("VERSION".equals(status)) throw error(ErrorCode.CHAT_VERSION_CONFLICT, "会话版本已变化，请使用最新版本重试");
        if ("BUSY".equals(status)) throw error(ErrorCode.CHAT_CONVERSATION_BUSY, "该会话正在处理另一条消息");
        if ("REPLAY".equals(status)) {
            return new BeginResult(BeginStatus.REPLAY, actualConversationId,
                    Long.parseLong(string(result, 1)), read(string(result, 2), CompletedTurn.class), null);
        }
        UUID interrupted = string(result, 2).isBlank() ? null : UUID.fromString(string(result, 2));
        return new BeginResult(BeginStatus.ACQUIRED, actualConversationId,
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
        if (result == null || result < 0) throw error(ErrorCode.CHAT_CONVERSATION_BUSY, "会话运行权已经失效");
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
        if (result < 0) throw error(ErrorCode.CHAT_CONVERSATION_EXPIRED, "会话不存在或已经过期");
        return result;
    }

    /** {@inheritDoc} */
    @Override
    public CompletedTurn complete(UUID ownerUserId, UUID conversationId, UUID runId, CompletedTurn turn,
                                  String serializedAgentState, Instant now) {
        String suggestionKey = turn.suggestionId() == null ? suggestionKey(conversationId, UUID.randomUUID())
                : suggestionKey(conversationId, turn.suggestionId());
        String script = """
                if redis.call('HGET',KEYS[1],'ownerUserId')~=ARGV[1] or redis.call('HGET',KEYS[1],'activeRunId')~=ARGV[2] then return 0 end
                redis.call('RPUSH',KEYS[3],ARGV[3])
                redis.call('HSET',KEYS[1],'version',ARGV[4],'status','IDLE','lastAccessAt',ARGV[5])
                redis.call('HDEL',KEYS[1],'activeRunId','runLeaseUntil','activeMessageKey')
                if ARGV[6]~='' then redis.call('HSET',KEYS[1],'agentState',ARGV[6]) end
                redis.call('HSET',KEYS[2],'status','COMPLETED','result',ARGV[3])
                if ARGV[7]=='1' then
                  redis.call('HSET',KEYS[4],'status','AVAILABLE','context',ARGV[8],
                    'sourceTurnId',ARGV[9]); redis.call('EXPIRE',KEYS[4],ARGV[11])
                end
                redis.call('EXPIRE',KEYS[1],ARGV[10]); redis.call('EXPIRE',KEYS[2],ARGV[10]);
                redis.call('EXPIRE',KEYS[3],ARGV[10]); return 1
                """;
        Long result = redis.execute(new DefaultRedisScript<>(script, Long.class),
                List.of(conversationKey(conversationId), messageKey(conversationId, turn.clientMessageId()),
                        turnsKey(conversationId), suggestionKey), ownerUserId.toString(), runId.toString(), write(turn),
                Long.toString(turn.conversationVersion()), Long.toString(now.toEpochMilli()),
                serializedAgentState == null ? "" : serializedAgentState,
                turn.suggestionId() == null ? "0" : "1", turn.suggestionContext(), turn.turnId().toString(),
                Long.toString(conversationTtl.toSeconds()), Long.toString(suggestionTtl.toSeconds()));
        if (!Long.valueOf(1).equals(result)) throw error(ErrorCode.CHAT_CONVERSATION_BUSY, "会话运行权已经失效");
        return turn;
    }

    /** {@inheritDoc} */
    @Override
    public void fail(UUID ownerUserId, UUID conversationId, UUID runId, Instant now) {
        String script = "if redis.call('HGET',KEYS[1],'ownerUserId')==ARGV[1] and redis.call('HGET',KEYS[1],'activeRunId')==ARGV[2] then "
                + "local messageKey=redis.call('HGET',KEYS[1],'activeMessageKey'); "
                + "redis.call('HSET',KEYS[1],'status','IDLE','lastAccessAt',ARGV[3]); "
                + "redis.call('HDEL',KEYS[1],'activeRunId','runLeaseUntil','activeMessageKey'); "
                + "if messageKey then redis.call('HSET',messageKey,'status','FAILED') end; return 1 end return 0";
        redis.execute(new DefaultRedisScript<>(script, Long.class),
                List.of(conversationKey(conversationId)),
                ownerUserId.toString(), runId.toString(), Long.toString(now.toEpochMilli()));
    }

    /** {@inheritDoc} */
    @Override
    public SuggestionClaim claimSuggestion(UUID ownerUserId, UUID conversationId,
                                           UUID suggestionId, Instant now) {
        UUID claimId = UUID.randomUUID();
        String script = """
                if redis.call('HGET',KEYS[2],'ownerUserId')~=ARGV[1] or redis.call('EXISTS',KEYS[1])==0 then return {'NOT_FOUND'} end
                local status=redis.call('HGET',KEYS[1],'status')
                if status=='CONSUMED' then return {'CONSUMED','','',redis.call('HGET',KEYS[1],'sourceTurnId'),redis.call('HGET',KEYS[1],'ticketNo')} end
                local lease=tonumber(redis.call('HGET',KEYS[1],'leaseUntil') or '0')
                if status=='PROCESSING' and lease>tonumber(ARGV[2]) then return {'PROCESSING'} end
                redis.call('HSET',KEYS[1],'status','PROCESSING','claimId',ARGV[3],'leaseUntil',ARGV[4])
                return {'CLAIMED',ARGV[3],redis.call('HGET',KEYS[1],'context'),redis.call('HGET',KEYS[1],'sourceTurnId'),''}
                """;
        List<?> result = executeList(script, List.of(suggestionKey(conversationId, suggestionId), conversationKey(conversationId)),
                ownerUserId.toString(), Long.toString(now.toEpochMilli()), claimId.toString(),
                Long.toString(now.plus(suggestionLease).toEpochMilli()));
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
        String script = "if redis.call('HGET',KEYS[2],'ownerUserId')~=ARGV[1] or redis.call('HGET',KEYS[1],'claimId')~=ARGV[2] then return 0 end "
                + "redis.call('HSET',KEYS[1],'status','CONSUMED','ticketNo',ARGV[3],'consumedAt',ARGV[4]); "
                + "redis.call('HDEL',KEYS[1],'claimId','leaseUntil','context'); return 1";
        Long result = redis.execute(new DefaultRedisScript<>(script, Long.class),
                List.of(suggestionKey(conversationId, suggestionId), conversationKey(conversationId)), ownerUserId.toString(), claimId.toString(), ticketNo,
                Long.toString(now.toEpochMilli()));
        if (!Long.valueOf(1).equals(result)) throw error(ErrorCode.TICKET_SUGGESTION_IN_PROGRESS, "工单建议消费租约已经失效");
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
        if ("OWNER".equals(string(raw, 0))) throw error(ErrorCode.CHAT_CONVERSATION_EXPIRED, "会话不存在或已经过期");
        List<String> json = raw.stream().skip(1).map(Object::toString).toList();
        List<String> reversed = new ArrayList<>();
        int characters = 0;
        for (int index = json.size() - 1; index >= 0; index--) {
            CompletedTurn turn = read(json.get(index), CompletedTurn.class);
            String value = "用户：" + turn.userMessage() + "\n助手：" + turn.answer();
            if (characters + value.length() > maximumCharacters) break;
            reversed.add(value);
            characters += value.length();
        }
        java.util.Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    /** {@inheritDoc} */
    @Override
    public MemorySnapshot memorySnapshot(UUID ownerUserId, UUID conversationId) {
        String script = """
                if redis.call('EXISTS',KEYS[1])==0 or redis.call('HGET',KEYS[1],'ownerUserId')~=ARGV[1] then return {'EXPIRED'} end
                local result={redis.call('HGET',KEYS[1],'version') or '0',
                  redis.call('HGET',KEYS[1],'summaryVersion') or '0',
                  redis.call('HGET',KEYS[1],'summary') or ''}
                local turns=redis.call('LRANGE',KEYS[2],0,-1)
                for _,turn in ipairs(turns) do table.insert(result,turn) end
                return result
                """;
        List<?> values = executeList(script,
                List.of(conversationKey(conversationId), turnsKey(conversationId)), ownerUserId.toString());
        if ("EXPIRED".equals(string(values, 0))) {
            throw error(ErrorCode.CHAT_CONVERSATION_EXPIRED, "会话不存在或已经过期");
        }
        List<CompletedTurn> turns = new ArrayList<>();
        for (int index = 3; index < values.size(); index++) {
            turns.add(read(string(values, index), CompletedTurn.class));
        }
        String summaryJson = string(values, 2);
        ConversationSummary summary = summaryJson.isBlank()
                ? null : read(summaryJson, ConversationSummary.class);
        return new MemorySnapshot(conversationId, Long.parseLong(string(values, 0)),
                Long.parseLong(string(values, 1)), summary, turns);
    }

    /** {@inheritDoc} */
    @Override
    public boolean commitSummary(UUID ownerUserId, UUID conversationId, long expectedSummaryVersion,
                                 ConversationSummary summary, int recentFullTurns, Instant now) {
        String script = """
                if redis.call('HGET',KEYS[1],'ownerUserId')~=ARGV[1] then return 0 end
                local current=tonumber(redis.call('HGET',KEYS[1],'summaryVersion') or '0')
                if current~=tonumber(ARGV[2]) then return 0 end
                local conversationVersion=tonumber(redis.call('HGET',KEYS[1],'version') or '-1')
                if conversationVersion<tonumber(ARGV[3]) then return 0 end
                local oldCovered=tonumber(redis.call('HGET',KEYS[1],'summaryCoveredThroughVersion') or '0')
                if oldCovered>=tonumber(ARGV[3]) then return 0 end
                local turns=redis.call('LRANGE',KEYS[2],0,-1)
                local keep={}
                local recent=tonumber(ARGV[5])
                for index,turn in ipairs(turns) do
                  local ok,value=pcall(cjson.decode,turn)
                  if not ok or not value.conversationVersion then return -2 end
                  if value.conversationVersion>tonumber(ARGV[3]) or index>#turns-recent then
                    table.insert(keep,turn)
                  end
                end
                redis.call('HSET',KEYS[1],'summary',ARGV[4],
                  'summaryVersion',tostring(current+1),
                  'summaryCoveredThroughVersion',ARGV[3],
                  'lastAccessAt',ARGV[6])
                redis.call('DEL',KEYS[2])
                if #keep>0 then redis.call('RPUSH',KEYS[2],unpack(keep)) end
                redis.call('EXPIRE',KEYS[1],ARGV[7]); redis.call('EXPIRE',KEYS[2],ARGV[7])
                return 1
                """;
        Long result = redis.execute(new DefaultRedisScript<>(script, Long.class),
                List.of(conversationKey(conversationId), turnsKey(conversationId)),
                ownerUserId.toString(), Long.toString(expectedSummaryVersion),
                Long.toString(summary.coveredThroughVersion()), write(summary),
                Integer.toString(recentFullTurns), Long.toString(now.toEpochMilli()),
                Long.toString(conversationTtl.toSeconds()));
        if (Long.valueOf(-2).equals(result)) {
            throw error(ErrorCode.COMMON_INTERNAL_ERROR, "会话轮次数据无法用于摘要提交");
        }
        return Long.valueOf(1).equals(result);
    }

    /** 执行返回多值数组的 Lua 脚本。 */
    private List<?> executeList(String source, List<String> keys, String... arguments) {
        List<?> result = redis.execute(new DefaultRedisScript<>(source, List.class), keys,
                (Object[]) arguments);
        if (result == null || result.isEmpty()) throw error(ErrorCode.DEPENDENCY_UNAVAILABLE, "Redis 会话操作失败");
        return result;
    }

    /** 把脚本返回值稳定转换为字符串。 */
    private String string(List<?> values, int index) {
        return index >= values.size() || values.get(index) == null ? "" : values.get(index).toString();
    }

    /** 序列化 Redis JSON 值。 */
    private String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (RuntimeException exception) { throw error(ErrorCode.COMMON_INTERNAL_ERROR, "会话序列化失败"); }
    }

    /** 反序列化 Redis JSON 值。 */
    private <T> T read(String value, Class<T> type) {
        try { return mapper.readValue(value, type); }
        catch (RuntimeException exception) { throw error(ErrorCode.COMMON_INTERNAL_ERROR, "会话数据无法读取"); }
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
    private void validateDurations(Duration conversation, Duration suggestion,
                                   Duration run, Duration claim) {
        if (!positive(conversation) || !positive(suggestion) || !positive(run) || !positive(claim)
                || run.compareTo(conversation) >= 0 || claim.compareTo(suggestion) >= 0) {
            throw new IllegalArgumentException("Redis 会话或租约时长配置不合法");
        }
    }

    /** 判断时长存在且严格大于零。 */
    private boolean positive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }

    /** 创建应用异常。 */
    private ApplicationException error(ErrorCode code, String message) { return new ApplicationException(code, message); }
    /** 返回会话 Hash 键。 */
    private String conversationKey(UUID id) { return PREFIX + "conversation:{" + id + "}"; }
    /** 返回客户端消息 Hash 键。 */
    private String messageKey(UUID id, UUID messageId) { return PREFIX + "message:{" + id + "}:" + messageId; }
    /** 返回最近轮次 List 键。 */
    private String turnsKey(UUID id) { return PREFIX + "turns:{" + id + "}"; }
    /** 返回工单建议 Hash 键。 */
    private String suggestionKey(UUID id, UUID suggestionId) { return PREFIX + "suggestion:{" + id + "}:" + suggestionId; }
}
