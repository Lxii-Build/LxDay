package main

import (
	"testing"

	"github.com/golang-jwt/jwt/v5"
)

func TestParseTokenOnlyAcceptsHS256AndIntegralClaims(t *testing.T) {
	previous := cfg
	cfg = &Config{}
	cfg.App.JWTSecret = "test-secret"
	t.Cleanup(func() { cfg = previous })

	valid := jwt.NewWithClaims(jwt.SigningMethodHS256, jwt.MapClaims{
		"uid": 42,
		"tv":  0,
		"exp": 4102444800,
	})
	raw, err := valid.SignedString([]byte(cfg.App.JWTSecret))
	if err != nil {
		t.Fatal(err)
	}
	uid, tv, err := ParseToken(raw)
	if err != nil || uid != 42 || tv != 0 {
		t.Fatalf("valid token rejected: uid=%d tv=%d err=%v", uid, tv, err)
	}

	for name, method := range map[string]jwt.SigningMethod{
		"hs384": jwt.SigningMethodHS384,
		"hs512": jwt.SigningMethodHS512,
	} {
		forged := jwt.NewWithClaims(method, jwt.MapClaims{
			"uid": 42,
			"exp": 4102444800,
		})
		forgedRaw, err := forged.SignedString([]byte(cfg.App.JWTSecret))
		if err != nil {
			t.Fatal(err)
		}
		if _, _, err := ParseToken(forgedRaw); err == nil {
			t.Errorf("%s token was accepted", name)
		}
	}

	fractional := jwt.NewWithClaims(jwt.SigningMethodHS256, jwt.MapClaims{
		"uid": 42.5,
		"exp": 4102444800,
	})
	fractionalRaw, err := fractional.SignedString([]byte(cfg.App.JWTSecret))
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err := ParseToken(fractionalRaw); err == nil {
		t.Fatal("fractional uid claim was accepted")
	}
}
