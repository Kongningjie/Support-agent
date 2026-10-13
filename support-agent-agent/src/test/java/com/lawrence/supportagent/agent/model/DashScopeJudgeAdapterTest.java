package com.lawrence.supportagent.agent.model;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
/** 离线验证评审JSON契约严格解析，无真实网络调用。 */
class DashScopeJudgeAdapterTest {
    private final DashScopeJudgeAdapter adapter=new DashScopeJudgeAdapter("test-key","judge-test","https://example.invalid/api/v1");
    /** 非JSON正文、额外字段及重复维度必须拒绝。 */
    @Test void shouldRejectMalformedAndDuplicateDimensions() {
        assertThatThrownBy(()->adapter.parse("不是JSON")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->adapter.parse("{\"factual\":{},\"factual\":{}}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->adapter.parse("{}")).isInstanceOf(IllegalArgumentException.class);
    }
    /** 五维完整整数结构才能进入应用层进一步校验。 */
    @Test void shouldParseStrictFiveDimensions() {
        String dimension="{\"score\":2,\"reason\":\"理由\",\"evidenceStart\":null,\"evidenceEnd\":null}";
        String value="{\"factual\":"+dimension+",\"citation\":"+dimension+",\"completeness\":"+dimension
                +",\"actionability\":"+dimension+",\"clarity\":"+dimension+"}";
        assertThat(adapter.parse(value)).hasSize(5);
        assertThatThrownBy(()->adapter.parse(value.replace("\"score\":2","\"score\":1.5"))).isInstanceOf(IllegalArgumentException.class);
    }
}
