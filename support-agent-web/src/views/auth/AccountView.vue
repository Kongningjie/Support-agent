<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth.store'
import { ApiError } from '@/types/api.types'

const router = useRouter()
const auth = useAuthStore()
const user = computed(() => auth.user)

/** 以浏览器本地时区展示 UTC 时间，同时通过 title 保留原始值。 */
function formatTime(value: string | null | undefined): string {
  return value
    ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(
        new Date(value),
      )
    : '—'
}

/** 二次确认后撤销当前用户全部有效 Token。 */
async function revokeAll(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '此操作会退出当前账号在所有设备上的登录，完成后需要重新登录。',
      '撤销全部登录',
      {
        confirmButtonText: '确认撤销',
        cancelButtonText: '取消',
        type: 'warning',
      },
    )
  } catch {
    return
  }
  try {
    await auth.revokeEveryToken()
    ElMessage.success('全部登录已撤销')
    await router.replace('/login')
  } catch (error) {
    const message = error instanceof ApiError ? error.message : '撤销失败，请稍后重试'
    ElMessage.error(message)
  }
}
</script>

<template>
  <section class="page-wide">
    <div class="page-heading">
      <div>
        <p class="eyebrow">账号</p>
        <h1>账号与登录安全</h1>
        <p>查看当前账号摘要，修改密码或撤销全部登录。</p>
      </div>
    </div>

    <div v-if="user" class="account-grid">
      <el-card shadow="never">
        <template #header><strong>本人信息</strong></template>
        <dl class="detail-list">
          <div>
            <dt>展示名称</dt>
            <dd>{{ user.displayName }}</dd>
          </div>
          <div>
            <dt>用户名</dt>
            <dd>{{ user.username }}</dd>
          </div>
          <div>
            <dt>角色</dt>
            <dd>{{ user.role === 'ADMIN' ? '管理员' : '普通用户' }}</dd>
          </div>
          <div>
            <dt>账号状态</dt>
            <dd>{{ user.status === 'ACTIVE' ? '正常' : '已禁用' }}</dd>
          </div>
          <div>
            <dt>创建时间</dt>
            <dd :title="user.createdAt">{{ formatTime(user.createdAt) }}</dd>
          </div>
          <div>
            <dt>最近更新</dt>
            <dd :title="user.updatedAt">{{ formatTime(user.updatedAt) }}</dd>
          </div>
        </dl>
      </el-card>

      <el-card shadow="never">
        <template #header><strong>安全操作</strong></template>
        <div class="security-actions">
          <div>
            <h2>修改密码</h2>
            <p>成功后全部旧 Token 都会失效，需要重新登录。</p>
            <el-button type="primary" @click="router.push('/change-password')">修改密码</el-button>
          </div>
          <el-divider />
          <div>
            <h2>撤销全部登录</h2>
            <p>用于怀疑凭据泄露或需要立即退出所有设备的场景。</p>
            <el-button type="danger" plain :loading="auth.pending" @click="revokeAll">
              撤销全部 Token
            </el-button>
          </div>
        </div>
      </el-card>
    </div>
  </section>
</template>
