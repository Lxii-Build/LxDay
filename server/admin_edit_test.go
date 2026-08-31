package main

import (
	"database/sql"
	"errors"
	"testing"
	"time"
)

// 后台编辑的存储层回归测试：每个字段都必须真正落库，且清空可空字段不能被
// pointer/interface 参数误写成字面量或静默成功。
func TestAdminEditableRecordsPersist(t *testing.T) {
	s := withTestStore(t)
	pair, uidA, uidB := seedPair(t, s, "admin-edit-a", "admin-edit-b", "ADMINEDIT")

	email := "edited@example.com"
	signature := "后台更新的简介"
	birthday := "1999-08-20"
	if err := s.UpdateAdminUserProfile(uidA, &email, "更新后的昵称", 2, &signature, &birthday); err != nil {
		t.Fatalf("update user profile: %v", err)
	}
	profile, err := s.GetUserProfile(uidA)
	if err != nil {
		t.Fatalf("get user profile: %v", err)
	}
	if profile.Email == nil || *profile.Email != email || profile.Nickname != "更新后的昵称" ||
		profile.Gender != 2 || profile.Signature == nil || *profile.Signature != signature ||
		profile.Birthday == nil || *profile.Birthday != birthday {
		t.Fatalf("user profile did not persist: %#v", profile)
	}
	if err := s.UpdateAdminUserProfile(uidA, nil, "更新后的昵称", 0, nil, nil); err != nil {
		t.Fatalf("clear user profile fields: %v", err)
	}
	profile, err = s.GetUserProfile(uidA)
	if err != nil {
		t.Fatalf("get cleared user profile: %v", err)
	}
	if profile.Email != nil || profile.Signature != nil || profile.Birthday != nil {
		t.Fatalf("nullable user fields were not cleared: %#v", profile)
	}

	anniversary := time.Date(2020, 8, 20, 0, 0, 0, 0, time.Local)
	if err := s.UpdateAdminPairAnniversary(pair.ID, &anniversary); err != nil {
		t.Fatalf("update anniversary: %v", err)
	}
	gotPair, err := s.GetPairByUserID(uidA)
	if err != nil || gotPair.AnniversaryDate == nil || !gotPair.AnniversaryDate.Equal(anniversary) {
		t.Fatalf("anniversary did not persist: pair=%#v err=%v", gotPair, err)
	}
	if err := s.UpdateAdminPairAnniversary(pair.ID, nil); err != nil {
		t.Fatalf("clear anniversary: %v", err)
	}
	gotPair, err = s.GetPairByUserID(uidA)
	if err != nil || gotPair.AnniversaryDate != nil {
		t.Fatalf("anniversary was not cleared: pair=%#v err=%v", gotPair, err)
	}

	todo, err := s.CreateTodo(pair.ID, uidA, uidB, "旧标题", "旧详情", &anniversary, 0, 2, 1|4, true)
	if err != nil {
		t.Fatalf("create todo: %v", err)
	}
	if err := s.UpdateAdminTodo(todo.ID, uidA, "新标题", "新详情", nil, 1, 1, 0, false); err != nil {
		t.Fatalf("update todo: %v", err)
	}
	gotTodo, err := s.GetTodo(todo.ID)
	if err != nil {
		t.Fatalf("get todo: %v", err)
	}
	if gotTodo.AssigneeID != uidA || gotTodo.Title != "新标题" || gotTodo.Note != "新详情" ||
		gotTodo.RemindAt != nil || gotTodo.RemindType != 1 || gotTodo.RepeatType != 1 ||
		gotTodo.Weekdays != 0 || gotTodo.RemindEnabled {
		t.Fatalf("todo did not persist: %#v", gotTodo)
	}

	photo := addPhoto(t, s, pair.ID, uidA, 0, "admin-edit-photo", nil)
	if err := s.UpdateAdminPhotoCaption(photo.ID, "后台修改描述"); err != nil {
		t.Fatalf("update photo caption: %v", err)
	}
	gotPhoto, err := s.GetPhoto(photo.ID)
	if err != nil || gotPhoto.Caption != "后台修改描述" {
		t.Fatalf("photo caption did not persist: photo=%#v err=%v", gotPhoto, err)
	}

	version := &AppVersion{
		Platform: "android", VersionName: "1.0.8", VersionCode: 10008,
		APKURL: "https://example.com/app.apk", Notes: "初始说明", ForceUpdate: false, Status: 1,
	}
	versionID, err := s.CreateAppVersion(version)
	if err != nil {
		t.Fatalf("create app version: %v", err)
	}
	if err := s.UpdateAppVersion(versionID, "1.0.8-hotfix", "https://example.com/app-hotfix.apk", "修订说明", true); err != nil {
		t.Fatalf("update app version: %v", err)
	}
	versions, _, err := s.ListAppVersions("android", 20, 0)
	if err != nil {
		t.Fatalf("list app versions: %v", err)
	}
	var found *AppVersion
	for i := range versions {
		if versions[i].ID == versionID {
			found = &versions[i]
			break
		}
	}
	if found == nil || found.VersionName != "1.0.8-hotfix" || found.APKURL != "https://example.com/app-hotfix.apk" ||
		found.Notes != "修订说明" || !found.ForceUpdate {
		t.Fatalf("app version did not persist: %#v", found)
	}

	if err := s.UpdateAppVersion(999999, "missing", "", "", false); !errors.Is(err, sql.ErrNoRows) {
		t.Fatalf("missing app version should return sql.ErrNoRows, got %v", err)
	}
}
