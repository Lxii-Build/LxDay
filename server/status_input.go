package main

import (
	"encoding/json"
	"fmt"
	"strings"
)

// 状态由手机客户端高频上报，不能只依赖 64KB WS 帧上限：若允许把整帧塞进
// SSID/应用名，攻击者每秒两次就能持续污染内存最新状态和 5 分钟历史记录。
// 上限按 UTF-8 字节计算，与 SQLite 实际存储成本一致。
const (
	maxStatusSSIDBytes       = 128
	maxStatusNetworkBytes    = 16
	maxStatusAppPackageBytes = 256
	maxStatusAppNameBytes    = 256
	maxStatusMusicTextBytes  = 256
	maxRingIDBytes           = 64
)

func validateDeviceStatus(in *DeviceStatus) error {
	if in.BatteryLevel < 0 || in.BatteryLevel > 100 {
		return fmt.Errorf("battery out of range")
	}
	if in.NetworkType != "wifi" && in.NetworkType != "cellular" {
		return fmt.Errorf("invalid network type")
	}
	if len(in.NetworkType) > maxStatusNetworkBytes || len(in.SSID) > maxStatusSSIDBytes {
		return fmt.Errorf("network fields too long")
	}
	if in.ForegroundApp != nil &&
		(len(in.ForegroundApp.Pkg) > maxStatusAppPackageBytes || len(in.ForegroundApp.Name) > maxStatusAppNameBytes) {
		return fmt.Errorf("foreground app fields too long")
	}
	if in.Music != nil &&
		(len(in.Music.Title) > maxStatusMusicTextBytes || len(in.Music.Artist) > maxStatusMusicTextBytes) {
		return fmt.Errorf("music fields too long")
	}
	return nil
}

// isClientWSMessage 只列出由手机客户端主动发送的消息。todo_new、admin_notice
// 等是服务端根据已落库的操作生成的事件，绝不能让已登录用户借 WS 伪造给伴侣。
func isClientWSMessage(messageType string) bool {
	switch messageType {
	case MsgStatusUpdate, MsgWifiJoined, MsgRingRequest, MsgComfortRequest,
		MsgCalmRequest, MsgRingCancel, MsgRingStopped:
		return true
	default:
		return false
	}
}

// clientInteractionPayload 丢弃客户端不应控制的字段，只保留响铃关联 id。
// 互动事件会在对方离线时进入内存补偿队列；直接转发原始 JSON 会让一帧 64KB 的
// 任意嵌套数据长期滞留，并把客户端协议意外变成了开放的消息中继。
func clientInteractionPayload(data []byte) (map[string]interface{}, bool) {
	var raw struct {
		Data struct {
			RingID string `json:"ring_id"`
		} `json:"data"`
	}
	if err := json.Unmarshal(data, &raw); err != nil {
		return nil, false
	}
	if raw.Data.RingID == "" {
		return map[string]interface{}{}, true
	}
	if !isSafeRingID(raw.Data.RingID) {
		return nil, false
	}
	return map[string]interface{}{"ring_id": raw.Data.RingID}, true
}

func isSafeRingID(id string) bool {
	if len(id) > maxRingIDBytes {
		return false
	}
	return strings.IndexFunc(id, func(r rune) bool {
		return !((r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') ||
			(r >= '0' && r <= '9') || r == '-' || r == '_')
	}) == -1
}
