package main

import (
	"bytes"
	"io"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
)

// 生产 502 回归测试。
//
// 现象：love.lxii.cc 上 body ≥2MB 且被中间件拒绝时返回 502 Bad Gateway，
// 而不是我们写的中文错误信封；≤1MB 正常回 403。
// 根因：Go 的 http server 在 handler 返回时只自动排空 ≤256KB 的未读 body，
// 超过就关连接 → Nginx 撞 RST → 502。
// 修法：fail/afail 里统一 drainRequestBody。
//
// 断言方式：调用 fail 后请求体必须已被读空。若仍有剩余字节，
// 生产上就会重现 502（本地 Go 直连不经 Nginx，看不到 502，只能这样验）。
func TestFailDrainsRequestBody(t *testing.T) {
	sizes := []int{
		1024,             // 小 body：本来就没问题
		512 * 1024,       // 512KB：仍在 Go 自动排空范围内
		2 * 1024 * 1024,  // 2MB：生产上开始 502 的门槛
		10 * 1024 * 1024, // 10MB：典型的高像素照片
	}
	for _, n := range sizes {
		body := bytes.Repeat([]byte("x"), n)
		rec := httptest.NewRecorder()
		c, _ := gin.CreateTestContext(rec)
		c.Request = httptest.NewRequest("POST", "/api/v1/media", bytes.NewReader(body))
		c.Request.Header.Set("Content-Type", "multipart/form-data; boundary=zz")

		fail(c, 403, 1016, "客户端校验失败")

		left, err := io.Copy(io.Discard, c.Request.Body)
		if err != nil {
			t.Fatalf("%d 字节：读剩余 body 出错 %v", n, err)
		}
		if left != 0 {
			t.Fatalf("%d 字节的 body 在 fail 后仍剩 %d 字节未读 —— 生产上会变成 502", n, left)
		}
		if rec.Code != 403 {
			t.Fatalf("%d 字节：状态码应为 403，实际 %d", n, rec.Code)
		}
	}
}

// afail（后台信封）同样要排空。
func TestAfailDrainsRequestBody(t *testing.T) {
	body := bytes.Repeat([]byte("y"), 3*1024*1024)
	rec := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(rec)
	c.Request = httptest.NewRequest("POST", "/api/admin/users", bytes.NewReader(body))

	afail(c, 401, 401, "登录已失效，请重新登录")

	left, _ := io.Copy(io.Discard, c.Request.Body)
	if left != 0 {
		t.Fatalf("afail 后仍剩 %d 字节未读 —— 大请求会在反代侧变成 502", left)
	}
	if rec.Code != 401 {
		t.Fatalf("状态码应为 401，实际 %d", rec.Code)
	}
}

// 无 body 的请求（GET/DELETE）不应因排空逻辑而出错或产生额外开销。
func TestDrainSkipsEmptyBody(t *testing.T) {
	rec := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(rec)
	c.Request = httptest.NewRequest("GET", "/api/v1/albums", nil)
	fail(c, 403, 1016, "客户端校验失败")
	if rec.Code != 403 {
		t.Fatalf("状态码应为 403，实际 %d", rec.Code)
	}
}
