package main

import (
	"database/sql"
	_ "embed"
	"fmt"
	"strings"
)

// baseSchemaSQL 内嵌 SQLite 建表脚本，作为唯一真源；服务端启动自动执行 → 单容器零手动导入。
//
//go:embed sql/schema.sql
var baseSchemaSQL string

// runMigrations 执行内嵌建表脚本（全部 CREATE TABLE/INDEX IF NOT EXISTS，幂等）。
// 采用单文件 SQLite + 单实例，无需版本化迁移与建议锁。
func runMigrations(db *sql.DB) error {
	for _, stmt := range splitSQLStatements(baseSchemaSQL) {
		if strings.TrimSpace(stmt) == "" {
			continue
		}
		if _, err := db.Exec(stmt); err != nil {
			return fmt.Errorf("apply schema failed: %w", err)
		}
	}
	return nil
}

// splitSQLStatements 按分号切分 SQL 脚本，逐行剔除 `--` 注释与空行（DDL 内部不含分号）。
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
