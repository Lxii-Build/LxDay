/**
 * 全局事件总线模块
 *
 * 基于 mitt 库实现的类型安全的事件总线
 *
 * ## 主要功能
 *
 * - 跨组件通信（发布/订阅模式）
 * - 类型安全的事件定义和调用
 * - 解耦组件间的直接依赖
 *
 * ## 用法示例
 *
 * ```typescript
 * // 订阅事件
 * mittBus.on('openSetting', () => { ... })
 *
 * // 发布事件
 * mittBus.emit('openSetting')
 * ```
 *
 * ## 已定义的事件
 *
 * - openSetting: 打开设置面板
 *
 * @module utils/sys/mittBus
 * @author LxDay
 */
import mitt, { type Emitter } from 'mitt'

// 定义事件类型映射
type Events = {
  // 打开设置面板事件 - 无参数
  openSetting: void
}

// 创建类型安全的事件总线实例
const mittBus: Emitter<Events> = mitt<Events>()

export default mittBus
