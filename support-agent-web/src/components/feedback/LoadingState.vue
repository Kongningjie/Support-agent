<script setup lang="ts">
withDefaults(
  defineProps<{
    label?: string
  }>(),
  {
    label: '正在加载，请稍候',
  },
)
</script>

<template>
  <div class="state-panel" role="status" aria-live="polite">
    <span class="state-panel__spinner" aria-hidden="true" />
    <p>{{ label }}</p>
  </div>
</template>
