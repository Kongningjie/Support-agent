<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { executeJudge, listJudgeRuns, getJudgeReports } from '@/api/judge.api'
import type { JudgeRun, JudgeReport } from '@/api/judge.api'
import { ApiError } from '@/types/api.types'

const props = defineProps<{ sampleId: string }>()
const runs = ref<JudgeRun[]>([])
const reports = ref<JudgeReport[]>([])
const busy = ref(false)
const reportsLoaded = ref(false)
const pendingKeys = new Map<string, string>()
const labels = ['事实正确', '引用支持', '完整性', '可操作性', '表达清晰度']
watch(
  () => props.sampleId,
  () => {
    runs.value = []
    void load()
  },
  { immediate: true },
)

/** 读取服务端结果，忽略切换样本后到达的旧响应。 */
async function load(): Promise<void> {
  const id = props.sampleId
  try {
    const rows = await listJudgeRuns(id)
    if (id === props.sampleId) runs.value = rows
  } catch (error) {
    showError(error)
  }
}
/** 确认三次调用；结果不确定时复用同一幂等Key，不重复消耗额度。 */
async function run(): Promise<void> {
  if (busy.value) return
  busy.value = true
  const id = props.sampleId
  try {
    await ElMessageBox.confirm(
      '将对这份脱敏样本串行评审3次，使用服务端已配置的模型额度。确认执行？',
      '模型评审',
      { confirmButtonText: '确认执行', cancelButtonText: '取消' },
    )
  } catch {
    busy.value = false
    return
  }
  const key = pendingKeys.get(id) || crypto.randomUUID()
  pendingKeys.set(id, key)
  try {
    const result = await executeJudge(id, key)
    if (result.status !== 'RUNNING') pendingKeys.delete(id)
    if (id === props.sampleId) await load()
  } catch (error) {
    showError(error)
  } finally {
    busy.value = false
  }
}
/** 人工已决样本才能进入校准统计，样本不足时不宣称质量通过。 */
async function report(): Promise<void> {
  try {
    reports.value = await getJudgeReports()
    reportsLoaded.value = true
  } catch (error) {
    showError(error)
  }
}
/** 仅展示安全公开错误，不显示原始HTTP对象。 */
function showError(error: unknown): void {
  ElMessage.error(error instanceof ApiError ? error.message : '模型评测请求失败')
}
/** 将一致率显示为百分比；没有有效样本时展示未知。 */
function percentage(value: number | null): string {
  return value === null ? '未验证' : `${(value * 100).toFixed(1)}%`
}

/** 用中文展示稳定执行状态，避免管理员猜测状态枚举。 */
function statusLabel(value: string): string {
  return { RUNNING: '执行中', COMPLETED: '已完成', FAILED: '已失败' }[value] || value
}
</script>

<template>
  <section aria-label="模型辅助评审">
    <h3>模型辅助评审</h3>
    <p>每次评审重复3次。人工结论形成前隐藏模型评分，避免影响独立评分。</p>
    <el-button :loading="busy" @click="run">执行模型评审 / 重试原请求</el-button>
    <el-button :disabled="busy" @click="load">查询运行结果</el-button>
    <el-button @click="report">查看人工一致性报告</el-button>
    <p v-if="runs.length === 0">暂无模型评审运行。</p>
    <article v-for="item in runs" :key="item.runId">
      <p>{{ item.model }} · {{ item.promptVersion }} · {{ statusLabel(item.status) }}</p>
      <p>单次输入预算估算：{{ item.estimatedInputTokens }} Token</p>
      <p v-if="item.hiddenUntilHumanDecision">人工评审未完成，模型结果暂时隐藏。</p>
      <div v-for="attempt in item.attempts" :key="attempt.ordinal">
        <p>
          第{{ attempt.ordinal }}次：{{ attempt.failure || '有效评分' }}；{{
            attempt.elapsedMillis
          }}毫秒；实际输入/输出Token：{{ attempt.inputTokens ?? '未知' }} /
          {{ attempt.outputTokens ?? '未知' }}
        </p>
        <p v-for="(dimension, index) in attempt.dimensions || []" :key="index">
          {{ labels[index] }}：{{ dimension.score }}分。{{ dimension.reason }}
          <span v-if="dimension.evidenceStart !== null"
            >证据区间 [{{ dimension.evidenceStart }}, {{ dimension.evidenceEnd }})</span
          >
        </p>
      </div>
      <p v-if="!item.hiddenUntilHumanDecision">
        三次评分一致：{{
          item.repeatStable === null ? '有效复测不足' : item.repeatStable ? '是' : '否'
        }}
      </p>
      <p v-for="(error, index) in item.meanAbsoluteErrors" :key="index">
        {{ labels[index] }}：平均绝对误差{{ error.toFixed(3) }}，人工精确一致率{{
          percentage(item.exactAgreementRates[index] ?? null)
        }}
      </p>
    </article>
    <p v-if="reportsLoaded && reports.length === 0">暂无已决样本的校准报告。</p>
    <article v-for="item in reports" :key="item.model + item.promptVersion">
      <h4>{{ item.model }} · {{ item.promptVersion }}</h4>
      <p>
        已决样本{{ item.sampleCount }}份；三次均有效{{ item.fullyValidSampleCount }}份；复测一致率{{
          percentage(item.repeatStabilityRate)
        }}
      </p>
      <p>
        {{
          item.qualityThresholdPassed
            ? '辅助评审量化门槛通过'
            : '尚未达到辅助评审量化门槛（至少30份完整有效样本）'
        }}
      </p>
      <p v-if="item.truncated">报告输入窗口已截断，需要缩小评测范围后重新校准。</p>
      <p v-if="item.sourceModelBiasUnverified">来源回答模型未知，同源模型偏差尚未验证。</p>
      <details>
        <summary>样本高估、低估和失败对照（正数高估、负数低估）</summary>
        <p v-for="sample in item.samples.slice(0, 20)" :key="sample.runId">
          样本 {{ sample.sampleId }}；五维平均分差
          {{ sample.signedMeanErrors.join(' / ') || '无有效评分' }}； 失败
          {{ sample.failureCount }} 次；总耗时 {{ sample.elapsedMillis }} 毫秒； 实际输入/输出Token
          {{ sample.inputTokens ?? '未知' }} / {{ sample.outputTokens ?? '未知' }}
        </p>
        <p v-if="item.samples.length > 20">页面显示前20份，完整数值对照见报告接口。</p>
      </details>
      <p v-for="(error, index) in item.meanAbsoluteErrors" :key="index">
        {{ labels[index] }}：MAE {{ error.toFixed(3) }}，一致率{{
          percentage(item.exactAgreementRates[index] ?? null)
        }}
      </p>
    </article>
  </section>
</template>
