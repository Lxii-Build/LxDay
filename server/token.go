package main

import (
	"errors"
	"math"

	"github.com/golang-jwt/jwt/v5"
)

const maxTokenIntExclusive = 1 << 63

// parseSignedToken is the single JWT parser for both app and admin sessions.
// Tokens are issued with HS256, so accepting any HMAC method would widen the
// verification boundary without providing a compatibility benefit.
func parseSignedToken(raw string) (*jwt.Token, error) {
	return jwt.Parse(raw, func(t *jwt.Token) (interface{}, error) {
		if t == nil || t.Method == nil || t.Method.Alg() != jwt.SigningMethodHS256.Alg() {
			return nil, errors.New("unexpected signing method")
		}
		return []byte(cfg.App.JWTSecret), nil
	}, jwt.WithValidMethods([]string{jwt.SigningMethodHS256.Alg()}))
}

// tokenClaimInt64 rejects fractional, negative, non-finite, and overflowing
// numeric claims before they are converted from float64. A missing optional
// claim remains compatible with tokens issued before token_ver was added.
func tokenClaimInt64(claims jwt.MapClaims, name string, required, nonZero bool) (int64, error) {
	raw, ok := claims[name]
	if !ok {
		if required {
			return 0, errors.New("missing claim")
		}
		return 0, nil
	}
	value, ok := raw.(float64)
	if !ok || math.IsNaN(value) || math.IsInf(value, 0) || math.Trunc(value) != value || value < 0 || value >= maxTokenIntExclusive {
		return 0, errors.New("invalid numeric claim")
	}
	if nonZero && value == 0 {
		return 0, errors.New("invalid numeric claim")
	}
	return int64(value), nil
}
