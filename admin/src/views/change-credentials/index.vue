<!-- 首次登录强制修改凭据页面 -->
<template>
  <div class="change-cred-page">
    <ElCard class="change-cred-card">
      <template #header>
        <div class="change-cred-header">
          <h3>{{ $t('changeCredentials.title') }}</h3>
          <p>{{ $t('changeCredentials.subTitle') }}</p>
        </div>
      </template>

      <ElForm ref="formRef" :model="formData" :rules="rules" label-width="96px" @keyup.enter="handleSubmit">
        <ElFormItem :label="$t('changeCredentials.oldPassword')" prop="old_password">
          <ElInput
            v-model.trim="formData.old_password"
            type="password"
            show-password
            :placeholder="$t('changeCredentials.oldPasswordPlaceholder')"
          />
        </ElFormItem>
        <ElFormItem :label="$t('changeCredentials.newUsername')" prop="username">
          <ElInput
            v-model.trim="formData.username"
            :placeholder="$t('changeCredentials.newUsernamePlaceholder')"
          />
        </ElFormItem>
        <ElFormItem :label="$t('changeCredentials.newPassword')" prop="password">
          <ElInput
            v-model.trim="formData.password"
            type="password"
            show-password
            :placeholder="$t('changeCredentials.newPasswordPlaceholder')"
          />
        </ElFormItem>
        <ElFormItem :label="$t('changeCredentials.confirmPassword')" prop="confirmPassword">
          <ElInput
            v-model.trim="formData.confirmPassword"
            type="password"
            show-password
            :placeholder="$t('changeCredentials.confirmPasswordPlaceholder')"
          />
        </ElFormItem>
        <ElFormItem :label="$t('changeCredentials.email')" prop="email">
          <ElInput
            v-model.trim="formData.email"
            :placeholder="$t('changeCredentials.emailPlaceholder')"
          />
        </ElFormItem>

        <div class="change-cred-actions">
          <ElButton type="primary" :loading="loading" @click="handleSubmit">
            {{ $t('changeCredentials.submit') }}
          </ElButton>
          <ElButton @click="handleLogout">{{ $t('changeCredentials.logout') }}</ElButton>
        </div>
      </ElForm>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { useI18n } from 'vue-i18n'
  import { fetchChangeCredentials } from '@/api/auth'
  import { useUserStore } from '@/store/modules/user'
  import type { FormInstance, FormRules } from 'element-plus'

  defineOptions({ name: 'ChangeCredentials' })

  const { t } = useI18n()
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
      callback(new Error(t('changeCredentials.rules.mismatch')))
    } else {
      callback()
    }
  }

  const rules = computed<FormRules>(() => ({
    old_password: [
      { required: true, message: t('changeCredentials.rules.oldPassword'), trigger: 'blur' }
    ],
    username: [
      { min: 3, max: 64, message: t('changeCredentials.rules.username'), trigger: 'blur' }
    ],
    password: [{ min: 6, message: t('changeCredentials.rules.password'), trigger: 'blur' }],
    confirmPassword: [{ validator: validateConfirm, trigger: 'blur' }]
  }))

  const handleSubmit = async () => {
    if (!formRef.value) return
    const valid = await formRef.value.validate().catch(() => false)
    if (!valid) return

    // 至少要修改用户名或密码之一
    if (!formData.username && !formData.password) {
      ElMessage.warning(t('changeCredentials.rules.atLeastOne'))
      return
    }

    loading.value = true
    try {
      const payload: Api.Auth.ChangeCredentialsParams = { old_password: formData.old_password }
      if (formData.username) payload.username = formData.username
      if (formData.password) payload.password = formData.password
      if (formData.email) payload.email = formData.email

      const res = await fetchChangeCredentials(payload)
      ElMessage.success(t('changeCredentials.success'))
      // 改密后服务端签发新 token（must_change 已清零）；必须换用新 token，否则后续请求仍带旧 token 被 403 拦。
      if (res?.token) userStore.setToken(res.token, res.refreshToken || res.token)
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
