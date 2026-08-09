package main

import (
	"database/sql"
	"fmt"
)

type migration struct {
	version int
	name    string
	statements []string
}

var migrations = []migration{
	{
		version: 1,
		name: "profile_and_anniversary",
		statements: []string{
			"ALTER TABLE `user` ADD COLUMN avatar_thumbnail_url VARCHAR(255) NULL AFTER avatar_url",
			"ALTER TABLE pair ADD COLUMN anniversary_date DATE NULL AFTER invite_code",
		},
	},
}

func runMigrations(db *sql.DB) error {
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS schema_migrations (
		version INT NOT NULL PRIMARY KEY,
		name VARCHAR(128) NOT NULL,
		applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
	) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`); err != nil {
		return fmt.Errorf("create schema_migrations: %w", err)
	}

	rows, err := db.Query("SELECT version FROM schema_migrations")
	if err != nil {
		return fmt.Errorf("read schema migrations: %w", err)
	}
	applied := map[int]bool{}
	for rows.Next() {
		var version int
		if err := rows.Scan(&version); err != nil {
			rows.Close()
			return err
		}
		applied[version] = true
	}
	if err := rows.Close(); err != nil {
		return err
	}

	for _, item := range migrations {
		if applied[item.version] {
			continue
		}
		tx, err := db.Begin()
		if err != nil {
			return err
		}
		for _, statement := range item.statements {
			if _, err := tx.Exec(statement); err != nil {
				tx.Rollback()
				return fmt.Errorf("migration %d %s: %w", item.version, item.name, err)
			}
		}
		if _, err := tx.Exec(
			"INSERT INTO schema_migrations(version,name) VALUES(?,?)",
			item.version,
			item.name,
		); err != nil {
			tx.Rollback()
			return fmt.Errorf("record migration %d: %w", item.version, err)
		}
		if err := tx.Commit(); err != nil {
			return fmt.Errorf("commit migration %d: %w", item.version, err)
		}
	}
	return nil
}
