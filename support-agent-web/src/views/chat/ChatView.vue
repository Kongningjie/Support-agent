<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import SafeMarkdown from '@/components/content/SafeMarkdown.vue'
import { useChatStore } from '@/stores/chat.store'
import type { ChatMessage } from '@/types/chat.types'
import { ApiError } from '@/types/api.types'

const route = useRoute()
const router = useRouter()
const chat = useChatStore()
const draft = ref('')
const scroller = ref<HTMLElement | null>(null)
const routeConversationId = computed(() =>
  typeof route.params.id === 'string' ? route.params.id : null,
)

watch(
  routeConversationId,
  async (conversationId) => {
    try {
      if (conversationId) await chat.openConversation(conversationId)
      else chat.startNewConversation()
    } catch (error) {
      ElMessage.error(error instanceof ApiError ? error.message : '无法读取会话')
      await router.replace('/chat')
    }
  },
  { immediate: true },
)

watch(
  () => chat.messages.map((item) => `${item.answer.length}:${item.state}`).join('|'),
  async () => {
    await nextTick()
    scroller.value?.scrollTo({ top: scroller.value.scrollHeight, behavior: 'smooth' })
  },
)

onBeforeUnmount(() => chat.cancel())

/** 发送输入框中的用户消息并在获得会话 ID 后同步路由。 */
async function submit(): Promise<void> {
  const message = draft.value.trim()
  if (!message || !chat.canSend) return
  draft.value = ''
  await chat.send(message)
  if (chat.conversation && routeConversationId.value !== chat.conversation.conversationId) {
    await router.replace(`/conversations/${chat.conversation.conversationId}`)
  }
}

/** 二次确认后重置当前会话的上下文和轮次。 */
async function resetConversation(): Promise<void> {
  if (!chat.conversation) return
  try {
    await ElMessageBox.confirm(
      '重置后将清空当前会话轮次和摘要，且无法恢复。是否继续？',
      '重置会话',
      {
        confirmButtonText: '确认重置',
        cancelButtonText: '取消',
        type: 'warning',
      },
    )
    await chat.reset()
    ElMessage.success('会话已重置')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof ApiError ? error.message : '会话重置失败')
  }
}

/** 二次确认后永久删除当前会话。 */
async function deleteConversation(): Promise<void> {
  if (!chat.conversation) return
  try {
    await ElMessageBox.confirm('删除后当前会话及其消息将无法恢复。是否继续？', '删除会话', {
      confirmButtonText: '永久删除',
      cancelButtonText: '取消',
      type: 'error',
    })
    await chat.remove()
    await router.replace('/chat')
    ElMessage.success('会话已删除')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof ApiError ? error.message : '会话删除失败')
  }
}

/** 返回检索状态的简体中文解释。 */
function retrievalLabel(message: ChatMessage): string | null {
  const labels = {
    GROUNDED: '已使用可靠知识',
    NO_RELIABLE_KNOWLEDGE: '没有找到可靠知识',
    RETRIEVAL_FAILED: '知识检索暂不可用',
  }
  return message.retrievalStatus ? labels[message.retrievalStatus] : null
}

/** 把 UTC 时间按浏览器本地时区展示。 */
function localTime(value: string | null): string {
  return value ? new Date(value).toLocaleString('zh-CN') : ''
}
</script>

<template>
  <section class="chat-workspace" aria-label="AI 技术支持对话">
    <header class="page-heading chat-heading">
      <div>
        <p class="eyebrow">AI Support</p>
        <h1>{{ chat.conversation ? '继续技术支持会话' : '新建技术支持会话' }}</h1>
        <p v-if="chat.conversation" class="muted-copy">
          版本 {{ chat.conversation.version }} ·
          {{ chat.conversation.status === 'RUNNING' ? '处理中' : '可继续对话' }}
        </p>
      </div>
      <div class="heading-actions">
        <el-button @click="router.push('/conversations')">会话记录</el-button>
        <el-button :disabled="!chat.conversation || chat.streaming" @click="resetConversation"
          >重置</el-button
        >
        <el-button
          type="danger"
          plain
          :disabled="!chat.conversation || chat.streaming"
          @click="deleteConversation"
        >
          删除
        </el-button>
      </div>
    </header>

    <div ref="scroller" class="chat-thread" aria-live="polite">
      <div v-if="chat.loading" class="chat-empty">正在读取会话…</div>
      <div v-else-if="chat.messages.length === 0" class="chat-empty">
        <span class="chat-empty__mark" aria-hidden="true">SA</span>
        <h2>描述你遇到的技术问题</h2>
        <p>系统会检索已发布企业知识，回答中的引用可追溯；涉及写操作时仍需你明确确认。</p>
      </div>

      <article v-for="message in chat.messages" :key="message.key" class="chat-turn">
        <div class="chat-bubble chat-bubble--user">
          <span class="chat-bubble__role">你</span>
          <p>{{ message.userMessage }}</p>
        </div>
        <div class="chat-bubble chat-bubble--assistant">
          <div class="chat-bubble__meta">
            <span class="chat-bubble__role">Support Agent</span>
            <span v-if="retrievalLabel(message)" class="status-chip">{{
              retrievalLabel(message)
            }}</span>
            <time v-if="message.completedAt">{{ localTime(message.completedAt) }}</time>
          </div>
          <SafeMarkdown v-if="message.answer" :content="message.answer" />
          <p v-else-if="message.state === 'running'" class="stream-placeholder">
            正在准备安全回答…
          </p>

          <div v-if="message.citations.length" class="citation-list" aria-label="回答引用">
            <article
              v-for="citation in message.citations"
              :key="citation.citationId"
              class="citation-card"
            >
              <strong>[{{ citation.citationId }}] {{ citation.documentTitle }}</strong>
              <span>{{ citation.headingPath || '文档正文' }}</span>
              <small>{{ citation.sourceType }} · {{ citation.documentId }}</small>
            </article>
          </div>

          <div v-if="message.suggestionId" class="ticket-suggestion">
            <div>
              <strong>可以创建工单草稿</strong>
              <span>建议有效至 {{ localTime(message.suggestionExpiresAt) }}</span>
            </div>
            <el-button disabled title="工单创建将在 F3 阶段接入">创建工单草稿（F3）</el-button>
          </div>

          <el-alert
            v-if="message.errorMessage"
            :title="message.state === 'cancelled' ? '本轮已取消' : '本轮未完成'"
            :description="message.errorMessage"
            :type="message.state === 'cancelled' ? 'info' : 'error'"
            :closable="false"
            show-icon
          />
          <el-button
            v-if="message.retryable && message.clientMessageId"
            size="small"
            :disabled="!chat.canSend"
            @click="chat.retry(message.key)"
          >
            重试同一条消息
          </el-button>
        </div>
      </article>
    </div>

    <footer class="chat-composer">
      <div v-if="chat.progress" class="chat-progress">
        <span aria-hidden="true" />{{ chat.progress }}
      </div>
      <label for="chat-message">问题描述</label>
      <el-input
        id="chat-message"
        v-model="draft"
        type="textarea"
        :rows="3"
        maxlength="4000"
        show-word-limit
        resize="none"
        placeholder="例如：MySQL 连接超时应该如何排查？"
        :disabled="chat.streaming"
        @keydown.ctrl.enter.prevent="submit"
      />
      <div class="composer-actions">
        <span>Ctrl + Enter 发送。模型输出仅在通过完整安全校验后展示。</span>
        <el-button v-if="chat.streaming" type="danger" plain @click="chat.cancel"
          >取消本轮</el-button
        >
        <el-button
          v-else
          type="primary"
          :disabled="!draft.trim() || draft.length > 4000"
          @click="submit"
        >
          发送问题
        </el-button>
      </div>
    </footer>
  </section>
</template>
