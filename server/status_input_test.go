package main

import (
	"strings"
	"testing"
)

func TestValidateDeviceStatusRejectsOutOfRangeAndOversizedFields(t *testing.T) {
	valid := &DeviceStatus{BatteryLevel: 50, NetworkType: "wifi", SSID: "home"}
	if err := validateDeviceStatus(valid); err != nil {
		t.Fatalf("valid status rejected: %v", err)
	}
	for _, status := range []*DeviceStatus{
		{BatteryLevel: -1, NetworkType: "wifi"},
		{BatteryLevel: 101, NetworkType: "wifi"},
		{BatteryLevel: 50, NetworkType: "satellite"},
		{BatteryLevel: 50, NetworkType: "wifi", SSID: strings.Repeat("x", maxStatusSSIDBytes+1)},
		{BatteryLevel: 50, NetworkType: "wifi", ForegroundApp: &AppInfo{Pkg: strings.Repeat("x", maxStatusAppPackageBytes+1)}},
		{BatteryLevel: 50, NetworkType: "wifi", Music: &MusicInfo{Title: strings.Repeat("x", maxStatusMusicTextBytes+1)}},
	} {
		if err := validateDeviceStatus(status); err == nil {
			t.Fatalf("invalid status accepted: %#v", status)
		}
	}
}

func TestOnlyClientWebSocketMessagesAreAccepted(t *testing.T) {
	for _, messageType := range []string{MsgStatusUpdate, MsgWifiJoined, MsgRingRequest, MsgComfortRequest, MsgCalmRequest, MsgRingCancel, MsgRingStopped} {
		if !isClientWSMessage(messageType) {
			t.Fatalf("client message %q rejected", messageType)
		}
	}
	for _, messageType := range []string{MsgTodoNew, MsgTodoCompleted, MsgAdminNotice, MsgPaired, "unknown"} {
		if isClientWSMessage(messageType) {
			t.Fatalf("server-only message %q accepted", messageType)
		}
	}
}

func TestClientInteractionPayloadKeepsOnlySafeRingID(t *testing.T) {
	payload, ok := clientInteractionPayload([]byte(`{"data":{"ring_id":"a0b1-c2_d3","ignored":"x"}}`))
	if !ok || len(payload) != 1 || payload["ring_id"] != "a0b1-c2_d3" {
		t.Fatalf("safe ring id was not preserved: %#v, ok=%v", payload, ok)
	}
	for _, raw := range []string{
		`{"data":{"ring_id":"<script>"}}`,
		`{"data":{"ring_id":"` + strings.Repeat("a", maxRingIDBytes+1) + `"}}`,
		`{"data":[]}`,
	} {
		if _, ok := clientInteractionPayload([]byte(raw)); ok {
			t.Fatalf("unsafe interaction payload accepted: %s", raw)
		}
	}
}

func TestLowBatteryNotificationIsNotRepeatedForEveryStatusReport(t *testing.T) {
	mem := newMemStore()
	if !mem.recordLowBattery(7, 14) {
		t.Fatal("first low-battery report should notify")
	}
	if mem.recordLowBattery(7, 14) {
		t.Fatal("same low-battery level should not repeatedly notify")
	}
	if !mem.recordLowBattery(7, 13) {
		t.Fatal("further battery drop should notify")
	}
	if mem.recordLowBattery(7, 14) {
		t.Fatal("battery recovery within the low range should not notify")
	}
	if mem.recordLowBattery(7, 20) {
		t.Fatal("normal battery level should not notify")
	}
	if !mem.recordLowBattery(7, 14) {
		t.Fatal("a new drop below the threshold should notify")
	}
}
