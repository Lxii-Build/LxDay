package main

import (
	"database/sql"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
)

func createPendingPairForTest(t *testing.T, s *Store, ownerID int64, code string) int64 {
	t.Helper()
	res, err := s.DB.Exec(
		`INSERT INTO pair(user_a_id,user_b_id,invite_code,status) VALUES(?,0,?,1)`,
		ownerID, code,
	)
	if err != nil {
		t.Fatal(err)
	}
	id, err := res.LastInsertId()
	if err != nil {
		t.Fatal(err)
	}
	return id
}

func createUserForPairTest(t *testing.T, s *Store, name string) int64 {
	t.Helper()
	id, err := s.CreateUser(name+"_u", name+"@pair.test", name, hashPassword("Abcdefghij12"))
	if err != nil {
		t.Fatal(err)
	}
	return id
}

func TestGetPairByUserIDIgnoresPendingInvite(t *testing.T) {
	s := withTestStore(t)
	owner := createUserForPairTest(t, s, "pending_owner")
	createPendingPairForTest(t, s, owner, "PENDING1")

	if _, err := s.GetPairByUserID(owner); !errors.Is(err, sql.ErrNoRows) {
		t.Fatalf("pending invite must not be treated as bound: %v", err)
	}
	if _, err := pairFrom(s.DB, owner); !errors.Is(err, sql.ErrNoRows) {
		t.Fatalf("pairFrom must not return pending invite: %v", err)
	}
}

func TestBindPairSameInviteHasOneWinner(t *testing.T) {
	s := withTestStore(t)
	owner := createUserForPairTest(t, s, "same_code_owner")
	binder := createUserForPairTest(t, s, "same_code_binder")
	createPendingPairForTest(t, s, owner, "RACE0001")

	const workers = 16
	var wg sync.WaitGroup
	var wins atomic.Int32
	for i := 0; i < workers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if _, err := s.BindPair("RACE0001", binder); err == nil {
				wins.Add(1)
			}
		}()
	}
	wg.Wait()
	if got := wins.Load(); got != 1 {
		t.Fatalf("same invite winners=%d want 1", got)
	}
	var bound int
	if err := s.DB.QueryRow(
		`SELECT COUNT(*) FROM pair WHERE status=1 AND user_b_id=?`, binder,
	).Scan(&bound); err != nil {
		t.Fatal(err)
	}
	if bound != 1 {
		t.Fatalf("binder active memberships=%d want 1", bound)
	}
}

func TestBindPairSameUserCannotJoinTwoPairs(t *testing.T) {
	s := withTestStore(t)
	ownerA := createUserForPairTest(t, s, "owner_a")
	ownerB := createUserForPairTest(t, s, "owner_b")
	binder := createUserForPairTest(t, s, "racing_binder")
	createPendingPairForTest(t, s, ownerA, "RACE0002")
	createPendingPairForTest(t, s, ownerB, "RACE0003")

	var wg sync.WaitGroup
	var wins atomic.Int32
	for _, code := range []string{"RACE0002", "RACE0003"} {
		code := code
		wg.Add(1)
		go func() {
			defer wg.Done()
			if _, err := s.BindPair(code, binder); err == nil {
				wins.Add(1)
			}
		}()
	}
	wg.Wait()
	if got := wins.Load(); got > 1 {
		t.Fatalf("same binder joined %d pairs", got)
	}
	var bound int
	if err := s.DB.QueryRow(
		`SELECT COUNT(*) FROM pair WHERE status=1 AND (user_a_id=? OR user_b_id=?) AND user_a_id>0 AND user_b_id>0`,
		binder, binder,
	).Scan(&bound); err != nil {
		t.Fatal(err)
	}
	if bound > 1 {
		t.Fatalf("binder active memberships=%d want at most 1", bound)
	}
}
