<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'
import { useAuthStore } from '@/stores/auth.store'
import { ApiError } from '@/types/api.types'
import ErrorState from '@/components/feedback/ErrorState.vue'

interface LoginForm {
  username: string
  password: string
}

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const formRef = ref<FormInstance>()
const form = reactive<LoginForm>({ username: '', password: '' })
const error = ref<ApiError | null>(null)
const expiredNotice = computed(() => route.query.reason === 'expired')

const rules: FormRules<LoginForm> = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { max: 64, message: '用户名不能超过 64 个字符', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 12, max: 72, message: '密码长度必须为 12～72 个字符', trigger: 'blur' },
  ],
}

/** 校验表单并登录，成功后遵守强制改密和安全重定向规则。 */
async function submit(): Promise<void> {
  if (!(await formRef.value?.validate().catch(() => false))) {
    return
  }
  error.value = null
  try {
    await auth.signIn(form)
    const destination = safeRedirect(route.query.redirect)
    await router.replace(auth.mustChangePassword ? '/change-password' : destination)
  } catch (caught) {
    error.value =
      caught instanceof ApiError
        ? caught
        : new ApiError('登录失败，请稍后重试', null, 'CLIENT_LOGIN_ERROR', null, false)
  }
}

/** 只允许使用站内绝对路径，避免登录重定向被用作开放跳转。 */
function safeRedirect(value: unknown): string {
  return typeof value === 'string' && value.startsWith('/') && !value.startsWith('//')
    ? value
    : '/chat'
}
</script>

<template>
  <div class="auth-card">
    <p class="eyebrow">欢迎回来</p>
    <h2>登录技术支持工作台</h2>
    <p class="auth-card__intro">使用管理员为你创建的本地账号登录。</p>

    <el-alert
      v-if="expiredNotice"
      title="登录状态已失效，请重新登录"
      type="warning"
      :closable="false"
      show-icon
    />

    <ErrorState
      v-if="error"
      :message="error.message"
      :trace-id="error.traceId"
      :retryable="false"
    />

    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-position="top"
      @submit.prevent="submit"
    >
      <el-form-item label="用户名" prop="username">
        <el-input
          v-model="form.username"
          name="username"
          autocomplete="username"
          maxlength="64"
          placeholder="请输入用户名"
        />
      </el-form-item>
      <el-form-item label="密码" prop="password">
        <el-input
          v-model="form.password"
          name="password"
          type="password"
          autocomplete="current-password"
          minlength="12"
          maxlength="72"
          show-password
          placeholder="请输入 12～72 位密码"
        />
      </el-form-item>
      <el-button
        class="auth-card__submit"
        type="primary"
        native-type="submit"
        :loading="auth.pending"
      >
        登录
      </el-button>
    </el-form>
    <p class="auth-card__help">连续输入错误密码会触发账号与来源退避，请根据服务端提示稍后再试。</p>
  </div>
</template>
