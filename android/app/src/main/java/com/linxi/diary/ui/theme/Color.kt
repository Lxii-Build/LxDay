package com.linxi.diary.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 业务状态语义色（独立于主题，液态玻璃组件所需固定色）。
 * 主题色由 Theme.kt 使用固定情侣种子色生成。
 */

// 情侣主题固定种子色（历史，粉色）——保留常量以兼容旧引用
val LinxiSeedPink = 0xFFE59DB9.toInt()

// 品牌固定主色（需求：全局固定蓝，不随壁纸/系统动态取色变化）
val LinxiSeedBlue = 0xFF277AF7.toInt()

// 正/负控件语义色：正面(同意/添加/登录/注册)=品牌蓝；负面(拒绝/退出/删除)=不刺眼的红
val BrandBlue = Color(0xFF277AF7)
val BrandRed = Color(0xFFE5484D)

// 状态语义色：充电 / 低电量 / 亮屏 / 音乐（通知卡与状态卡用）
val StatusCharging = Color(0xFF4CAF50)
val StatusLowBattery = Color(0xFFF44336)
val StatusScreenOn = Color(0xFF2196F3)
val StatusMusic = Color(0xFF9C27B0)
val StatusTheme = Color(0xFF607D8B)
