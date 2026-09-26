<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import * as conversationApi from '@/api/conversation.api'
import ErrorState from '@/components/feedback/ErrorState.vue'
import LoadingState from '@/components/feedback/LoadingState.vue'
import EmptyState from '@/components/feedback/EmptyState.vue'
import type { ConversationOverview } from '@/types/chat.types'
import { ApiError } from '@/types/api.types'

const router = useRouter()
const items = ref<ConversationOverview[]>([])
const page = ref(1)
const totalPages = ref(0)
const loading = ref(false)
const error = ref<ApiError | null>(null)

onMounted(load)

/** 读取当前页会话，过期或无权限由统一错误状态展示。 */
async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const result = await conversationApi.listConversations(page.value, 20)
    items.value = result.items
    totalPages.value = result.totalPages
  } catch (caught) {
    error.value =
      caught instanceof ApiError
        ? caught
        : new ApiError('无法读取会话列表', null, 'CLIENT_ERROR', null, true)
  } finally {
    loading.value = false
  }
}

/** 切换分页并重新查询。 */
async function changePage(next: number): Promise<void> {
  page.value = next
  await load()
}

/** 使用浏览器本地时区显示 UTC 时间。 */
function localTime(value: string): string {
  return new Date(value).toLocaleString('zh-CN')
}
</script>

<template>
  <section class="content-page">
    <header class="page-heading">
      <div>
        <p class="eyebrow">Conversations</p>
        <h1>会话记录</h1>
        <p class="muted-copy">仅展示当前账号可访问且尚未过期的会话。</p>
      </div>
      <el-button type="primary" @click="router.push('/chat')">新建会话</el-button>
    </header>
    <LoadingState v-if="loading" title="正在读取会话" />
    <ErrorState
      v-else-if="error"
      :message="error.message"
      :trace-id="error.traceId"
      @retry="load"
    />
    <EmptyState
      v-else-if="items.length === 0"
      title="暂无会话"
      description="发送第一条问题后，会话将显示在这里。"
    />
    <div v-else class="record-list">
      <button
        v-for="item in items"
        :key="item.conversationId"
        class="record-card"
        type="button"
        @click="router.push(`/conversations/${item.conversationId}`)"
      >
        <span class="record-card__title">会话 {{ item.conversationId.slice(0, 8) }}</span>
        <span>{{ item.status === 'RUNNING' ? '处理中' : '可继续' }} · 版本 {{ item.version }}</span>
        <span>最近访问：{{ localTime(item.lastAccessAt) }}</span>
        <small>预计过期：{{ localTime(item.expiresAt) }}</small>
      </button>
    </div>
    <nav v-if="totalPages > 1" class="pager" aria-label="会话分页">
      <el-button :disabled="page <= 1" @click="changePage(page - 1)">上一页</el-button>
      <span>第 {{ page }} / {{ totalPages }} 页</span>
      <el-button :disabled="page >= totalPages" @click="changePage(page + 1)">下一页</el-button>
    </nav>
  </section>
</template>
