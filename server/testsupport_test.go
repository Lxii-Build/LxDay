package main

import (
	"encoding/json"
	"net/http/httptest"
	"testing"
)

// responseCode 解析 {code,...} 信封中的业务码，供各测试断言。
// （原定义在已删除的 profile_handlers_test.go 中，此处保留供 avatar 等测试复用。）
func responseCode(t *testing.T, response *httptest.ResponseRecorder) int {
	t.Helper()
	var body struct {
		Code int `json:"code"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	return body.Code
}
