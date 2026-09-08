package com.lawrence.supportagent.retrieval;

import com.lawrence.supportagent.knowledge.ExactTerm;
import java.util.List;
import java.util.Set;

/**
 * 表示经检索和来源回查后可交给回答模型的单个证据分块。
 *
 * @param chunkId 稳定分块 ID
 * @param sourceType 来源类型
 * @param sourceId 来源内部 ID，对外序列化为字符串
 * @param sourceVersion 来源发布版本
 * @param title 来源标题
 * @param headingPath 标题路径
 * @param content 分块正文
 * @param exactTerms 分块精确技术词
 * @param matchedQueries BM25 命名查询命中标记
 * @param bm25Rank BM25 排名，可为空
 * @param vectorRank 向量排名，可为空
 * @param rrfScore RRF 分数
 * @param rerankScore 重排分数，可为空
 */
public record RetrievalEvidence(String chunkId, String sourceType, long sourceId,
                                long sourceVersion, String title, String headingPath,
                                String content, List<ExactTerm> exactTerms,
                                Set<String> matchedQueries, Integer bm25Rank,
                                Integer vectorRank, double rrfScore, Double rerankScore) { }
