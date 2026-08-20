<!-- APP 版本 - 新增弹窗 -->
<template>
  <ElDialog v-model="visible" :title="$t('appVersion.dialog.title')" width="520px" align-center>
    <ElForm ref="formRef" :model="formData" :rules="rules" label-width="100px">
      <ElFormItem :label="$t('appVersion.form.platform')" prop="platform">
        <ElSelect v-model="formData.platform" style="width: 100%">
          <ElOption :label="$t('appVersion.platform.android')" value="android" />
          <ElOption :label="$t('appVersion.platform.ios')" value="ios" />
        </ElSelect>
      </ElFormItem>
      <ElFormItem :label="$t('appVersion.form.versionName')" prop="version_name">
        <ElInput
          v-model.trim="formData.version_name"
          :placeholder="$t('appVersion.form.versionNamePlaceholder')"
        />
      </ElFormItem>
      <ElFormItem :label="$t('appVersion.form.versionCode')" prop="version_code">
        <ElInputNumber
          v-model="formData.version_code"
          :min="1"
          :step="1"
          :precision="0"
          style="width: 100%"
        />
      </ElFormItem>
      <ElFormItem :label="$t('appVersion.form.apkUrl')" prop="apk_url">
        <ElInput
          v-model.trim="formData.apk_url"
          :placeholder="$t('appVersion.form.apkUrlPlaceholder')"
        >
          <template #append>
            <ElUpload
              :action="uploadAction"
              :headers="uploadHeaders"
              :show-file-list="false"
              :on-success="handleUploadSuccess"
              :on-error="handleUploadError"
              accept=".apk"
            >
              <ElButton>{{ $t('appVersion.form.upload') }}</ElButton>
            </ElUpload>
          </template>
        </ElInput>
      </ElFormItem>
      <ElFormItem :label="$t('appVersion.form.notes')" prop="notes">
        <ElInput
          v-model="formData.notes"
          type="textarea"
          :rows="3"
          :placeholder="$t('appVersion.form.notesPlaceholder')"
        />
      </ElFormItem>
      <ElFormItem :label="$t('appVersion.form.forceUpdate')" prop="force_update">
        <ElSwitch v-model="formData.force_update" />
      </ElFormItem>
    </ElForm>
    <template #footer>
      <ElButton @click="visible = false">{{ $t('common.cancel') }}</ElButton>
      <ElButton type="primary" :loading="submitting" @click="handleSubmit">
        {{ $t('appVersion.dialog.submit') }}
      </ElButton>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
  import { useI18n } from 'vue-i18n'
  import { createAppVersion } from '@/api/admin'
  import { useUserStore } from '@/store/modules/user'
  import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'

  defineOptions({ name: 'AppVersionDialog' })

  /** 列表中已存在的版本，用于提交前的重复提示 */
  interface ExistingVersion {
    platform: string
    version_code: number
    version_name: string
  }

  const props = withDefaults(
    defineProps<{ modelValue: boolean; existing?: ExistingVersion[] }>(),
    { existing: () => [] }
  )

  const emit = defineEmits<{
    (e: 'update:modelValue', v: boolean): void
    (e: 'success'): void
  }>()

  const { t } = useI18n()

  const visible = computed({
    get: () => props.modelValue,
    set: (v) => emit('update:modelValue', v)
  })

  const userStore = useUserStore()
  const uploadAction = '/api/admin/upload'
  const uploadHeaders = computed(() => ({ Authorization: userStore.accessToken }))

  const formRef = ref<FormInstance>()
  const submitting = ref(false)

  const defaultForm = (): Api.Admin.AppVersionCreateParams => ({
    platform: 'android',
    version_name: '',
    version_code: 1,
    apk_url: '',
    notes: '',
    force_update: false
  })

  const formData = reactive<Api.Admin.AppVersionCreateParams>(defaultForm())

  /** version_code 必填且为正整数 */
  const validateVersionCode = (
    _rule: unknown,
    value: unknown,
    callback: (e?: Error) => void
  ): void => {
    if (value === null || value === undefined || value === '') {
      return callback(new Error(t('appVersion.rules.versionCodeRequired')))
    }
    const n = Number(value)
    if (!Number.isInteger(n) || n < 1) {
      return callback(new Error(t('appVersion.rules.versionCodePositive')))
    }
    callback()
  }

  const rules = computed<FormRules>(() => ({
    platform: [{ required: true, message: t('appVersion.rules.platform'), trigger: 'change' }],
    version_name: [
      { required: true, message: t('appVersion.rules.versionName'), trigger: 'blur' }
    ],
    version_code: [{ required: true, validator: validateVersionCode, trigger: 'blur' }]
  }))

  watch(visible, (v) => {
    if (v) {
      Object.assign(formData, defaultForm())
      formRef.value?.clearValidate()
    }
  })

  const handleUploadSuccess = (response: any) => {
    const url = response?.data?.apk_url || response?.data?.url || response?.url
    if (url) {
      formData.apk_url = url
      ElMessage.success(t('appVersion.uploadSuccess'))
    } else {
      ElMessage.warning(t('appVersion.uploadNoUrl'))
    }
  }

  const handleUploadError = () => {
    ElMessage.error(t('appVersion.uploadFailed'))
  }

  const handleSubmit = async () => {
    if (!formRef.value) return
    const valid = await formRef.value.validate().catch(() => false)
    if (!valid) return

    // 同平台下 version_code 重复：先提示，由操作者确认后再提交
    const dup = props.existing.find(
      (item) =>
        item.platform === formData.platform && item.version_code === formData.version_code
    )
    if (dup) {
      const confirmed = await ElMessageBox.confirm(
        t('appVersion.rules.versionCodeDuplicate', {
          code: formData.version_code,
          name: dup.version_name
        }),
        t('common.tips'),
        {
          confirmButtonText: t('common.confirm'),
          cancelButtonText: t('common.cancel'),
          type: 'warning'
        }
      ).catch(() => false)
      if (!confirmed) return
    }

    submitting.value = true
    try {
      await createAppVersion({ ...formData })
      ElMessage.success(t('appVersion.publishSuccess'))
      visible.value = false
      emit('success')
    } finally {
      submitting.value = false
    }
  }
</script>
