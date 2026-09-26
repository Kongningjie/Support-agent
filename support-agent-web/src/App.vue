<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import LoginLayout from '@/layouts/LoginLayout.vue'
import MainLayout from '@/layouts/MainLayout.vue'

const route = useRoute()
const layout = computed(() => (route.meta.requiresAuth ? MainLayout : LoginLayout))
</script>

<template>
  <component :is="layout">
    <RouterView />
  </component>
</template>
