<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import * as ticketApi from '@/api/ticket.api'
import EmptyState from '@/components/feedback/EmptyState.vue'
import ErrorState from '@/components/feedback/ErrorState.vue'
import LoadingState from '@/components/feedback/LoadingState.vue'
import KnowledgeSpaceSelect from '@/components/knowledge-space/KnowledgeSpaceSelect.vue'
import { ticketStatusLabel } from '@/ticket/ticket.rules'
import { ApiError } from '@/types/api.types'
import type { TicketStatus, TicketSummary } from '@/types/ticket.types'

const router = useRouter()
const items = ref<TicketSummary[]>([])
const status = ref<TicketStatus | null>(null)
const keywordInput = ref('')
const appliedKeyword = ref('')
const page = ref(1)
const size = ref(20)
const totalElements = ref(0)
const totalPages = ref(0)
const loading = ref(false)
const saving = ref(false)
const error = ref<ApiError | null>(null)
const createVisible = ref(false)
const createKey = ref<string | null>(null)
const createSpaceId = ref<string | null>(null)
const draft = reactive({ title: '', problemDescription: '', attemptedActions: '' })

onMounted(load)

/** 使用当前已应用筛选读取后端授权范围内的工单。 */
async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const result = await ticketApi.listTickets(
      status.value,
      appliedKeyword.value,
      page.value,
      size.value,
    )
    items.value = result.items
    totalElements.value = result.totalElements
    totalPages.value = result.totalPages
  } catch (caught) {
    error.value = asApiError(caught, '无法读取工单列表')
  } finally {
    loading.value = false
  }
}

/** 应用状态与关键词筛选，并从第一页重新查询。 */
async function applyFilters(): Promise<void> {
  if (keywordInput.value.trim().length > 160) {
    ElMessage.warning('关键词最多 160 个字符')
    return
  }
  appliedKeyword.value = keywordInput.value.trim()
  page.value = 1
  await load()
}

/** 清空筛选条件并返回第一页。 */
async function clearFilters(): Promise<void> {
  status.value = null
  keywordInput.value = ''
  appliedKeyword.value = ''
  page.value = 1
  await load()
}

/** 切换当前页或每页数量后重新查询。 */
async function changePage(nextPage: number, nextSize = size.value): Promise<void> {
  size.value = nextSize
  page.value = nextPage
  await load()
}

/** 打开手工草稿表单，每次新动作生成一个可复用幂等键。 */
function openCreate(): void {
  draft.title = ''
  draft.problemDescription = ''
  draft.attemptedActions = ''
  createSpaceId.value = null
  createKey.value = crypto.randomUUID()
  createVisible.value = true
}

/** 校验人工事实字段并创建工单草稿，网络结果不确定时复用原幂等键。 */
async function createDraft(): Promise<void> {
  if (!createSpaceId.value) {
    ElMessage.warning('请选择工单所属的知识空间')
    return
  }
  const title = draft.title.trim()
  const problemDescription = draft.problemDescription.trim()
  const attemptedActions = draft.attemptedActions.trim()
  if (!title || title.length > 160) {
    ElMessage.warning('工单标题必须为 1～160 个字符')
    return
  }
  if (!problemDescription || problemDescription.length > 8000) {
    ElMessage.warning('问题描述必须为 1～8000 个字符')
    return
  }
  if (attemptedActions.length > 8000) {
    ElMessage.warning('已尝试操作最多 8000 个字符')
    return
  }
  const key = createKey.value || crypto.randomUUID()
  createKey.value = key
  saving.value = true
  try {
    const created = await ticketApi.createDraft({
      spaceId: createSpaceId.value,
      title,
      problemDescription,
      attemptedActions: attemptedActions || null,
      idempotencyKey: key,
    })
    createKey.value = null
    createVisible.value = false
    ElMessage.success(`工单草稿 ${created.ticketNo} 已创建`)
    await router.push(`/tickets/${created.ticketNo}`)
  } catch (caught) {
    ElMessage.error(asApiError(caught, '创建工单草稿失败').message)
  } finally {
    saving.value = false
  }
}

/** 使用浏览器本地时区展示服务端 UTC 时间。 */
function localTime(value: string): string {
  return new Date(value).toLocaleString('zh-CN')
}

/** 将未知异常收敛为可公开展示的统一错误。 */
function asApiError(value: unknown, fallback: string): ApiError {
  return value instanceof ApiError
    ? value
    : new ApiError(fallback, null, 'CLIENT_ERROR', null, true)
}
</script>

<template>
  <section class="content-page">
    <header class="page-heading">
      <div>
        <p class="eyebrow">Tickets</p>
        <h1>技术支持工单</h1>
        <p class="muted-copy">列表只展示后端授权给当前账号的工单，不由前端推断归属范围。</p>
      </div>
      <el-button type="primary" @click="openCreate">手工创建草稿</el-button>
    </header>

    <form class="filter-bar ticket-filter" @submit.prevent="applyFilters">
      <label for="ticket-status">状态</label>
      <select id="ticket-status" v-model="status">
        <option :value="null">全部状态</option>
        <option value="DRAFT">草稿</option>
        <option value="OPEN">待处理</option>
        <option value="RESOLVED">已解决</option>
        <option value="CLOSED">已关闭</option>
      </select>
      <label for="ticket-keyword">标题或问题关键词</label>
      <input
        id="ticket-keyword"
        v-model="keywordInput"
        maxlength="160"
        placeholder="例如：MySQL 连接失败"
      />
      <el-button native-type="submit" type="primary">查询</el-button>
      <el-button @click="clearFilters">清空</el-button>
    </form>

    <LoadingState v-if="loading" title="正在读取工单" />
    <ErrorState
      v-else-if="error"
      :message="error.message"
      :trace-id="error.traceId"
      :retryable="error.retryable"
      @retry="load"
    />
    <EmptyState
      v-else-if="items.length === 0"
      title="暂无工单"
      description="可以手工创建草稿，或在 AI 无可靠知识时确认建议建单。"
    />
    <div v-else class="ticket-list">
      <button
        v-for="item in items"
        :key="item.ticketNo"
        class="ticket-row"
        type="button"
        @click="router.push(`/tickets/${item.ticketNo}`)"
      >
        <span>
          <strong>{{ item.title }}</strong>
          <small>{{ item.ticketNo }}</small>
          <small>{{ item.space.name }}（{{ item.space.code }}）</small>
        </span>
        <span class="status-chip">{{ ticketStatusLabel(item.status) }}</span>
        <span>版本 {{ item.version }}</span>
        <time>{{ localTime(item.updatedAt) }}</time>
      </button>
    </div>

    <nav v-if="totalPages > 0" class="pager ticket-pager" aria-label="工单分页">
      <span>共 {{ totalElements }} 条</span>
      <label for="ticket-size">每页</label>
      <select
        id="ticket-size"
        :value="size"
        @change="changePage(1, Number(($event.target as HTMLSelectElement).value))"
      >
        <option :value="10">10</option>
        <option :value="20">20</option>
        <option :value="50">50</option>
        <option :value="100">100</option>
      </select>
      <el-button :disabled="page <= 1" @click="changePage(page - 1)">上一页</el-button>
      <span>第 {{ page }} / {{ totalPages }} 页</span>
      <el-button :disabled="page >= totalPages" @click="changePage(page + 1)">下一页</el-button>
    </nav>

    <el-dialog v-model="createVisible" title="手工创建工单草稿" width="680px">
      <form id="ticket-create-form" class="ticket-form" @submit.prevent="createDraft">
        <KnowledgeSpaceSelect
          v-model="createSpaceId"
          input-id="ticket-create-space"
          label="问题所属知识空间（必选）"
        />
        <label for="ticket-create-title">工单标题（必填，1～160 字符）</label>
        <el-input id="ticket-create-title" v-model="draft.title" maxlength="160" show-word-limit />
        <label for="ticket-create-problem">问题描述（必填，1～8000 字符）</label>
        <el-input
          id="ticket-create-problem"
          v-model="draft.problemDescription"
          type="textarea"
          :rows="5"
          maxlength="8000"
          show-word-limit
        />
        <label for="ticket-create-attempts">已尝试操作及结果（可选，最多 8000 字符）</label>
        <el-input
          id="ticket-create-attempts"
          v-model="draft.attemptedActions"
          type="textarea"
          :rows="4"
          maxlength="8000"
          show-word-limit
        />
      </form>
      <template #footer>
        <el-button :disabled="saving" @click="createVisible = false">取消</el-button>
        <el-button type="primary" native-type="submit" form="ticket-create-form" :loading="saving">
          创建草稿
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>
