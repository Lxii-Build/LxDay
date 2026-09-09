<template>
  <section class="lx-mobile-record-list" :aria-label="ariaLabel">
    <div v-if="loading" class="lx-mobile-record-list__loading" role="status" aria-live="polite">
      <span class="lx-mobile-record-list__spinner" aria-hidden="true" />
      <span>{{ loadingText }}</span>
    </div>

    <ElEmpty
      v-else-if="!rows.length"
      class="lx-mobile-record-list__empty"
      :description="emptyText"
      :image-size="88"
    />

    <article
      v-for="(row, rowIndex) in rows"
      v-else
      :key="rowKey(row, rowIndex)"
      class="lx-mobile-record"
    >
      <dl class="lx-mobile-record__fields">
        <div v-for="field in fields" :key="field.key" class="lx-mobile-record__field">
          <dt>{{ field.label }}</dt>
          <dd>
            <slot :name="field.key" :row="row" :index="rowIndex" :value="row[field.key]">
              {{ field.format ? field.format(row) : displayValue(row[field.key]) }}
            </slot>
          </dd>
        </div>
      </dl>

      <div v-if="$slots.actions" class="lx-mobile-record__actions">
        <slot name="actions" :row="row" :index="rowIndex" />
      </div>
    </article>
  </section>
</template>

<script setup lang="ts">
  import { ElEmpty } from 'element-plus'

  defineOptions({ name: 'LxMobileRecordList' })

  interface RecordField {
    key: string
    label: string
    format?: (row: Record<string, any>) => string
  }

  const props = withDefaults(
    defineProps<{
      rows: Record<string, any>[]
      fields: RecordField[]
      loading?: boolean
      emptyText?: string
      loadingText?: string
      ariaLabel?: string
    }>(),
    {
      loading: false,
      emptyText: '暂无数据',
      loadingText: '正在加载…',
      ariaLabel: '数据列表'
    }
  )

  const rowKey = (row: Record<string, any>, index: number): string | number =>
    row.id ?? row.uid ?? row.pair_id ?? row.code ?? row.version_code ?? `row-${index}`

  const displayValue = (value: unknown): string => {
    if (value === null || value === undefined || value === '') return '-'
    if (typeof value === 'boolean') return value ? '是' : '否'
    return String(value)
  }

  const { rows, fields, loading, emptyText, loadingText, ariaLabel } = toRefs(props)
</script>

<style lang="scss" scoped>
  .lx-mobile-record-list {
    display: none;
  }

  @media (width < 768px) {
    .lx-mobile-record-list {
      display: flex;
      flex-direction: column;
      gap: 12px;
      padding: 2px 0 calc(8px + env(safe-area-inset-bottom, 0px));
    }

    .lx-mobile-record {
      padding: 14px 16px;
      overflow: hidden;
      background: var(--lx-surface);
      border: 1px solid color-mix(in srgb, var(--lx-line) 72%, transparent);
      border-radius: var(--lx-radius-card, 16px);
      box-shadow:
        -6px -6px 14px var(--lx-highlight-raised),
        6px 6px 14px var(--lx-shadow-raised);
    }

    .lx-mobile-record__fields {
      padding: 0;
      margin: 0;
    }

    .lx-mobile-record__field {
      display: grid;
      grid-template-columns: minmax(76px, 34%) minmax(0, 1fr);
      gap: 12px;
      align-items: start;
      padding: 8px 0;
      font-size: 13px;
      line-height: 1.5;

      & + .lx-mobile-record__field {
        border-top: 1px dashed color-mix(in srgb, var(--lx-line) 70%, transparent);
      }
    }

    dt {
      color: var(--lx-text-secondary);
      white-space: nowrap;
    }

    dd {
      min-width: 0;
      padding: 0;
      margin: 0;
      color: var(--lx-text);
      text-align: right;
      overflow-wrap: anywhere;
    }

    .lx-mobile-record__actions {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      justify-content: flex-end;
      padding-top: 10px;
      margin-top: 8px;
      border-top: 1px solid color-mix(in srgb, var(--lx-line) 72%, transparent);

      :deep(.el-button) {
        min-height: 44px;
        padding: 9px 12px;
      }
    }

    .lx-mobile-record-list__loading,
    .lx-mobile-record-list__empty {
      min-height: 140px;
    }

    .lx-mobile-record-list__loading {
      display: flex;
      gap: 10px;
      align-items: center;
      justify-content: center;
      color: var(--lx-text-secondary);
    }

    .lx-mobile-record-list__spinner {
      width: 18px;
      height: 18px;
      border: 2px solid color-mix(in srgb, var(--lx-focus) 20%, transparent);
      border-top-color: var(--lx-focus);
      border-radius: 50%;
      animation: lx-record-spin 0.75s linear infinite;
    }
  }

  @keyframes lx-record-spin {
    to {
      transform: rotate(360deg);
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .lx-mobile-record-list__spinner {
      animation: none;
    }
  }
</style>
