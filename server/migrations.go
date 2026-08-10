package main

import (
	"database/sql"
	"fmt"
)

type migration struct {
	version int
	name    string
	columns []migrationColumn
}

type migrationColumn struct {
	table  string
	column string
	alter  string
}

var migrations = []migration{
	{
		version: 1,
		name: "profile_and_anniversary",
		columns: []migrationColumn{
			{
				table: "user",
				column: "avatar_thumbnail_url",
				alter: "ALTER TABLE `user` ADD COLUMN avatar_thumbnail_url VARCHAR(255) NULL AFTER avatar_url",
			},
			{
				table: "pair",
				column: "anniversary_date",
				alter: "ALTER TABLE pair ADD COLUMN anniversary_date DATE NULL AFTER invite_code",
			},
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
		for _, column := range item.columns {
			var exists int
			if err := db.QueryRow(
				"SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name=?",
				column.table,
				column.column,
			).Scan(&exists); err != nil {
				return fmt.Errorf("check migration %d %s: %w", item.version, item.name, err)
			}
			if exists == 0 {
				if _, err := db.Exec(column.alter); err != nil {
					return fmt.Errorf("migration %d %s: %w", item.version, item.name, err)
				}
			}
		}
		if _, err := db.Exec(
			"INSERT INTO schema_migrations(version,name) VALUES(?,?)",
			item.version,
			item.name,
		); err != nil {
			return fmt.Errorf("record migration %d: %w", item.version, err)
		}
	}
	return nil
}
