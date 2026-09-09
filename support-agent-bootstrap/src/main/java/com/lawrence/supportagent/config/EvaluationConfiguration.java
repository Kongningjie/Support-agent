package com.lawrence.supportagent.config;

import com.lawrence.supportagent.evaluation.ClasspathRetrievalEvaluationDatasetAdapter;
import com.lawrence.supportagent.evaluation.RetrievalEvaluationDatasetPort;
import com.lawrence.supportagent.evaluation.RetrievalEvaluationReportPort;
import com.lawrence.supportagent.evaluation.RetrievalEvaluationService;
import com.lawrence.supportagent.evaluation.EvaluationRuntimeMetadataPort;
import com.lawrence.supportagent.evaluation.RetrievalEvaluationContext;
import com.lawrence.supportagent.evaluation.WorkingTreeGitCommitResolver;
import com.lawrence.supportagent.evaluation.RetrievalMetricsCalculator;
import com.lawrence.supportagent.evaluation.TargetRetrievalEvaluationReportAdapter;
import com.lawrence.supportagent.retrieval.RetrievalService;
import com.lawrence.supportagent.sharedkernel.port.TimeProvider;
import com.lawrence.supportagent.sharedkernel.port.UuidGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import java.util.Map;
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
            UuidGenerator ids, TimeProvider time, EvaluationRuntimeMetadataPort metadata) {
        return new RetrievalEvaluationService(dataset, reports, retrieval, metrics, ids, time, metadata);
    }

    /** 创建包含提交、模型和冻结检索参数的报告元数据端口。 */
    @Bean
    public EvaluationRuntimeMetadataPort evaluationRuntimeMetadataPort(
            SupportAgentProperties properties,
            @Value("${support-agent.retrieval.vector-minimum-similarity:0.20}") double similarity,
            @Value("${support-agent.retrieval.vector-candidates:200}") int vectorCandidates,
            @Value("${support-agent.retrieval.rerank-grounded-threshold:0.35}") double threshold) {
        String commit = new WorkingTreeGitCommitResolver().resolve();
        return dataset -> new RetrievalEvaluationContext("1.0", dataset.kind(), dataset.version(),
                dataset.contentSha256(), commit, Map.of(
                "chat", properties.dashscope().chatModel(),
                "embedding", properties.dashscope().embeddingModel(),
                "rerank", properties.dashscope().rerankModel()), Map.of(
                "vectorMinimumSimilarity", Double.toString(similarity),
                "vectorCandidates", Integer.toString(vectorCandidates),
                "rerankGroundedThreshold", Double.toString(threshold),
                "bm25TopK", "50", "vectorTopK", "50", "rrfK", "60",
                "fusionTopK", "30", "finalTopK", "5"));
    }
}
