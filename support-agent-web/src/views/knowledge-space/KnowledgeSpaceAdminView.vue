<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as spaceApi from '@/api/knowledge-space-admin.api'
import * as userApi from '@/api/user-admin.api'
import { hasUncertainOutcome } from '@/api/idempotency'
import EmptyState from '@/components/feedback/EmptyState.vue'
import ErrorState from '@/components/feedback/ErrorState.vue'
import LoadingState from '@/components/feedback/LoadingState.vue'
import { ApiError } from '@/types/api.types'
import type {
  KnowledgeSpaceStatus,
  KnowledgeSpaceView,
  KnowledgeSpaceVisibility,
  SpaceMembershipView,
  SpaceRole,
} from '@/types/knowledge-space.types'
import type { AdminUserView } from '@/types/user-admin.types'

const spaces = ref<KnowledgeSpaceView[]>([])
const status = ref<KnowledgeSpaceStatus | null>(null)
const visibility = ref<KnowledgeSpaceVisibility | null>(null)
const keyword = ref('')
const page = ref(1)
const size = ref(20)
const totalElements = ref(0)
const totalPages = ref(0)
const loading = ref(false)
const saving = ref(false)
const error = ref<ApiError | null>(null)
const selected = ref<KnowledgeSpaceView | null>(null)
const createVisible = ref(false)
const editVisible = ref(false)
const membersVisible = ref(false)
const createKey = ref<string | null>(null)
const editKey = ref<string | null>(null)
const createDraft = reactive({ code: '', name: '', description: '' })
const editDraft = reactive<{
  name: string
  description: string
  visibility: KnowledgeSpaceVisibility
}>({
  name: '',
  description: '',
  visibility: 'RESTRICTED',
})
const members = ref<SpaceMembershipView[]>([])
const memberPage = ref(1)
const memberTotalPages = ref(0)
const memberTotal = ref(0)
const candidateUsers = ref<AdminUserView[]>([])
const memberDraft = reactive<{ userId: string; role: SpaceRole }>({ userId: '', role: 'READER' })
const actionKeys = new Map<string, string>()

const activeManagerCount = computed(
  () =>
    members.value.filter((member) => member.status === 'ACTIVE' && member.role === 'MANAGER')
      .length,
)

onMounted(load)

/** 使用当前筛选读取管理员可见空间。 */
async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const result = await spaceApi.listSpaces({
      status: status.value,
      visibility: visibility.value,
      keyword: keyword.value.trim(),
      page: page.value,
      size: size.value,
    })
    spaces.value = result.items
    totalElements.value = result.totalElements
    totalPages.value = result.totalPages
  } catch (caught) {
    error.value = asApiError(caught, '无法读取知识空间')
  } finally {
    loading.value = false
  }
}

/** 应用筛选并回到第一页。 */
async function applyFilters(): Promise<void> {
  if (keyword.value.trim().length > 100) {
    ElMessage.warning('关键词最多 100 个字符')
    return
  }
  page.value = 1
  await load()
}

/** 清空空间筛选。 */
async function clearFilters(): Promise<void> {
  status.value = null
  visibility.value = null
  keyword.value = ''
  page.value = 1
  await load()
}

/** 切换空间分页。 */
async function changePage(nextPage: number, nextSize = size.value): Promise<void> {
  page.value = nextPage
  size.value = nextSize
  await load()
}

/** 打开空间创建表单并固定幂等键。 */
function openCreate(): void {
  Object.assign(createDraft, { code: '', name: '', description: '' })
  createKey.value = crypto.randomUUID()
  createVisible.value = true
}

/** 创建默认受限空间，失败时保留输入和幂等键。 */
async function submitCreate(): Promise<void> {
  const code = createDraft.code.trim()
  const name = createDraft.name.trim()
  if (
    !code ||
    code.length > 64 ||
    !name ||
    name.length > 100 ||
    createDraft.description.trim().length > 500
  ) {
    ElMessage.warning('空间代码、名称或说明长度不符合要求')
    return
  }
  const key = createKey.value || crypto.randomUUID()
  createKey.value = key
  saving.value = true
  try {
    await spaceApi.createSpace(
      { code, name, description: createDraft.description.trim() || null },
      key,
    )
    createVisible.value = false
    createKey.value = null
    ElMessage.success('知识空间已创建')
    await load()
  } catch (caught) {
    if (!hasUncertainOutcome(caught)) createKey.value = crypto.randomUUID()
    showMutationError(caught, '创建知识空间失败')
  } finally {
    saving.value = false
  }
}

/** 打开普通空间编辑表单；系统空间保持只读。 */
function openEdit(space: KnowledgeSpaceView): void {
  if (space.systemSpace) return
  selected.value = space
  Object.assign(editDraft, {
    name: space.name,
    description: space.description || '',
    visibility: space.visibility,
  })
  editKey.value = crypto.randomUUID()
  editVisible.value = true
}

/** 按详情版本保存空间，409 时保留表单等待人工刷新。 */
async function submitEdit(): Promise<void> {
  if (!selected.value) return
  const name = editDraft.name.trim()
  if (!name || name.length > 100 || editDraft.description.trim().length > 500) {
    ElMessage.warning('空间名称需为 1～100 字符，说明最多 500 字符')
    return
  }
  const key = editKey.value || crypto.randomUUID()
  editKey.value = key
  saving.value = true
  try {
    selected.value = await spaceApi.updateSpace(
      selected.value.spaceId,
      {
        name,
        description: editDraft.description.trim() || null,
        visibility: editDraft.visibility,
        expectedVersion: selected.value.version,
      },
      key,
    )
    editVisible.value = false
    editKey.value = null
    ElMessage.success('空间信息已更新')
    await load()
  } catch (caught) {
    if (!hasUncertainOutcome(caught)) editKey.value = crypto.randomUUID()
    showMutationError(caught, '修改知识空间失败')
  } finally {
    saving.value = false
  }
}

/** 二次确认后停用或启用普通空间，并在不确定结果下复用幂等键。 */
async function toggleSpace(space: KnowledgeSpaceView): Promise<void> {
  if (space.systemSpace) return
  const action = space.status === 'ACTIVE' ? 'disable' : 'enable'
  const label = action === 'disable' ? '停用' : '启用'
  try {
    await ElMessageBox.confirm(
      action === 'disable'
        ? '停用后新会话、知识写入和检索将立即拒绝该空间，此操作影响所有成员。'
        : '启用后，符合权限的用户可重新选择该空间。',
      `${label}知识空间`,
      { type: 'warning', confirmButtonText: label },
    )
  } catch {
    return
  }
  const actionId = `space:${action}:${space.spaceId}:${space.version}`
  const key = actionKeys.get(actionId) || crypto.randomUUID()
  actionKeys.set(actionId, key)
  saving.value = true
  try {
    await spaceApi.changeSpaceStatus(space.spaceId, action, space.version, key)
    actionKeys.delete(actionId)
    ElMessage.success(`空间已${label}`)
    await load()
  } catch (caught) {
    if (!hasUncertainOutcome(caught)) actionKeys.delete(actionId)
    showMutationError(caught, `${label}知识空间失败`)
  } finally {
    saving.value = false
  }
}

/** 打开成员治理并加载真实成员和全部活动用户候选。 */
async function openMembers(space: KnowledgeSpaceView): Promise<void> {
  selected.value = space
  memberDraft.userId = ''
  memberDraft.role = space.systemSpace ? 'EDITOR' : 'READER'
  memberPage.value = 1
  membersVisible.value = true
  try {
    await Promise.all([loadMembers(), loadCandidateUsers()])
  } catch (caught) {
    ElMessage.error(asApiError(caught, '无法准备成员治理数据').message)
  }
}

/** 分页读取选中空间的成员。 */
async function loadMembers(): Promise<void> {
  if (!selected.value) return
  try {
    const result = await spaceApi.listMembers(selected.value.spaceId, memberPage.value, 20)
    members.value = result.items
    memberTotal.value = result.totalElements
    memberTotalPages.value = result.totalPages
  } catch (caught) {
    ElMessage.error(asApiError(caught, '无法读取空间成员').message)
  }
}

/** 切换成员分页并重新读取空间成员。 */
async function changeMemberPage(nextPage: number): Promise<void> {
  memberPage.value = nextPage
  await loadMembers()
}

/** 读取全部活动用户，作为新增成员的真实候选来源。 */
async function loadCandidateUsers(): Promise<void> {
  const first = await userApi.listUsers({ role: null, status: 'ACTIVE', page: 1, size: 100 })
  const all = [...first.items]
  for (let current = 2; current <= first.totalPages; current += 1) {
    const next = await userApi.listUsers({ role: null, status: 'ACTIVE', page: current, size: 100 })
    all.push(...next.items)
  }
  candidateUsers.value = all
}

/** 新增成员；GLOBAL 不允许创建普通 READER 关系。 */
async function addMember(): Promise<void> {
  if (!selected.value || !memberDraft.userId) {
    ElMessage.warning('请选择目标用户')
    return
  }
  if (selected.value.systemSpace && memberDraft.role === 'READER') {
    ElMessage.warning('GLOBAL 的读取权限是隐式的，不能创建普通 READER 成员')
    return
  }
  await mutateMember(memberDraft.userId, memberDraft.role, null, '成员已新增或恢复')
}

/** 修改成员角色，并提前阻止当前页可确认的最后一个 MANAGER 降级。 */
async function changeMemberRole(member: SpaceMembershipView, role: SpaceRole): Promise<void> {
  if (member.role === role) return
  if (selected.value?.systemSpace && role === 'READER') {
    ElMessage.warning('GLOBAL 不允许普通 READER 成员关系')
    return
  }
  if (isLastManager(member) && role !== 'MANAGER') {
    ElMessage.warning('受限空间必须保留至少一个活动 MANAGER')
    return
  }
  await mutateMember(member.userId, role, member.version, '成员角色已更新')
}

/** 二次确认后撤销成员关系。 */
async function revoke(member: SpaceMembershipView): Promise<void> {
  if (!selected.value || isLastManager(member)) {
    ElMessage.warning('受限空间必须保留至少一个活动 MANAGER')
    return
  }
  try {
    await ElMessageBox.confirm('撤销后该用户的新请求会立即失去该空间权限。', '撤销空间成员', {
      type: 'warning',
      confirmButtonText: '确认撤销',
    })
  } catch {
    return
  }
  const actionId = `member:revoke:${selected.value.spaceId}:${member.userId}:${member.version}`
  const key = actionKeys.get(actionId) || crypto.randomUUID()
  actionKeys.set(actionId, key)
  saving.value = true
  try {
    await spaceApi.revokeMember(selected.value.spaceId, member.userId, member.version, key)
    actionKeys.delete(actionId)
    ElMessage.success('成员关系已撤销')
    await loadMembers()
  } catch (caught) {
    if (!hasUncertainOutcome(caught)) actionKeys.delete(actionId)
    showMutationError(caught, '撤销成员失败')
  } finally {
    saving.value = false
  }
}

/** 新增、恢复或修改成员并在网络结果不确定时保留幂等键。 */
async function mutateMember(
  userId: string,
  role: SpaceRole,
  version: number | null,
  success: string,
): Promise<void> {
  if (!selected.value) return
  const actionId = `member:put:${selected.value.spaceId}:${userId}:${version ?? 'new'}:${role}`
  const key = actionKeys.get(actionId) || crypto.randomUUID()
  actionKeys.set(actionId, key)
  saving.value = true
  try {
    await spaceApi.putMember(selected.value.spaceId, userId, role, version, key)
    actionKeys.delete(actionId)
    memberDraft.userId = ''
    ElMessage.success(success)
    await loadMembers()
  } catch (caught) {
    if (!hasUncertainOutcome(caught)) actionKeys.delete(actionId)
    showMutationError(caught, '保存成员关系失败')
  } finally {
    saving.value = false
  }
}

/** 判断当前页内可明确识别的受限空间最后一个活动 MANAGER。 */
function isLastManager(member: SpaceMembershipView): boolean {
  return (
    selected.value?.visibility === 'RESTRICTED' &&
    memberTotalPages.value === 1 &&
    member.status === 'ACTIVE' &&
    member.role === 'MANAGER' &&
    activeManagerCount.value <= 1
  )
}

/** 对 409 给出保留输入和刷新版本的明确提示。 */
function showMutationError(value: unknown, fallback: string): void {
  const issue = asApiError(value, fallback)
  ElMessage.error(
    issue.status === 409
      ? `${issue.message}；当前输入已保留，请刷新最新版本后再次确认`
      : issue.message,
  )
}

/** 将未知异常收敛为可展示错误。 */
function asApiError(value: unknown, fallback: string): ApiError {
  return value instanceof ApiError
    ? value
    : new ApiError(fallback, null, 'CLIENT_ERROR', null, true)
}

/** 使用浏览器本地时区展示 UTC 时间。 */
function localTime(value: string): string {
  return new Date(value).toLocaleString('zh-CN')
}
</script>

<template>
  <section class="content-page">
    <header class="page-heading">
      <div>
        <p class="eyebrow">Admin</p>
        <h1>知识空间治理</h1>
        <p class="muted-copy">管理企业内部知识边界、生命周期和成员角色。</p>
      </div>
      <el-button type="primary" @click="openCreate">创建空间</el-button>
    </header>
    <form class="filter-bar admin-filter admin-filter--wide" @submit.prevent="applyFilters">
      <label for="space-status">状态</label
      ><select id="space-status" v-model="status">
        <option :value="null">全部</option>
        <option value="ACTIVE">活动</option>
        <option value="DISABLED">已停用</option></select
      ><label for="space-visibility">可见性</label
      ><select id="space-visibility" v-model="visibility">
        <option :value="null">全部</option>
        <option value="ENTERPRISE">企业可读</option>
        <option value="RESTRICTED">受限</option></select
      ><label for="space-keyword">代码或名称</label
      ><el-input id="space-keyword" v-model="keyword" maxlength="100" /><el-button
        native-type="submit"
        type="primary"
        >查询</el-button
      ><el-button @click="clearFilters">清空</el-button>
    </form>
    <LoadingState v-if="loading" title="正在读取知识空间" /><ErrorState
      v-else-if="error"
      :message="error.message"
      :trace-id="error.traceId"
      @retry="load"
    /><EmptyState
      v-else-if="spaces.length === 0"
      title="暂无知识空间"
      description="可以创建默认受限的普通空间。"
    />
    <div v-else class="admin-table-wrap">
      <table class="admin-table">
        <thead>
          <tr>
            <th>空间</th>
            <th>可见性</th>
            <th>状态</th>
            <th>版本</th>
            <th>更新时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="space in spaces" :key="space.spaceId">
            <td>
              <strong>{{ space.name }}</strong
              ><small
                >{{ space.code }}<template v-if="space.systemSpace"> · 系统空间</template></small
              >
            </td>
            <td>{{ space.visibility === 'ENTERPRISE' ? '企业可读' : '受限' }}</td>
            <td>
              <span class="status-chip">{{ space.status === 'ACTIVE' ? '活动' : '已停用' }}</span>
            </td>
            <td>{{ space.version }}</td>
            <td>{{ localTime(space.updatedAt) }}</td>
            <td>
              <div class="table-actions">
                <el-button size="small" :disabled="space.systemSpace" @click="openEdit(space)"
                  >编辑</el-button
                ><el-button size="small" @click="openMembers(space)">成员</el-button
                ><el-button
                  size="small"
                  :type="space.status === 'ACTIVE' ? 'danger' : 'primary'"
                  plain
                  :disabled="space.systemSpace"
                  @click="toggleSpace(space)"
                  >{{ space.status === 'ACTIVE' ? '停用' : '启用' }}</el-button
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
        aria-label="空间每页数量"
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

    <el-dialog v-model="createVisible" title="创建知识空间" width="620px"
      ><form id="space-create-form" class="admin-form" @submit.prevent="submitCreate">
        <label for="space-code">稳定代码（1～64 字符，创建后不可修改）</label
        ><el-input id="space-code" v-model="createDraft.code" maxlength="64" /><label
          for="space-name"
          >名称（1～100 字符）</label
        ><el-input id="space-name" v-model="createDraft.name" maxlength="100" /><label
          for="space-description"
          >用途和知识边界说明（最多 500 字符）</label
        ><el-input
          id="space-description"
          v-model="createDraft.description"
          type="textarea"
          :rows="4"
          maxlength="500"
          show-word-limit
        />
        <p class="form-note">新空间固定创建为 RESTRICTED、ACTIVE。</p>
      </form>
      <template #footer
        ><el-button @click="createVisible = false">取消</el-button
        ><el-button type="primary" :loading="saving" native-type="submit" form="space-create-form"
          >创建</el-button
        ></template
      ></el-dialog
    >
    <el-dialog v-model="editVisible" title="修改知识空间" width="620px"
      ><form id="space-edit-form" class="admin-form" @submit.prevent="submitEdit">
        <label for="space-edit-name">名称</label
        ><el-input id="space-edit-name" v-model="editDraft.name" maxlength="100" /><label
          for="space-edit-description"
          >用途和知识边界说明</label
        ><el-input
          id="space-edit-description"
          v-model="editDraft.description"
          type="textarea"
          :rows="4"
          maxlength="500"
          show-word-limit
        /><label for="space-edit-visibility">可见性</label
        ><select id="space-edit-visibility" v-model="editDraft.visibility">
          <option value="RESTRICTED">受限</option>
          <option value="ENTERPRISE">企业可读</option>
        </select>
        <p class="form-note">
          版本 {{ selected?.version }}；冲突时输入会保留，不会覆盖服务端新版本。
        </p>
      </form>
      <template #footer
        ><el-button @click="editVisible = false">取消</el-button
        ><el-button type="primary" :loading="saving" native-type="submit" form="space-edit-form"
          >保存</el-button
        ></template
      ></el-dialog
    >
    <el-dialog v-model="membersVisible" :title="`${selected?.name || ''} · 成员治理`" width="900px"
      ><form class="member-create" @submit.prevent="addMember">
        <label for="member-user">活动用户</label
        ><select id="member-user" v-model="memberDraft.userId">
          <option value="">请选择用户</option>
          <option v-for="user in candidateUsers" :key="user.userId" :value="user.userId">
            {{ user.displayName }}（{{ user.username }}）
          </option></select
        ><label for="member-role">角色</label
        ><select id="member-role" v-model="memberDraft.role">
          <option v-if="!selected?.systemSpace" value="READER">READER</option>
          <option value="EDITOR">EDITOR</option>
          <option value="MANAGER">MANAGER</option></select
        ><el-button type="primary" :loading="saving" native-type="submit">新增或恢复</el-button>
      </form>
      <div class="admin-table-wrap">
        <table class="admin-table">
          <thead>
            <tr>
              <th>成员</th>
              <th>角色</th>
              <th>状态</th>
              <th>版本</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="member in members" :key="member.userId">
              <td>
                <strong>{{ member.displayName }}</strong
                ><small>{{ member.username }}</small>
              </td>
              <td>
                <select
                  :value="member.role"
                  :disabled="member.status === 'REVOKED' || saving"
                  :aria-label="`${member.username} 的空间角色`"
                  @change="
                    changeMemberRole(
                      member,
                      ($event.target as HTMLSelectElement).value as SpaceRole,
                    )
                  "
                >
                  <option v-if="!selected?.systemSpace" value="READER">READER</option>
                  <option value="EDITOR">EDITOR</option>
                  <option value="MANAGER">MANAGER</option>
                </select>
              </td>
              <td>{{ member.status === 'ACTIVE' ? '活动' : '已撤销' }}</td>
              <td>{{ member.version }}</td>
              <td>
                <el-button
                  v-if="member.status === 'ACTIVE'"
                  size="small"
                  type="danger"
                  plain
                  :disabled="isLastManager(member)"
                  @click="revoke(member)"
                  >撤销</el-button
                ><el-button
                  v-else
                  size="small"
                  @click="mutateMember(member.userId, member.role, member.version, '成员已恢复')"
                  >恢复</el-button
                >
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <nav v-if="memberTotalPages > 0" class="pager">
        <span>共 {{ memberTotal }} 条</span
        ><el-button :disabled="memberPage <= 1" @click="changeMemberPage(memberPage - 1)"
          >上一页</el-button
        ><span>第 {{ memberPage }} / {{ memberTotalPages }} 页</span
        ><el-button
          :disabled="memberPage >= memberTotalPages"
          @click="changeMemberPage(memberPage + 1)"
          >下一页</el-button
        >
      </nav></el-dialog
    >
  </section>
</template>
