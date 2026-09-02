package main

import (
	"errors"
	"testing"
)

func TestDeleteUserRefusesActivePair(t *testing.T) {
	s := withTestStore(t)
	pair, uidA, _ := seedPair(t, s, "delete-active-a", "delete-active-b", "DELETEA1")

	if err := s.DeleteUser(uidA); !errors.Is(err, errUserHasActivePair) {
		t.Fatalf("DeleteUser(active pair)=%v, want errUserHasActivePair", err)
	}
	var exists int
	if err := s.DB.QueryRow("SELECT 1 FROM `user` WHERE id=?", uidA).Scan(&exists); err != nil {
		t.Fatalf("active-pair refusal removed user: %v", err)
	}
	if got, err := s.GetPairByUserID(uidA); err != nil || got.ID != pair.ID {
		t.Fatalf("active pair changed after refused delete: pair=%#v err=%v", got, err)
	}
}

func TestDeleteUserRemovesOwnedDataAfterUnbind(t *testing.T) {
	s := withTestStore(t)
	pair, uidA, uidB := seedPair(t, s, "delete-a", "delete-b", "DELETEB1")

	album, err := s.CreateAlbum(pair.ID, uidA, "待删除用户相册")
	if err != nil {
		t.Fatalf("create album: %v", err)
	}
	photoA := addPhoto(t, s, pair.ID, uidA, album.ID, "delete-a", nil)
	photoB := addPhoto(t, s, pair.ID, uidB, album.ID, "keep-b", nil)
	if _, err := s.DB.Exec("UPDATE album SET cover_photo_id=? WHERE id=?", photoA.ID, album.ID); err != nil {
		t.Fatalf("set album cover: %v", err)
	}
	if _, err := s.DB.Exec(
		`INSERT INTO photo_comment(photo_id,pair_id,user_id,content) VALUES(?,?,?,?)`,
		photoA.ID, pair.ID, uidB, "属于被删除照片的评论"); err != nil {
		t.Fatalf("insert photo comment: %v", err)
	}
	if _, err := s.DB.Exec(
		`INSERT INTO photo_comment(photo_id,pair_id,user_id,content) VALUES(?,?,?,?)`,
		photoB.ID, pair.ID, uidA, "属于被删除用户的评论"); err != nil {
		t.Fatalf("insert own comment: %v", err)
	}
	if _, err := s.DB.Exec(`INSERT INTO photo_like(photo_id,user_id) VALUES(?,?)`, photoB.ID, uidA); err != nil {
		t.Fatalf("insert own like: %v", err)
	}
	if _, err := s.DB.Exec(
		`INSERT INTO todo(pair_id,creator_id,assignee_id,title) VALUES(?,?,?,?)`,
		pair.ID, uidA, uidB, "待删除待办"); err != nil {
		t.Fatalf("insert todo: %v", err)
	}
	if _, err := s.DB.Exec(
		`INSERT INTO status_history(pair_id,user_id,ts) VALUES(?,?,datetime('now'))`, pair.ID, uidA); err != nil {
		t.Fatalf("insert status history: %v", err)
	}
	if _, err := s.DB.Exec(
		`INSERT INTO push_token(user_id,platform,channel,token) VALUES(?,?,?,?)`,
		uidA, "android", "fcm", "unused"); err != nil {
		t.Fatalf("insert push token: %v", err)
	}

	if _, err := s.DB.Exec(`UPDATE pair SET status=0, unbind_time=datetime('now') WHERE id=?`, pair.ID); err != nil {
		t.Fatalf("unbind pair: %v", err)
	}
	if err := s.DeleteUser(uidA); err != nil {
		t.Fatalf("DeleteUser: %v", err)
	}

	var count int
	for _, tc := range []struct {
		name  string
		query string
		args  []any
		want  int
	}{
		{"user", "SELECT COUNT(*) FROM `user` WHERE id=?", []any{uidA}, 0},
		{"owned photo", "SELECT COUNT(*) FROM photo WHERE uploader_id=?", []any{uidA}, 0},
		{"kept photo", "SELECT COUNT(*) FROM photo WHERE id=?", []any{photoB.ID}, 1},
		{"photo comments", "SELECT COUNT(*) FROM photo_comment WHERE user_id=? OR photo_id=?", []any{uidA, photoA.ID}, 0},
		{"photo likes", "SELECT COUNT(*) FROM photo_like WHERE user_id=?", []any{uidA}, 0},
		{"todos", "SELECT COUNT(*) FROM todo WHERE creator_id=? OR assignee_id=?", []any{uidA, uidA}, 0},
		{"status history", "SELECT COUNT(*) FROM status_history WHERE user_id=?", []any{uidA}, 0},
		{"push tokens", "SELECT COUNT(*) FROM push_token WHERE user_id=?", []any{uidA}, 0},
		{"pair slot", "SELECT COUNT(*) FROM pair WHERE id=? AND (user_a_id=? OR user_b_id=?)", []any{pair.ID, uidA, uidA}, 0},
		{"album cover", "SELECT COUNT(*) FROM album WHERE id=? AND cover_photo_id IS NOT NULL", []any{album.ID}, 0},
	} {
		if err := s.DB.QueryRow(tc.query, tc.args...).Scan(&count); err != nil {
			t.Fatalf("count %s: %v", tc.name, err)
		}
		if count != tc.want {
			t.Errorf("%s count=%d, want %d", tc.name, count, tc.want)
		}
	}
	if err := s.DB.QueryRow("SELECT 1 FROM `user` WHERE id=?", uidB).Scan(&count); err != nil {
		t.Fatalf("remaining user was deleted: %v", err)
	}
	if err := s.DB.QueryRow("SELECT created_by FROM album WHERE id=?", album.ID).Scan(&count); err != nil {
		t.Fatalf("album after delete: %v", err)
	}
	if count != 0 {
		t.Fatalf("album created_by=%d, want 0", count)
	}
	if err := s.DeleteUser(uidA); err == nil {
		t.Fatal("deleting a missing user should fail")
	}
}
