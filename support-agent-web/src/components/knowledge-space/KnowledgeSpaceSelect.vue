<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { listAllReadableKnowledgeSpaces } from '@/api/knowledge-space.api'
import { ApiError } from '@/types/api.types'
import type { KnowledgeSpaceView } from '@/types/knowledge-space.types'

const props = withDefaults(
  defineProps<{
    modelValue: string | null
    inputId: string
    label?: string
    disabled?: boolean
  }>(),
  { label: '知识空间（必选）', disabled: false },
)

const emit = defineEmits<{
  'update:modelValue': [value: string | null]
  loaded: [spaces: KnowledgeSpaceView[]]
}>()

const spaces = ref<KnowledgeSpaceView[]>([])
const loading = ref(false)
const errorMessage = ref<string | null>(null)

onMounted(load)

/** 从后端读取当前用户可读的活动空间，不使用前端固定列表代替权限结果。 */
async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = null
  try {
    spaces.value = await listAllReadableKnowledgeSpaces()
    emit('loaded', spaces.value)
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '无法读取可用知识空间'
  } finally {
    loading.value = false
  }
}

/** 把原生选择值转换为可空空间 UUID。 */
function update(value: string): void {
  emit('update:modelValue', value || null)
}
</script>

<template>
  <div class="space-field">
    <label :for="props.inputId">{{ props.label }}</label>
    <select
      :id="props.inputId"
      :value="props.modelValue || ''"
      :disabled="props.disabled || loading"
      @change="update(($event.target as HTMLSelectElement).value)"
    >
      <option value="" disabled>{{ loading ? '正在读取空间…' : '请选择知识空间' }}</option>
      <option v-for="space in spaces" :key="space.spaceId" :value="space.spaceId">
        {{ space.name }}（{{ space.code }}）
      </option>
    </select>
    <span v-if="errorMessage" class="space-field__error">
      {{ errorMessage }}
      <button type="button" @click="load">重试</button>
    </span>
    <small v-else>系统只会检索企业公共知识与当前所选空间中的知识。</small>
  </div>
</template>
