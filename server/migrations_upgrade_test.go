package main

import (
	"database/sql"
	"path/filepath"
	"strings"
	"testing"
)

// oldSchemaSubset 是 0821 之前的老库形态（**不含**本轮新增的四列）。
//
// 只建与本轮迁移相关的表，够用即可：迁移逻辑是按表逐个补列的，
// 其余表存不存在不影响这条路径的验证。
const oldSchemaSubset = `
CREATE TABLE IF NOT EXISTS pair (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  user_a_id    INTEGER NOT NULL DEFAULT 0,
  user_b_id    INTEGER NOT NULL DEFAULT 0,
  invite_code  TEXT,
  status       INTEGER NOT NULL DEFAULT 1,
  anniversary_date DATE,
  created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS photo (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  album_id    INTEGER NOT NULL DEFAULT 0,
  pair_id     INTEGER NOT NULL,
  uploader_id INTEGER NOT NULL,
  url         TEXT    NOT NULL,
  thumb_url   TEXT,
  width       INTEGER NOT NULL DEFAULT 0,
  height      INTEGER NOT NULL DEFAULT 0,
  size_bytes  INTEGER NOT NULL DEFAULT 0,
  mime        TEXT,
  taken_at    DATETIME,
  caption     TEXT,
  status      INTEGER NOT NULL DEFAULT 1,
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
`

func openTempDB(t *testing.T) *sql.DB {
	t.Helper()
	dsn := "file:" + filepath.Join(t.TempDir(), "old.db") +
		"?_pragma=busy_timeout(5000)&_pragma=foreign_keys(on)"
	db, err := sqlOpen(dsn)
	if err != nil {
		t.Fatalf("open sqlite: %v", err)
	}
	db.SetMaxOpenConns(1)
	t.Cleanup(func() { db.Close() })
	return db
}

// 老库升级必须成功。
//
// **这是 0821 生产事故的回归测试**：当时 runMigrations 一趟执行整个 schema.sql，
// 而老库里 photo 表已存在（CREATE TABLE IF NOT EXISTS 空操作、新列不会出现），
// 紧随其后的 `CREATE INDEX ... ON photo(status, deleted_at)` 就引用了不存在的列，
// 报 `no such column: deleted_at`，容器**直接起不来**。
//
// 我当时所有测试都用全新临时库（CREATE TABLE 自带新列），
// 所以这条升级路径一次都没被跑到 —— 这个测试专门补上它。
func TestMigrateFromOldSchema(t *testing.T) {
	db := openTempDB(t)

	// ① 先造一个"老库"：只有旧表结构，没有本轮新增的列
	for _, stmt := range splitSQLStatements(oldSchemaSubset) {
		if strings.TrimSpace(stmt) == "" {
			continue
		}
		if _, err := db.Exec(stmt); err != nil {
			t.Fatalf("建老库失败: %v", err)
		}
	}
	// 塞一行老数据，确认迁移不会丢数据
	if _, err := db.Exec(
		`INSERT INTO photo(pair_id,uploader_id,url,width,height,size_bytes,mime,status)
		 VALUES(1,1,'upload/2026/01/01/old.jpg',800,600,1024,'image/jpeg',1)`); err != nil {
		t.Fatalf("插老数据失败: %v", err)
	}

	// 确认老库确实没有新列（否则这个测试就是自欺欺人）
	for _, col := range []string{"preview_path", "deleted_at", "upload_idempotency_key"} {
		has, err := columnExists(db, "photo", col)
		if err != nil {
			t.Fatal(err)
		}
		if has {
			t.Fatalf("前置条件错误：老库不该已有 photo.%s", col)
		}
	}

	// ② 跑迁移 —— 这一步在修复前会报 "no such column: deleted_at"
	if err := runMigrations(db); err != nil {
		t.Fatalf("老库升级失败: %v", err)
	}

	// ③ 新列必须都补上了
	for _, tc := range []struct{ table, col string }{
		{"photo", "preview_path"},
		{"photo", "deleted_at"},
		{"photo", "upload_idempotency_key"},
		{"pair", "unclassified_name"},
	} {
		has, err := columnExists(db, tc.table, tc.col)
		if err != nil {
			t.Fatal(err)
		}
		if !has {
			t.Errorf("迁移后 %s.%s 仍不存在", tc.table, tc.col)
		}
	}

	// ④ 索引必须建出来了（它依赖 deleted_at）
	var idxName string
	if err := db.QueryRow(
		`SELECT name FROM sqlite_master WHERE type='index' AND name='idx_photo_status_deleted'`).
		Scan(&idxName); err != nil {
		t.Fatalf("依赖 deleted_at 的索引未建出: %v", err)
	}

	// ⑤ 老数据还在，且新列为 NULL（代码里用 COALESCE 兜底）
	var n int
	if err := db.QueryRow(`SELECT COUNT(*) FROM photo`).Scan(&n); err != nil {
		t.Fatal(err)
	}
	if n != 1 {
		t.Fatalf("老数据丢了，剩 %d 行", n)
	}
	var preview, deleted sql.NullString
	if err := db.QueryRow(`SELECT preview_path, deleted_at FROM photo WHERE id=1`).
		Scan(&preview, &deleted); err != nil {
		t.Fatalf("读老行失败: %v", err)
	}
	if preview.Valid || deleted.Valid {
		t.Error("老行的新列应为 NULL")
	}

	// ⑥ 幂等：再跑一次不能报错（每次启动都会执行）
	if err := runMigrations(db); err != nil {
		t.Fatalf("重复迁移失败（幂等性破了）: %v", err)
	}
}

// 全新库也要能一次建好（别为了修老库把新库搞坏）。
func TestMigrateFreshDB(t *testing.T) {
	db := openTempDB(t)
	if err := runMigrations(db); err != nil {
		t.Fatalf("新库建表失败: %v", err)
	}
	// 抽查：新列与依赖它的索引都在
	for _, tc := range []struct{ table, col string }{
		{"photo", "preview_path"},
		{"photo", "deleted_at"},
		{"pair", "unclassified_name"},
		{"user", "token_ver"},
	} {
		has, err := columnExists(db, tc.table, tc.col)
		if err != nil {
			t.Fatal(err)
		}
		if !has {
			t.Errorf("新库缺 %s.%s", tc.table, tc.col)
		}
	}
	var name string
	if err := db.QueryRow(
		`SELECT name FROM sqlite_master WHERE type='index' AND name='idx_photo_status_deleted'`).
		Scan(&name); err != nil {
		t.Fatalf("新库缺索引 idx_photo_status_deleted: %v", err)
	}
	// 幂等
	if err := runMigrations(db); err != nil {
		t.Fatalf("新库重复迁移失败: %v", err)
	}
}

// 迁移记账必须在同一事务里提交：新库跑完只记录一次；重复启动不再重复执行整套
// DDL。这个断言防止未来又把 runMigrations 退回“每次启动碰碰运气跑一遍”。
func TestMigrationBaselineIsRecordedOnce(t *testing.T) {
	db := openTempDB(t)
	if err := runMigrations(db); err != nil {
		t.Fatal(err)
	}
	if err := runMigrations(db); err != nil {
		t.Fatal(err)
	}
	var count, version int
	if err := db.QueryRow(`SELECT COUNT(*), MAX(version) FROM schema_migrations`).Scan(&count, &version); err != nil {
		t.Fatal(err)
	}
	if count != 1 || version != schemaBaselineVersion {
		t.Fatalf("ledger count=%d version=%d, want one v%d row", count, version, schemaBaselineVersion)
	}
}

// schema.sql 里**任何**引用「后加列」的索引，都必须在补列之后才执行。
//
// 这条断言是为了防止同类问题再犯：日后有人又在 schema.sql 里加一条
// 引用新列的索引，只要拆分逻辑没把它归到 index 组，这里就会红。
func TestIndexesReferencingAddedColumnsAreSeparated(t *testing.T) {
	tables, indexes := splitSchemaByKind(baseSchemaSQL)

	// 所有后加列名
	added := map[string]string{}
	for _, ac := range schemaAddedColumns {
		added[ac.column] = ac.table
	}

	// 建表组里不该出现 CREATE INDEX
	for _, stmt := range tables {
		if strings.Contains(strings.ToUpper(stmt), "CREATE INDEX") ||
			strings.Contains(strings.ToUpper(stmt), "CREATE UNIQUE INDEX") {
			t.Errorf("建表组里混入了索引语句：%s", strings.TrimSpace(stmt))
		}
	}

	// 引用了后加列的索引，必须在 index 组里（这样才会在补列之后执行）
	found := 0
	for _, stmt := range indexes {
		for col := range added {
			if strings.Contains(stmt, col) {
				found++
			}
		}
	}
	if found == 0 {
		t.Skip("当前 schema 没有引用后加列的索引（idx_photo_status_deleted 被删了？）")
	}
	t.Logf("有 %d 条索引引用了后加列，均已归入补列之后执行", found)
}
