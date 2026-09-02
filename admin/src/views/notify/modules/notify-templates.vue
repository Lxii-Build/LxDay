<!-- 通知 - 模板管理 -->
<template>
  <div class="notify-templates">
    <ElAlert
      v-if="!isSuper"
      type="info"
      show-icon
      :closable="false"
      :title="$t('systemSettings.runtime.superOnly')"
      class="mb-3"
    />
    <div class="mb-3 flex justify-end">
      <ElButton :loading="loading" @click="loadTemplates">{{ $t('common.refresh') }}</ElButton>
      <ElButton v-if="isSuper" type="primary" @click="openDialog()">
        {{ $t('notify.templates.create') }}
      </ElButton>
    </div>

    <ElTable v-loading="loading" :data="templates" border>
      <ElTableColumn prop="code" :label="$t('notify.templates.table.code')" width="180" />
      <ElTableColumn prop="title" :label="$t('notify.templates.table.title')" min-width="160" />
      <ElTableColumn
        prop="body"
        :label="$t('notify.templates.table.body')"
        min-width="240"
        show-overflow-tooltip
      />
      <ElTableColumn :label="$t('notify.templates.table.enabled')" width="90">
        <template #default="{ row }">
          <ElTag :type="row.enabled ? 'success' : 'info'">
            {{ row.enabled ? $t('common.enabled') : $t('common.disabled') }}
          </ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn :label="$t('notify.templates.table.updatedAt')" width="180">
        <template #default="{ row }">{{ formatDateTime(row.updated_at) }}</template>
      </ElTableColumn>
      <ElTableColumn :label="$t('common.operation')" width="140" fixed="right">
        <template #default="{ row }">
          <template v-if="isSuper">
            <ElButton type="primary" link @click="openDialog(row)">{{
              $t('common.edit')
            }}</ElButton>
            <ElButton type="danger" link @click="handleDelete(row)">
              {{ $t('common.delete') }}
            </ElButton>
          </template>
          <span v-else class="art-text-gray-400">{{ $t('systemSettings.runtime.superOnly') }}</span>
        </template>
      </ElTableColumn>
      <template #empty>
        <ElEmpty :description="$t('notify.templates.empty')" :image-size="120" />
      </template>
    </ElTable>

    <ElDialog
      v-model="dialogVisible"
      :title="isEdit ? $t('notify.templates.editTitle') : $t('notify.templates.createTitle')"
      width="520px"
      align-center
    >
      <ElForm ref="formRef" :model="formData" :rules="rules" label-width="80px">
        <ElFormItem :label="$t('notify.templates.table.code')" prop="code">
          <ElInput
            v-model.trim="formData.code"
            :disabled="isEdit"
            maxlength="64"
            show-word-limit
            :placeholder="$t('notify.templates.codePlaceholder')"
          />
        </ElFormItem>
        <ElFormItem :label="$t('notify.templates.table.title')" prop="title">
          <ElInput v-model.trim="formData.title" maxlength="100" show-word-limit />
        </ElFormItem>
        <ElFormItem :label="$t('notify.templates.table.body')" prop="body">
          <ElInput
            v-model="formData.body"
            type="textarea"
            :rows="4"
            maxlength="2000"
            show-word-limit
          />
        </ElFormItem>
        <ElFormItem :label="$t('notify.templates.table.enabled')">
          <ElSwitch v-model="formData.enabled" :active-value="1" :inactive-value="0" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="dialogVisible = false">{{ $t('common.cancel') }}</ElButton>
        <ElButton type="primary" :loading="submitting" @click="handleSubmit">
          {{ $t('common.save') }}
        </ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { useI18n } from 'vue-i18n'
  import { fetchNotifyTemplates, upsertNotifyTemplate, deleteNotifyTemplate } from '@/api/admin'
  import { useUserStore } from '@/store/modules/user'
  import { formatDateTime } from '@/utils/format/datetime'
  import { ElAlert, ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'

  defineOptions({ name: 'NotifyTemplates' })

  const { t } = useI18n()
  const userStore = useUserStore()
  const isSuper = computed(() => userStore.getUserInfo.roles?.includes('super') ?? false)

  const templates = ref<Api.Admin.NotifyTemplate[]>([])
  const loading = ref(false)
  const dialogVisible = ref(false)
  const isEdit = ref(false)
  const submitting = ref(false)
  const formRef = ref<FormInstance>()

  const formData = reactive({
    code: '',
    title: '',
    body: '',
    enabled: 1
  })

  const rules = computed<FormRules>(() => ({
    code: [{ required: true, message: t('notify.rules.code'), trigger: 'blur' }],
    title: [{ required: true, message: t('notify.rules.title'), trigger: 'blur' }],
    body: [{ required: true, message: t('notify.rules.body'), trigger: 'blur' }]
  }))

  const loadTemplates = async () => {
    loading.value = true
    try {
      templates.value = await fetchNotifyTemplates()
    } finally {
      loading.value = false
    }
  }

  const openDialog = (row?: Api.Admin.NotifyTemplate) => {
    isEdit.value = !!row
    Object.assign(formData, {
      code: row?.code ?? '',
      title: row?.title ?? '',
      body: row?.body ?? '',
      enabled: row?.enabled ?? 1
    })
    dialogVisible.value = true
    formRef.value?.clearValidate()
  }

  const handleSubmit = async () => {
    if (!formRef.value) return
    const valid = await formRef.value.validate().catch(() => false)
    if (!valid) return
    submitting.value = true
    try {
      await upsertNotifyTemplate({ ...formData })
      ElMessage.success(t('common.saveSuccess'))
      dialogVisible.value = false
      await loadTemplates()
    } finally {
      submitting.value = false
    }
  }

  const handleDelete = async (row: Api.Admin.NotifyTemplate) => {
    try {
      await ElMessageBox.confirm(
        t('notify.templates.deleteConfirm', { code: row.code }),
        t('notify.templates.deleteTitle'),
        {
          confirmButtonText: t('common.confirmDelete'),
          cancelButtonText: t('common.cancel'),
          type: 'warning'
        }
      )
      await deleteNotifyTemplate(row.id)
      ElMessage.success(t('common.deleteSuccess'))
      await loadTemplates()
    } catch (error) {
      if (error === 'cancel' || error === 'close') return
    }
  }

  onMounted(loadTemplates)
</script>
