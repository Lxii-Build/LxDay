<!-- APP 版本 - 新增弹窗 -->
<template>
  <ElDialog v-model="visible" title="发布新版本" width="520px" align-center>
    <ElForm ref="formRef" :model="formData" :rules="rules" label-width="100px">
      <ElFormItem label="平台" prop="platform">
        <ElSelect v-model="formData.platform" style="width: 100%">
          <ElOption label="Android" value="android" />
          <ElOption label="iOS" value="ios" />
        </ElSelect>
      </ElFormItem>
      <ElFormItem label="版本名" prop="version_name">
        <ElInput v-model.trim="formData.version_name" placeholder="如 1.2.0" />
      </ElFormItem>
      <ElFormItem label="版本号" prop="version_code">
        <ElInputNumber v-model="formData.version_code" :min="1" :step="1" style="width: 100%" />
      </ElFormItem>
      <ElFormItem label="APK 地址" prop="apk_url">
        <ElInput v-model.trim="formData.apk_url" placeholder="填写 APK 下载地址，或点击右侧上传">
          <template #append>
            <ElUpload
              :action="uploadAction"
              :headers="uploadHeaders"
              :show-file-list="false"
              :on-success="handleUploadSuccess"
              :on-error="handleUploadError"
              accept=".apk"
            >
              <ElButton>上传</ElButton>
            </ElUpload>
          </template>
        </ElInput>
      </ElFormItem>
      <ElFormItem label="更新说明" prop="notes">
        <ElInput v-model="formData.notes" type="textarea" :rows="3" placeholder="本次更新内容" />
      </ElFormItem>
      <ElFormItem label="强制更新" prop="force_update">
        <ElSwitch v-model="formData.force_update" />
      </ElFormItem>
    </ElForm>
    <template #footer>
      <ElButton @click="visible = false">取消</ElButton>
      <ElButton type="primary" :loading="submitting" @click="handleSubmit">发布</ElButton>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
  import { createAppVersion } from '@/api/admin'
  import { useUserStore } from '@/store/modules/user'
  import { ElMessage, type FormInstance, type FormRules } from 'element-plus'

  defineOptions({ name: 'AppVersionDialog' })

  const props = defineProps<{ modelValue: boolean }>()
  const emit = defineEmits<{
    (e: 'update:modelValue', v: boolean): void
    (e: 'success'): void
  }>()

  const visible = computed({
    get: () => props.modelValue,
    set: (v) => emit('update:modelValue', v)
  })

  const userStore = useUserStore()
  // 上传接口（如后端未实现，请改为对象存储直传或后端上传接口）
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

  const rules = reactive<FormRules>({
    platform: [{ required: true, message: '请选择平台', trigger: 'change' }],
    version_name: [{ required: true, message: '请输入版本名', trigger: 'blur' }],
    version_code: [{ required: true, message: '请输入版本号', trigger: 'blur' }]
  })

  watch(visible, (v) => {
    if (v) Object.assign(formData, defaultForm())
  })

  const handleUploadSuccess = (response: any) => {
    const url = response?.data?.apk_url || response?.data?.url || response?.url
    if (url) {
      formData.apk_url = url
      ElMessage.success('上传成功')
    } else {
      ElMessage.warning('上传成功，但未返回地址，请手动填写')
    }
  }

  const handleUploadError = () => {
    ElMessage.error('上传失败，请稍后重试或直接填写 APK 地址')
  }

  const handleSubmit = async () => {
    if (!formRef.value) return
    const valid = await formRef.value.validate().catch(() => false)
    if (!valid) return
    submitting.value = true
    try {
      await createAppVersion({ ...formData })
      ElMessage.success('发布成功')
      visible.value = false
      emit('success')
    } finally {
      submitting.value = false
    }
  }
</script>
