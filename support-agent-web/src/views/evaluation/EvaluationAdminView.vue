<script setup lang="ts">
import { onUnmounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import * as evaluationApi from '@/api/evaluation.api'
import KnowledgeSpaceSelect from '@/components/knowledge-space/KnowledgeSpaceSelect.vue'
import { ApiError } from '@/types/api.types'
import type { EvaluationDatasetKind, EvaluationRun, RetrievalMode } from '@/types/evaluation.types'

const saving = ref(false)
const endpointUnavailable = ref(false)
const run = ref<EvaluationRun | null>(null)
const form = reactive<{
  spaceId: string | null
  datasetKind: EvaluationDatasetKind
  mode: RetrievalMode
  caseIds: string
}>({
  spaceId: null,
  datasetKind: 'LOCKED_REGRESSION',
  mode: 'HYBRID_RERANK',
  caseIds: '',
})
let pollTimer: ReturnType<typeof setTimeout> | null = null

onUnmounted(stopPolling)

/** 启动一次开发环境内存评测并轮询至最终状态。 */
async function start(): Promise<void> {
  if (!form.spaceId) {
    ElMessage.warning('请选择显式测试空间')
    return
  }
  const caseIds = form.caseIds
    .split(',')
    .map((value) => value.trim())
    .filter(Boolean)
  if (caseIds.length > 150) {
    ElMessage.warning('用例 ID 最多 150 个')
    return
  }
  saving.value = true
  try {
    run.value = await evaluationApi.startEvaluation({
      spaceId: form.spaceId,
      datasetKind: form.datasetKind,
      mode: form.mode,
      caseIds,
    })
    endpointUnavailable.value = false
    schedulePoll()
  } catch (caught) {
    handleError(caught, '启动评测失败')
  } finally {
    saving.value = false
  }
}

/** 手工刷新或轮询当前运行快照。 */
async function refresh(): Promise<void> {
  if (!run.value) return
  try {
    run.value = await evaluationApi.getEvaluation(run.value.evaluationRunId)
    if (run.value.status === 'PENDING' || run.value.status === 'RUNNING') schedulePoll()
  } catch (caught) {
    handleError(caught, '读取评测结果失败')
  }
}

/** 在运行未结束时安排一次有限频率轮询。 */
function schedulePoll(): void {
  stopPolling()
  if (!run.value || !['PENDING', 'RUNNING'].includes(run.value.status)) return
  pollTimer = setTimeout(() => void refresh(), 1_500)
}

/** 清理页面离开或终态后的轮询计时器。 */
function stopPolling(): void {
  if (pollTimer) clearTimeout(pollTimer)
  pollTimer = null
}

/** 显式隐藏 dev/test 中不存在的评测接口，其余错误安全展示。 */
function handleError(value: unknown, fallback: string): void {
  stopPolling()
  const issue =
    value instanceof ApiError ? value : new ApiError(fallback, null, 'CLIENT_ERROR', null, true)
  if (issue.status === 404) {
    endpointUnavailable.value = true
    run.value = null
    return
  }
  ElMessage.error(issue.message)
}

/** 把 0～1 指标转换为便于阅读的百分比。 */
function percentage(value: number): string {
  return `${(value * 100).toFixed(2)}%`
}

/** 使用浏览器本地时区展示 UTC 时间。 */
function localTime(value: string | null): string {
  return value ? new Date(value).toLocaleString('zh-CN') : '—'
}
</script>

<template>
  <section class="content-page">
    <header class="page-heading">
      <div>
        <p class="eyebrow">Dev / Test</p>
        <h1>本地检索评测</h1>
        <p class="muted-copy">
          仅在功能开关和后端 dev/test Profile 同时启用时可用，不会自动修改线上参数。
        </p>
      </div>
    </header>
    <el-alert
      v-if="endpointUnavailable"
      title="当前后端未开放评测接口，本页面已停止请求。"
      type="info"
      :closable="false"
      show-icon
    />
    <template v-else
      ><el-card shadow="never"
        ><form class="admin-form evaluation-form" @submit.prevent="start">
          <KnowledgeSpaceSelect
            v-model="form.spaceId"
            input-id="evaluation-space"
            label="测试活动空间（必选）"
          /><label for="evaluation-dataset">数据集</label
          ><select id="evaluation-dataset" v-model="form.datasetKind">
            <option value="LOCKED_REGRESSION">锁定回归集</option>
            <option value="OPTIMIZATION_DEVELOPMENT">优化开发集</option></select
          ><label for="evaluation-mode">检索模式</label
          ><select id="evaluation-mode" v-model="form.mode">
            <option value="BM25_ONLY">BM25_ONLY</option>
            <option value="VECTOR_ONLY">VECTOR_ONLY</option>
            <option value="HYBRID">HYBRID</option>
            <option value="HYBRID_RERANK">HYBRID_RERANK</option></select
          ><label for="evaluation-cases">用例 ID（可选，逗号分隔）</label
          ><el-input
            id="evaluation-cases"
            v-model="form.caseIds"
            placeholder="为空运行当前数据集全部用例"
          />
          <div class="form-actions">
            <el-button type="primary" native-type="submit" :loading="saving">启动评测</el-button>
          </div>
        </form></el-card
      >
      <el-card v-if="run" class="evaluation-result" shadow="never"
        ><template #header
          ><div class="panel-heading">
            <strong>运行 {{ run.evaluationRunId }}</strong
            ><el-button
              size="small"
              :disabled="['PENDING', 'RUNNING'].includes(run.status)"
              @click="refresh"
              >刷新</el-button
            >
          </div></template
        >
        <dl class="admin-details">
          <div>
            <dt>状态</dt>
            <dd>{{ run.status }}</dd>
          </div>
          <div>
            <dt>进度</dt>
            <dd>{{ run.completedCases }} / {{ run.totalCases }}</dd>
          </div>
          <div>
            <dt>模式</dt>
            <dd>{{ run.mode }}</dd>
          </div>
          <div>
            <dt>开始 / 完成</dt>
            <dd>{{ localTime(run.startedAt) }} / {{ localTime(run.finishedAt) }}</dd>
          </div>
          <div v-if="run.failureMessage">
            <dt>失败摘要</dt>
            <dd>{{ run.failureMessage }}</dd>
          </div>
        </dl>
        <div v-if="run.metrics" class="metric-grid">
          <article>
            <span>Recall@5</span><strong>{{ percentage(run.metrics.recallAt5) }}</strong>
          </article>
          <article>
            <span>MRR@10</span><strong>{{ percentage(run.metrics.mrrAt10) }}</strong>
          </article>
          <article>
            <span>nDCG@5</span><strong>{{ percentage(run.metrics.ndcgAt5) }}</strong>
          </article>
          <article>
            <span>无命中准确率</span><strong>{{ percentage(run.metrics.noHitAccuracy) }}</strong>
          </article>
          <article>
            <span>精确词召回率</span><strong>{{ percentage(run.metrics.exactTermRecall) }}</strong>
          </article>
        </div></el-card
      >
    </template>
  </section>
</template>
