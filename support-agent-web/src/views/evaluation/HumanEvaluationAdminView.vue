<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as api from '@/api/human-evaluation.api'
import { getConversation } from '@/api/conversation.api'
import { ApiError } from '@/types/api.types'
import type { ConversationTurn } from '@/types/chat.types'
import type {
  HumanCreateInput,
  HumanDatasetState,
  HumanDetail,
  HumanFeedbackCandidate,
  HumanFixedCandidate,
  HumanSample,
  HumanScore,
} from '@/types/human-evaluation.types'

const globalSpaceId = '00000000-0000-0000-0000-000000000001'
const candidates = ref<HumanFeedbackCandidate[]>([])
const fixed = ref<HumanFixedCandidate[]>([])
const samples = ref<HumanSample[]>([])
const selected = ref<HumanDetail | null>(null)
const sourcePreview = ref<ConversationTurn | null>(null)
const candidatePage = ref(1)
const samplePage = ref(1)
const busy = ref(false)
const createForm = reactive<HumanCreateInput>({
  sourceType: 'FIXED',
  sourceRef: '',
  spaceId: globalSpaceId,
  question: '',
  answer: '',
  evidence: '',
  sanitizedConfirmed: false,
})
const reviewForm = reactive<HumanScore & { reason: string }>({
  factual: 2,
  citation: 2,
  completeness: 2,
  actionability: 2,
  clarity: 2,
  reason: '',
})

onMounted(() => void refresh())

/** 刷新两种候选来源及当前样本页，不暴露用户反馈说明。 */
async function refresh(): Promise<void> {
  try {
    const [feedback, fixedCases, sampleRows] = await Promise.all([
      api.feedbackCandidates(candidatePage.value),
      api.fixedCandidates(),
      api.listSamples(samplePage.value),
    ])
    candidates.value = feedback
    fixed.value = fixedCases
    samples.value = sampleRows
  } catch (error) {
    showError(error, '读取人工评测数据失败')
  }
}

/** 选择固定用例，只预填已有问题，不生成或伪造回答。 */
function selectFixed(item: HumanFixedCandidate): void {
  createForm.sourceType = 'FIXED'
  createForm.sourceRef = item.caseId
  createForm.spaceId = globalSpaceId
  createForm.question = item.question
  createForm.answer = ''
  createForm.evidence = ''
  createForm.sanitizedConfirmed = false
  sourcePreview.value = null
}

/** 选择反馈候选，仅设置最小来源标识；原始会话需再次显式查看。 */
function selectFeedback(item: HumanFeedbackCandidate): void {
  createForm.sourceType = 'FEEDBACK'
  createForm.sourceRef = item.runId
  createForm.question = ''
  createForm.answer = ''
  createForm.evidence = ''
  createForm.sanitizedConfirmed = false
  sourcePreview.value = null
}

/** 显式读取仍可访问的会话回答，供管理员手工脱敏，不自动写入表单或数据库。 */
async function previewFeedback(item: HumanFeedbackCandidate): Promise<void> {
  try {
    const details = await getConversation(item.conversationId)
    sourcePreview.value = details.recentTurns.find((turn) => turn.runId === item.runId) || null
    if (!sourcePreview.value) ElMessage.warning('原回答已不在最近会话中，不能创建该反馈样本')
    else createForm.spaceId = details.conversation.spaceId
  } catch (error) {
    showError(error, '原会话不可读取，不能创建该反馈样本')
  }
}

/** 确认所有文本已脱敏后，显式保存独立评测副本。 */
async function create(): Promise<void> {
  if (!createForm.sanitizedConfirmed) {
    ElMessage.warning('请先逐项检查并确认脱敏')
    return
  }
  busy.value = true
  try {
    const saved = await api.createSample({ ...createForm })
    ElMessage.success('脱敏评测样本已保存')
    selected.value = await api.getSample(saved.id)
    createForm.sanitizedConfirmed = false
    sourcePreview.value = null
    await refresh()
  } catch (error) {
    showError(error, '创建评测样本失败')
  } finally {
    busy.value = false
  }
}

/** 查询一份样本和按当前评审人权限裁剪的评分。 */
async function selectSample(item: HumanSample): Promise<void> {
  try {
    selected.value = await api.getSample(item.id)
  } catch (error) {
    showError(error, '读取样本失败')
  }
}

/** 提交不可更改的盲评分；由服务端决定本次是首评、次评还是复核。 */
async function submitReview(): Promise<void> {
  if (!selected.value) return
  busy.value = true
  try {
    selected.value = await api.submitReview(selected.value.sample.id, { ...reviewForm })
    ElMessage.success('评分已提交，不能修改')
    reviewForm.reason = ''
    await refresh()
  } catch (error) {
    showError(error, '提交评分失败')
  } finally {
    busy.value = false
  }
}

/** 再次确认开发集纳入或回归集单独审批，避免误操作。 */
async function promote(target: Exclude<HumanDatasetState, 'NONE'>): Promise<void> {
  if (!selected.value) return
  try {
    await ElMessageBox.confirm(
      target === 'DEVELOPMENT' ? '确认将此样本纳入独立开发集？' : '确认单独审批晋升到回归集？',
      '确认评测集变更',
      { type: 'warning' },
    )
  } catch {
    return
  }
  busy.value = true
  try {
    selected.value = await api.promoteSample(selected.value.sample.id, target)
    ElMessage.success('评测集版本已更新')
    await refresh()
  } catch (error) {
    showError(error, '晋升评测样本失败')
  } finally {
    busy.value = false
  }
}

/** 切换候选或样本页，列表返回不足一页时保持当前页并展示空态。 */
async function changePage(kind: 'feedback' | 'sample', delta: number): Promise<void> {
  if (kind === 'feedback') candidatePage.value = Math.max(1, candidatePage.value + delta)
  else samplePage.value = Math.max(1, samplePage.value + delta)
  await refresh()
}

/** 仅显示后端提供的安全公开错误说明。 */
function showError(error: unknown, fallback: string): void {
  ElMessage.error(error instanceof ApiError ? error.message : fallback)
}

/** 用本地时区展示 UTC 时间。 */
function localTime(value: string): string {
  return new Date(value).toLocaleString('zh-CN')
}

/** 将服务端评审状态转换成管理员可直接理解的中文阶段。 */
function reviewStatusLabel(value: HumanSample['status']): string {
  return {
    FIRST_PENDING: '待首评',
    SECOND_PENDING: '待次评',
    ARBITRATION_PENDING: '待第三人复核',
    DECIDED: '已形成结论',
  }[value]
}

/** 解释人工评测数据集层级；它不等同于原有固定回归基线。 */
function datasetStateLabel(value: HumanDatasetState): string {
  return { NONE: '未纳入', DEVELOPMENT: '独立开发集', REGRESSION: '独立回归集' }[value]
}

/** 显示提交者在盲评流程中的角色。 */
function reviewRoleLabel(value: string): string {
  return { FIRST: '首评', SECOND: '次评', ARBITER: '第三人复核' }[value] || value
}
</script>

<template>
  <section class="content-page">
    <header class="page-heading">
      <div>
        <p class="eyebrow">Quality / Human Review</p>
        <h1>人工评测</h1>
        <p class="muted-copy">固定用例与无帮助反馈仅是线索。先手工脱敏，再由不同管理员独立评分。</p>
      </div>
    </header>

    <el-card shadow="never">
      <h2>固定用例候选</h2>
      <p class="muted-copy">选择已有用例后，仍需手工提供被评回答和允许展示的证据。</p>
      <el-select
        aria-label="选择固定用例"
        placeholder="选择 30 条固定回答用例之一"
        style="width: 100%"
        @change="
          (id: string) => {
            const item = fixed.find((row) => row.caseId === id)
            if (item) selectFixed(item)
          }
        "
      >
        <el-option
          v-for="item in fixed"
          :key="item.caseId"
          :label="`${item.caseId} · ${item.question}`"
          :value="item.caseId"
        />
      </el-select>
    </el-card>

    <el-card shadow="never">
      <h2>无帮助反馈候选</h2>
      <p class="muted-copy">仅显示运行编号、原因和时间。原会话过期后不能补造样本。</p>
      <el-table :data="candidates" empty-text="暂无可评反馈候选">
        <el-table-column prop="runId" label="运行编号" min-width="240" />
        <el-table-column prop="reason" label="无帮助原因" width="150" />
        <el-table-column label="反馈时间" width="180">
          <template #default="scope">{{ localTime(scope.row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180">
          <template #default="scope">
            <el-button size="small" @click="selectFeedback(scope.row)">选择</el-button>
            <el-button size="small" @click="previewFeedback(scope.row)">查看原会话</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="form-actions">
        <el-button :disabled="candidatePage === 1" @click="changePage('feedback', -1)"
          >上一页</el-button
        >
        <span>第 {{ candidatePage }} 页</span>
        <el-button :disabled="candidates.length < 20" @click="changePage('feedback', 1)"
          >下一页</el-button
        >
      </div>
      <div v-if="sourcePreview" class="source-preview">
        <h3>原会话预览（不会自动保存）</h3>
        <p>请手工去除个人信息、密钥和不允许展示的内容，再填写下方副本。</p>
        <h4>用户问题</h4>
        <pre>{{ sourcePreview.userMessage }}</pre>
        <h4>模型回答</h4>
        <pre>{{ sourcePreview.answer }}</pre>
      </div>
    </el-card>

    <el-card shadow="never">
      <h2>创建脱敏样本</h2>
      <form class="admin-form" @submit.prevent="create">
        <label for="human-source-type">来源类型</label>
        <select id="human-source-type" v-model="createForm.sourceType">
          <option value="FIXED">固定用例</option>
          <option value="FEEDBACK">无帮助反馈</option>
        </select>
        <label for="human-source-ref">来源标识（固定用例 ID 或反馈运行 UUID）</label>
        <el-input id="human-source-ref" v-model="createForm.sourceRef" maxlength="100" />
        <label for="human-space">知识空间 UUID（固定用例必须为 GLOBAL）</label>
        <el-input id="human-space" v-model="createForm.spaceId" />
        <label for="human-question">脱敏问题（最多 2000 字符）</label>
        <el-input
          id="human-question"
          v-model="createForm.question"
          type="textarea"
          :rows="3"
          maxlength="2000"
          show-word-limit
        />
        <label for="human-answer">脱敏回答（最多 10000 字符）</label>
        <el-input
          id="human-answer"
          v-model="createForm.answer"
          type="textarea"
          :rows="5"
          maxlength="10000"
          show-word-limit
        />
        <label for="human-evidence"
          >允许评审查看的脱敏证据；无引用义务时填写“无”（最多 10000 字符）</label
        >
        <el-input
          id="human-evidence"
          v-model="createForm.evidence"
          type="textarea"
          :rows="4"
          maxlength="10000"
          show-word-limit
        />
        <label
          ><input v-model="createForm.sanitizedConfirmed" type="checkbox" />
          我已逐项核对问题、回答和证据，确认没有个人信息、密钥或越权内容</label
        >
        <div class="form-actions">
          <el-button type="primary" native-type="submit" :loading="busy">保存脱敏样本</el-button>
        </div>
      </form>
    </el-card>

    <el-card shadow="never">
      <h2>评审样本</h2>
      <el-table :data="samples" empty-text="暂无人工评测样本">
        <el-table-column prop="sourceRef" label="来源标识" min-width="200" />
        <el-table-column label="评审状态" width="210">
          <template #default="scope">{{ reviewStatusLabel(scope.row.status) }}</template>
        </el-table-column>
        <el-table-column label="数据集" width="150">
          <template #default="scope">{{ datasetStateLabel(scope.row.datasetState) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="110"
          ><template #default="scope"
            ><el-button size="small" @click="selectSample(scope.row)">查看</el-button></template
          ></el-table-column
        >
      </el-table>
      <div class="form-actions">
        <el-button :disabled="samplePage === 1" @click="changePage('sample', -1)">上一页</el-button>
        <span>第 {{ samplePage }} 页</span>
        <el-button :disabled="samples.length < 20" @click="changePage('sample', 1)"
          >下一页</el-button
        >
      </div>
    </el-card>

    <el-card v-if="selected" shadow="never">
      <h2>样本 {{ selected.sample.sourceRef }}</h2>
      <p>
        状态：{{ reviewStatusLabel(selected.sample.status) }}；数据集：{{
          datasetStateLabel(selected.sample.datasetState)
        }}；版本：{{ selected.sample.datasetVersion }}
      </p>
      <h3>脱敏问题</h3>
      <pre>{{ selected.sample.question }}</pre>
      <h3>脱敏回答</h3>
      <pre>{{ selected.sample.answer }}</pre>
      <h3>允许查看的证据</h3>
      <pre>{{ selected.sample.evidence }}</pre>
      <p v-if="selected.sample.status !== 'DECIDED'" class="muted-copy">
        结论形成前，其他评审人的分数和理由对你不可见。
      </p>
      <div v-for="item in selected.reviews" :key="item.reviewerId">
        <p>
          {{ reviewRoleLabel(item.role) }}：事实 {{ item.score.factual }} / 引用
          {{ item.score.citation }} / 完整 {{ item.score.completeness }} / 操作
          {{ item.score.actionability }} / 清晰
          {{ item.score.clarity }}
        </p>
        <p>{{ item.reason }}</p>
      </div>
      <p v-if="selected.finalScore">
        最终分数：{{ selected.finalScore.factual }} / {{ selected.finalScore.citation }} /
        {{ selected.finalScore.completeness }} / {{ selected.finalScore.actionability }} /
        {{ selected.finalScore.clarity }}
      </p>

      <form
        v-if="selected.sample.status !== 'DECIDED' && selected.reviews.length === 0"
        class="admin-form"
        @submit.prevent="submitReview"
      >
        <h3>独立评分（提交后不可修改）</h3>
        <label
          v-for="field in [
            ['factual', '事实正确'],
            ['citation', '引用支持'],
            ['completeness', '完整性'],
            ['actionability', '可操作性'],
            ['clarity', '表达清晰度'],
          ] as const"
          :key="field[0]"
          :for="`score-${field[0]}`"
          >{{ field[1] }}（0 明显不满足、1 部分满足、2 满足）
          <select :id="`score-${field[0]}`" v-model.number="reviewForm[field[0]]">
            <option :value="0">0</option>
            <option :value="1">1</option>
            <option :value="2">2</option>
          </select>
        </label>
        <label for="human-review-reason">五维评分理由；无引用义务时注明“不适用”</label>
        <el-input
          id="human-review-reason"
          v-model="reviewForm.reason"
          type="textarea"
          :rows="3"
          maxlength="1000"
          show-word-limit
        />
        <div class="form-actions">
          <el-button type="primary" native-type="submit" :loading="busy"
            >提交不可更改评分</el-button
          >
        </div>
      </form>
      <div v-if="selected.sample.status === 'DECIDED'" class="form-actions">
        <el-button
          v-if="selected.sample.datasetState === 'NONE'"
          :loading="busy"
          @click="promote('DEVELOPMENT')"
          >纳入开发集</el-button
        >
        <el-button
          v-if="selected.sample.datasetState === 'DEVELOPMENT'"
          :loading="busy"
          @click="promote('REGRESSION')"
          >单独审批晋升回归集</el-button
        >
      </div>
    </el-card>
  </section>
</template>

<style scoped>
.source-preview pre,
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.form-actions {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  margin-top: 1rem;
}
</style>
