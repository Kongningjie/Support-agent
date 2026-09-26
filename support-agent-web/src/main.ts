import { createApp } from 'vue'
import { createPinia } from 'pinia'
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDivider,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElForm,
  ElFormItem,
  ElInput,
  ElTag,
} from 'element-plus'
import 'element-plus/dist/index.css'
import './styles/tokens.css'
import './styles/base.css'
import App from './App.vue'
import { AUTH_UNAUTHORIZED_EVENT } from './api/http.client'
import { router } from './router'
import { useAuthStore } from './stores/auth.store'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)

for (const component of [
  ElAlert,
  ElButton,
  ElCard,
  ElDivider,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElForm,
  ElFormItem,
  ElInput,
  ElTag,
]) {
  if (!component.name) {
    throw new Error('Element Plus 组件缺少注册名称')
  }
  app.component(component.name, component)
}

window.addEventListener(AUTH_UNAUTHORIZED_EVENT, () => {
  useAuthStore(pinia).clearSession()
  if (router.currentRoute.value.name !== 'login') {
    void router.replace({ name: 'login', query: { reason: 'expired' } })
  }
})

app.mount('#app')
