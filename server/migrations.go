package main

import (
	"context"
	"database/sql"
	_ "embed"
	"fmt"
	"strings"
)

// baseSchemaSQL 内嵌基础建表脚本，作为唯一真源（与手动导入 sql/schema.sql 同一份），
// 服务端启动时自动执行 → 任何环境（compose / 宝塔自带 MySQL / 裸机 / 复用旧卷）零手动导入。
//
//go:embed sql/schema.sql
var baseSchemaSQL string

type migration struct {
	version    int
	name       string
	columns    []migrationColumn
	statements []string // 原始 SQL（建表等，需自身幂等，如 CREATE TABLE IF NOT EXISTS）
}

type migrationColumn struct {
	table  string
	column string
	alter  string
}

var migrations = []migration{
	{
		version: 1,
		name:    "profile_and_anniversary",
		columns: []migrationColumn{
			{
				table:  "user",
				column: "avatar_thumbnail_url",
				alter:  "ALTER TABLE `user` ADD COLUMN avatar_thumbnail_url VARCHAR(255) NULL AFTER avatar_url",
			},
			{
				table:  "pair",
				column: "anniversary_date",
				alter:  "ALTER TABLE pair ADD COLUMN anniversary_date DATE NULL AFTER invite_code",
			},
		},
	},
	{
		version: 2,
		name:    "account_and_settings",
		columns: []migrationColumn{
			{table: "user", column: "username", alter: "ALTER TABLE `user` ADD COLUMN username VARCHAR(32) NULL AFTER id"},
			{table: "user", column: "email", alter: "ALTER TABLE `user` ADD COLUMN email VARCHAR(128) NULL AFTER username"},
			{table: "user", column: "gender", alter: "ALTER TABLE `user` ADD COLUMN gender TINYINT NOT NULL DEFAULT 0 AFTER avatar_thumbnail_url"},
			{table: "user", column: "signature", alter: "ALTER TABLE `user` ADD COLUMN signature VARCHAR(200) NULL AFTER gender"},
			{table: "user", column: "birthday", alter: "ALTER TABLE `user` ADD COLUMN birthday DATE NULL AFTER signature"},
		},
		statements: []string{
			"ALTER TABLE `user` ADD UNIQUE KEY `uk_username` (`username`)",
			"ALTER TABLE `user` ADD UNIQUE KEY `uk_email` (`email`)",
			`CREATE TABLE IF NOT EXISTS app_setting (
				k VARCHAR(64) NOT NULL PRIMARY KEY,
				v TEXT,
				updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
			) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`,
		},
	},
	{
		version: 3,
		name:    "todo_repeat",
		columns: []migrationColumn{
			{table: "todo", column: "repeat_type", alter: "ALTER TABLE todo ADD COLUMN repeat_type TINYINT NOT NULL DEFAULT 0 AFTER remind_type"},
			{table: "todo", column: "weekdays", alter: "ALTER TABLE todo ADD COLUMN weekdays TINYINT NOT NULL DEFAULT 0 AFTER repeat_type"},
		},
	},
	{
		version: 4,
		name:    "app_version_and_admin",
		statements: []string{
			`CREATE TABLE IF NOT EXISTS app_version (
				id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
				platform VARCHAR(16) NOT NULL DEFAULT 'android',
				version_name VARCHAR(32) NOT NULL,
				version_code INT NOT NULL DEFAULT 0,
				apk_url VARCHAR(500) DEFAULT NULL,
				notes TEXT,
				force_update TINYINT NOT NULL DEFAULT 0,
				status TINYINT NOT NULL DEFAULT 1,
				created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
				PRIMARY KEY (id),
				KEY idx_platform_code (platform, version_code)
			) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`,
			`CREATE TABLE IF NOT EXISTS admin_user (
				id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
				username VARCHAR(64) NOT NULL,
				password_hash VARCHAR(255) NOT NULL,
				email VARCHAR(128) DEFAULT NULL,
				role VARCHAR(32) NOT NULL DEFAULT 'admin',
				must_change TINYINT NOT NULL DEFAULT 0,
				status TINYINT NOT NULL DEFAULT 1,
				last_login_at DATETIME DEFAULT NULL,
				created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
				PRIMARY KEY (id),
				UNIQUE KEY uk_admin_username (username)
			) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`,
			`CREATE TABLE IF NOT EXISTS admin_audit_log (
				id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
				admin_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
				admin_name VARCHAR(64) DEFAULT NULL,
				action VARCHAR(64) NOT NULL,
				detail VARCHAR(500) DEFAULT NULL,
				ip VARCHAR(64) DEFAULT NULL,
				created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
				PRIMARY KEY (id),
				KEY idx_admin_created (admin_id, created_at)
			) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`,
			`CREATE TABLE IF NOT EXISTS notify_template (
				id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
				code VARCHAR(64) NOT NULL,
				title VARCHAR(128) NOT NULL,
				body VARCHAR(500) NOT NULL,
				enabled TINYINT NOT NULL DEFAULT 1,
				updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
				PRIMARY KEY (id),
				UNIQUE KEY uk_tpl_code (code)
			) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`,
			`CREATE TABLE IF NOT EXISTS notify_record (
				id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
				template_code VARCHAR(64) DEFAULT NULL,
				title VARCHAR(128) NOT NULL,
				body VARCHAR(500) NOT NULL,
				target VARCHAR(64) NOT NULL DEFAULT 'all',
				sent_count INT NOT NULL DEFAULT 0,
				created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
				PRIMARY KEY (id)
			) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`,
		},
	},
	{
		version: 5,
		name:    "todo_remind_enabled",
		columns: []migrationColumn{
			{table: "todo", column: "remind_enabled", alter: "ALTER TABLE todo ADD COLUMN remind_enabled TINYINT(1) NOT NULL DEFAULT 1 AFTER weekdays"},
		},
	},
	{
		version: 6,
		name:    "request_log",
		statements: []string{
			`CREATE TABLE IF NOT EXISTS request_log (
				id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
				method VARCHAR(8) NOT NULL,
				path VARCHAR(255) NOT NULL,
				status INT NOT NULL DEFAULT 0,
				latency_ms BIGINT NOT NULL DEFAULT 0,
				ip VARCHAR(64) DEFAULT NULL,
				ua VARCHAR(255) DEFAULT NULL,
				request_id VARCHAR(32) DEFAULT NULL,
				created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
				PRIMARY KEY (id),
				KEY idx_created (created_at),
				KEY idx_path (path),
				KEY idx_status (status)
			) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`,
		},
	},
}

func runMigrations(db *sql.DB) error {
	// 单连接建议锁：多副本同时启动时串行化迁移，避免并发建表打架（单实例亦无害）。
	ctx := context.Background()
	if conn, err := db.Conn(ctx); err == nil {
		defer conn.Close()
		var locked sql.NullInt64
		if err := conn.QueryRowContext(ctx, "SELECT GET_LOCK('lxday_migrate', 30)").Scan(&locked); err == nil && locked.Valid && locked.Int64 == 1 {
			defer conn.ExecContext(ctx, "SELECT RELEASE_LOCK('lxday_migrate')")
		}
	}
	// ① 基础表：内嵌 schema.sql 自动建表（幂等，CREATE TABLE IF NOT EXISTS）
	if err := applyBaseSchema(db); err != nil {
		return err
	}
	// ② 增量迁移：在基础表之上补列/建新表
	return applyMigrations(db, migrations)
}

// applyBaseSchema 执行内嵌的 sql/schema.sql 建立所有基础表。
// 跳过 CREATE DATABASE / USE（库由 DSN 指定，且业务账号通常无建库权限）。
func applyBaseSchema(db *sql.DB) error {
	for _, stmt := range splitSQLStatements(baseSchemaSQL) {
		up := strings.ToUpper(strings.TrimSpace(stmt))
		if up == "" || strings.HasPrefix(up, "CREATE DATABASE") || strings.HasPrefix(up, "USE ") {
			continue
		}
		if _, err := db.Exec(stmt); err != nil {
			// 容忍已存在类错误，保证幂等
			if strings.Contains(err.Error(), "exists") || strings.Contains(err.Error(), "Duplicate") {
				continue
			}
			return fmt.Errorf("apply base schema: %w", err)
		}
	}
	return nil
}

// splitSQLStatements 按分号切分 SQL 脚本，逐行剔除 `--` 注释与空行；
// 这些 DDL 内部不含分号，故简单切分即可（不引入 multiStatements）。
func splitSQLStatements(sqlText string) []string {
	var out []string
	var b strings.Builder
	for _, line := range strings.Split(sqlText, "\n") {
		t := strings.TrimSpace(line)
		if t == "" || strings.HasPrefix(t, "--") {
			continue
		}
		b.WriteString(line)
		b.WriteByte('\n')
		if strings.HasSuffix(t, ";") {
			out = append(out, b.String())
			b.Reset()
		}
	}
	if strings.TrimSpace(b.String()) != "" {
		out = append(out, b.String())
	}
	return out
}

func applyMigrations(db *sql.DB, list []migration) error {
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

	for _, item := range list {
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
		for _, stmt := range item.statements {
			if _, err := db.Exec(stmt); err != nil {
				// 容忍重复存在类错误，保证语句幂等（如唯一键/表已存在）
				if strings.Contains(err.Error(), "Duplicate") || strings.Contains(err.Error(), "already exists") {
					continue
				}
				return fmt.Errorf("migration %d %s stmt: %w", item.version, item.name, err)
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
