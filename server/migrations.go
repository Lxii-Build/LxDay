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

// runMigrations 执行内嵌建表脚本（全部 CREATE TABLE/INDEX IF NOT EXISTS，幂等），
// 随后对「已存在的老库」补齐新增列（addColumns）。
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
	return addColumns(db)
}

// addedColumn 描述一个「后加的列」：新库由 schema.sql 的 CREATE TABLE 直接建出，
// 老库则由这里补。SQLite 的 ALTER TABLE ADD COLUMN 不支持 IF NOT EXISTS，
// 若不先探测就执行，每次启动都会报 "duplicate column name" —— 故先查 PRAGMA table_info。
type addedColumn struct {
	table, column, definition string
}

// schemaAddedColumns 与 schema.sql 中标注的迁移锚点一一对应，缺一个列老库就会在运行时报错。
var schemaAddedColumns = []addedColumn{
	// token_ver：JWT 撤销版本号，见 schema.sql 注释。老库缺此列会导致所有鉴权查询直接失败。
	{"user", "token_ver", "INTEGER NOT NULL DEFAULT 0"},
	{"admin_user", "token_ver", "INTEGER NOT NULL DEFAULT 0"},
}

// addColumns 幂等补列：已存在则跳过，不存在才 ALTER，保证重复启动不报错。
func addColumns(db *sql.DB) error {
	for _, ac := range schemaAddedColumns {
		has, err := columnExists(db, ac.table, ac.column)
		if err != nil {
			return fmt.Errorf("inspect %s.%s failed: %w", ac.table, ac.column, err)
		}
		if has {
			continue
		}
		// 表名用双引号包裹：user 是 SQLite 保留字，既有代码一律加引号/反引号。
		stmt := fmt.Sprintf(`ALTER TABLE "%s" ADD COLUMN %s %s`, ac.table, ac.column, ac.definition)
		if _, err := db.Exec(stmt); err != nil {
			return fmt.Errorf("add column %s.%s failed: %w", ac.table, ac.column, err)
		}
	}
	return nil
}

// columnExists 通过 PRAGMA table_info 判断列是否已存在（表不存在时返回 false，不报错）。
func columnExists(db *sql.DB, table, column string) (bool, error) {
	rows, err := db.Query(fmt.Sprintf(`PRAGMA table_info("%s")`, table))
	if err != nil {
		return false, err
	}
	defer rows.Close()
	for rows.Next() {
		var cid int
		var name, ctype string
		var notNull, pk int
		var dflt sql.NullString
		if err := rows.Scan(&cid, &name, &ctype, &notNull, &dflt, &pk); err != nil {
			return false, err
		}
		if strings.EqualFold(name, column) {
			return true, nil
		}
	}
	return false, rows.Err()
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
