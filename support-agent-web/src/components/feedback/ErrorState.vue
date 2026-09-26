<script setup lang="ts">
const props = defineProps<{
  message: string
  traceId?: string | null
  retryable?: boolean
}>()

const emit = defineEmits<{
  retry: []
}>()

/** 将非敏感 traceId 复制到剪贴板，便于后端排查。 */
async function copyTraceId(): Promise<void> {
  if (props.traceId) {
    await navigator.clipboard.writeText(props.traceId)
  }
}
</script>

<template>
  <div class="state-panel state-panel--error" role="alert">
    <strong>操作未完成</strong>
    <p>{{ message }}</p>
    <div v-if="traceId" class="state-panel__trace">
      <span>追踪标识：{{ traceId }}</span>
      <el-button link type="primary" aria-label="复制追踪标识" @click="copyTraceId">
        复制
      </el-button>
    </div>
    <el-button v-if="retryable" type="primary" plain @click="emit('retry')">重新尝试</el-button>
  </div>
</template>
