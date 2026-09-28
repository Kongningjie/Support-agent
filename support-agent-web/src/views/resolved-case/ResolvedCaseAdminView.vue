<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as caseApi from '@/api/resolved-case.api'
import { hasUncertainOutcome } from '@/api/idempotency'
import EmptyState from '@/components/feedback/EmptyState.vue'
import ErrorState from '@/components/feedback/ErrorState.vue'
import LoadingState from '@/components/feedback/LoadingState.vue'
import KnowledgeSpaceSelect from '@/components/knowledge-space/KnowledgeSpaceSelect.vue'
import { ApiError } from '@/types/api.types'
import type {
  ResolvedCaseDetails,
  ResolvedCaseStatus,
  ResolvedCaseSummary,
} from '@/types/resolved-case.types'

const items = ref<ResolvedCaseSummary[]>([])
const status = ref<ResolvedCaseStatus | null>(null)
const sourceTicketNo = ref('')
const keyword = ref('')
const spaceId = ref<string | null>(null)
const page = ref(1)
const size = ref(20)
const totalElements = ref(0)
const totalPages = ref(0)
const loading = ref(false)
const saving = ref(false)
const error = ref<ApiError | null>(null)
const details = ref<ResolvedCaseDetails | null>(null)
const detailsVisible = ref(false)
const draft = reactive({ title: '', problem: '', cause: '', solution: '' })
const actionKeys = new Map<string, string>()

onMounted(load)

/** 使用当前筛选读取案例分页。 */
async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const result = await caseApi.listCases({
      status: status.value,
      sourceTicketNo: sourceTicketNo.value.trim(),
      keyword: keyword.value.trim(),
      spaceId: spaceId.value,
      page: page.value,
      size: size.value,
    })
    items.value = result.items
    totalElements.value = result.totalElements
    totalPages.value = result.totalPages
  } catch (caught) {
    error.value = asApiError(caught, '无法读取案例列表')
  } finally {
    loading.value = false
  }
}

/** 应用案例筛选并回到第一页。 */
async function applyFilters(): Promise<void> {
  if (sourceTicketNo.value.trim().length > 32 || keyword.value.trim().length > 160) {
    ElMessage.warning('工单号或关键词长度不符合要求')
    return
  }
  page.value = 1
  await load()
}

/** 清空案例筛选。 */
async function clearFilters(): Promise<void> {
  status.value = null
  sourceTicketNo.value = ''
  keyword.value = ''
  spaceId.value = null
  page.value = 1
  await load()
}

/** 切换案例分页。 */
async function changePage(nextPage: number, nextSize = size.value): Promise<void> {
  page.value = nextPage
  size.value = nextSize
  await load()
}

/** 读取完整案例并初始化审核表单。 */
async function openDetails(caseId: string): Promise<void> {
  loading.value = true
  try {
    details.value = await caseApi.getCase(caseId)
    Object.assign(draft, {
      title: details.value.title,
      problem: details.value.problem,
      cause: details.value.cause,
      solution: details.value.solution,
    })
    detailsVisible.value = true
  } catch (caught) {
    ElMessage.error(asApiError(caught, '读取案例详情失败').message)
  } finally {
    loading.value = false
  }
}

/** 按当前版本保存待审核内容。 */
async function saveDraft(): Promise<void> {
  if (!details.value) return
  const input = {
    title: draft.title.trim(),
    problem: draft.problem.trim(),
    cause: draft.cause.trim(),
    solution: draft.solution.trim(),
    version: details.value.version,
  }
  if (
    !input.title ||
    input.title.length > 160 ||
    !input.problem ||
    input.problem.length > 4000 ||
    !input.cause ||
    input.cause.length > 4000 ||
    !input.solution ||
    input.solution.length > 8000
  ) {
    ElMessage.warning('标题、问题、根因或解决方案长度不符合要求')
    return
  }
  saving.value = true
  try {
    details.value = await caseApi.reviseCase(details.value.caseId, input)
    ElMessage.success('案例草稿已保存')
    await load()
  } catch (caught) {
    showMutationError(caught, '保存案例失败')
  } finally {
    saving.value = false
  }
}

/** 二次确认后发布案例。 */
async function publish(): Promise<void> {
  if (
    !details.value ||
    !(await confirmAction('发布后案例将异步进入知识索引，确认继续？', '发布案例'))
  )
    return
  await act('publish', {})
}

/** 输入原因后永久拒绝案例。 */
async function reject(): Promise<void> {
  const reason = await promptReason('拒绝后不能重新发布，请填写 1～500 字符原因。', '拒绝案例')
  if (reason !== null) await act('reject', { rejectionReason: reason })
}

/** 输入原因后归档已发布案例。 */
async function archive(): Promise<void> {
  const reason = await promptReason('归档会异步移除检索分块，请填写 1～500 字符原因。', '归档案例')
  if (reason !== null) await act('archive', { archiveReason: reason })
}

/** 执行案例状态动作并复用网络结果不确定时的幂等键。 */
async function act(
  action: 'publish' | 'reject' | 'archive',
  extra: Record<string, string>,
): Promise<void> {
  if (!details.value) return
  const current = details.value
  const actionId = `${action}:${current.caseId}:${current.version}`
  const key = actionKeys.get(actionId) || crypto.randomUUID()
  actionKeys.set(actionId, key)
  saving.value = true
  try {
    details.value = await caseApi.actOnCase(current.caseId, action, {
      ...extra,
      version: current.version,
      idempotencyKey: key,
    })
    actionKeys.delete(actionId)
    ElMessage.success(
      action === 'publish'
        ? '案例发布已发起'
        : action === 'reject'
          ? '案例已拒绝'
          : '案例归档已发起',
    )
    await load()
  } catch (caught) {
    if (!hasUncertainOutcome(caught)) actionKeys.delete(actionId)
    showMutationError(caught, '案例状态操作失败')
  } finally {
    saving.value = false
  }
}

/** 显示原因输入框，取消时返回 null。 */
async function promptReason(message: string, title: string): Promise<string | null> {
  try {
    const result = await ElMessageBox.prompt(message, title, {
      inputPattern: /^.{1,500}$/s,
      inputErrorMessage: '原因必须为 1～500 个字符',
      type: 'warning',
      confirmButtonText: '确认',
    })
    return result.value.trim()
  } catch {
    return null
  }
}

/** 显示确认框，取消时返回 false。 */
async function confirmAction(message: string, title: string): Promise<boolean> {
  try {
    await ElMessageBox.confirm(message, title, { type: 'warning', confirmButtonText: '确认' })
    return true
  } catch {
    return false
  }
}

/** 对版本冲突明确说明当前审核输入仍被保留。 */
function showMutationError(value: unknown, fallback: string): void {
  const issue = asApiError(value, fallback)
  ElMessage.error(
    issue.status === 409 ? `${issue.message}；当前审核输入已保留，请刷新详情后重试` : issue.message,
  )
}

/** 将未知异常收敛为可展示错误。 */
function asApiError(value: unknown, fallback: string): ApiError {
  return value instanceof ApiError
    ? value
    : new ApiError(fallback, null, 'CLIENT_ERROR', null, true)
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
        <p class="eyebrow">Admin</p>
        <h1>案例治理</h1>
        <p class="muted-copy">人工审核由已解决工单生成的候选知识。</p>
      </div>
    </header>
    <form class="filter-bar admin-filter admin-filter--wide" @submit.prevent="applyFilters">
      <label for="case-status">状态</label
      ><select id="case-status" v-model="status">
        <option :value="null">全部</option>
        <option
          v-for="value in [
            'DRAFT',
            'PUBLISHING',
            'PUBLISHED',
            'PUBLISH_FAILED',
            'REJECTED',
            'ARCHIVED',
          ]"
          :key="value"
          :value="value"
        >
          {{ value }}
        </option></select
      ><label for="case-ticket">来源工单</label
      ><el-input id="case-ticket" v-model="sourceTicketNo" maxlength="32" /><label
        for="case-keyword"
        >关键词</label
      ><el-input id="case-keyword" v-model="keyword" maxlength="160" /><KnowledgeSpaceSelect
        v-model="spaceId"
        input-id="case-space-filter"
        label="知识空间（可选）"
      /><el-button native-type="submit" type="primary">查询</el-button
      ><el-button @click="clearFilters">清空</el-button>
    </form>
    <LoadingState v-if="loading" title="正在读取案例" /><ErrorState
      v-else-if="error"
      :message="error.message"
      :trace-id="error.traceId"
      :retryable="error.retryable"
      @retry="load"
    /><EmptyState
      v-else-if="items.length === 0"
      title="暂无案例"
      description="案例由已解决工单的异步任务生成。"
    />
    <div v-else class="admin-table-wrap">
      <table class="admin-table">
        <thead>
          <tr>
            <th>案例</th>
            <th>来源工单</th>
            <th>空间</th>
            <th>状态</th>
            <th>版本</th>
            <th>更新时间</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="item in items"
            :key="item.caseId"
            class="clickable-row"
            tabindex="0"
            :aria-label="`查看案例：${item.title}`"
            @click="openDetails(item.caseId)"
            @keydown.enter="openDetails(item.caseId)"
            @keydown.space.prevent="openDetails(item.caseId)"
          >
            <td>
              <strong>{{ item.title }}</strong
              ><small>#{{ item.caseId }}</small>
            </td>
            <td>{{ item.sourceTicketNo || '受权限保护' }}</td>
            <td>
              {{ item.space.name }}<small>{{ item.space.code }}</small>
            </td>
            <td>
              <span class="status-chip">{{ item.status }}</span>
            </td>
            <td>{{ item.version }}</td>
            <td>{{ localTime(item.updatedAt) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <nav v-if="totalPages > 0" class="pager">
      <span>共 {{ totalElements }} 条</span
      ><select
        :value="size"
        aria-label="案例每页数量"
        @change="changePage(1, Number(($event.target as HTMLSelectElement).value))"
      >
        <option :value="10">10</option>
        <option :value="20">20</option>
        <option :value="50">50</option>
        <option :value="100">100</option></select
      ><el-button :disabled="page <= 1" @click="changePage(page - 1)">上一页</el-button
      ><span>第 {{ page }} / {{ totalPages }} 页</span
      ><el-button :disabled="page >= totalPages" @click="changePage(page + 1)">下一页</el-button>
    </nav>
    <el-dialog v-model="detailsVisible" :title="details?.title || '案例详情'" width="900px"
      ><template v-if="details"
        ><dl class="admin-details">
          <div>
            <dt>来源工单</dt>
            <dd>
              {{ details.sourceTicketNo || '受权限保护' }} {{ details.sourceTicketTitle || '' }}
            </dd>
          </div>
          <div>
            <dt>空间</dt>
            <dd>{{ details.space.name }}（{{ details.space.code }}）</dd>
          </div>
          <div>
            <dt>状态 / 版本</dt>
            <dd>{{ details.status }} / {{ details.version }}</dd>
          </div>
          <div v-if="details.publishFailureReason">
            <dt>发布失败</dt>
            <dd>{{ details.publishFailureReason }}</dd>
          </div>
          <div v-if="details.rejectionReason">
            <dt>拒绝原因</dt>
            <dd>{{ details.rejectionReason }}</dd>
          </div>
          <div v-if="details.archiveReason">
            <dt>归档原因</dt>
            <dd>{{ details.archiveReason }}</dd>
          </div>
        </dl>
        <form class="admin-form" @submit.prevent="saveDraft">
          <label for="case-title">标题</label
          ><el-input
            id="case-title"
            v-model="draft.title"
            maxlength="160"
            :disabled="!['DRAFT', 'PUBLISH_FAILED'].includes(details.status)"
          /><label for="case-problem">问题现象与背景</label
          ><el-input
            id="case-problem"
            v-model="draft.problem"
            type="textarea"
            :rows="4"
            maxlength="4000"
            :disabled="!['DRAFT', 'PUBLISH_FAILED'].includes(details.status)"
          /><label for="case-cause">根因</label
          ><el-input
            id="case-cause"
            v-model="draft.cause"
            type="textarea"
            :rows="4"
            maxlength="4000"
            :disabled="!['DRAFT', 'PUBLISH_FAILED'].includes(details.status)"
          /><label for="case-solution">解决方案</label
          ><el-input
            id="case-solution"
            v-model="draft.solution"
            type="textarea"
            :rows="6"
            maxlength="8000"
            :disabled="!['DRAFT', 'PUBLISH_FAILED'].includes(details.status)"
          />
          <div class="form-actions">
            <el-button
              v-if="['DRAFT', 'PUBLISH_FAILED'].includes(details.status)"
              :loading="saving"
              native-type="submit"
              >保存</el-button
            ><el-button
              v-if="['DRAFT', 'PUBLISH_FAILED'].includes(details.status)"
              type="primary"
              :loading="saving"
              @click="publish"
              >发布</el-button
            ><el-button
              v-if="details.status === 'DRAFT'"
              type="danger"
              plain
              :loading="saving"
              @click="reject"
              >拒绝</el-button
            ><el-button
              v-if="details.status === 'PUBLISHED'"
              type="danger"
              plain
              :loading="saving"
              @click="archive"
              >归档</el-button
            >
          </div>
        </form></template
      ></el-dialog
    >
  </section>
</template>
