package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
)

func TestDisabledUserLoginUsesSameFailureAsWrongPassword(t *testing.T) {
	s := withTestStore(t)
	userID, err := s.CreateUser("disabled_user", "disabled@example.test", "禁用用户", hashPassword("Abcdefghij12"))
	if err != nil {
		t.Fatalf("create user: %v", err)
	}
	if _, err := s.DB.Exec("UPDATE `user` SET status=2 WHERE id=?", userID); err != nil {
		t.Fatalf("disable user: %v", err)
	}

	gin.SetMode(gin.TestMode)
	call := func(account, password string) (int, string) {
		t.Helper()
		req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/login",
			strings.NewReader(`{"account":"`+account+`","password":"`+password+`"}`))
		req.Header.Set("Content-Type", "application/json")
		rec := httptest.NewRecorder()
		c, _ := gin.CreateTestContext(rec)
		c.Request = req
		handleLogin(c)
		return rec.Code, rec.Body.String()
	}

	gotStatus, gotBody := call("disabled_user", "Abcdefghij12")
	wrongStatus, wrongBody := call("disabled_user", "wrong-password")
	if gotStatus != http.StatusBadRequest || wrongStatus != http.StatusBadRequest {
		t.Fatalf("disabled=%d wrong=%d，均应为 400", gotStatus, wrongStatus)
	}
	if gotBody != wrongBody {
		t.Fatalf("禁用账号与密码错误的响应必须一致：disabled=%q wrong=%q", gotBody, wrongBody)
	}
}
