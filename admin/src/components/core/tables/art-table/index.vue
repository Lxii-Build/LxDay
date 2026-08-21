<!-- 表格组件 -->
<!-- 支持：el-table 全部属性、事件、插槽，同官方文档写法 -->
<!-- 扩展功能：分页组件、渲染自定义列、loading、表格全局边框、斑马纹、表格尺寸、表头背景配置 -->
<!-- 获取 ref：默认暴露了 elTableRef 外部通过 ref.value.elTableRef 可以调用 el-table 方法 -->
<template>
  <div class="art-table" :class="{ 'is-empty': isEmpty }" :style="containerHeight">
    <!--
      窄屏卡片化（管理员 Q44=B）。

      此前 el-table 在移动端**除了分页器布局切换之外零处理**：
      没有横向滚动容器、没有卡片化、没有按断点隐藏列，
      11 个用表格的页面里 9 个还没设列宽 —— 手机上列被挤成一团或整体溢出，
      这就是管理员说的「后台手机上不能用」。

      收敛在这个共享组件里做，11 个页面零改动即全部受益；
      逐页去调不仅工作量大，日后新增页面还会漏。

      卡片复用与表格**完全相同的列配置与插槽**（同名 slot、同一份 scope），
      所以各页的自定义渲染（状态标签、操作按钮）在卡片里照常工作。
    -->
    <div v-if="isMobileLayout && canRenderCards" v-loading="!!loading" class="art-table__cards">
      <ElEmpty v-if="isEmpty && !loading" :description="emptyText" :image-size="100" />
      <div v-for="(row, rowIndex) in mobileRows" :key="rowIndex" class="art-table__card">
        <div v-for="col in mobileFieldColumns" :key="col.prop || col.label" class="art-table__cell">
          <span class="art-table__cell-label">{{ col.label }}</span>
          <span class="art-table__cell-value">
            <slot
              v-if="col.useSlot && col.prop"
              :name="col.slotName || col.prop"
              v-bind="{
                row,
                $index: rowIndex,
                column: col,
                prop: col.prop,
                value: row[col.prop]
              }"
            />
            <template v-else>{{ formatCellText(row, col) }}</template>
          </span>
        </div>
        <!-- 操作列单独放卡底：手机上按钮要足够大、位置固定，不该混在字段里 -->
        <div v-if="mobileActionColumns.length" class="art-table__card-actions">
          <template v-for="col in mobileActionColumns" :key="col.prop || col.label">
            <slot
              :name="col.slotName || col.prop || 'operation'"
              v-bind="{
                row,
                $index: rowIndex,
                column: col,
                prop: col.prop,
                value: col.prop ? row[col.prop] : undefined
              }"
            />
          </template>
        </div>
      </div>
    </div>

    <!--
      表格模式。窄屏走不到这里，除非该页用的是 <ElTableColumn> 子元素写法
      而不是 :columns 配置（如 admin-manage）——那种页面拿不到列元信息，
      无法卡片化，只能退回"可横向滚动的表格"，由 .art-table--scroll-x 保证不撑破布局。
    -->
    <ElTable
      v-else
      ref="elTableRef"
      v-loading="!!loading"
      :class="{ 'art-table--scroll-x': isMobileLayout }"
      v-bind="mergedTableProps"
    >
      <template v-for="col in columns" :key="col.prop || col.type">
        <!-- 渲染全局序号列 -->
        <ElTableColumn v-if="col.type === 'globalIndex'" v-bind="{ ...col }">
          <template #default="{ $index }">
            <span>{{ getGlobalIndex($index) }}</span>
          </template>
        </ElTableColumn>

        <!-- 渲染展开行 -->
        <ElTableColumn v-else-if="col.type === 'expand'" v-bind="cleanColumnProps(col)">
          <template #default="{ row }">
            <component :is="col.formatter ? col.formatter(row) : null" />
          </template>
        </ElTableColumn>

        <!-- 渲染普通列 -->
        <ElTableColumn v-else v-bind="cleanColumnProps(col)">
          <template v-if="col.useHeaderSlot && col.prop" #header="headerScope">
            <slot
              :name="col.headerSlotName || `${col.prop}-header`"
              v-bind="{ ...headerScope, prop: col.prop, label: col.label }"
            >
              {{ col.label }}
            </slot>
          </template>
          <template v-if="col.useSlot && col.prop" #default="slotScope">
            <slot
              v-if="shouldRenderSlotScope(slotScope)"
              :name="col.slotName || col.prop"
              v-bind="{
                ...slotScope,
                prop: col.prop,
                value: col.prop ? slotScope.row[col.prop] : undefined
              }"
            />
          </template>
        </ElTableColumn>
      </template>

      <template v-if="$slots.default" #default><slot /></template>

      <template #empty>
        <div v-if="loading"></div>
        <ElEmpty v-else :description="emptyText" :image-size="120" />
      </template>
    </ElTable>

    <div
      class="pagination custom-pagination"
      v-if="showPagination"
      :class="mergedPaginationOptions?.align"
      ref="paginationRef"
    >
      <ElPagination
        v-bind="mergedPaginationOptions"
        :total="pagination?.total"
        :disabled="loading"
        :page-size="pagination?.size"
        :current-page="pagination?.current"
        @size-change="handleSizeChange"
        @current-change="handleCurrentChange"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
  import { ref, computed, nextTick, watchEffect, getCurrentInstance, useAttrs } from 'vue'
  import type { ElTable, TableProps } from 'element-plus'
  import { storeToRefs } from 'pinia'
  import { ColumnOption } from '@/types'
  import { useTableStore } from '@/store/modules/table'
  import { useCommon } from '@/hooks/core/useCommon'
  import { useTableHeight } from '@/hooks/core/useTableHeight'
  import { useResizeObserver, useWindowSize } from '@vueuse/core'

  defineOptions({ name: 'ArtTable' })

  const { width } = useWindowSize()
  const elTableRef = ref<InstanceType<typeof ElTable> | null>(null)
  const paginationRef = ref<HTMLElement>()
  const tableHeaderRef = ref<HTMLElement>()
  const tableStore = useTableStore()
  const { isBorder, isZebra, tableSize, isFullScreen, isHeaderBackground } = storeToRefs(tableStore)

  /** 分页配置接口 */
  interface PaginationConfig {
    /** 当前页码 */
    current: number
    /** 每页显示条目个数 */
    size: number
    /** 总条目数 */
    total: number
  }

  /** 分页器配置选项接口 */
  interface PaginationOptions {
    /** 每页显示个数选择器的选项列表 */
    pageSizes?: number[]
    /** 分页器的对齐方式 */
    align?: 'left' | 'center' | 'right'
    /** 分页器的布局 */
    layout?: string
    /** 是否显示分页器背景 */
    background?: boolean
    /** 只有一页时是否隐藏分页器 */
    hideOnSinglePage?: boolean
    /** 分页器的大小 */
    size?: 'small' | 'default' | 'large'
    /** 分页器的页码数量 */
    pagerCount?: number
  }

  /** ArtTable 组件的 Props 接口 */
  interface ArtTableProps extends TableProps<Record<string, any>> {
    /** 加载状态 */
    loading?: boolean
    /** 列渲染配置 */
    columns?: ColumnOption[]
    /** 分页状态 */
    pagination?: PaginationConfig
    /** 分页配置 */
    paginationOptions?: PaginationOptions
    /** 空数据表格高度 */
    emptyHeight?: string
    /** 空数据时显示的文本 */
    emptyText?: string
    /** 是否开启 ArtTableHeader，解决表格高度自适应问题 */
    showTableHeader?: boolean
  }

  const props = withDefaults(defineProps<ArtTableProps>(), {
    columns: () => [],
    fit: true,
    showHeader: true,
    stripe: undefined,
    border: undefined,
    size: undefined,
    emptyHeight: '100%',
    emptyText: '暂无数据',
    showTableHeader: true
  })
  const instance = getCurrentInstance()
  const attrs = useAttrs()

  const LAYOUT = {
    MOBILE: 'prev, pager, next, sizes, jumper, total',
    IPAD: 'prev, pager, next, jumper, total',
    DESKTOP: 'total, prev, pager, next, sizes, jumper'
  }

  const layout = computed(() => {
    if (width.value < 768) {
      return LAYOUT.MOBILE
    } else if (width.value < 1024) {
      return LAYOUT.IPAD
    } else {
      return LAYOUT.DESKTOP
    }
  })

  /**
   * 卡片化断点。768px 与 Tailwind 的 md 一致，也正好是"平板竖屏 / 手机"的分界。
   * 一加 15 的视口约 412px、iPhone 约 390px，都会走卡片；平板 768 起仍是表格。
   */
  const MOBILE_TABLE_BREAKPOINT = 768
  const isMobileLayout = computed(() => width.value < MOBILE_TABLE_BREAKPOINT)

  /**
   * 能否卡片化。
   *
   * 卡片渲染依赖 `:columns` 配置（要拿到 label/prop/slot 元信息）。
   * 有些页面（如 admin-manage）直接写 `<ElTableColumn>` 子元素，
   * columns 是空的 —— 那种页面无法卡片化，退回可横向滚动的表格，
   * 至少不会把整页撑出横向溢出。
   */
  const canRenderCards = computed(() => (props.columns?.length ?? 0) > 0)

  /** 卡片模式的数据源。与表格共用 data，不额外请求。 */
  const mobileRows = computed<Record<string, any>[]>(() => {
    const data = (attrs.data ?? (props as any).data) as Record<string, any>[] | undefined
    return Array.isArray(data) ? data : []
  })

  /**
   * 卡片里要展示的字段列。
   *
   * 排除掉在卡片语境下没有意义或会造成困扰的列：
   *  - selection / expand / index：卡片没有表头，勾选与展开无处安放
   *  - 操作列：单独渲染到卡底（见 mobileActionColumns）
   */
  const mobileFieldColumns = computed(() =>
    (props.columns || []).filter((col) => {
      if (col.type && ['selection', 'expand', 'index', 'globalIndex'].includes(col.type)) return false
      if (isActionColumn(col)) return false
      return !!(col.prop || col.label)
    })
  )

  /** 操作列（按 prop/label 识别，兼容各页的不同写法）。 */
  const mobileActionColumns = computed(() => (props.columns || []).filter(isActionColumn))

  function isActionColumn(col: ColumnOption): boolean {
    const prop = String(col.prop ?? '')
    const label = String(col.label ?? '')
    return (
      prop === 'operation' ||
      prop === 'actions' ||
      prop === 'action' ||
      label === '操作' ||
      label === 'Operation' ||
      label === 'Actions'
    )
  }

  /**
   * 卡片单元格的兜底文本。
   * 优先用列自带的 formatter（各页用它做时间格式化、枚举翻译），
   * 否则直接取值；空值统一显示 "-" 而不是空白，避免看起来像渲染失败。
   */
  function formatCellText(row: Record<string, any>, col: ColumnOption): string {
    if (typeof col.formatter === 'function' && col.prop) {
      const out = (col.formatter as any)(row, col, row[col.prop], 0)
      // formatter 可能返回 VNode（那种列一般也配了 useSlot），此处只接受可直接显示的值
      if (typeof out === 'string' || typeof out === 'number') return String(out)
    }
    if (!col.prop) return '-'
    const v = row[col.prop]
    if (v === null || v === undefined || v === '') return '-'
    return String(v)
  }

  // 默认分页常量
  const DEFAULT_PAGINATION_OPTIONS: PaginationOptions = {
    pageSizes: [10, 20, 30, 50, 100],
    align: 'center',
    background: true,
    layout: layout.value,
    hideOnSinglePage: false,
    size: 'default',
    pagerCount: width.value > 1200 ? 7 : 5
  }

  // 合并分页配置
  const mergedPaginationOptions = computed(() => ({
    ...DEFAULT_PAGINATION_OPTIONS,
    ...props.paginationOptions
  }))

  // 边框 (优先级：props > store)
  const border = computed(() => props.border ?? isBorder.value)
  // 斑马纹
  const stripe = computed(() => props.stripe ?? isZebra.value)
  // 表格尺寸
  const size = computed(() => props.size ?? tableSize.value)
  // 数据是否为空
  const isEmpty = computed(() => props.data?.length === 0)

  const paginationHeight = ref(0)
  const tableHeaderHeight = ref(0)

  // 使用 useResizeObserver 监听分页器高度变化
  useResizeObserver(paginationRef, (entries) => {
    const entry = entries[0]
    if (entry) {
      // 使用 requestAnimationFrame 避免 ResizeObserver loop 警告
      requestAnimationFrame(() => {
        paginationHeight.value = entry.contentRect.height
      })
    }
  })

  // 使用 useResizeObserver 监听表格头部高度变化
  useResizeObserver(tableHeaderRef, (entries) => {
    const entry = entries[0]
    if (entry) {
      // 使用 requestAnimationFrame 避免 ResizeObserver loop 警告
      requestAnimationFrame(() => {
        tableHeaderHeight.value = entry.contentRect.height
      })
    }
  })

  // 分页器与表格之间的间距常量（计算属性，响应 showTableHeader 变化）
  const PAGINATION_SPACING = computed(() => (props.showTableHeader ? 6 : 15))

  // 使用表格高度计算 Hook
  const { containerHeight } = useTableHeight({
    showTableHeader: computed(() => props.showTableHeader),
    paginationHeight,
    tableHeaderHeight,
    paginationSpacing: PAGINATION_SPACING
  })

  // 表格高度逻辑
  const height = computed(() => {
    // 全屏模式下占满全屏
    if (isFullScreen.value) return '100%'
    // 空数据且非加载状态时固定高度
    if (isEmpty.value && !props.loading) return props.emptyHeight
    // 使用传入的高度
    if (props.height) return props.height
    // 默认占满容器高度
    return '100%'
  })

  // 表头背景颜色样式
  const headerCellStyle = computed(() => ({
    background: isHeaderBackground.value
      ? 'var(--el-fill-color-lighter)'
      : 'var(--default-box-color)',
    ...(props.headerCellStyle || {}) // 合并用户传入的样式
  }))

  // 只有显式传入时才覆盖 ElTable 的原生默认值，避免继承的 Boolean props 把官方默认值冲掉。
  const hasExplicitTableProp = (propName: string): boolean => {
    const rawProps = (instance?.vnode.props || {}) as Record<string, unknown>
    const kebabName = propName.replace(/[A-Z]/g, (match) => `-${match.toLowerCase()}`)
    return propName in rawProps || kebabName in rawProps
  }

  const mergedTableProps = computed(() => ({
    ...attrs,
    ...props,
    height: height.value,
    stripe: stripe.value,
    border: border.value,
    size: size.value,
    headerCellStyle: headerCellStyle.value,
    // Element Plus 默认值为 true，未显式传入时不应被 ArtTable 覆盖成 false。
    selectOnIndeterminate: hasExplicitTableProp('selectOnIndeterminate')
      ? props.selectOnIndeterminate
      : undefined
  }))

  // 是否显示分页器
  const showPagination = computed(() => props.pagination && !isEmpty.value)

  // Element Plus 在部分场景会先用 $index = -1 进行预渲染。
  // 这对普通展示无影响，但会让 ElForm 错误注册出 lineList.-1.xxx 这类字段。
  const shouldRenderSlotScope = (slotScope: { $index?: number }) => {
    return slotScope.$index === undefined || slotScope.$index >= 0
  }

  // 清理列属性，移除插槽相关的自定义属性，确保它们不会被 ElTableColumn 错误解释
  const cleanColumnProps = (col: ColumnOption) => {
    const columnProps = { ...col }
    // 删除自定义的插槽控制属性
    delete columnProps.useHeaderSlot
    delete columnProps.headerSlotName
    delete columnProps.useSlot
    delete columnProps.slotName
    return columnProps
  }

  // 分页大小变化
  const handleSizeChange = (val: number) => {
    emit('pagination:size-change', val)
  }

  // 分页当前页变化
  const handleCurrentChange = (val: number) => {
    emit('pagination:current-change', val)
    scrollToTop() // 页码改变后滚动到表格顶部
  }

  const { scrollToTop: scrollPageToTop } = useCommon()

  // 滚动表格内容到顶部，并可以联动页面滚动到顶部
  const scrollToTop = () => {
    nextTick(() => {
      elTableRef.value?.setScrollTop(0) // 滚动 ElTable 内部滚动条到顶部
      scrollPageToTop() // 调用公共 composable 滚动页面到顶部
    })
  }

  // 全局序号
  const getGlobalIndex = (index: number) => {
    if (!props.pagination) return index + 1
    const { current, size } = props.pagination
    return (current - 1) * size + index + 1
  }

  const emit = defineEmits<{
    (e: 'pagination:size-change', val: number): void
    (e: 'pagination:current-change', val: number): void
  }>()

  // 查找并绑定表格头部元素 - 使用 VueUse 优化
  const findTableHeader = () => {
    if (!props.showTableHeader) {
      tableHeaderRef.value = undefined
      return
    }

    const tableHeader = document.getElementById('art-table-header')
    if (tableHeader) {
      tableHeaderRef.value = tableHeader
    } else {
      // 如果找不到表格头部，设置为 undefined，useElementSize 会返回 0
      tableHeaderRef.value = undefined
    }
  }

  watchEffect(
    () => {
      // 访问响应式数据以建立依赖追踪
      void props.data?.length // 追踪数据变化
      const shouldShow = props.showTableHeader

      // 只有在需要显示表格头部时才查找
      if (shouldShow) {
        nextTick(() => {
          findTableHeader()
        })
      } else {
        // 不显示时清空引用
        tableHeaderRef.value = undefined
      }
    },
    { flush: 'post' }
  )

  defineExpose({
    scrollToTop,
    elTableRef
  })
</script>

<style lang="scss" scoped>
  @use './style';
</style>
