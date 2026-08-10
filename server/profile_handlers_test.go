package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/DATA-DOG/go-sqlmock"
	"github.com/alicebob/miniredis/v2"
	"github.com/gin-gonic/gin"
	"github.com/redis/go-redis/v9"
)

func TestRegisterCreatesAccountAfterVerifyingCode(t *testing.T) {
	store, mock, closeStore := newMockStore(t)
	defer closeStore()
	withServerGlobals(store, nil, func() {
		store.Rdb.Set(context.Background(), emailCodeKey("linxi@example.com"), "123456", time.Minute)
		mock.ExpectExec("INSERT INTO `user`").
			WithArgs("Linxi", "linxi@example.com", "林曦", sqlmock.AnyArg()).
			WillReturnResult(sqlmock.NewResult(1, 1))

		response := performHandlerRequest(
			handleRegister,
			0,
			`{"username":"Linxi","email":"Linxi@Example.com","code":"123456","password":"123456","nickname":"  林曦  "}`,
		)

		if response.Code != http.StatusOK {
			t.Fatalf("status = %d, body = %s", response.Code, response.Body.String())
		}
		if err := mock.ExpectationsWereMet(); err != nil {
			t.Fatal(err)
		}
	})
}

func TestRegisterRejectsWrongCode(t *testing.T) {
	store, _, closeStore := newMockStore(t)
	defer closeStore()
	withServerGlobals(store, nil, func() {
		store.Rdb.Set(context.Background(), emailCodeKey("a@b.com"), "111111", time.Minute)
		response := performHandlerRequest(
			handleRegister,
			0,
			`{"username":"Linxi","email":"a@b.com","code":"999999","password":"123456"}`,
		)
		if response.Code != http.StatusBadRequest || responseCode(t, response) != 1015 {
			t.Fatalf("response = %d %s", response.Code, response.Body.String())
		}
	})
}

func TestUpdateProfileDoesNotWriteWhenUserIsUnbound(t *testing.T) {
	store, mock, closeStore := newMockStore(t)
	defer closeStore()
	withServerGlobals(store, nil, func() {
		mock.ExpectBegin()
		mock.ExpectQuery("SELECT id,user_a_id,user_b_id,invite_code,anniversary_date FROM pair").
			WithArgs(int64(1), int64(1)).
			WillReturnError(sqlmock.ErrCancelled)
		mock.ExpectRollback()

		response := performHandlerRequest(
			handleUpdateProfile,
			1,
			`{"nickname":"新昵称"}`,
		)

		if response.Code != http.StatusOK || responseCode(t, response) != 1001 {
			t.Fatalf("response = %d %s", response.Code, response.Body.String())
		}
		if err := mock.ExpectationsWereMet(); err != nil {
			t.Fatal(err)
		}
	})
}

func TestUpdateProfileRollsBackWhenAuthoritativeProfileCannotBeRead(t *testing.T) {
	store, mock, closeStore := newMockStore(t)
	defer closeStore()
	withServerGlobals(store, nil, func() {
		mock.ExpectBegin()
		expectPair(mock, "2024-02-29")
		mock.ExpectExec("UPDATE `user` SET nickname=\\? WHERE id=\\?").
			WithArgs("新昵称", int64(1)).
			WillReturnResult(sqlmock.NewResult(0, 1))
		expectPair(mock, "2024-02-29")
		mock.ExpectQuery("SELECT id,nickname,avatar_url,avatar_thumbnail_url FROM `user`").
			WithArgs(int64(1)).
			WillReturnError(sqlmock.ErrCancelled)
		mock.ExpectRollback()

		response := performHandlerRequest(
			handleUpdateProfile,
			1,
			`{"nickname":"新昵称"}`,
		)

		if response.Code != http.StatusInternalServerError || responseCode(t, response) != 1010 {
			t.Fatalf("response = %d %s", response.Code, response.Body.String())
		}
		if err := mock.ExpectationsWereMet(); err != nil {
			t.Fatal(err)
		}
	})
}

func TestUpdateProfileReturnsAuthoritativePairProfileAndNotifiesPartner(t *testing.T) {
	store, mock, closeStore := newMockStore(t)
	defer closeStore()
	push := NewPushGateway("none", store)
	testHub := NewHub(store, push)
	withServerGlobals(store, testHub, func() {
		mock.ExpectBegin()
		expectPair(mock, "2024-02-29")
		mock.ExpectExec("UPDATE `user` SET nickname=\\? WHERE id=\\?").
			WithArgs("新昵称", int64(1)).
			WillReturnResult(sqlmock.NewResult(0, 1))
		expectPair(mock, "2024-02-29")
		expectUser(mock, 1, "新昵称", nil, nil)
		expectUser(mock, 2, "伴侣", nil, nil)
		mock.ExpectCommit()

		response := performHandlerRequest(
			handleUpdateProfile,
			1,
			`{"nickname":" 新昵称 "}`,
		)

		assertPairProfileResponse(t, response, "新昵称", "2024-02-29")
		queued, err := store.Rdb.RPop(context.Background(), keyEventQ(2)).Result()
		if err != nil {
			t.Fatalf("profile_updated event missing: %v", err)
		}
		var event struct {
			Type string         `json:"type"`
			Data map[string]any `json:"data"`
		}
		if err := json.Unmarshal([]byte(queued), &event); err != nil {
			t.Fatal(err)
		}
		if event.Type != MsgProfileUpdated || event.Data["user_id"] != float64(1) || len(event.Data) != 1 {
			t.Fatalf("unexpected event = %#v", event)
		}
	})
}

func TestUpdateAnniversaryRejectsUnboundAndFutureDate(t *testing.T) {
	store, mock, closeStore := newMockStore(t)
	defer closeStore()
	withServerGlobals(store, nil, func() {
		mock.ExpectBegin()
		mock.ExpectQuery("SELECT id,user_a_id,user_b_id,invite_code,anniversary_date FROM pair").
			WithArgs(int64(1), int64(1)).
			WillReturnError(sqlmock.ErrCancelled)
		mock.ExpectRollback()
		unbound := performHandlerRequest(
			handleUpdateAnniversary,
			1,
			`{"anniversary_date":"2024-02-29"}`,
		)
		if unbound.Code != http.StatusOK || responseCode(t, unbound) != 1001 {
			t.Fatalf("unbound response = %d %s", unbound.Code, unbound.Body.String())
		}

		mock.ExpectBegin()
		expectPair(mock, "2024-02-29")
		mock.ExpectRollback()
		future := performHandlerRequest(
			handleUpdateAnniversary,
			1,
			`{"anniversary_date":"2999-01-01"}`,
		)
		if future.Code != http.StatusBadRequest || responseCode(t, future) != 1002 {
			t.Fatalf("future response = %d %s", future.Code, future.Body.String())
		}
	})
}

func TestUpdateAnniversaryStoresDateAndReturnsPairProfile(t *testing.T) {
	store, mock, closeStore := newMockStore(t)
	defer closeStore()
	push := NewPushGateway("none", store)
	testHub := NewHub(store, push)
	withServerGlobals(store, testHub, func() {
		mock.ExpectBegin()
		expectPair(mock, "")
		mock.ExpectExec("UPDATE pair SET anniversary_date=\\? WHERE id=\\? AND status=1 AND \\(user_a_id=\\? OR user_b_id=\\?\\)").
			WithArgs(sqlmock.AnyArg(), int64(7), int64(1), int64(1)).
			WillReturnResult(sqlmock.NewResult(0, 1))
		expectPair(mock, "2024-02-29")
		expectUser(mock, 1, "林曦", nil, nil)
		expectUser(mock, 2, "伴侣", nil, nil)
		mock.ExpectCommit()

		response := performHandlerRequest(
			handleUpdateAnniversary,
			1,
			`{"anniversary_date":"2024-02-29"}`,
		)

		assertPairProfileResponse(t, response, "林曦", "2024-02-29")
	})
}

func TestPairStatusReturnsUnboundOnlyWhenPairDoesNotExist(t *testing.T) {
	store, mock, closeStore := newMockStore(t)
	defer closeStore()
	withServerGlobals(store, nil, func() {
		mock.ExpectQuery("SELECT id,user_a_id,user_b_id,invite_code,anniversary_date FROM pair").
			WithArgs(int64(1), int64(1)).
			WillReturnError(sql.ErrNoRows)

		response := performHandlerRequest(handlePairStatus, 1, "")

		if response.Code != http.StatusOK {
			t.Fatalf("response = %d %s", response.Code, response.Body.String())
		}
		var body struct {
			Code int `json:"code"`
			Data struct {
				Bound bool `json:"bound"`
			} `json:"data"`
		}
		if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
			t.Fatal(err)
		}
		if body.Code != 0 || body.Data.Bound {
			t.Fatalf("unexpected response = %#v", body)
		}
	})
}

func TestPairStatusReturnsServerErrorWhenBoundUserIsMissing(t *testing.T) {
	store, mock, closeStore := newMockStore(t)
	defer closeStore()
	withServerGlobals(store, nil, func() {
		expectPair(mock, "2024-02-29")
		mock.ExpectQuery("SELECT id,nickname,avatar_url,avatar_thumbnail_url FROM `user`").
			WithArgs(int64(1)).
			WillReturnError(sql.ErrNoRows)

		response := performHandlerRequest(handlePairStatus, 1, "")

		if response.Code != http.StatusInternalServerError || responseCode(t, response) != 1010 {
			t.Fatalf("response = %d %s", response.Code, response.Body.String())
		}
	})
}

func TestPairStatusReturnsServerErrorWhenProfileReadFails(t *testing.T) {
	store, mock, closeStore := newMockStore(t)
	defer closeStore()
	withServerGlobals(store, nil, func() {
		mock.ExpectQuery("SELECT id,user_a_id,user_b_id,invite_code,anniversary_date FROM pair").
			WithArgs(int64(1), int64(1)).
			WillReturnError(sqlmock.ErrCancelled)

		response := performHandlerRequest(handlePairStatus, 1, "")

		if response.Code != http.StatusInternalServerError || responseCode(t, response) != 1010 {
			t.Fatalf("response = %d %s", response.Code, response.Body.String())
		}
	})
}

func TestPairStatusIncludesBothProfilesAndAnniversary(t *testing.T) {
	store, mock, closeStore := newMockStore(t)
	defer closeStore()
	withServerGlobals(store, nil, func() {
		expectPair(mock, "2024-02-29")
		expectUser(mock, 1, "林曦", stringPointer("/avatar/me.webp"), stringPointer("/avatar/me.png"))
		expectUser(mock, 2, "伴侣", nil, nil)

		response := performHandlerRequest(handlePairStatus, 1, "")

		assertPairProfileResponse(t, response, "林曦", "2024-02-29")
		var body struct {
			Data struct {
				Me User `json:"me"`
			} `json:"data"`
		}
		if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
			t.Fatal(err)
		}
		if body.Data.Me.AvatarThumbnailURL == nil || *body.Data.Me.AvatarThumbnailURL != "/avatar/me.png" {
			t.Fatalf("thumbnail = %#v", body.Data.Me.AvatarThumbnailURL)
		}
	})
}

func TestRunMigrationsRepairsColumnsBeforeRecordingVersion(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	mock.ExpectExec("CREATE TABLE IF NOT EXISTS schema_migrations").
		WillReturnResult(sqlmock.NewResult(0, 0))
	mock.ExpectQuery("SELECT version FROM schema_migrations").
		WillReturnRows(sqlmock.NewRows([]string{"version"}))
	mock.ExpectQuery("SELECT COUNT\\(\\*\\) FROM information_schema.columns").
		WithArgs("user", "avatar_thumbnail_url").
		WillReturnRows(sqlmock.NewRows([]string{"count"}).AddRow(1))
	mock.ExpectQuery("SELECT COUNT\\(\\*\\) FROM information_schema.columns").
		WithArgs("pair", "anniversary_date").
		WillReturnRows(sqlmock.NewRows([]string{"count"}).AddRow(0))
	mock.ExpectExec("ALTER TABLE pair ADD COLUMN anniversary_date").
		WillReturnResult(sqlmock.NewResult(0, 0))
	mock.ExpectExec("INSERT INTO schema_migrations").
		WithArgs(1, "profile_and_anniversary").
		WillReturnResult(sqlmock.NewResult(1, 1))

	if err := applyMigrations(db, migrations[:1]); err != nil {
		t.Fatal(err)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func TestRunMigrationsAppliesPendingVersionOnce(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	mock.ExpectExec("CREATE TABLE IF NOT EXISTS schema_migrations").
		WillReturnResult(sqlmock.NewResult(0, 0))
	mock.ExpectQuery("SELECT version FROM schema_migrations").
		WillReturnRows(sqlmock.NewRows([]string{"version"}))
	mock.ExpectQuery("SELECT COUNT\\(\\*\\) FROM information_schema.columns").
		WithArgs("user", "avatar_thumbnail_url").
		WillReturnRows(sqlmock.NewRows([]string{"count"}).AddRow(0))
	mock.ExpectExec("ALTER TABLE `user` ADD COLUMN avatar_thumbnail_url").
		WillReturnResult(sqlmock.NewResult(0, 0))
	mock.ExpectQuery("SELECT COUNT\\(\\*\\) FROM information_schema.columns").
		WithArgs("pair", "anniversary_date").
		WillReturnRows(sqlmock.NewRows([]string{"count"}).AddRow(0))
	mock.ExpectExec("ALTER TABLE pair ADD COLUMN anniversary_date").
		WillReturnResult(sqlmock.NewResult(0, 0))
	mock.ExpectExec("INSERT INTO schema_migrations").
		WithArgs(1, "profile_and_anniversary").
		WillReturnResult(sqlmock.NewResult(1, 1))

	if err := applyMigrations(db, migrations[:1]); err != nil {
		t.Fatal(err)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func newMockStore(t *testing.T) (*Store, sqlmock.Sqlmock, func()) {
	t.Helper()
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	redisServer := miniredis.RunT(t)
	rdb := redis.NewClient(&redis.Options{Addr: redisServer.Addr()})
	return &Store{DB: db, Rdb: rdb}, mock, func() {
		rdb.Close()
		redisServer.Close()
		db.Close()
	}
}

func performHandlerRequest(handler gin.HandlerFunc, uid int64, body string) *httptest.ResponseRecorder {
	gin.SetMode(gin.TestMode)
	response := httptest.NewRecorder()
	context, _ := gin.CreateTestContext(response)
	context.Request = httptest.NewRequest(http.MethodPut, "/", strings.NewReader(body))
	context.Request.Header.Set("Content-Type", "application/json")
	if uid > 0 {
		context.Set("uid", uid)
	}
	handler(context)
	return response
}

func expectPair(mock sqlmock.Sqlmock, anniversary string) {
	var value any
	if anniversary != "" {
		parsed, _ := time.Parse("2006-01-02", anniversary)
		value = parsed
	}
	mock.ExpectQuery("SELECT id,user_a_id,user_b_id,invite_code,anniversary_date FROM pair").
		WithArgs(int64(1), int64(1)).
		WillReturnRows(sqlmock.NewRows([]string{"id", "user_a_id", "user_b_id", "invite_code", "anniversary_date"}).
			AddRow(7, 1, 2, "123456", value))
}

func expectUser(mock sqlmock.Sqlmock, id int64, nickname string, avatar, thumbnail *string) {
	mock.ExpectQuery("SELECT id,nickname,avatar_url,avatar_thumbnail_url FROM `user`").
		WithArgs(id).
		WillReturnRows(sqlmock.NewRows([]string{"id", "nickname", "avatar_url", "avatar_thumbnail_url"}).
			AddRow(id, nickname, nullableString(avatar), nullableString(thumbnail)))
}

func assertPairProfileResponse(t *testing.T, response *httptest.ResponseRecorder, nickname, anniversary string) {
	t.Helper()
	if response.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", response.Code, response.Body.String())
	}
	var body struct {
		Code int `json:"code"`
		Data struct {
			Bound           bool   `json:"bound"`
			PairID          int64  `json:"pair_id"`
			AnniversaryDate string `json:"anniversary_date"`
			Me              User   `json:"me"`
			Partner         User   `json:"partner"`
		} `json:"data"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if body.Code != 0 || !body.Data.Bound || body.Data.PairID != 7 || body.Data.Me.Nickname != nickname || body.Data.Partner.ID != 2 || body.Data.AnniversaryDate != anniversary {
		t.Fatalf("unexpected response = %#v", body)
	}
}

func responseCode(t *testing.T, response *httptest.ResponseRecorder) int {
	t.Helper()
	var body struct {
		Code int `json:"code"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	return body.Code
}

func stringPointer(value string) *string { return &value }

func nullableString(value *string) any {
	if value == nil {
		return nil
	}
	return *value
}

func withServerGlobals(store *Store, testHub *Hub, run func()) {
	oldStore, oldHub, oldConfig := st, hub, cfg
	st, hub = store, testHub
	cfg = &Config{}
	cfg.App.JWTSecret = "test-secret"
	cfg.App.TokenTTLHours = 1
	defer func() {
		st, hub, cfg = oldStore, oldHub, oldConfig
	}()
	run()
}
