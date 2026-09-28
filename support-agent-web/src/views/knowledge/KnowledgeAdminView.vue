<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as knowledgeApi from '@/api/knowledge-admin.api'
import { hasUncertainOutcome } from '@/api/idempotency'
import EmptyState from '@/components/feedback/EmptyState.vue'
import ErrorState from '@/components/feedback/ErrorState.vue'
import LoadingState from '@/components/feedback/LoadingState.vue'
import KnowledgeSpaceSelect from '@/components/knowledge-space/KnowledgeSpaceSelect.vue'
import { ApiError } from '@/types/api.types'
import type {
  ManagedDocumentDetails,
  ManagedDocumentStatus,
  ManagedDocumentSummary,
} from '@/types/knowledge.types'

const items = ref<ManagedDocumentSummary[]>([])
const status = ref<ManagedDocumentStatus | null>(null)
const keyword = ref('')
const spaceId = ref<string | null>(null)
const page = ref(1)
const size = ref(20)
const totalElements = ref(0)
const totalPages = ref(0)
const loading = ref(false)
const saving = ref(false)
const error = ref<ApiError | null>(null)
const details = ref<ManagedDocumentDetails | null>(null)
const detailsVisible = ref(false)
const createVisible = ref(false)
const createMode = ref<'text' | 'file'>('text')
const createKey = ref<string | null>(null)
const actionKeys = new Map<string, string>()
const createDraft = reactive({
  spaceId: null as string | null,
  title: '',
  content: '',
  file: null as File | null,
})
const editDraft = reactive({ title: '', content: '' })

onMounted(load)

/** 使用当前筛选读取知识文档分页。 */
async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const result = await knowledgeApi.listDocuments({
      status: status.value,
      keyword: keyword.value.trim(),
      spaceId: spaceId.value,
      page: page.value,
      size: size.value,
    })
    items.value = result.items
    totalElements.value = result.totalElements
    totalPages.value = result.totalPages
  } catch (caught) {
    error.value = asApiError(caught, '无法读取知识文档')
  } finally {
    loading.value = false
  }
}

/** 应用知识筛选并回到第一页。 */
async function applyFilters(): Promise<void> {
  if (keyword.value.trim().length > 160) {
    ElMessage.warning('关键词最多 160 个字符')
    return
  }
  page.value = 1
  await load()
}

/** 清空知识筛选。 */
async function clearFilters(): Promise<void> {
  status.value = null
  keyword.value = ''
  spaceId.value = null
  page.value = 1
  await load()
}

/** 切换列表分页。 */
async function changePage(nextPage: number, nextSize = size.value): Promise<void> {
  page.value = nextPage
  size.value = nextSize
  await load()
}

/** 打开知识导入表单并固定创建幂等键。 */
function openCreate(): void {
  Object.assign(createDraft, { spaceId: null, title: '', content: '', file: null })
  createMode.value = 'text'
  createKey.value = crypto.randomUUID()
  createVisible.value = true
}

/** 接收浏览器选择的单个文件。 */
function chooseFile(event: Event): void {
  const file = (event.target as HTMLInputElement).files?.[0] || null
  createDraft.file = file
}

/** 校验并创建文本或文件草稿，失败时保留表单与幂等键。 */
async function submitCreate(): Promise<void> {
  if (!createDraft.spaceId) {
    ElMessage.warning('请选择知识空间')
    return
  }
  const title = createDraft.title.trim()
  if (title.length > 160) {
    ElMessage.warning('标题最多 160 个字符')
    return
  }
  if (
    createMode.value === 'text' &&
    (!title || !createDraft.content.trim() || createDraft.content.length > 1_048_576)
  ) {
    ElMessage.warning('文本导入需要标题和不超过 1 MiB 的正文')
    return
  }
  if (createMode.value === 'file' && !validFile(createDraft.file)) return
  const key = createKey.value || crypto.randomUUID()
  createKey.value = key
  saving.value = true
  try {
    const created =
      createMode.value === 'text'
        ? await knowledgeApi.createTextDocument({
            spaceId: createDraft.spaceId,
            title,
            content: createDraft.content,
            idempotencyKey: key,
          })
        : await knowledgeApi.uploadDocument({
            spaceId: createDraft.spaceId,
            title,
            file: createDraft.file as File,
            idempotencyKey: key,
          })
    createVisible.value = false
    createKey.value = null
    ElMessage.success('知识草稿已创建')
    await openDetails(created.documentId)
    await load()
  } catch (caught) {
    if (!hasUncertainOutcome(caught)) createKey.value = crypto.randomUUID()
    showMutationError(caught, '创建知识草稿失败')
  } finally {
    saving.value = false
  }
}

/** 读取文档详情并初始化可编辑草稿。 */
async function openDetails(documentId: string): Promise<void> {
  loading.value = true
  try {
    details.value = await knowledgeApi.getDocument(documentId)
    editDraft.title = details.value.title
    editDraft.content = details.value.rawContent
    detailsVisible.value = true
  } catch (caught) {
    ElMessage.error(asApiError(caught, '读取知识详情失败').message)
  } finally {
    loading.value = false
  }
}

/** 按当前版本保存草稿或索引失败文档。 */
async function saveDraft(): Promise<void> {
  if (!details.value) return
  const title = editDraft.title.trim()
  if (
    !title ||
    title.length > 160 ||
    !editDraft.content.trim() ||
    editDraft.content.length > 1_048_576
  ) {
    ElMessage.warning('标题和正文长度不符合要求')
    return
  }
  saving.value = true
  try {
    details.value = await knowledgeApi.reviseDocument(
      details.value.documentId,
      title,
      editDraft.content,
      details.value.version,
    )
    ElMessage.success('知识草稿已保存')
    await load()
  } catch (caught) {
    showMutationError(caught, '保存知识草稿失败')
  } finally {
    saving.value = false
  }
}

/** 二次确认后发起文档发布，复用不确定请求的幂等键。 */
async function publish(): Promise<void> {
  if (!details.value) return
  if (!(await confirmAction('发布后将异步生成向量并进入检索，确认继续？', '发布知识'))) return
  const current = details.value
  const actionId = `publish:${current.documentId}:${current.version}`
  const key = actionKeys.get(actionId) || crypto.randomUUID()
  actionKeys.set(actionId, key)
  saving.value = true
  try {
    const result = await knowledgeApi.publishDocument(current.documentId, current.version, key)
    actionKeys.delete(actionId)
    details.value = result.document
    ElMessage.success(`发布任务 ${result.taskId} 已创建`)
    await load()
  } catch (caught) {
    if (!hasUncertainOutcome(caught)) actionKeys.delete(actionId)
    showMutationError(caught, '发布知识失败')
  } finally {
    saving.value = false
  }
}

/** 输入归档原因并归档已发布文档。 */
async function archive(): Promise<void> {
  if (!details.value) return
  let reason: string
  try {
    const result = await ElMessageBox.prompt(
      '归档会异步移除检索分块，请填写 1～500 字符原因。',
      '归档知识',
      {
        inputPattern: /^.{1,500}$/s,
        inputErrorMessage: '归档原因必须为 1～500 个字符',
        confirmButtonText: '确认归档',
        type: 'warning',
      },
    )
    reason = result.value.trim()
  } catch {
    return
  }
  const current = details.value
  const actionId = `archive:${current.documentId}:${current.version}`
  const key = actionKeys.get(actionId) || crypto.randomUUID()
  actionKeys.set(actionId, key)
  saving.value = true
  try {
    const result = await knowledgeApi.archiveDocument(
      current.documentId,
      current.version,
      reason,
      key,
    )
    actionKeys.delete(actionId)
    details.value = result.document
    ElMessage.success(`归档任务 ${result.taskId} 已创建`)
    await load()
  } catch (caught) {
    if (!hasUncertainOutcome(caught)) actionKeys.delete(actionId)
    showMutationError(caught, '归档知识失败')
  } finally {
    saving.value = false
  }
}

/** 二次确认后删除从未发布的草稿。 */
async function deleteDraft(): Promise<void> {
  if (!details.value || !(await confirmAction('删除草稿后无法恢复，确认继续？', '删除知识草稿')))
    return
  saving.value = true
  try {
    await knowledgeApi.deleteDocumentDraft(details.value.documentId, details.value.version)
    detailsVisible.value = false
    details.value = null
    ElMessage.success('知识草稿已删除')
    await load()
  } catch (caught) {
    showMutationError(caught, '删除知识草稿失败')
  } finally {
    saving.value = false
  }
}

/** 校验文件类型和 1 MiB 大小上限。 */
function validFile(file: File | null): file is File {
  if (!file) {
    ElMessage.warning('请选择一个 Markdown 或 TXT 文件')
    return false
  }
  const extension = file.name.toLowerCase().split('.').pop()
  if (!['md', 'markdown', 'txt'].includes(extension || '') || file.size > 1_048_576) {
    ElMessage.warning('只允许不超过 1 MiB 的 .md、.markdown 或 .txt 文件')
    return false
  }
  return true
}

/** 显示确认框；取消时返回 false。 */
async function confirmAction(message: string, title: string): Promise<boolean> {
  try {
    await ElMessageBox.confirm(message, title, { type: 'warning', confirmButtonText: '确认' })
    return true
  } catch {
    return false
  }
}

/** 对版本冲突明确说明输入仍被保留。 */
function showMutationError(value: unknown, fallback: string): void {
  const issue = asApiError(value, fallback)
  ElMessage.error(
    issue.status === 409 ? `${issue.message}；当前输入已保留，请刷新详情后重试` : issue.message,
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
        <h1>知识治理</h1>
        <p class="muted-copy">导入、审核并管理进入检索索引的托管知识。</p>
      </div>
      <el-button type="primary" @click="openCreate">导入知识</el-button>
    </header>
    <form class="filter-bar admin-filter admin-filter--wide" @submit.prevent="applyFilters">
      <label for="doc-status">状态</label
      ><select id="doc-status" v-model="status">
        <option :value="null">全部</option>
        <option
          v-for="value in ['DRAFT', 'INDEXING', 'PUBLISHED', 'FAILED', 'ARCHIVED']"
          :key="value"
          :value="value"
        >
          {{ value }}
        </option></select
      ><label for="doc-keyword">关键词</label
      ><el-input id="doc-keyword" v-model="keyword" maxlength="160" /><KnowledgeSpaceSelect
        v-model="spaceId"
        input-id="doc-space-filter"
        label="知识空间（可选）"
      /><el-button native-type="submit" type="primary">查询</el-button
      ><el-button @click="clearFilters">清空</el-button>
    </form>
    <LoadingState v-if="loading" title="正在读取知识文档" /><ErrorState
      v-else-if="error"
      :message="error.message"
      :trace-id="error.traceId"
      :retryable="error.retryable"
      @retry="load"
    /><EmptyState
      v-else-if="items.length === 0"
      title="暂无知识文档"
      description="可以导入直接文本或单个 Markdown/TXT 文件。"
    />
    <div v-else class="admin-table-wrap">
      <table class="admin-table">
        <thead>
          <tr>
            <th>标题</th>
            <th>空间</th>
            <th>输入类型</th>
            <th>状态</th>
            <th>版本</th>
            <th>更新时间</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="item in items"
            :key="item.documentId"
            class="clickable-row"
            tabindex="0"
            :aria-label="`查看知识：${item.title}`"
            @click="openDetails(item.documentId)"
            @keydown.enter="openDetails(item.documentId)"
            @keydown.space.prevent="openDetails(item.documentId)"
          >
            <td>
              <strong>{{ item.title }}</strong
              ><small>#{{ item.documentId }}</small>
            </td>
            <td>
              {{ item.space.name }}<small>{{ item.space.code }}</small>
            </td>
            <td>{{ item.inputType }}</td>
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
        aria-label="知识每页数量"
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
    <el-dialog v-model="createVisible" title="导入知识草稿" width="760px"
      ><form id="knowledge-create-form" class="admin-form" @submit.prevent="submitCreate">
        <label for="knowledge-mode">导入方式</label
        ><select id="knowledge-mode" v-model="createMode">
          <option value="text">直接文本</option>
          <option value="file">Markdown/TXT 文件</option></select
        ><KnowledgeSpaceSelect
          v-model="createDraft.spaceId"
          input-id="knowledge-create-space"
          label="知识空间（必选）"
        /><label for="knowledge-title"
          >标题{{ createMode === 'text' ? '（必填）' : '（可选，留空使用文件名）' }}</label
        ><el-input
          id="knowledge-title"
          v-model="createDraft.title"
          maxlength="160"
          show-word-limit
        /><template v-if="createMode === 'text'"
          ><label for="knowledge-content">正文（最大 1 MiB）</label
          ><el-input
            id="knowledge-content"
            v-model="createDraft.content"
            type="textarea"
            :rows="10" /></template
        ><template v-else
          ><label for="knowledge-file">单个文件（.md、.markdown、.txt，最大 1 MiB）</label
          ><input
            id="knowledge-file"
            type="file"
            accept=".md,.markdown,.txt,text/markdown,text/plain"
            @change="chooseFile"
        /></template>
      </form>
      <template #footer
        ><el-button @click="createVisible = false">取消</el-button
        ><el-button
          type="primary"
          :loading="saving"
          native-type="submit"
          form="knowledge-create-form"
          >创建草稿</el-button
        ></template
      ></el-dialog
    >
    <el-dialog v-model="detailsVisible" :title="details?.title || '知识详情'" width="900px"
      ><template v-if="details"
        ><dl class="admin-details">
          <div>
            <dt>空间</dt>
            <dd>{{ details.space.name }}（{{ details.space.code }}）</dd>
          </div>
          <div>
            <dt>状态 / 版本</dt>
            <dd>{{ details.status }} / {{ details.version }}</dd>
          </div>
          <div>
            <dt>内容哈希</dt>
            <dd>{{ details.contentHash }}</dd>
          </div>
          <div>
            <dt>发布时间</dt>
            <dd>{{ localTime(details.publishedAt) }}</dd>
          </div>
          <div v-if="details.indexFailureReason">
            <dt>索引失败</dt>
            <dd>{{ details.indexFailureReason }}</dd>
          </div>
          <div v-if="details.archiveReason">
            <dt>归档原因</dt>
            <dd>{{ details.archiveReason }}</dd>
          </div>
        </dl>
        <form class="admin-form" @submit.prevent="saveDraft">
          <label for="knowledge-edit-title">标题</label
          ><el-input
            id="knowledge-edit-title"
            v-model="editDraft.title"
            maxlength="160"
            :disabled="!['DRAFT', 'FAILED'].includes(details.status)"
          /><label for="knowledge-edit-content">正文</label
          ><el-input
            id="knowledge-edit-content"
            v-model="editDraft.content"
            type="textarea"
            :rows="12"
            :disabled="!['DRAFT', 'FAILED'].includes(details.status)"
          />
          <div class="form-actions">
            <el-button
              v-if="['DRAFT', 'FAILED'].includes(details.status)"
              :loading="saving"
              native-type="submit"
              >保存草稿</el-button
            ><el-button
              v-if="['DRAFT', 'FAILED'].includes(details.status)"
              type="primary"
              :loading="saving"
              @click="publish"
              >发布</el-button
            ><el-button
              v-if="details.status === 'PUBLISHED'"
              type="danger"
              plain
              :loading="saving"
              @click="archive"
              >归档</el-button
            ><el-button
              v-if="details.status === 'DRAFT'"
              type="danger"
              plain
              :loading="saving"
              @click="deleteDraft"
              >删除草稿</el-button
            >
          </div>
        </form></template
      ></el-dialog
    >
  </section>
</template>
