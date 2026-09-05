package main

import (
	"database/sql"
	"testing"
)

func TestListenRoomMigrationAndSecrets(t *testing.T) {
	s := newTestStore(t)
	var table string
	if err := s.DB.QueryRow(`SELECT name FROM sqlite_master WHERE type='table' AND name='listen_room'`).Scan(&table); err != nil {
		t.Fatalf("listen_room table missing after migration: %v", err)
	}
	if table != "listen_room" {
		t.Fatalf("unexpected listen table %q", table)
	}
	var uniqueIndex int
	if err := s.DB.QueryRow(`SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='uk_listen_room_pair_active'`).Scan(&uniqueIndex); err != nil {
		t.Fatalf("active room index lookup failed: %v", err)
	}
	if uniqueIndex != 1 {
		t.Fatal("each pair must have a database-enforced single active listen room")
	}
	if _, err := s.DB.Exec(`INSERT INTO listen_room(id,pair_id,host_user_id,join_secret_hash) VALUES('TESTA',42,7,'hash')`); err != nil {
		t.Fatalf("insert first active room: %v", err)
	}
	if _, err := s.DB.Exec(`INSERT INTO listen_room(id,pair_id,host_user_id,join_secret_hash) VALUES('TESTB',42,8,'hash')`); err == nil {
		t.Fatal("second active room for one pair must be rejected by SQLite")
	}
	roomID, err := listenRoomID()
	if err != nil || len(roomID) != 6 {
		t.Fatalf("room id generation failed: %q %v", roomID, err)
	}
	secret, err := listenJoinSecret()
	if err != nil || len(secret) != 8 {
		t.Fatalf("join secret generation failed: %q %v", secret, err)
	}
	hashed := listenSecretHash(secret)
	if hashed == secret || len(hashed) != 64 {
		t.Fatal("join secret must be stored as a deterministic one-way hash")
	}
	var missing sql.NullString
	if err := s.DB.QueryRow(`SELECT state_json FROM listen_room WHERE id=?`, "missing").Scan(&missing); err != sql.ErrNoRows {
		t.Fatalf("missing room lookup should return sql.ErrNoRows, got %v", err)
	}
}

func TestListenPositionIsBounded(t *testing.T) {
	if got := clampListenPosition(-1, 100); got != 0 {
		t.Fatalf("negative position = %d", got)
	}
	if got := clampListenPosition(200, 100); got != 100 {
		t.Fatalf("position past duration = %d", got)
	}
	if got := clampListenPosition(200, 0); got != 200 {
		t.Fatalf("unknown duration should keep positive position, got %d", got)
	}
}

func TestListenStateNormalizesThirdPartyLinks(t *testing.T) {
	state := normalizeListenState(listenRoomState{
		Source:     "netease",
		SongID:     123,
		AudioURL:   "https://example.com/temporary.mp3",
		CoverURL:   "https://music.163.com/cover.jpg",
		DurationMs: 1000,
		PositionMs: 2000,
	})
	if state.AudioURL != "" {
		t.Fatalf("third-party playback URL survived normalization: %q", state.AudioURL)
	}
	if state.PositionMs != state.DurationMs {
		t.Fatalf("position was not clamped: %d", state.PositionMs)
	}
}

func TestListenNeteaseTrackPolicy(t *testing.T) {
	if err := validateListenTrack("netease", 123); err != nil {
		t.Fatalf("valid netease track rejected: %v", err)
	}
	if err := validateListenTrack("local", 123); err == nil {
		t.Fatal("local tracks must not enter a shared room")
	}
	if err := validateListenTrack("netease", 0); err == nil {
		t.Fatal("zero song id must be rejected")
	}
	if got := safeListenCoverURL("http://music.163.com/cover.jpg"); got != "" {
		t.Fatalf("insecure cover URL accepted: %q", got)
	}
	if got := safeListenCoverURL("https://music.163.com/cover.jpg"); got == "" {
		t.Fatal("valid cover URL rejected")
	}
}

func TestListenSessionRevocation(t *testing.T) {
	h := NewListenHub(nil)
	token, err := h.issueSession("ABC123", 7, true)
	if err != nil {
		t.Fatalf("issue session: %v", err)
	}
	if _, ok := h.session(token); !ok {
		t.Fatal("issued session is not readable")
	}
	h.revokeRoomSessions("ABC123")
	if _, ok := h.session(token); ok {
		t.Fatal("revoked room session is still valid")
	}
}

func TestListenPairRevocationClosesRoom(t *testing.T) {
	s := newTestStore(t)
	if _, err := s.DB.Exec(`INSERT INTO listen_room(id,pair_id,host_user_id,join_secret_hash)
		VALUES('PAIRRM',42,7,'hash')`); err != nil {
		t.Fatalf("insert active pair room: %v", err)
	}
	h := NewListenHub(s)
	token, err := h.issueSession("PAIRRM", 7, true)
	if err != nil {
		t.Fatalf("issue pair room session: %v", err)
	}
	h.closePairRooms(42)
	var status int
	if err := s.DB.QueryRow(`SELECT status FROM listen_room WHERE id='PAIRRM'`).Scan(&status); err != nil {
		t.Fatalf("read closed room status: %v", err)
	}
	if status != 0 {
		t.Fatalf("pair revocation left room active with status %d", status)
	}
	if _, ok := h.session(token); ok {
		t.Fatal("pair revocation left the room session valid")
	}
}
