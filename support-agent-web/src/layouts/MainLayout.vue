<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth.store'
import { ApiError } from '@/types/api.types'

const router = useRouter()
const auth = useAuthStore()
const collapsed = ref(false)
const displayName = computed(() => auth.user?.displayName || auth.user?.username || '当前用户')

/** 注销当前 Token 并返回登录页。 */
async function handleLogout(): Promise<void> {
  try {
    await auth.signOut()
  } catch (error) {
    const message = error instanceof ApiError ? error.message : '注销请求未成功，本地会话已清除'
    ElMessage.warning(message)
  }
  await router.replace({ name: 'login' })
}
</script>

<template>
  <div v-if="auth.isAuthenticated" class="app-shell">
    <aside class="app-sidebar" :class="{ 'app-sidebar--collapsed': collapsed }">
      <RouterLink class="app-sidebar__brand" to="/chat" aria-label="返回 Support Agent 首页">
        <span class="brand-mark brand-mark--small" aria-hidden="true">SA</span>
        <span v-if="!collapsed">Support Agent</span>
      </RouterLink>

      <nav aria-label="主导航">
        <RouterLink class="nav-item" to="/chat">
          <span aria-hidden="true">◈</span>
          <span v-if="!collapsed">AI 支持</span>
        </RouterLink>
        <RouterLink class="nav-item" to="/conversations">
          <span aria-hidden="true">▤</span>
          <span v-if="!collapsed">会话记录</span>
        </RouterLink>
        <RouterLink class="nav-item" to="/memories">
          <span aria-hidden="true">◇</span>
          <span v-if="!collapsed">长期记忆</span>
        </RouterLink>
        <RouterLink class="nav-item" to="/account">
          <span aria-hidden="true">○</span>
          <span v-if="!collapsed">账号安全</span>
        </RouterLink>
        <template v-if="auth.isAdmin">
          <p v-if="!collapsed" class="nav-section">管理员</p>
          <RouterLink class="nav-item" to="/admin/users">
            <span aria-hidden="true">◇</span>
            <span v-if="!collapsed">用户治理</span>
          </RouterLink>
        </template>
      </nav>

      <button
        class="sidebar-toggle"
        type="button"
        :aria-label="collapsed ? '展开侧边导航' : '收起侧边导航'"
        @click="collapsed = !collapsed"
      >
        {{ collapsed ? '»' : '« 收起' }}
      </button>
    </aside>

    <div class="app-main">
      <header class="app-header">
        <div>
          <span class="connection-dot" aria-hidden="true" />
          <span>本地工作台</span>
        </div>
        <el-dropdown trigger="click">
          <button class="user-menu" type="button" aria-label="打开用户菜单">
            <span class="avatar" aria-hidden="true">{{ displayName.slice(0, 1) }}</span>
            <span>{{ displayName }}</span>
            <span class="role-badge">{{ auth.isAdmin ? '管理员' : '用户' }}</span>
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item @click="router.push('/account')">账号安全</el-dropdown-item>
              <el-dropdown-item @click="router.push('/change-password')">修改密码</el-dropdown-item>
              <el-dropdown-item divided @click="handleLogout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </header>
      <main class="page-content">
        <slot />
      </main>
    </div>
  </div>
  <main v-else class="standalone-page">
    <slot />
  </main>
</template>
