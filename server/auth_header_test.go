package main

import "testing"

func TestBearerTokenOnlyAcceptsAuthorizationBearer(t *testing.T) {
	cases := []struct {
		header string
		want   string
	}{
		{"Bearer abc123", "abc123"},
		{" bearer   abc123 ", "abc123"},
		{"Basic abc123", ""},
		{"Bearer", ""},
		{"abc123", ""},
		{"Bearer ", ""},
	}
	for _, tc := range cases {
		if got := bearerToken(tc.header); got != tc.want {
			t.Errorf("bearerToken(%q)=%q want %q", tc.header, got, tc.want)
		}
	}
}
