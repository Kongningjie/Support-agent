<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as userApi from '@/api/user-admin.api'
import { hasUncertainOutcome } from '@/api/idempotency'
import EmptyState from '@/components/feedback/EmptyState.vue'
import ErrorState from '@/components/feedback/ErrorState.vue'
import LoadingState from '@/components/feedback/LoadingState.vue'
import { ApiError } from '@/types/api.types'
import type { AdminUserView, UserRole, UserStatus } from '@/types/user-admin.types'

const items = ref<AdminUserView[]>([])
const role = ref<UserRole | null>(null)
const status = ref<UserStatus | null>(null)
const page = ref(1)
const size = ref(20)
const totalElements = ref(0)
const totalPages = ref(0)
const loading = ref(false)
const saving = ref(false)
const error = ref<ApiError | null>(null)
const createVisible = ref(false)
const createKey = ref<string | null>(null)
const createDraft = reactive<{
  username: string
  displayName: string
  password: string
  role: UserRole
}>({
  username: '',
  displayName: '',
  password: '',
  role: 'USER',
})

onMounted(load)

/** 使用当前筛选读取用户分页。 */
async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const result = await userApi.listUsers({
      role: role.value,
      status: status.value,
      page: page.value,
      size: size.value,
    })
    items.value = result.items
    totalElements.value = result.totalElements
    totalPages.value = result.totalPages
  } catch (caught) {
    error.value = asApiError(caught, '无法读取用户列表')
  } finally {
    loading.value = false
  }
}

/** 筛选变化后回到第一页。 */
async function applyFilters(): Promise<void> {
  page.value = 1
  await load()
}

/** 切换分页并重新读取后端事实。 */
async function changePage(nextPage: number, nextSize = size.value): Promise<void> {
  page.value = nextPage
  size.value = nextSize
  await load()
}

/** 打开创建用户表单并为本次动作固定幂等键。 */
function openCreate(): void {
  Object.assign(createDraft, { username: '', displayName: '', password: '', role: 'USER' })
  createKey.value = crypto.randomUUID()
  createVisible.value = true
}

/** 校验并创建本地用户，失败时保留表单和幂等键。 */
async function submitCreate(): Promise<void> {
  const username = createDraft.username.trim()
  const displayName = createDraft.displayName.trim()
  if (!username || username.length > 64 || !displayName || displayName.length > 100) {
    ElMessage.warning('用户名需为 1～64 字符，展示名称需为 1～100 字符')
    return
  }
  if (createDraft.password.length < 12 || createDraft.password.length > 72) {
    ElMessage.warning('初始密码必须为 12～72 个字符')
    return
  }
  const key = createKey.value || crypto.randomUUID()
  createKey.value = key
  saving.value = true
  try {
    await userApi.createUser({ ...createDraft, username, displayName, idempotencyKey: key })
    createVisible.value = false
    createKey.value = null
    ElMessage.success('用户已创建，请通过安全渠道交付初始密码')
    await load()
  } catch (caught) {
    if (!hasUncertainOutcome(caught)) createKey.value = crypto.randomUUID()
    ElMessage.error(asApiError(caught, '创建用户失败').message)
  } finally {
    saving.value = false
  }
}

/** 修改用户角色并以服务端响应替换当前行。 */
async function changeRole(user: AdminUserView, nextRole: UserRole): Promise<void> {
  if (user.role === nextRole) return
  await runUserMutation(
    () => userApi.changeUserRole(user.userId, nextRole, user.version),
    '角色已更新',
  )
}

/** 二次确认后启用或禁用用户。 */
async function toggleStatus(user: AdminUserView): Promise<void> {
  const next: UserStatus = user.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  const label = next === 'DISABLED' ? '禁用' : '启用'
  try {
    await ElMessageBox.confirm(
      next === 'DISABLED'
        ? '禁用后该用户的新请求会立即被拒绝，已有 Token 也不可继续使用。'
        : '确认重新启用该账号？',
      `${label}用户`,
      { type: 'warning', confirmButtonText: label },
    )
  } catch {
    return
  }
  await runUserMutation(
    () => userApi.changeUserStatus(user.userId, next, user.version),
    `用户已${label}`,
  )
}

/** 要求管理员输入一次性密码后执行重置。 */
async function resetPassword(user: AdminUserView): Promise<void> {
  let password: string
  try {
    const result = await ElMessageBox.prompt(
      '请输入 12～72 字符的一次性密码；操作后用户必须先改密。',
      '重置密码',
      {
        inputType: 'password',
        inputPattern: /^.{12,72}$/,
        inputErrorMessage: '密码必须为 12～72 个字符',
        confirmButtonText: '确认重置',
      },
    )
    password = result.value
  } catch {
    return
  }
  await runUserMutation(
    () => userApi.resetUserPassword(user.userId, password, user.version),
    '一次性密码已设置',
  )
}

/** 清除用户临时锁定，不改变禁用状态。 */
async function unlock(user: AdminUserView): Promise<void> {
  await runUserMutation(() => userApi.unlockUser(user.userId, user.version), '临时锁定已解除')
}

/** 二次确认后撤销目标用户全部 Token。 */
async function revokeTokens(user: AdminUserView): Promise<void> {
  try {
    await ElMessageBox.confirm('该用户所有设备都会退出登录，此操作不可撤销。', '撤销全部 Token', {
      type: 'warning',
      confirmButtonText: '确认撤销',
    })
  } catch {
    return
  }
  saving.value = true
  try {
    await userApi.revokeAllUserTokens(user.userId)
    ElMessage.success('全部 Token 已撤销')
  } catch (caught) {
    ElMessage.error(asApiError(caught, '撤销 Token 失败').message)
  } finally {
    saving.value = false
  }
}

/** 执行一个返回最新用户视图的写操作并刷新列表。 */
async function runUserMutation(
  action: () => Promise<AdminUserView>,
  success: string,
): Promise<void> {
  saving.value = true
  try {
    await action()
    ElMessage.success(success)
    await load()
  } catch (caught) {
    ElMessage.error(asApiError(caught, '用户治理操作失败').message)
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
        <h1>用户治理</h1>
        <p class="muted-copy">管理本地账号、角色、状态和认证会话。</p>
      </div>
      <el-button type="primary" @click="openCreate">创建用户</el-button>
    </header>
    <form class="filter-bar admin-filter" @submit.prevent="applyFilters">
      <label for="user-role">角色</label>
      <select id="user-role" v-model="role">
        <option :value="null">全部</option>
        <option value="USER">用户</option>
        <option value="ADMIN">管理员</option>
      </select>
      <label for="user-status">状态</label>
      <select id="user-status" v-model="status">
        <option :value="null">全部</option>
        <option value="ACTIVE">活动</option>
        <option value="DISABLED">已禁用</option>
      </select>
      <el-button native-type="submit" type="primary">查询</el-button>
    </form>
    <LoadingState v-if="loading" title="正在读取用户" />
    <ErrorState
      v-else-if="error"
      :message="error.message"
      :trace-id="error.traceId"
      @retry="load"
    />
    <EmptyState
      v-else-if="items.length === 0"
      title="暂无用户"
      description="可以创建第一个业务用户。"
    />
    <div v-else class="admin-table-wrap">
      <table class="admin-table">
        <thead>
          <tr>
            <th>用户</th>
            <th>角色</th>
            <th>状态</th>
            <th>账号安全</th>
            <th>更新时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="user in items" :key="user.userId">
            <td>
              <strong>{{ user.displayName }}</strong
              ><small>{{ user.username }}</small>
            </td>
            <td>
              <select
                :value="user.role"
                :disabled="saving"
                :aria-label="`${user.username} 的角色`"
                @change="changeRole(user, ($event.target as HTMLSelectElement).value as UserRole)"
              >
                <option value="USER">用户</option>
                <option value="ADMIN">管理员</option>
              </select>
            </td>
            <td>
              <span class="status-chip">{{ user.status === 'ACTIVE' ? '活动' : '已禁用' }}</span>
            </td>
            <td>
              <small v-if="user.mustChangePassword">等待强制改密</small
              ><small v-else-if="user.lockedUntil">锁定至 {{ localTime(user.lockedUntil) }}</small
              ><small v-else>正常</small>
            </td>
            <td>{{ localTime(user.updatedAt) }}</td>
            <td>
              <div class="table-actions">
                <el-button size="small" @click="toggleStatus(user)">{{
                  user.status === 'ACTIVE' ? '禁用' : '启用'
                }}</el-button
                ><el-button size="small" @click="resetPassword(user)">重置密码</el-button
                ><el-button size="small" :disabled="!user.lockedUntil" @click="unlock(user)"
                  >解锁</el-button
                ><el-button size="small" type="danger" plain @click="revokeTokens(user)"
                  >撤销 Token</el-button
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
        aria-label="用户每页数量"
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
    <el-dialog v-model="createVisible" title="创建本地用户" width="620px"
      ><form id="user-create-form" class="admin-form" @submit.prevent="submitCreate">
        <label for="new-username">登录用户名（1～64 字符）</label
        ><el-input id="new-username" v-model="createDraft.username" maxlength="64" /><label
          for="new-display-name"
          >展示名称（1～100 字符）</label
        ><el-input id="new-display-name" v-model="createDraft.displayName" maxlength="100" /><label
          for="new-password"
          >初始密码（12～72 字符）</label
        ><el-input
          id="new-password"
          v-model="createDraft.password"
          type="password"
          maxlength="72"
          show-password
        /><label for="new-role">角色</label
        ><select id="new-role" v-model="createDraft.role">
          <option value="USER">用户</option>
          <option value="ADMIN">管理员</option>
        </select>
      </form>
      <template #footer
        ><el-button :disabled="saving" @click="createVisible = false">取消</el-button
        ><el-button type="primary" :loading="saving" native-type="submit" form="user-create-form"
          >创建</el-button
        ></template
      ></el-dialog
    >
  </section>
</template>
