package com.lawrence.supportagent.config;

import com.lawrence.supportagent.evaluation.ClasspathRetrievalEvaluationDatasetAdapter;
import com.lawrence.supportagent.evaluation.RetrievalEvaluationDatasetPort;
import com.lawrence.supportagent.evaluation.RetrievalEvaluationReportPort;
import com.lawrence.supportagent.evaluation.RetrievalEvaluationService;
import com.lawrence.supportagent.evaluation.RetrievalMetricsCalculator;
import com.lawrence.supportagent.evaluation.TargetRetrievalEvaluationReportAdapter;
import com.lawrence.supportagent.retrieval.RetrievalService;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import com.lawrence.supportagent.sharedkernel.port.UuidGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import tools.jackson.databind.ObjectMapper;

/** 仅在开发和测试环境装配固定检索评测能力。 */
@Configuration
@Profile({"dev", "test"})
public class EvaluationConfiguration {
    /** 创建严格读取仓库固定 JSONL 的数据集端口。 */
    @Bean public RetrievalEvaluationDatasetPort retrievalEvaluationDatasetPort(ObjectMapper mapper) {
        return new ClasspathRetrievalEvaluationDatasetAdapter(mapper);
    }

    /** 创建只向 target 目录写入 JSON 的评测报告端口。 */
    @Bean public RetrievalEvaluationReportPort retrievalEvaluationReportPort(ObjectMapper mapper) {
        return new TargetRetrievalEvaluationReportAdapter(mapper);
    }

    /** 创建二元相关性的五项检索指标计算器。 */
    @Bean public RetrievalMetricsCalculator retrievalMetricsCalculator() {
        return new RetrievalMetricsCalculator();
    }

    /** 创建进程内异步评测服务。 */
    @Bean(destroyMethod = "close")
    public RetrievalEvaluationService retrievalEvaluationService(
            RetrievalEvaluationDatasetPort dataset, RetrievalEvaluationReportPort reports,
            RetrievalService retrieval, RetrievalMetricsCalculator metrics,
            UuidGenerator ids, TimeProvider time) {
        return new RetrievalEvaluationService(dataset, reports, retrieval, metrics, ids, time);
    }
}
