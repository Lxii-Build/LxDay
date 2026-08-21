package main

import (
	"log"
	"log/slog"
)

// ================= 推送网关适配层 =================
// 生产接入个推/极光：安装官方 SDK 后，将 Send 的实现替换为 SDK 调用。
// 当前给出接口 + 日志占位，方便先行开发联调。

type PushGateway struct {
	provider string
	store    *Store
}

func NewPushGateway(provider string, s *Store) *PushGateway {
	return &PushGateway{provider: provider, store: s}
}

// Send 推送高优事件给指定用户。
// eventType: ring_request / comfort_request / calm_request / todo_new ...
func (p *PushGateway) Send(uid int64, eventType string, data interface{}) {
	// 1. 查用户推送 token
	rows, err := p.store.DB.Query(
		`SELECT channel, token FROM push_token WHERE user_id=? AND status=1`, uid)
	if err != nil {
		return
	}
	defer rows.Close()

	var tokens []string
	for rows.Next() {
		var ch, tk string
		if err := rows.Scan(&ch, &tk); err != nil {
			// 单行坏数据（如可空列为 NULL）不能静默变成零值：
			// 忽略 Scan 错误曾导致状态历史整行零值 → 客户端撞重复 key 崩溃。
			slog.Error("scan push_token row failed", "err", err)
			continue
		}
		tokens = append(tokens, tk)
	}
	if len(tokens) == 0 {
		log.Printf("[push] user=%d no push token, skip", uid)
		return
	}

	title, body := eventContent(eventType, data)

	// 2. 调用推送厂商（此处为占位，接入 SDK 后替换）
	switch p.provider {
	case "getui":
		// getui 官方 Go SDK: https://docs.getui.com/getui/server/apiv2/push/
		// pushToSingle(uid, title, body, tokens)
		log.Printf("[push:getui] uid=%d type=%s title=%s", uid, eventType, title)
	case "jpush":
		// jpush-api-go-client
		log.Printf("[push:jpush] uid=%d type=%s title=%s", uid, eventType, title)
	case "none":
		log.Printf("[push:none] uid=%d type=%s title=%s body=%s", uid, eventType, title, body)
	default:
		log.Printf("[push:%s] uid=%d type=%s title=%s", p.provider, uid, eventType, title)
	}
}

// 事件 → 通知文案（含发信人）
func eventContent(eventType string, data interface{}) (title, body string) {
	from := "对方"
	if m, ok := data.(map[string]interface{}); ok {
		if n, ok := m["from_name"].(string); ok && n != "" {
			from = n
		}
	}
	switch eventType {
	case MsgRingRequest:
		return from + " 正在找你", "紧急响铃请求，点击查看"
	case MsgComfortRequest:
		return from + " 需要你的陪伴", "点一下回应 TA"
	case MsgCalmRequest:
		return from + " 现在需要冷静", "暂时放缓沟通，给 TA 一点空间"
	case MsgTodoNew:
		return from + " 给你添加了待办", "点击查看待办详情"
	case MsgTodoCompleted:
		return "待办已完成", from + " 完成了一项待办"
	case MsgLowBattery:
		return from + " 电量不足 15%", "及时联系 TA 充电"
	case MsgWifiJoined:
		return from + " 已连接你关注的 WiFi", "点击查看网络状态"
	case MsgTodoRemind:
		return "待办提醒", from + " 到点了"
	default:
		return "林曦日记", "有一条新的互动消息"
	}
}
