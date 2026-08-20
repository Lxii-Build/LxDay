package main

import (
	"strings"
	"testing"
)

// 后台密码强度：此前只要 len>=6，`123456` 就能过。
func TestValidateStrongPassword(t *testing.T) {
	bad := map[string]string{
		"短":        "Ab3",
		"纯数字":      "123456789012",
		"纯小写":      "abcdefghijkl",
		"缺数字":      "AbcdefghIjkl",
		"缺大写":      "abcdefgh1234",
		"缺小写":      "ABCDEFGH1234",
		"刚好11位":    "Abcdefghij1",
		"经典弱口令":    "123456",
		"admin弱口令": "admin123",
	}
	for name, pw := range bad {
		if err := validateStrongPassword(pw); err == nil {
			t.Errorf("%s(%q) 应被拒绝", name, pw)
		}
	}
	good := []string{
		"Abcdefghij12",   // 恰好 12 位，含大小写与数字
		"MyS3cretPass99", // 常规强口令
		"Lx-Day-2026-Ok1",
	}
	for _, pw := range good {
		if err := validateStrongPassword(pw); err != nil {
			t.Errorf("%q 应被接受，却报 %v", pw, err)
		}
	}
}

// role 白名单：此前 role 无校验，可写入任意字符串，
// 拼错一个字母就产生既非 admin 也非 super 的「幽灵角色」。
func TestIsValidAdminRole(t *testing.T) {
	for _, r := range []string{"admin", "super"} {
		if !isValidAdminRole(r) {
			t.Errorf("%q 应合法", r)
		}
	}
	for _, r := range []string{"", "Super", "ADMIN", "root", "superadmin", "admin ", "guest"} {
		if isValidAdminRole(r) {
			t.Errorf("%q 应非法", r)
		}
	}
}

// 上传 URL 校验：此前 AddDiaryImages 直接存客户端传入的任意字符串，
// 可注入外部图床或 javascript: 串。
func TestValidateUploadURL(t *testing.T) {
	valid := []string{
		"/upload/2026/08/20/abc123.jpg",
		"/upload/2026/01/01/x.png",
	}
	for _, u := range valid {
		if err := validateUploadURL(u); err != nil {
			t.Errorf("%q 应合法，却报 %v", u, err)
		}
	}
	invalid := []string{
		"",
		"javascript:alert(1)",
		"http://evil.com/a.jpg",
		"https://love.lxii.cc/upload/x.jpg", // 必须是相对路径
		"/etc/passwd",
		"/upload/../../etc/passwd", // 路径穿越
		"/uploads-evil/a.jpg",
		"//evil.com/a.jpg", // 协议相对 URL
	}
	for _, u := range invalid {
		if err := validateUploadURL(u); err == nil {
			t.Errorf("%q 应被拒绝", u)
		}
	}
}

// 通知定向投递解析：此前 target 字段是假的（永远全站广播）。
func TestResolveNotifyTargetsParsing(t *testing.T) {
	// 只验证格式解析分支：涉及库查询的 all / 有效 uid 由集成层覆盖。
	if _, err := resolveNotifyTargets("bogus"); err == nil {
		t.Error("非 all 且无 uid: 前缀应报错")
	}
	if _, err := resolveNotifyTargets("uid:abc"); err == nil {
		t.Error("非数字 uid 应报错")
	}
	if _, err := resolveNotifyTargets("uid:0"); err == nil {
		t.Error("uid=0 应报错")
	}
	if _, err := resolveNotifyTargets("uid:-3"); err == nil {
		t.Error("负数 uid 应报错")
	}
}

// 邀请码字母表不得包含易混字符，否则口头/截图转达时必然出错。
func TestInviteAlphabetExcludesAmbiguousChars(t *testing.T) {
	for _, ch := range []string{"0", "O", "1", "I", "L"} {
		if strings.Contains(inviteAlphabet, ch) {
			t.Errorf("字母表不应包含易混字符 %q", ch)
		}
	}
	if inviteCodeLen < 8 {
		t.Errorf("邀请码长度 %d 过短，6 位纯数字可被爆破", inviteCodeLen)
	}
	// 熵下限：32^8 远大于 10^6，爆破成本提升数个数量级。
	if len(inviteAlphabet) < 30 {
		t.Errorf("字母表过小：%d", len(inviteAlphabet))
	}
}

// 邀请码生成：长度正确、字符全部来自字母表、且多次生成不重复。
func TestGenerateInviteCode(t *testing.T) {
	seen := map[string]bool{}
	for i := 0; i < 200; i++ {
		code := generateInviteCode()
		if len(code) != inviteCodeLen {
			t.Fatalf("长度 %d，期望 %d", len(code), inviteCodeLen)
		}
		for _, r := range code {
			if !strings.ContainsRune(inviteAlphabet, r) {
				t.Fatalf("非法字符 %q in %q", r, code)
			}
		}
		if seen[code] {
			t.Fatalf("200 次生成内出现重复码 %q，随机性不足", code)
		}
		seen[code] = true
	}
}
