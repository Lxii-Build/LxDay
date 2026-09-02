<!-- 管理员管理（仅超级管理员） -->
<template>
  <div class="admin-manage-page art-full-height">
    <ElCard class="art-table-card" shadow="never">
      <div class="mb-3 flex justify-end">
        <ElButton :loading="loading" @click="loadAdmins">{{ $t('common.refresh') }}</ElButton>
        <ElButton type="primary" @click="openCreate">{{ $t('adminManage.create') }}</ElButton>
      </div>

      <ElTable v-loading="loading" :data="pagedAdmins" border>
        <ElTableColumn prop="id" :label="$t('adminManage.table.id')" width="80" />
        <ElTableColumn prop="username" :label="$t('adminManage.table.username')" min-width="160" />
        <ElTableColumn prop="email" :label="$t('adminManage.table.email')" min-width="180">
          <template #default="{ row }">{{ row.email || '-' }}</template>
        </ElTableColumn>
        <ElTableColumn :label="$t('adminManage.table.role')" width="140">
          <template #default="{ row }">
            <ElTag :type="row.role === 'super' ? 'danger' : 'primary'">
              {{
                row.role === 'super' ? $t('adminManage.role.super') : $t('adminManage.role.admin')
              }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn :label="$t('adminManage.table.status')" width="100">
          <template #default="{ row }">
            <ElTag :type="row.status === 1 ? 'success' : 'info'">
              {{
                row.status === 1
                  ? $t('adminManage.status.normal')
                  : $t('adminManage.status.disabled')
              }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn :label="$t('adminManage.table.mustChange')" width="110">
          <template #default="{ row }">
            <ElTag v-if="row.must_change" type="warning">
              {{ $t('adminManage.mustChange.pending') }}
            </ElTag>
            <span v-else>-</span>
          </template>
        </ElTableColumn>
        <ElTableColumn :label="$t('common.operation')" width="300" fixed="right">
          <template #default="{ row }">
            <ElButton type="primary" link @click="openEditRole(row)">
              {{ $t('adminManage.editRole') }}
            </ElButton>
            <ElButton
              :type="row.status === 1 ? 'warning' : 'success'"
              link
              @click="toggleStatus(row)"
            >
              {{ row.status === 1 ? $t('common.disable') : $t('common.enable') }}
            </ElButton>
            <ElButton type="warning" link @click="openResetPassword(row)">
              {{ $t('adminManage.resetPassword') }}
            </ElButton>
            <ElButton type="danger" link @click="handleDelete(row)">
              {{ $t('common.delete') }}
            </ElButton>
          </template>
        </ElTableColumn>
        <template #empty>
          <ElEmpty :description="$t('adminManage.empty')" :image-size="120" />
        </template>
      </ElTable>

      <div class="mt-4 flex justify-end">
        <ElPagination
          v-model:current-page="pagination.current"
          v-model:page-size="pagination.size"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, prev, pager, next, sizes, jumper"
          background
        />
      </div>
    </ElCard>

    <!-- 新增管理员 -->
    <ElDialog
      v-model="createVisible"
      :title="$t('adminManage.createTitle')"
      width="480px"
      align-center
    >
      <ElForm ref="createFormRef" :model="createForm" :rules="createRules" label-width="90px">
        <ElFormItem :label="$t('adminManage.table.username')" prop="username">
          <ElInput v-model.trim="createForm.username" />
        </ElFormItem>
        <ElFormItem :label="$t('adminManage.form.password')" prop="password">
          <ElInput
            v-model.trim="createForm.password"
            type="password"
            show-password
            :placeholder="$t('adminManage.form.passwordPlaceholder')"
          />
        </ElFormItem>
        <ElFormItem :label="$t('adminManage.table.role')" prop="role">
          <ElSelect v-model="createForm.role" style="width: 100%">
            <ElOption :label="$t('adminManage.role.admin')" value="admin" />
            <ElOption :label="$t('adminManage.role.super')" value="super" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem :label="$t('adminManage.table.email')" prop="email">
          <ElInput
            v-model.trim="createForm.email"
            :placeholder="$t('adminManage.form.emailPlaceholder')"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="createVisible = false">{{ $t('common.cancel') }}</ElButton>
        <ElButton type="primary" :loading="submitting" @click="handleCreate">
          {{ $t('adminManage.form.submit') }}
        </ElButton>
      </template>
    </ElDialog>

    <!-- 编辑角色 -->
    <ElDialog
      v-model="roleVisible"
      :title="$t('adminManage.editRoleTitle')"
      width="420px"
      align-center
    >
      <ElForm label-width="90px">
        <ElFormItem :label="$t('adminManage.table.username')">
          <span>{{ current?.username }}</span>
        </ElFormItem>
        <ElFormItem :label="$t('adminManage.table.role')">
          <ElSelect v-model="roleForm.role" style="width: 100%">
            <ElOption :label="$t('adminManage.role.admin')" value="admin" />
            <ElOption :label="$t('adminManage.role.super')" value="super" />
          </ElSelect>
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="roleVisible = false">{{ $t('common.cancel') }}</ElButton>
        <ElButton type="primary" :loading="submitting" @click="handleUpdateRole">
          {{ $t('common.save') }}
        </ElButton>
      </template>
    </ElDialog>

    <!-- 重置密码 -->
    <ElDialog
      v-model="passwordVisible"
      :title="$t('adminManage.resetPasswordTitle')"
      width="460px"
      align-center
    >
      <ElForm
        ref="passwordFormRef"
        :model="passwordForm"
        :rules="passwordRules"
        label-width="100px"
      >
        <ElFormItem :label="$t('adminManage.table.username')">
          <span>{{ current?.username }}</span>
        </ElFormItem>
        <ElFormItem :label="$t('adminManage.form.newPassword')" prop="password">
          <ElInput
            v-model.trim="passwordForm.password"
            type="password"
            show-password
            :placeholder="$t('adminManage.form.passwordPlaceholder')"
          />
        </ElFormItem>
        <ElFormItem :label="$t('adminManage.form.confirmPassword')" prop="confirmPassword">
          <ElInput v-model.trim="passwordForm.confirmPassword" type="password" show-password />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="passwordVisible = false">{{ $t('common.cancel') }}</ElButton>
        <ElButton type="primary" :loading="submitting" @click="handleResetPassword">
          {{ $t('common.save') }}
        </ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { useI18n } from 'vue-i18n'
  import {
    fetchAdmins,
    createAdmin,
    updateAdminRole,
    updateAdminStatus,
    resetAdminPassword,
    deleteAdmin
  } from '@/api/admin'
  import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'

  defineOptions({ name: 'AdminManage' })

  type AdminItem = Api.Admin.AdminItem

  const { t } = useI18n()

  /** 密码规则：至少 12 位且同时包含大写、小写、数字 */
  const PASSWORD_RE = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).{12,}$/

  const admins = ref<AdminItem[]>([])
  const total = ref(0)
  const loading = ref(false)
  const submitting = ref(false)
  const current = ref<AdminItem | null>(null)

  const createVisible = ref(false)
  const roleVisible = ref(false)
  const passwordVisible = ref(false)

  const createFormRef = ref<FormInstance>()
  const passwordFormRef = ref<FormInstance>()

  const pagination = reactive({ current: 1, size: 10 })

  /**
   * 服务端目前返回裸数组（无分页），此处做前端分页；
   * 若后续服务端改为 {list,total}，归一化逻辑会自动走服务端分页。
   */
  const serverPaged = ref(false)

  const pagedAdmins = computed(() => {
    if (serverPaged.value) return admins.value
    const start = (pagination.current - 1) * pagination.size
    return admins.value.slice(start, start + pagination.size)
  })

  const createForm = reactive<Api.Admin.AdminCreateParams>({
    username: '',
    password: '',
    role: 'admin',
    email: ''
  })

  const roleForm = reactive({ role: 'admin' })
  const passwordForm = reactive({ password: '', confirmPassword: '' })

  const validatePassword = (_r: unknown, value: string, callback: (e?: Error) => void): void => {
    if (!value) return callback(new Error(t('adminManage.rules.passwordRequired')))
    if (!PASSWORD_RE.test(value)) return callback(new Error(t('adminManage.rules.passwordWeak')))
    callback()
  }

  const validateConfirm = (_r: unknown, value: string, callback: (e?: Error) => void): void => {
    if (value !== passwordForm.password) {
      return callback(new Error(t('adminManage.rules.passwordMismatch')))
    }
    callback()
  }

  const createRules = computed<FormRules>(() => ({
    username: [{ required: true, message: t('adminManage.rules.username'), trigger: 'blur' }],
    password: [{ required: true, validator: validatePassword, trigger: 'blur' }],
    role: [{ required: true, message: t('adminManage.rules.role'), trigger: 'change' }],
    email: [{ type: 'email', message: t('adminManage.rules.email'), trigger: 'blur' }]
  }))

  const passwordRules = computed<FormRules>(() => ({
    password: [{ required: true, validator: validatePassword, trigger: 'blur' }],
    confirmPassword: [{ required: true, validator: validateConfirm, trigger: 'blur' }]
  }))

  const loadAdmins = async () => {
    loading.value = true
    try {
      const res = await fetchAdmins({ current: pagination.current, size: pagination.size })
      if (Array.isArray(res)) {
        serverPaged.value = false
        admins.value = res
        total.value = res.length
      } else {
        serverPaged.value = true
        admins.value = res?.list ?? []
        total.value = res?.total ?? 0
      }
    } finally {
      loading.value = false
    }
  }

  // 服务端分页时切页需重新拉取；前端分页由 computed 处理
  watch([() => pagination.current, () => pagination.size], () => {
    if (serverPaged.value) loadAdmins()
  })

  const openCreate = () => {
    Object.assign(createForm, { username: '', password: '', role: 'admin', email: '' })
    createVisible.value = true
    createFormRef.value?.clearValidate()
  }

  const handleCreate = async () => {
    if (!createFormRef.value) return
    const valid = await createFormRef.value.validate().catch(() => false)
    if (!valid) return
    submitting.value = true
    try {
      await createAdmin({ ...createForm })
      ElMessage.success(t('adminManage.createSuccess'))
      createVisible.value = false
      await loadAdmins()
    } finally {
      submitting.value = false
    }
  }

  const openEditRole = (row: AdminItem) => {
    current.value = row
    roleForm.role = row.role === 'super' ? 'super' : 'admin'
    roleVisible.value = true
  }

  const handleUpdateRole = async () => {
    if (!current.value) return
    submitting.value = true
    try {
      await updateAdminRole(current.value.id, roleForm.role)
      ElMessage.success(t('common.saveSuccess'))
      roleVisible.value = false
      await loadAdmins()
    } finally {
      submitting.value = false
    }
  }

  /** 启用=1 / 禁用=2（服务端约定） */
  const toggleStatus = async (row: AdminItem) => {
    const next = row.status === 1 ? 2 : 1
    try {
      await ElMessageBox.confirm(
        next === 1
          ? t('adminManage.enableConfirm', { name: row.username })
          : t('adminManage.disableConfirm', { name: row.username }),
        next === 1 ? t('adminManage.enableTitle') : t('adminManage.disableTitle'),
        {
          confirmButtonText: t('common.confirm'),
          cancelButtonText: t('common.cancel'),
          type: 'warning'
        }
      )
      await updateAdminStatus(row.id, next)
      ElMessage.success(next === 1 ? t('common.enableSuccess') : t('common.disableSuccess'))
      await loadAdmins()
    } catch (error) {
      if (error === 'cancel' || error === 'close') return
    }
  }

  const openResetPassword = (row: AdminItem) => {
    current.value = row
    passwordForm.password = ''
    passwordForm.confirmPassword = ''
    passwordVisible.value = true
    passwordFormRef.value?.clearValidate()
  }

  const handleResetPassword = async () => {
    if (!passwordFormRef.value || !current.value) return
    const valid = await passwordFormRef.value.validate().catch(() => false)
    if (!valid) return

    const confirmed = await ElMessageBox.confirm(
      t('adminManage.resetPasswordConfirm', { name: current.value.username }),
      t('adminManage.resetPasswordTitle'),
      {
        confirmButtonText: t('common.confirm'),
        cancelButtonText: t('common.cancel'),
        type: 'warning'
      }
    ).catch(() => false)
    if (!confirmed) return

    submitting.value = true
    try {
      await resetAdminPassword(current.value.id, passwordForm.password)
      ElMessage.success(t('adminManage.resetPasswordSuccess'))
      passwordVisible.value = false
    } finally {
      submitting.value = false
    }
  }

  const handleDelete = async (row: AdminItem) => {
    try {
      await ElMessageBox.confirm(
        t('adminManage.deleteConfirm', { name: row.username }),
        t('adminManage.deleteTitle'),
        {
          confirmButtonText: t('common.confirmDelete'),
          cancelButtonText: t('common.cancel'),
          type: 'warning'
        }
      )
      await deleteAdmin(row.id)
      ElMessage.success(t('common.deleteSuccess'))
      await loadAdmins()
    } catch (error) {
      if (error === 'cancel' || error === 'close') return
    }
  }

  onMounted(loadAdmins)
</script>
