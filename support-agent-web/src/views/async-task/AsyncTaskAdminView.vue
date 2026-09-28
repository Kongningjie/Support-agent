<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as taskApi from '@/api/async-task.api'
import { hasUncertainOutcome } from '@/api/idempotency'
import EmptyState from '@/components/feedback/EmptyState.vue'
import ErrorState from '@/components/feedback/ErrorState.vue'
import LoadingState from '@/components/feedback/LoadingState.vue'
import { ApiError } from '@/types/api.types'
import type {
  AggregateType,
  AsyncTaskStatus,
  AsyncTaskType,
  AsyncTaskView,
} from '@/types/async-task.types'

const items = ref<AsyncTaskView[]>([])
const taskType = ref<AsyncTaskType | null>(null)
const status = ref<AsyncTaskStatus | null>(null)
const aggregateType = ref<AggregateType | null>(null)
const aggregateId = ref('')
const page = ref(1)
const size = ref(20)
const totalElements = ref(0)
const totalPages = ref(0)
const loading = ref(false)
const saving = ref(false)
const error = ref<ApiError | null>(null)
const details = ref<AsyncTaskView | null>(null)
const detailsVisible = ref(false)
const retryKeys = new Map<string, string>()

onMounted(load)

/** 使用当前过滤条件读取异步任务。 */
async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const result = await taskApi.listTasks({
      taskType: taskType.value,
      status: status.value,
      aggregateType: aggregateType.value,
      aggregateId: aggregateId.value.trim(),
      page: page.value,
      size: size.value,
    })
    items.value = result.items
    totalElements.value = result.totalElements
    totalPages.value = result.totalPages
  } catch (caught) {
    error.value = asApiError(caught, '无法读取异步任务')
  } finally {
    loading.value = false
  }
}

/** 校验关联 ID 并从第一页应用筛选。 */
async function applyFilters(): Promise<void> {
  if (aggregateId.value.trim() && !/^[1-9]\d*$/.test(aggregateId.value.trim())) {
    ElMessage.warning('关联对象 ID 必须为正整数')
    return
  }
  page.value = 1
  await load()
}

/** 清空任务筛选。 */
async function clearFilters(): Promise<void> {
  taskType.value = null
  status.value = null
  aggregateType.value = null
  aggregateId.value = ''
  page.value = 1
  await load()
}

/** 切换任务分页。 */
async function changePage(nextPage: number, nextSize = size.value): Promise<void> {
  page.value = nextPage
  size.value = nextSize
  await load()
}

/** 读取任务安全详情。 */
async function openDetails(taskId: string): Promise<void> {
  loading.value = true
  try {
    details.value = await taskApi.getTask(taskId)
    detailsVisible.value = true
  } catch (caught) {
    ElMessage.error(asApiError(caught, '读取任务详情失败').message)
  } finally {
    loading.value = false
  }
}

/** 输入人工理由后为 DEAD 任务创建新任务。 */
async function retry(task: AsyncTaskView): Promise<void> {
  let reason: string
  try {
    const result = await ElMessageBox.prompt(
      '仅当原业务状态和版本仍匹配时才能重试，请填写 1～500 字符原因。',
      '人工重试任务',
      {
        inputPattern: /^.{1,500}$/s,
        inputErrorMessage: '原因必须为 1～500 个字符',
        type: 'warning',
        confirmButtonText: '确认重试',
      },
    )
    reason = result.value.trim()
  } catch {
    return
  }
  const keyId = `${task.taskId}:${reason}`
  const key = retryKeys.get(keyId) || crypto.randomUUID()
  retryKeys.set(keyId, key)
  saving.value = true
  try {
    const created = await taskApi.retryTask(task.taskId, reason, key)
    retryKeys.delete(keyId)
    ElMessage.success(`新任务 ${created.taskId} 已创建`)
    details.value = created
    await load()
  } catch (caught) {
    if (!hasUncertainOutcome(caught)) retryKeys.delete(keyId)
    ElMessage.error(asApiError(caught, '人工重试失败').message)
  } finally {
    saving.value = false
  }
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
        <h1>异步任务</h1>
        <p class="muted-copy">查看 Outbox 任务状态、脱敏失败摘要并人工重试死亡任务。</p>
      </div>
    </header>
    <form class="filter-bar admin-filter admin-filter--wide" @submit.prevent="applyFilters">
      <label for="task-type">任务类型</label
      ><select id="task-type" v-model="taskType">
        <option :value="null">全部</option>
        <option
          v-for="value in ['CASE_GENERATION', 'KNOWLEDGE_INDEX', 'KNOWLEDGE_DELETE']"
          :key="value"
          :value="value"
        >
          {{ value }}
        </option></select
      ><label for="task-status">状态</label
      ><select id="task-status" v-model="status">
        <option :value="null">全部</option>
        <option
          v-for="value in ['PENDING', 'RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'DEAD', 'CANCELLED']"
          :key="value"
          :value="value"
        >
          {{ value }}
        </option></select
      ><label for="aggregate-type">聚合</label
      ><select id="aggregate-type" v-model="aggregateType">
        <option :value="null">全部</option>
        <option
          v-for="value in ['TICKET', 'MANAGED_DOCUMENT', 'RESOLVED_CASE']"
          :key="value"
          :value="value"
        >
          {{ value }}
        </option></select
      ><label for="aggregate-id">聚合 ID</label
      ><el-input id="aggregate-id" v-model="aggregateId" /><el-button
        native-type="submit"
        type="primary"
        >查询</el-button
      ><el-button @click="clearFilters">清空</el-button>
    </form>
    <LoadingState v-if="loading" title="正在读取异步任务" /><ErrorState
      v-else-if="error"
      :message="error.message"
      :trace-id="error.traceId"
      @retry="load"
    /><EmptyState
      v-else-if="items.length === 0"
      title="暂无异步任务"
      description="任务会由知识、工单和案例业务动作创建。"
    />
    <div v-else class="admin-table-wrap">
      <table class="admin-table">
        <thead>
          <tr>
            <th>任务</th>
            <th>关联对象</th>
            <th>状态</th>
            <th>尝试</th>
            <th>失败摘要</th>
            <th>更新时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in items" :key="item.taskId">
            <td>
              <strong>{{ item.taskType }}</strong
              ><small>#{{ item.taskId }}</small>
            </td>
            <td>
              {{ item.aggregateType }} #{{ item.aggregateId
              }}<small>版本 {{ item.aggregateVersion }}</small>
            </td>
            <td>
              <span class="status-chip">{{ item.status }}</span>
            </td>
            <td>{{ item.attemptCount }} / {{ item.maxAttempts }}</td>
            <td>
              {{ item.lastErrorMessage || '—'
              }}<small v-if="item.lastErrorCode">{{ item.lastErrorCode }}</small>
            </td>
            <td>{{ localTime(item.updatedAt) }}</td>
            <td>
              <div class="table-actions">
                <el-button size="small" @click="openDetails(item.taskId)">详情</el-button
                ><el-button
                  v-if="item.status === 'DEAD'"
                  size="small"
                  type="danger"
                  plain
                  :loading="saving"
                  @click="retry(item)"
                  >人工重试</el-button
                >
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <nav v-if="totalPages > 0" class="pager">
      <span>共 {{ totalElements }} 条</span
      ><select
        :value="size"
        aria-label="任务每页数量"
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
    <el-dialog v-model="detailsVisible" title="异步任务详情" width="720px"
      ><dl v-if="details" class="admin-details">
        <div>
          <dt>任务</dt>
          <dd>{{ details.taskType }} #{{ details.taskId }}</dd>
        </div>
        <div>
          <dt>关联对象</dt>
          <dd>
            {{ details.aggregateType }} #{{ details.aggregateId }}，版本
            {{ details.aggregateVersion }}
          </dd>
        </div>
        <div>
          <dt>状态 / 尝试</dt>
          <dd>{{ details.status }} / {{ details.attemptCount }} of {{ details.maxAttempts }}</dd>
        </div>
        <div>
          <dt>下次运行</dt>
          <dd>{{ localTime(details.nextRunAt) }}</dd>
        </div>
        <div>
          <dt>失败码</dt>
          <dd>{{ details.lastErrorCode || '—' }}</dd>
        </div>
        <div>
          <dt>失败摘要</dt>
          <dd>{{ details.lastErrorMessage || '—' }}</dd>
        </div>
        <div>
          <dt>人工重试来源</dt>
          <dd>{{ details.retryOfTaskId || '—' }}</dd>
        </div>
        <div>
          <dt>人工原因</dt>
          <dd>{{ details.manualRetryReason || '—' }}</dd>
        </div>
        <div>
          <dt>创建 / 完成</dt>
          <dd>{{ localTime(details.createdAt) }} / {{ localTime(details.finishedAt) }}</dd>
        </div>
      </dl>
      <template #footer
        ><el-button
          v-if="details?.status === 'DEAD'"
          type="danger"
          plain
          :loading="saving"
          @click="retry(details)"
          >人工重试</el-button
        ><el-button @click="detailsVisible = false">关闭</el-button></template
      ></el-dialog
    >
  </section>
</template>
