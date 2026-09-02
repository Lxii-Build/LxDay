<!-- 内容审核 - 待办列表 -->
<template>
  <div>
    <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="refreshData">
      <template #left>
        <ElSpace wrap>
          <ElInput
            v-model="searchForm.keyword"
            :placeholder="$t('contentAudit.todo.searchPlaceholder')"
            clearable
            style="width: 260px"
            @keyup.enter="handleSearch"
            @clear="handleSearch"
          />
          <ElButton type="primary" @click="handleSearch">{{ $t('common.search') }}</ElButton>
          <ElButton @click="handleReset">{{ $t('common.reset') }}</ElButton>
        </ElSpace>
      </template>
    </ArtTableHeader>

    <ArtTable
      :loading="loading"
      :data="data"
      :columns="columns"
      :pagination="pagination"
      :empty-text="$t('contentAudit.todo.empty')"
      @pagination:size-change="handleSizeChange"
      @pagination:current-change="handleCurrentChange"
    >
    </ArtTable>

    <ElDialog
      v-model="editVisible"
      :title="$t('contentAudit.todo.editTitle')"
      width="560px"
      align-center
    >
      <ElForm ref="editFormRef" :model="editForm" :rules="editRules" label-width="92px">
        <ElFormItem :label="$t('contentAudit.todo.form.title')" prop="title">
          <ElInput v-model.trim="editForm.title" maxlength="200" show-word-limit />
        </ElFormItem>
        <ElFormItem :label="$t('contentAudit.todo.form.note')" prop="note">
          <ElInput
            v-model="editForm.note"
            type="textarea"
            :rows="4"
            maxlength="5000"
            show-word-limit
          />
        </ElFormItem>
        <ElFormItem :label="$t('contentAudit.todo.form.assignee')" prop="assignee_id">
          <ElSelect v-model="editForm.assignee_id" style="width: 100%">
            <ElOption
              v-for="option in assigneeOptions"
              :key="option.id"
              :label="`${option.name || '-'} (#${option.id})`"
              :value="option.id"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem :label="$t('contentAudit.todo.form.remindEnabled')" prop="remind_enabled">
          <ElSwitch v-model="editForm.remind_enabled" />
        </ElFormItem>
        <ElFormItem :label="$t('contentAudit.todo.form.remindAt')" prop="remind_at">
          <ElDatePicker
            v-model="editForm.remind_at"
            type="datetime"
            value-format="YYYY-MM-DD HH:mm:ss"
            clearable
            style="width: 100%"
          />
        </ElFormItem>
        <ElFormItem :label="$t('contentAudit.todo.form.remindType')" prop="remind_type">
          <ElSelect v-model="editForm.remind_type" style="width: 100%">
            <ElOption :label="$t('contentAudit.todo.remind.normal')" :value="0" />
            <ElOption :label="$t('contentAudit.todo.remind.strong')" :value="1" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem :label="$t('contentAudit.todo.form.repeatType')" prop="repeat_type">
          <ElSelect v-model="editForm.repeat_type" style="width: 100%">
            <ElOption :label="$t('contentAudit.todo.repeat.once')" :value="0" />
            <ElOption :label="$t('contentAudit.todo.repeat.daily')" :value="1" />
            <ElOption :label="$t('contentAudit.todo.repeat.weekly')" :value="2" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem
          v-if="editForm.repeat_type === 2"
          :label="$t('contentAudit.todo.form.weekdays')"
          prop="weekdays"
        >
          <ElCheckboxGroup v-model="editForm.weekdays">
            <ElCheckbox v-for="day in weekdayOptions" :key="day.value" :value="day.value">
              {{ day.label }}
            </ElCheckbox>
          </ElCheckboxGroup>
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="editVisible = false">{{ $t('common.cancel') }}</ElButton>
        <ElButton type="primary" :loading="editSubmitting" @click="handleEditSubmit">
          {{ $t('common.save') }}
        </ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { useI18n } from 'vue-i18n'
  import { useTable } from '@/hooks/core/useTable'
  import { deleteTodo, fetchTodoList, updateTodo } from '@/api/admin'
  import { useUserStore } from '@/store/modules/user'
  import { formatDateTime } from '@/utils/format/datetime'
  import {
    ElButton,
    ElCheckbox,
    ElCheckboxGroup,
    ElDatePicker,
    ElForm,
    ElInput,
    ElMessage,
    ElMessageBox,
    ElOption,
    ElSelect,
    ElSwitch,
    ElTag,
    type FormInstance,
    type FormRules
  } from 'element-plus'

  defineOptions({ name: 'ContentAuditTodo' })

  type TodoItem = Api.Admin.TodoItem

  const { t } = useI18n()
  const userStore = useUserStore()
  const isSuper = computed(() => userStore.getUserInfo.roles?.includes('super') ?? false)

  const searchForm = ref({ keyword: '' })
  const editVisible = ref(false)
  const editSubmitting = ref(false)
  const editingTodo = ref<TodoItem | null>(null)
  const editFormRef = ref<FormInstance>()
  const editForm = reactive({
    assignee_id: 0,
    title: '',
    note: '',
    remind_at: '',
    remind_type: 0,
    repeat_type: 0,
    weekdays: [] as number[],
    remind_enabled: true
  })
  const weekdayOptions = computed(() => [
    { value: 1, label: t('contentAudit.todo.weekdays.mon') },
    { value: 2, label: t('contentAudit.todo.weekdays.tue') },
    { value: 4, label: t('contentAudit.todo.weekdays.wed') },
    { value: 8, label: t('contentAudit.todo.weekdays.thu') },
    { value: 16, label: t('contentAudit.todo.weekdays.fri') },
    { value: 32, label: t('contentAudit.todo.weekdays.sat') },
    { value: 64, label: t('contentAudit.todo.weekdays.sun') }
  ])
  const assigneeOptions = computed(() => {
    if (!editingTodo.value) return []
    const options = [
      { id: editingTodo.value.creator_id, name: editingTodo.value.creator_name },
      { id: editingTodo.value.assignee_id, name: editingTodo.value.assignee_name }
    ]
    return options.filter(
      (option, index) => option.id > 0 && options.findIndex((v) => v.id === option.id) === index
    )
  })
  const editRules = computed<FormRules>(() => ({
    title: [
      { required: true, message: t('contentAudit.todo.rules.title'), trigger: 'blur' },
      { max: 200, message: t('contentAudit.todo.rules.titleLength'), trigger: 'blur' }
    ],
    note: [{ max: 5000, message: t('contentAudit.todo.rules.note'), trigger: 'blur' }],
    assignee_id: [
      { required: true, message: t('contentAudit.todo.rules.assignee'), trigger: 'change' }
    ]
  }))

  /** 提醒频率映射：0 仅一次 / 1 每天 / 2 每周 */
  const repeatText = (type: number) => {
    if (type === 1) return t('contentAudit.todo.repeat.daily')
    if (type === 2) return t('contentAudit.todo.repeat.weekly')
    return t('contentAudit.todo.repeat.once')
  }

  /**
   * 状态映射，取值与服务端 store.go 一致：
   * 0 = 待办（CreateTodo 写入 0）、1 = 已完成（CompleteTodo 置 1）、2 = 已删除（DeleteTodo 置 2）
   */
  const statusMap: Record<number, { key: string; type: 'warning' | 'success' | 'info' }> = {
    0: { key: 'contentAudit.todo.status.pending', type: 'warning' },
    1: { key: 'contentAudit.todo.status.done', type: 'success' },
    2: { key: 'contentAudit.todo.status.deleted', type: 'info' }
  }

  const {
    columns,
    columnChecks,
    data,
    loading,
    pagination,
    getData,
    replaceSearchParams,
    resetSearchParams,
    handleSizeChange,
    handleCurrentChange,
    refreshData
  } = useTable({
    core: {
      apiFn: fetchTodoList,
      apiParams: { current: 1, size: 20, keyword: '' },
      columnsFactory: () => [
        { prop: 'id', label: t('contentAudit.todo.table.id'), width: 80 },
        { prop: 'title', label: t('contentAudit.todo.table.title'), minWidth: 180 },
        {
          prop: 'note',
          label: t('contentAudit.todo.table.note'),
          minWidth: 200,
          showOverflowTooltip: true,
          formatter: (row) => row.note || '-'
        },
        {
          prop: 'creator_name',
          label: t('contentAudit.todo.table.creator'),
          minWidth: 120,
          formatter: (row) => row.creator_name || `#${row.creator_id}`
        },
        {
          prop: 'assignee_name',
          label: t('contentAudit.todo.table.assignee'),
          minWidth: 120,
          formatter: (row) => row.assignee_name || '-'
        },
        {
          prop: 'remind_enabled',
          label: t('contentAudit.todo.table.remindEnabled'),
          width: 110,
          formatter: (row) =>
            h(ElTag, { type: row.remind_enabled ? 'success' : 'info' }, () =>
              row.remind_enabled
                ? t('contentAudit.todo.remind.on')
                : t('contentAudit.todo.remind.off')
            )
        },
        {
          prop: 'repeat_type',
          label: t('contentAudit.todo.table.repeat'),
          width: 110,
          formatter: (row) => repeatText(row.repeat_type)
        },
        {
          prop: 'remind_at',
          label: t('contentAudit.todo.table.remindAt'),
          minWidth: 180,
          formatter: (row) => formatDateTime(row.remind_at)
        },
        { prop: 'pair_id', label: t('contentAudit.todo.table.pairId'), width: 100 },
        {
          prop: 'status',
          label: t('contentAudit.todo.table.status'),
          width: 110,
          formatter: (row) => {
            const s = statusMap[row.status]
            return s
              ? h(ElTag, { type: s.type }, () => t(s.key))
              : h(ElTag, { type: 'info' }, () => String(row.status))
          }
        },
        {
          prop: 'operation',
          label: t('common.operation'),
          width: 160,
          fixed: 'right',
          formatter: (row) =>
            isSuper.value
              ? h('div', [
                  row.status !== 2
                    ? h(
                        ElButton,
                        { type: 'primary', link: true, onClick: () => openEdit(row) },
                        () => t('common.edit')
                      )
                    : null,
                  h(
                    ElButton,
                    {
                      type: 'danger',
                      link: true,
                      disabled: row.status === 2,
                      onClick: () => handleDelete(row)
                    },
                    () => t('common.delete')
                  )
                ])
              : h('span', { class: 'art-text-gray-400' }, t('systemSettings.runtime.superOnly'))
        }
      ]
    }
  })

  const handleSearch = () => {
    replaceSearchParams({ keyword: searchForm.value.keyword })
    getData()
  }

  const handleReset = () => {
    searchForm.value.keyword = ''
    resetSearchParams()
  }

  const openEdit = (row: TodoItem) => {
    editingTodo.value = row
    Object.assign(editForm, {
      assignee_id: row.assignee_id,
      title: row.title || '',
      note: row.note || '',
      remind_at: formatDateTime(row.remind_at, ''),
      remind_type: row.remind_type ?? 0,
      repeat_type: row.repeat_type ?? 0,
      weekdays: weekdayOptions.value
        .map((day) => day.value)
        .filter((day) => (row.weekdays & day) !== 0),
      remind_enabled: row.remind_enabled
    })
    editVisible.value = true
    nextTick(() => editFormRef.value?.clearValidate())
  }

  const handleEditSubmit = async () => {
    if (!editingTodo.value || !editFormRef.value) return
    const valid = await editFormRef.value.validate().catch(() => false)
    if (!valid) return
    const weekdays =
      editForm.repeat_type === 2 ? editForm.weekdays.reduce((mask, day) => mask | day, 0) : 0
    editSubmitting.value = true
    try {
      await updateTodo(editingTodo.value.id, {
        assignee_id: editForm.assignee_id,
        title: editForm.title.trim(),
        note: editForm.note.trim(),
        remind_at: editForm.remind_at || null,
        remind_type: editForm.remind_type,
        repeat_type: editForm.repeat_type,
        weekdays,
        remind_enabled: editForm.remind_enabled
      })
      ElMessage.success(t('contentAudit.todo.editSuccess'))
      editVisible.value = false
      await refreshData()
    } finally {
      editSubmitting.value = false
    }
  }

  const handleDelete = async (row: TodoItem) => {
    try {
      await ElMessageBox.confirm(
        t('contentAudit.todo.deleteConfirm', { title: row.title }),
        t('contentAudit.todo.deleteTitle'),
        {
          confirmButtonText: t('common.confirm'),
          cancelButtonText: t('common.cancel'),
          type: 'warning'
        }
      )
      await deleteTodo(row.id)
      ElMessage.success(t('common.deleteSuccess'))
      await refreshData()
    } catch (error) {
      if (error === 'cancel' || error === 'close') return
    }
  }
</script>
