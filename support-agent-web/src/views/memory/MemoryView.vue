<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as memoryApi from '@/api/memory.api'
import EmptyState from '@/components/feedback/EmptyState.vue'
import ErrorState from '@/components/feedback/ErrorState.vue'
import LoadingState from '@/components/feedback/LoadingState.vue'
import { ApiError } from '@/types/api.types'
import type { MemorySettings, MemoryStatus, UserMemory } from '@/types/memory.types'

const settings = ref<MemorySettings | null>(null)
const memories = ref<UserMemory[]>([])
const status = ref<MemoryStatus | null>(null)
const page = ref(1)
const totalPages = ref(0)
const loading = ref(false)
const saving = ref(false)
const error = ref<ApiError | null>(null)
const editingId = ref<string | null>(null)
const edit = reactive({ content: '', pinned: false, expiresAt: '' })

onMounted(load)

/** 同时读取记忆设置与当前筛选页。 */
async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const [nextSettings, result] = await Promise.all([
      memoryApi.getMemorySettings(),
      memoryApi.listMemories(status.value, page.value, 20),
    ])
    settings.value = nextSettings
    memories.value = result.items
    totalPages.value = result.totalPages
  } catch (caught) {
    error.value = asApiError(caught, '无法读取长期记忆')
  } finally {
    loading.value = false
  }
}

/** 用户显式切换长期记忆生成与注入能力。 */
async function toggleSettings(): Promise<void> {
  if (!settings.value || saving.value) return
  saving.value = true
  const expected = settings.value
  try {
    settings.value = await memoryApi.updateMemorySettings(
      !expected.enabled,
      expected.version,
      crypto.randomUUID(),
    )
    ElMessage.success(settings.value.enabled ? '长期记忆已启用' : '长期记忆已关闭')
  } catch (caught) {
    ElMessage.error(asApiError(caught, '设置更新失败').message)
    if (caught instanceof ApiError && caught.status === 409) await load()
  } finally {
    saving.value = false
  }
}

/** 切换状态筛选后从第一页重新加载。 */
async function filter(nextStatus: string): Promise<void> {
  status.value = nextStatus === '' ? null : (nextStatus as MemoryStatus)
  page.value = 1
  await load()
}

/** 确认模型产生的候选记忆。 */
async function confirm(memory: UserMemory): Promise<void> {
  await mutate(memory, (key) => memoryApi.confirmMemory(memory, key), '候选已确认')
}

/** 打开内联编辑器，并把 UTC 时间转换为本地 datetime-local 值。 */
function beginEdit(memory: UserMemory): void {
  editingId.value = memory.memoryId
  edit.content = memory.content
  edit.pinned = memory.pinned
  edit.expiresAt = memory.expiresAt ? toLocalInput(memory.expiresAt) : ''
}

/** 提交正文、固定状态和可选失效时间更正。 */
async function saveEdit(memory: UserMemory): Promise<void> {
  const content = edit.content.trim()
  if (!content || content.length > 500) {
    ElMessage.warning('记忆正文必须为 1～500 个字符')
    return
  }
  const expiresAt = edit.expiresAt ? new Date(edit.expiresAt).toISOString() : null
  if (expiresAt && new Date(expiresAt).getTime() <= Date.now()) {
    ElMessage.warning('失效时间必须晚于当前时间')
    return
  }
  const succeeded = await mutate(
    memory,
    (key) =>
      memoryApi.reviseMemory(
        memory.memoryId,
        {
          content,
          expiresAt,
          clearExpiresAt: !edit.expiresAt,
          pinned: edit.pinned,
          expectedVersion: memory.version,
        },
        key,
      ),
    '记忆已更新',
  )
  if (succeeded) editingId.value = null
}

/** 二次确认后撤销有效记忆的注入资格。 */
async function revoke(memory: UserMemory): Promise<void> {
  try {
    await ElMessageBox.confirm('撤销后该记忆将立即停止注入对话上下文。是否继续？', '撤销记忆', {
      confirmButtonText: '确认撤销',
      cancelButtonText: '取消',
      type: 'warning',
    })
    await mutate(memory, (key) => memoryApi.revokeMemory(memory, key), '记忆已撤销')
  } catch (caught) {
    if (caught !== 'cancel' && caught !== 'close') throw caught
  }
}

/** 二次确认后永久删除正文与审计字段。 */
async function remove(memory: UserMemory): Promise<void> {
  try {
    await ElMessageBox.confirm('永久删除后记忆正文无法恢复。是否继续？', '永久删除记忆', {
      confirmButtonText: '永久删除',
      cancelButtonText: '取消',
      type: 'error',
    })
    saving.value = true
    await memoryApi.deleteMemory(memory, crypto.randomUUID())
    memories.value = memories.value.filter((item) => item.memoryId !== memory.memoryId)
    ElMessage.success('记忆已永久删除')
  } catch (caught) {
    if (caught === 'cancel' || caught === 'close') return
    ElMessage.error(asApiError(caught, '记忆删除失败').message)
    if (caught instanceof ApiError && caught.status === 409) await load()
  } finally {
    saving.value = false
  }
}

/** 执行返回新版本记忆的写操作，并统一处理并发冲突。 */
async function mutate(
  memory: UserMemory,
  operation: (idempotencyKey: string) => Promise<UserMemory>,
  successMessage: string,
): Promise<boolean> {
  if (saving.value) return false
  saving.value = true
  try {
    const updated = await operation(crypto.randomUUID())
    const index = memories.value.findIndex((item) => item.memoryId === memory.memoryId)
    if (index >= 0) memories.value[index] = updated
    ElMessage.success(successMessage)
    return true
  } catch (caught) {
    ElMessage.error(asApiError(caught, '记忆操作失败').message)
    if (caught instanceof ApiError && caught.status === 409) await load()
    return false
  } finally {
    saving.value = false
  }
}

/** 切换当前分页。 */
async function changePage(next: number): Promise<void> {
  page.value = next
  await load()
}

/** 返回记忆类型中文说明。 */
function typeLabel(memory: UserMemory): string {
  return { PREFERENCE: '偏好', CONSTRAINT: '约束', ENVIRONMENT: '环境' }[memory.memoryType]
}

/** 返回记忆状态中文说明。 */
function statusLabel(memory: UserMemory): string {
  return { PROPOSED: '待确认', ACTIVE: '已生效', REVOKED: '已撤销' }[memory.status]
}

/** 使用浏览器本地时区展示 UTC 时间。 */
function localTime(value: string | null): string {
  return value ? new Date(value).toLocaleString('zh-CN') : '长期有效'
}

/** 把 UTC 时间转换为 datetime-local 控件值。 */
function toLocalInput(value: string): string {
  const date = new Date(value)
  const offset = date.getTimezoneOffset() * 60_000
  return new Date(date.getTime() - offset).toISOString().slice(0, 16)
}

/** 将未知异常转换为页面可安全展示的错误。 */
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
        <p class="eyebrow">Long-term memory</p>
        <h1>长期记忆</h1>
        <p class="muted-copy">只有你确认的记忆才会在预算内进入后续对话；默认关闭。</p>
      </div>
      <div v-if="settings" class="memory-setting">
        <div>
          <strong>{{ settings.enabled ? '长期记忆已启用' : '长期记忆已关闭' }}</strong>
          <span>设置版本 {{ settings.version }}</span>
        </div>
        <el-button
          :type="settings.enabled ? 'danger' : 'primary'"
          plain
          :loading="saving"
          @click="toggleSettings"
        >
          {{ settings.enabled ? '关闭' : '启用' }}
        </el-button>
      </div>
    </header>

    <div class="filter-bar">
      <label for="memory-status">状态筛选</label>
      <select
        id="memory-status"
        :value="status || ''"
        @change="filter(($event.target as HTMLSelectElement).value)"
      >
        <option value="">全部状态</option>
        <option value="PROPOSED">待确认</option>
        <option value="ACTIVE">已生效</option>
        <option value="REVOKED">已撤销</option>
      </select>
    </div>

    <LoadingState v-if="loading" title="正在读取长期记忆" />
    <ErrorState
      v-else-if="error"
      :message="error.message"
      :trace-id="error.traceId"
      @retry="load"
    />
    <EmptyState
      v-else-if="memories.length === 0"
      title="暂无长期记忆"
      description="启用后，模型只能提出候选；仍需你确认才会生效。"
    />
    <div v-else class="memory-list">
      <article v-for="memory in memories" :key="memory.memoryId" class="memory-card">
        <div class="memory-card__header">
          <div>
            <span class="status-chip">{{ typeLabel(memory) }}</span>
            <strong>{{ statusLabel(memory) }}</strong>
            <span v-if="memory.pinned">已固定</span>
          </div>
          <small>版本 {{ memory.version }}</small>
        </div>

        <form
          v-if="editingId === memory.memoryId"
          class="memory-editor"
          @submit.prevent="saveEdit(memory)"
        >
          <label :for="`memory-content-${memory.memoryId}`">记忆正文（1～500 字符）</label>
          <el-input
            :id="`memory-content-${memory.memoryId}`"
            v-model="edit.content"
            type="textarea"
            maxlength="500"
            show-word-limit
            :rows="3"
          />
          <label :for="`memory-expiry-${memory.memoryId}`">失效时间（留空表示长期有效）</label>
          <input
            :id="`memory-expiry-${memory.memoryId}`"
            v-model="edit.expiresAt"
            type="datetime-local"
            :min="toLocalInput(new Date(Date.now() + 60_000).toISOString())"
          />
          <label class="check-field"
            ><input v-model="edit.pinned" type="checkbox" />固定并优先注入</label
          >
          <div class="heading-actions">
            <el-button @click="editingId = null">取消</el-button>
            <el-button type="primary" native-type="submit" :loading="saving">保存更正</el-button>
          </div>
        </form>
        <template v-else>
          <p class="memory-card__content">{{ memory.content }}</p>
          <dl class="memory-meta">
            <div>
              <dt>失效时间</dt>
              <dd>{{ localTime(memory.expiresAt) }}</dd>
            </div>
            <div>
              <dt>最近更新</dt>
              <dd>{{ localTime(memory.updatedAt) }}</dd>
            </div>
            <div>
              <dt>来源会话</dt>
              <dd>{{ memory.sourceConversationId.slice(0, 8) }}</dd>
            </div>
          </dl>
          <div class="heading-actions">
            <el-button
              v-if="memory.status === 'PROPOSED'"
              type="primary"
              :loading="saving"
              @click="confirm(memory)"
              >确认候选</el-button
            >
            <el-button
              v-if="memory.status !== 'REVOKED'"
              :disabled="saving"
              @click="beginEdit(memory)"
              >更正</el-button
            >
            <el-button v-if="memory.status === 'ACTIVE'" :disabled="saving" @click="revoke(memory)"
              >撤销</el-button
            >
            <el-button type="danger" plain :disabled="saving" @click="remove(memory)"
              >永久删除</el-button
            >
          </div>
        </template>
      </article>
    </div>

    <nav v-if="totalPages > 1" class="pager" aria-label="长期记忆分页">
      <el-button :disabled="page <= 1" @click="changePage(page - 1)">上一页</el-button>
      <span>第 {{ page }} / {{ totalPages }} 页</span>
      <el-button :disabled="page >= totalPages" @click="changePage(page + 1)">下一页</el-button>
    </nav>
  </section>
</template>
