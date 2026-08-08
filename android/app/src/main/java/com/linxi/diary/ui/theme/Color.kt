package com.linxi.diary.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 业务状态语义色（独立于主题，液态玻璃组件所需固定色）。
 * 主题色由 Theme.kt 的动态取色 / 种子色决定。
 */

// 情侣主题默认种子色（粉紫，用于「动态取色关闭」时的固定色）
val LinxiSeedPink = 0xFFE59DB9.toInt()
val LinxiSeedPurple = 0xFFB49EDE.toInt()

// 状态语义色：充电 / 低电量 / 亮屏 / 音乐（通知卡与状态卡用）
val StatusCharging = Color(0xFF4CAF50)
val StatusLowBattery = Color(0xFFF44336)
val StatusScreenOn = Color(0xFF2196F3)
val StatusMusic = Color(0xFF9C27B0)
val StatusTheme = Color(0xFF607D8B)
