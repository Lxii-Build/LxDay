package com.linxi.diary.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * MilkGlass 设计令牌（摘自 MilkGlassDesignScheme.md + 深色语义反转扩展）。
 *
 * 浅色（规范默认）：
 *  - 背景 #f6eef2，正文 #5b5560，次文 #8a8290
 *  - 玻璃一档(输入框) rgba(255,255,255,0.35) + blur14
 *  - 玻璃二档(卡片)  渐变 0.55→0.30 + blur16(移动端)
 *  - 玻璃三档(浮层)  渐变 0.60→0.35 + blur34
 *  - 主渐变 #e59db9→#b49ede，主色 #b9a9dc
 *  - 语义四对(面/墨)：成功 #bfe3cf/#5f9d7c 警告 #f5dcc0/#c2915f
 *                     错误 #f2c2cf/#c9748c 信息 #cfe0f2/#6f96c2
 *  - 圆角 14/22/34/44/999，间距 4 网格，缓动 easeOutCubic
 *
 * 深色（语义反转扩展，规范 ADR-01 无深色，此处自创保持玻璃层级）：
 *  - 背景 #1a1720，正文 #ece8f0，次文 #a9a0b4
 *  - 玻璃白改 rgba(30,27,36,0.55) 系，描边 rgba(255,255,255,0.10)
 */

// ---------- 浅色 ----------
val MilkGlassBg = Color(0xFFF6EEF2)
val MilkGlassText = Color(0xFF5B5560)
val MilkGlassTextSecondary = Color(0xFF8A8290)
val MilkGlassPrimary = Color(0xFFB9A9DC)
val MilkGlassGradStart = Color(0xFFE59DB9)
val MilkGlassGradEnd = Color(0xFFB49EDE)
val MilkGlassGlass1 = Color(0x59FFFFFF) // 0.35
val MilkGlassGlass2 = Color(0x8CFFFFFF) // 0.55（卡片渐变起点，实色近似）
val MilkGlassBorder = Color(0x8CFFFFFF) // 描边 0.55
val MilkGlassPlaceholder = Color(0xE6B2A4BC) // 0.9

// 语义四对
val MilkGlassSuccess = Color(0xFFBFE3CF); val MilkGlassSuccessInk = Color(0xFF5F9D7C)
val MilkGlassWarning = Color(0xFFF5DCC0); val MilkGlassWarningInk = Color(0xFFC2915F)
val MilkGlassError   = Color(0xFFF2C2CF); val MilkGlassErrorInk   = Color(0xFFC9748C)
val MilkGlassInfo    = Color(0xFFCFE0F2); val MilkGlassInfoInk    = Color(0xFF6F96C2)

// 状态色（通知卡/状态卡片）
val StatusCharging = Color(0xFF4CAF50) // 充电绿
val StatusLowBattery = Color(0xFFF44336) // 低电红
val StatusScreenOn = Color(0xFF2196F3) // 亮屏蓝
val StatusMusic = Color(0xFF9C27B0) // 音乐紫
val StatusTheme = Color(0xFF607D8B) // 默认蓝灰

// ---------- 深色（语义反转） ----------
val DarkBg = Color(0xFF1A1720)
val DarkSurface = Color(0xFF221E29)
val DarkText = Color(0xFFECE8F0)
val DarkTextSecondary = Color(0xFFA9A0B4)
val DarkGlass = Color(0x8C1E1B24) // 玻璃面 0.55
val DarkGlass2 = Color(0x591E1B24) // 玻璃二档 0.35
val DarkBorder = Color(0x1AFFFFFF) // 描边 0.10
val DarkPlaceholder = Color(0x66A9A0B4)

// 深色语义（深底亮面）
val DarkSuccess = Color(0xFF2E4A3B); val DarkSuccessInk = Color(0xFF8CD3AB)
val DarkWarning = Color(0xFF4A3A26); val DarkWarningInk = Color(0xFFE2B285)
val DarkError   = Color(0xFF4A2B35); val DarkErrorInk   = Color(0xFFE28BA4)
val DarkInfo    = Color(0xFF2A3A4A); val DarkInfoInk    = Color(0xFF8FB4DC)
