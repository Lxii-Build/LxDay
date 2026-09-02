package main

import (
	"database/sql"
	"errors"
	"log/slog"
)

// errUserHasActivePair prevents a destructive account removal from silently
// breaking the other member's active relationship. The administrator must
// unbind the pair explicitly first, so that operation is visible and audited.
var errUserHasActivePair = errors.New("user has an active pair")

// DeleteUser permanently removes a user account and rows owned by that
// account. It deliberately refuses active pairs: deleting one side without an
// explicit unbind would leave the remaining user with an unexpected loss of
// shared access and no recovery path.
//
// Database rows are removed in one transaction. Files are removed only after
// commit, and every path goes through the same traversal-safe helper used by
// recycle-bin purges. A failed file removal is logged but cannot roll back a
// committed account deletion.
func (s *Store) DeleteUser(id int64) error {
	if id <= 0 {
		return sql.ErrNoRows
	}

	tx, err := s.DB.Begin()
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()

	var activePairCount int
	if err := tx.QueryRow(
		`SELECT COUNT(*) FROM pair
		 WHERE status=1 AND user_a_id>0 AND user_b_id>0
		   AND (user_a_id=? OR user_b_id=?)`, id, id).Scan(&activePairCount); err != nil {
		return err
	}
	if activePairCount > 0 {
		return errUserHasActivePair
	}

	var avatarURL, avatarThumbnailURL sql.NullString
	if err := tx.QueryRow(
		"SELECT avatar_url,avatar_thumbnail_url FROM `user` WHERE id=?", id,
	).Scan(&avatarURL, &avatarThumbnailURL); err != nil {
		return err
	}

	// Keep all disk paths before deleting photo rows. url/thumb_url/preview_path
	// are storage-relative paths in the database, not the generated /media URLs.
	rows, err := tx.Query(
		`SELECT url,thumb_url,preview_path FROM photo WHERE uploader_id=?`, id)
	if err != nil {
		return err
	}
	photos := make([]*Photo, 0)
	for rows.Next() {
		var p Photo
		var thumb, preview sql.NullString
		if err := rows.Scan(&p.diskPath, &thumb, &preview); err != nil {
			slog.Error("scan user photo paths failed", "user_id", id, "err", err)
			_ = rows.Close()
			return err
		}
		p.diskThumb = thumb.String
		p.diskPreview = preview.String
		photos = append(photos, &p)
	}
	if err := rows.Err(); err != nil {
		_ = rows.Close()
		return err
	}
	if err := rows.Close(); err != nil {
		return err
	}

	statements := []struct {
		query string
		args  []any
	}{
		{
			`UPDATE album SET cover_photo_id=NULL
			 WHERE cover_photo_id IN (SELECT id FROM photo WHERE uploader_id=?)`,
			[]any{id},
		},
		{
			`DELETE FROM photo_comment
			 WHERE photo_id IN (SELECT id FROM photo WHERE uploader_id=?) OR user_id=?`,
			[]any{id, id},
		},
		{
			`DELETE FROM photo_like
			 WHERE photo_id IN (SELECT id FROM photo WHERE uploader_id=?) OR user_id=?`,
			[]any{id, id},
		},
		{`DELETE FROM photo WHERE uploader_id=?`, []any{id}},
		{`DELETE FROM todo WHERE creator_id=? OR assignee_id=?`, []any{id, id}},
		{`DELETE FROM status_history WHERE user_id=?`, []any{id}},
		{`DELETE FROM push_token WHERE user_id=?`, []any{id}},
		// Keep couple albums as historical records, but do not leave a dangling
		// creator id after the account is gone.
		{`UPDATE album SET created_by=0 WHERE created_by=?`, []any{id}},
		// A pending invite owned by the deleted user is no longer usable.
		{
			`DELETE FROM pair WHERE status=1 AND
			 ((user_a_id=? AND user_b_id=0) OR (user_b_id=? AND user_a_id=0))`,
			[]any{id, id},
		},
		// Preserve historical unbound rows without retaining a dangling user id.
		{
			`UPDATE pair SET user_a_id=CASE WHEN user_a_id=? THEN 0 ELSE user_a_id END,
			 user_b_id=CASE WHEN user_b_id=? THEN 0 ELSE user_b_id END
			 WHERE status<>1 AND (user_a_id=? OR user_b_id=?)`,
			[]any{id, id, id, id},
		},
	}
	for _, statement := range statements {
		if _, err := tx.Exec(statement.query, statement.args...); err != nil {
			return err
		}
	}

	res, err := tx.Exec("DELETE FROM `user` WHERE id=?", id)
	if err != nil {
		return err
	}
	deleted, err := res.RowsAffected()
	if err != nil {
		return err
	}
	if deleted != 1 {
		return sql.ErrNoRows
	}
	if err := tx.Commit(); err != nil {
		return err
	}

	// These helpers accept only known upload URL/path shapes and reject
	// traversal. They are best-effort after the transaction has committed.
	removeOldAvatar(avatarURL.String)
	removeOldAvatar(avatarThumbnailURL.String)
	for _, photo := range photos {
		removePhotoFiles(photo)
	}
	return nil
}
