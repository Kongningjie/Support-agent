<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import ErrorState from '@/components/feedback/ErrorState.vue'
import { useAuthStore } from '@/stores/auth.store'
import { ApiError } from '@/types/api.types'

interface PasswordForm {
  oldPassword: string
  newPassword: string
  confirmPassword: string
}

const router = useRouter()
const auth = useAuthStore()
const formRef = ref<FormInstance>()
const error = ref<ApiError | null>(null)
const form = reactive<PasswordForm>({ oldPassword: '', newPassword: '', confirmPassword: '' })
const forced = computed(() => auth.mustChangePassword)

const rules: FormRules<PasswordForm> = {
  oldPassword: [
    { required: true, message: '请输入当前密码', trigger: 'blur' },
    { min: 12, max: 72, message: '密码长度必须为 12～72 个字符', trigger: 'blur' },
  ],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 12, max: 72, message: '新密码长度必须为 12～72 个字符', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        callback(value === form.oldPassword ? new Error('新密码不能与当前密码相同') : undefined)
      },
      trigger: 'blur',
    },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        callback(value !== form.newPassword ? new Error('两次输入的新密码不一致') : undefined)
      },
      trigger: ['blur', 'change'],
    },
  ],
}

/** 修改本人密码；成功后提示全部旧 Token 已失效并返回登录页。 */
async function submit(): Promise<void> {
  if (!(await formRef.value?.validate().catch(() => false))) {
    return
  }
  error.value = null
  try {
    await auth.updatePassword({ oldPassword: form.oldPassword, newPassword: form.newPassword })
    ElMessage.success('密码已更新，全部旧登录已失效，请重新登录')
    await router.replace('/login')
  } catch (caught) {
    error.value =
      caught instanceof ApiError
        ? caught
        : new ApiError('密码修改失败，请稍后重试', null, 'CLIENT_PASSWORD_ERROR', null, false)
  }
}
</script>

<template>
  <section class="page-narrow">
    <div class="page-heading">
      <div>
        <p class="eyebrow">账号安全</p>
        <h1>{{ forced ? '首次登录必须修改密码' : '修改登录密码' }}</h1>
        <p>
          {{
            forced
              ? '当前使用的是管理员设置的一次性密码，完成修改前不能访问其他业务。'
              : '修改成功后，当前账号在所有设备上的登录都会失效。'
          }}
        </p>
      </div>
    </div>

    <el-alert
      v-if="forced"
      title="这是受限会话，只允许查询本人信息、修改密码或退出登录。"
      type="warning"
      :closable="false"
      show-icon
    />
    <ErrorState v-if="error" :message="error.message" :trace-id="error.traceId" />

    <el-card shadow="never">
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        @submit.prevent="submit"
      >
        <el-form-item label="当前密码" prop="oldPassword">
          <el-input
            v-model="form.oldPassword"
            type="password"
            autocomplete="current-password"
            minlength="12"
            maxlength="72"
            show-password
          />
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input
            v-model="form.newPassword"
            type="password"
            autocomplete="new-password"
            minlength="12"
            maxlength="72"
            show-password
          />
          <p class="field-help">长度 12～72 个字符，且不能与当前密码相同。</p>
        </el-form-item>
        <el-form-item label="确认新密码" prop="confirmPassword">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            autocomplete="new-password"
            minlength="12"
            maxlength="72"
            show-password
          />
        </el-form-item>
        <div class="form-actions">
          <el-button v-if="!forced" @click="router.push('/account')">取消</el-button>
          <el-button type="primary" native-type="submit" :loading="auth.pending"
            >确认修改</el-button
          >
        </div>
      </el-form>
    </el-card>
  </section>
</template>
