<!-- 首次登录强制修改凭据页面 -->
<template>
  <div class="change-cred-page">
    <ElCard class="change-cred-card">
      <template #header>
        <div class="change-cred-header">
          <h3>修改登录凭据</h3>
          <p>首次登录或安全策略要求，请修改您的账号信息</p>
        </div>
      </template>

      <ElForm ref="formRef" :model="formData" :rules="rules" label-width="96px" @keyup.enter="handleSubmit">
        <ElFormItem label="原密码" prop="old_password">
          <ElInput
            v-model.trim="formData.old_password"
            type="password"
            show-password
            placeholder="请输入当前密码（默认 123456）"
          />
        </ElFormItem>
        <ElFormItem label="新用户名" prop="username">
          <ElInput v-model.trim="formData.username" placeholder="留空则不修改用户名" />
        </ElFormItem>
        <ElFormItem label="新密码" prop="password">
          <ElInput
            v-model.trim="formData.password"
            type="password"
            show-password
            placeholder="留空则不修改密码，至少 6 位"
          />
        </ElFormItem>
        <ElFormItem label="确认新密码" prop="confirmPassword">
          <ElInput
            v-model.trim="formData.confirmPassword"
            type="password"
            show-password
            placeholder="请再次输入新密码"
          />
        </ElFormItem>
        <ElFormItem label="邮箱" prop="email">
          <ElInput v-model.trim="formData.email" placeholder="选填，用于找回等" />
        </ElFormItem>

        <div class="change-cred-actions">
          <ElButton type="primary" :loading="loading" @click="handleSubmit">保存并继续</ElButton>
          <ElButton @click="handleLogout">退出登录</ElButton>
        </div>
      </ElForm>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { fetchChangeCredentials } from '@/api/auth'
  import { useUserStore } from '@/store/modules/user'
  import type { FormInstance, FormRules } from 'element-plus'

  defineOptions({ name: 'ChangeCredentials' })

  const router = useRouter()
  const userStore = useUserStore()
  const formRef = ref<FormInstance>()
  const loading = ref(false)

  const formData = reactive({
    old_password: '',
    username: '',
    password: '',
    confirmPassword: '',
    email: ''
  })

  const validateConfirm = (_rule: unknown, value: string, callback: (e?: Error) => void) => {
    if (formData.password && value !== formData.password) {
      callback(new Error('两次输入的密码不一致'))
    } else {
      callback()
    }
  }

  const rules = reactive<FormRules>({
    old_password: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
    username: [{ min: 3, max: 64, message: '用户名长度 3-64', trigger: 'blur' }],
    password: [{ min: 6, message: '密码至少 6 位', trigger: 'blur' }],
    confirmPassword: [{ validator: validateConfirm, trigger: 'blur' }]
  })

  const handleSubmit = async () => {
    if (!formRef.value) return
    const valid = await formRef.value.validate().catch(() => false)
    if (!valid) return

    // 至少要修改用户名或密码之一
    if (!formData.username && !formData.password) {
      ElMessage.warning('请至少修改用户名或密码')
      return
    }

    loading.value = true
    try {
      const payload: Api.Auth.ChangeCredentialsParams = { old_password: formData.old_password }
      if (formData.username) payload.username = formData.username
      if (formData.password) payload.password = formData.password
      if (formData.email) payload.email = formData.email

      await fetchChangeCredentials(payload)
      ElMessage.success('修改成功')
      if (userStore.info) userStore.info.must_change = false
      router.push('/')
    } finally {
      loading.value = false
    }
  }

  const handleLogout = () => {
    userStore.logOut()
  }
</script>

<style lang="scss" scoped>
  .change-cred-page {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 100%;
    height: 100vh;
    background: var(--art-main-bg-color);

    .change-cred-card {
      width: 480px;
      max-width: 92vw;
    }

    .change-cred-header {
      h3 {
        margin: 0;
        font-size: 20px;
      }

      p {
        margin: 6px 0 0;
        font-size: 13px;
        color: var(--art-gray-600);
      }
    }

    .change-cred-actions {
      display: flex;
      gap: 12px;
      justify-content: flex-end;
      margin-top: 8px;
    }
  }
</style>
