package main

import (
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
)

// postFormContext 构造带 url-encoded 表单的 gin 上下文与响应记录器。
func postFormContext(form map[string]string) (*gin.Context, *httptest.ResponseRecorder) {
	gin.SetMode(gin.TestMode)
	rec := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(rec)
	values := url.Values{}
	for k, v := range form {
		values.Set(k, v)
	}
	c.Request = httptest.NewRequest(http.MethodPost, "/", strings.NewReader(values.Encode()))
	c.Request.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	return c, rec
}

func TestParseCropParamsDefaultsToCenteredFull(t *testing.T) {
	c, _ := postFormContext(map[string]string{})
	crop, err := parseCropParams(c)
	if err != nil {
		t.Fatalf("unexpected err=%v", err)
	}
	if crop != (CropParams{CenterX: 0.5, CenterY: 0.5, Scale: 1.0}) {
		t.Fatalf("crop=%#v", crop)
	}
}

func TestParseCropParamsRejectsNonNumeric(t *testing.T) {
	c, _ := postFormContext(map[string]string{"scale": "abc"})
	if _, err := parseCropParams(c); err == nil {
		t.Fatal("expected error for non-numeric scale")
	}
}

func TestMapAvatarErrorStatusCodes(t *testing.T) {
	cases := []struct {
		err  error
		code int
		biz  int
	}{
		{ErrAvatarTooLarge, 400, 1002},
		{ErrAvatarTooLong, 400, 1002},
		{ErrAvatarTooManyFrames, 400, 1002},
		{ErrAvatarBadCrop, 400, 1002},
		{ErrAnimatedNotSupported, 400, 1002},
		{ErrAvatarProcessingFailed, 500, 1010},
	}
	for _, tc := range cases {
		c, rec := postFormContext(map[string]string{})
		mapAvatarError(c, tc.err)
		if rec.Code != tc.code {
			t.Fatalf("err=%v http=%d want %d", tc.err, rec.Code, tc.code)
		}
		if got := responseCode(t, rec); got != tc.biz {
			t.Fatalf("err=%v biz=%d want %d", tc.err, got, tc.biz)
		}
	}
}

func TestPublicAvatarURLUsesPairScopedBasename(t *testing.T) {
	got := publicAvatarURL(7, "/var/data/uploads/avatar/7/abc.webp")
	if got != "/uploads/avatar/7/abc.webp" {
		t.Fatalf("url=%s", got)
	}
}
