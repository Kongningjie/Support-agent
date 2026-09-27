<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as ticketApi from '@/api/ticket.api'
import ErrorState from '@/components/feedback/ErrorState.vue'
import LoadingState from '@/components/feedback/LoadingState.vue'
import {
  canCloseTicket,
  canResolveTicket,
  canReviseTicket,
  canSubmitTicket,
  ticketStatusLabel,
} from '@/ticket/ticket.rules'
import { ApiError } from '@/types/api.types'
import type { TicketDetails } from '@/types/ticket.types'

type ActionName = 'submit' | 'resolve' | 'close'

const route = useRoute()
const router = useRouter()
const ticketNo = computed(() => String(route.params.ticketNo || ''))
const ticket = ref<TicketDetails | null>(null)
const loading = ref(false)
const saving = ref(false)
const error = ref<ApiError | null>(null)
const conflict = ref<ApiError | null>(null)
const editing = ref(false)
const resolveVisible = ref(false)
const closeVisible = ref(false)
const edit = reactive({ title: '', problemDescription: '', attemptedActions: '' })
const resolution = reactive({ rootCause: '', solution: '' })
const closure = reactive({ closeReason: '' })
const actionKeys = reactive<Partial<Record<ActionName, string>>>({})

onMounted(() => load(false))

/** 读取最新工单；冲突刷新时保留尚未提交的人工输入。 */
async function load(preserveForms: boolean): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const latest = await ticketApi.getTicket(ticketNo.value)
    ticket.value = latest
    conflict.value = null
    if (!preserveForms) fillEdit(latest)
  } catch (caught) {
    error.value = asApiError(caught, '无法读取工单详情')
  } finally {
    loading.value = false
  }
}

/** 从服务端详情初始化草稿编辑字段。 */
function fillEdit(value: TicketDetails): void {
  edit.title = value.title
  edit.problemDescription = value.problemDescription
  edit.attemptedActions = value.attemptedActions || ''
}

/** 打开草稿编辑区并加载当前服务端内容。 */
function beginEdit(): void {
  if (!ticket.value) return
  fillEdit(ticket.value)
  editing.value = true
}

/** 校验并提交草稿修改；409 时保留输入并等待用户刷新版本。 */
async function saveDraft(): Promise<void> {
  if (!ticket.value || saving.value) return
  const title = edit.title.trim()
  const problemDescription = edit.problemDescription.trim()
  const attemptedActions = edit.attemptedActions.trim()
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
  saving.value = true
  try {
    ticket.value = await ticketApi.reviseDraft(ticket.value.ticketNo, {
      title,
      problemDescription,
      attemptedActions: attemptedActions || null,
      version: ticket.value.version,
    })
    conflict.value = null
    editing.value = false
    ElMessage.success('工单草稿已更新')
  } catch (caught) {
    handleWriteError(caught, '工单草稿更新失败')
  } finally {
    saving.value = false
  }
}

/** 二次确认后把当前草稿提交为开放工单。 */
async function submit(): Promise<void> {
  if (!ticket.value || saving.value) return
  const current = ticket.value
  try {
    await ElMessageBox.confirm(
      '提交后工单将进入待处理状态，草稿正文不能再编辑。是否继续？',
      '提交工单',
      { confirmButtonText: '确认提交', cancelButtonText: '取消', type: 'warning' },
    )
  } catch (caught) {
    if (caught === 'cancel' || caught === 'close') return
    throw caught
  }
  await runAction('submit', (key) =>
    ticketApi.submitTicket(current.ticketNo, {
      version: current.version,
      idempotencyKey: key,
    }),
  )
}

/** 打开解决表单并重置上一次已成功提交的内容。 */
function openResolve(): void {
  resolution.rootCause = ''
  resolution.solution = ''
  resolveVisible.value = true
}

/** 校验人工根因与方案并解决开放工单。 */
async function resolve(): Promise<void> {
  if (!ticket.value || saving.value) return
  const current = ticket.value
  const rootCause = resolution.rootCause.trim()
  const solution = resolution.solution.trim()
  if (!rootCause || rootCause.length > 4000) {
    ElMessage.warning('根因必须为 1～4000 个字符')
    return
  }
  if (!solution || solution.length > 8000) {
    ElMessage.warning('解决方案必须为 1～8000 个字符')
    return
  }
  const succeeded = await runAction('resolve', (key) =>
    ticketApi.resolveTicket(current.ticketNo, {
      rootCause,
      solution,
      version: current.version,
      idempotencyKey: key,
    }),
  )
  if (succeeded) resolveVisible.value = false
}

/** 打开关闭表单并要求填写未解决关闭原因。 */
function openClose(): void {
  closure.closeReason = ''
  closeVisible.value = true
}

/** 校验人工原因并关闭草稿或开放工单。 */
async function close(): Promise<void> {
  if (!ticket.value || saving.value) return
  const current = ticket.value
  const closeReason = closure.closeReason.trim()
  if (!closeReason || closeReason.length > 500) {
    ElMessage.warning('关闭原因必须为 1～500 个字符')
    return
  }
  const succeeded = await runAction('close', (key) =>
    ticketApi.closeTicket(current.ticketNo, {
      closeReason,
      version: current.version,
      idempotencyKey: key,
    }),
  )
  if (succeeded) closeVisible.value = false
}

/** 执行幂等状态动作；失败重试沿用同一业务动作的 Key。 */
async function runAction(
  name: ActionName,
  operation: (idempotencyKey: string) => Promise<TicketDetails>,
): Promise<boolean> {
  if (saving.value) return false
  const key = actionKeys[name] || crypto.randomUUID()
  actionKeys[name] = key
  saving.value = true
  try {
    ticket.value = await operation(key)
    delete actionKeys[name]
    conflict.value = null
    ElMessage.success(actionSuccessMessage(name))
    return true
  } catch (caught) {
    handleWriteError(caught, '工单操作失败')
    return false
  } finally {
    saving.value = false
  }
}

/** 记录并发冲突但不覆盖任何表单输入。 */
function handleWriteError(value: unknown, fallback: string): void {
  const apiError = asApiError(value, fallback)
  if (apiError.status === 409) conflict.value = apiError
  ElMessage.error(apiError.message)
}

/** 返回状态动作成功后的明确提示。 */
function actionSuccessMessage(name: ActionName): string {
  return { submit: '工单已提交', resolve: '工单已解决，案例生成任务已创建', close: '工单已关闭' }[
    name
  ]
}

/** 使用浏览器本地时区展示服务端 UTC 时间。 */
function localTime(value: string | null): string {
  return value ? new Date(value).toLocaleString('zh-CN') : '—'
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
        <p class="eyebrow">Ticket details</p>
        <h1>{{ ticket?.ticketNo || '工单详情' }}</h1>
        <p v-if="ticket" class="muted-copy">
          {{ ticketStatusLabel(ticket.status) }} · 版本 {{ ticket.version }}
        </p>
      </div>
      <div class="heading-actions">
        <el-button @click="router.push('/tickets')">返回列表</el-button>
        <el-button v-if="ticket && canReviseTicket(ticket.status)" @click="beginEdit">
          编辑草稿
        </el-button>
        <el-button
          v-if="ticket && canSubmitTicket(ticket.status)"
          type="primary"
          :loading="saving"
          @click="submit"
        >
          提交工单
        </el-button>
        <el-button
          v-if="ticket && canResolveTicket(ticket.status)"
          type="success"
          @click="openResolve"
        >
          解决工单
        </el-button>
        <el-button
          v-if="ticket && canCloseTicket(ticket.status)"
          type="danger"
          plain
          @click="openClose"
        >
          关闭工单
        </el-button>
      </div>
    </header>

    <LoadingState v-if="loading" title="正在读取工单详情" />
    <ErrorState
      v-else-if="error"
      :message="error.message"
      :trace-id="error.traceId"
      @retry="load(false)"
    />
    <template v-else-if="ticket">
      <el-alert
        v-if="conflict"
        title="工单已被其他操作更新"
        :description="`${conflict.message}。你的表单内容仍然保留，请先刷新最新版本再决定是否重新提交。`"
        type="warning"
        :closable="false"
        show-icon
      >
        <template #default>
          <el-button size="small" @click="load(true)">刷新最新版本并保留输入</el-button>
        </template>
      </el-alert>

      <form v-if="editing" class="ticket-form ticket-panel" @submit.prevent="saveDraft">
        <h2>修改草稿</h2>
        <label for="ticket-edit-title">工单标题（必填，1～160 字符）</label>
        <el-input id="ticket-edit-title" v-model="edit.title" maxlength="160" show-word-limit />
        <label for="ticket-edit-problem">问题描述（必填，1～8000 字符）</label>
        <el-input
          id="ticket-edit-problem"
          v-model="edit.problemDescription"
          type="textarea"
          :rows="5"
          maxlength="8000"
          show-word-limit
        />
        <label for="ticket-edit-attempts">已尝试操作及结果（可选，最多 8000 字符）</label>
        <el-input
          id="ticket-edit-attempts"
          v-model="edit.attemptedActions"
          type="textarea"
          :rows="4"
          maxlength="8000"
          show-word-limit
        />
        <div class="heading-actions">
          <el-button :disabled="saving" @click="editing = false">取消</el-button>
          <el-button type="primary" native-type="submit" :loading="saving">保存草稿</el-button>
        </div>
      </form>

      <article v-else class="ticket-panel ticket-details">
        <div class="ticket-details__title">
          <div>
            <span class="status-chip">{{ ticketStatusLabel(ticket.status) }}</span>
            <h2>{{ ticket.title }}</h2>
          </div>
          <small>当前版本 {{ ticket.version }}</small>
        </div>
        <section>
          <h3>问题描述</h3>
          <p>{{ ticket.problemDescription }}</p>
        </section>
        <section>
          <h3>已尝试操作及结果</h3>
          <p>{{ ticket.attemptedActions || '未填写' }}</p>
        </section>
        <section v-if="ticket.rootCause">
          <h3>人工确认根因</h3>
          <p>{{ ticket.rootCause }}</p>
        </section>
        <section v-if="ticket.solution">
          <h3>实际解决方案</h3>
          <p>{{ ticket.solution }}</p>
        </section>
        <section v-if="ticket.closeReason">
          <h3>关闭原因</h3>
          <p>{{ ticket.closeReason }}</p>
        </section>
        <dl class="ticket-meta">
          <div>
            <dt>所属知识空间</dt>
            <dd>{{ ticket.space.name }}（{{ ticket.space.code }}）</dd>
          </div>
          <div>
            <dt>创建时间</dt>
            <dd>{{ localTime(ticket.createdAt) }}</dd>
          </div>
          <div>
            <dt>最近更新</dt>
            <dd>{{ localTime(ticket.updatedAt) }}</dd>
          </div>
          <div>
            <dt>解决时间</dt>
            <dd>{{ localTime(ticket.resolvedAt) }}</dd>
          </div>
          <div>
            <dt>关闭时间</dt>
            <dd>{{ localTime(ticket.closedAt) }}</dd>
          </div>
        </dl>
      </article>
    </template>

    <el-dialog v-model="resolveVisible" title="解决开放工单" width="720px">
      <form id="ticket-resolve-form" class="ticket-form" @submit.prevent="resolve">
        <p class="form-note">解决成功后，后端会在同一事务中创建异步案例生成任务。</p>
        <label for="ticket-root-cause">人工确认根因（必填，1～4000 字符）</label>
        <el-input
          id="ticket-root-cause"
          v-model="resolution.rootCause"
          type="textarea"
          :rows="4"
          maxlength="4000"
          show-word-limit
        />
        <label for="ticket-solution">实际执行且有效的解决方案（必填，1～8000 字符）</label>
        <el-input
          id="ticket-solution"
          v-model="resolution.solution"
          type="textarea"
          :rows="5"
          maxlength="8000"
          show-word-limit
        />
      </form>
      <template #footer>
        <el-button :disabled="saving" @click="resolveVisible = false">取消</el-button>
        <el-button type="success" native-type="submit" form="ticket-resolve-form" :loading="saving">
          确认解决
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="closeVisible" title="关闭工单" width="620px">
      <form id="ticket-close-form" class="ticket-form" @submit.prevent="close">
        <p class="form-note">关闭表示不再继续处理，不会生成解决案例。</p>
        <label for="ticket-close-reason">人工关闭原因（必填，1～500 字符）</label>
        <el-input
          id="ticket-close-reason"
          v-model="closure.closeReason"
          type="textarea"
          :rows="4"
          maxlength="500"
          show-word-limit
        />
      </form>
      <template #footer>
        <el-button :disabled="saving" @click="closeVisible = false">取消</el-button>
        <el-button type="danger" native-type="submit" form="ticket-close-form" :loading="saving">
          确认关闭
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>
