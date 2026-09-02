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

// schemaBaselineVersion 是当前已知迁移的最高版本。迁移 1 负责“建表 + 补列 + 建索引”，
// 迁移 2 负责移除旧版 APP 版本表；历史数据库升级后会记账，之后不再重复执行。
const schemaBaselineVersion = 2

// migrationExecutor 是 sql.DB 与 sql.Tx 的共同子集，让建表、补列、建索引能在同一
// 事务内完成。迁移失败时不会留下“只建了一半索引却已标记成功”的状态。
type migrationExecutor interface {
	Exec(query string, args ...any) (sql.Result, error)
	Query(query string, args ...any) (*sql.Rows, error)
}

// runMigrations 执行有版本记录的内嵌迁移。迁移 1 是建表/补列/建索引基线，
// 迁移 2 起每个版本都必须追加新迁移，不能把数据回填或破坏性 DDL 偷塞回旧基线。
//
// **执行顺序必须是「建表 → 补列 → 建索引」，不能一趟走完。**
//
// 原因（0821 踩过，生产库直接起不来）：老库里 `photo` 表已存在，
// `CREATE TABLE IF NOT EXISTS` 是空操作，新列 `deleted_at` 不会凭空出现；
// 而 schema.sql 里紧跟着就有 `CREATE INDEX ... ON photo(status, deleted_at)`，
// 于是在补列之前就引用了不存在的列 → `no such column: deleted_at` → 启动失败。
//
// 新库不会暴露这个问题（CREATE TABLE 自带新列），所以只用全新临时库做测试
// 永远测不到这条升级路径 —— 见 migrations_upgrade_test.go 的回归测试。
func runMigrations(db *sql.DB) error {
	// SQLite 不允许在事务内切换 journal_mode；它必须先于下面的原子 DDL
	// 事务单独执行。schema.sql 保留这条声明作为新库真源，split 时会跳过它，
	// 防止以后又被意外放回事务。
	if _, err := db.Exec(`PRAGMA journal_mode=WAL`); err != nil {
		return fmt.Errorf("enable WAL journal mode failed: %w", err)
	}
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS schema_migrations (
		version INTEGER PRIMARY KEY,
		applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
	)`); err != nil {
		return fmt.Errorf("create migration ledger failed: %w", err)
	}
	var current sql.NullInt64
	if err := db.QueryRow(`SELECT MAX(version) FROM schema_migrations`).Scan(&current); err != nil {
		return fmt.Errorf("read migration ledger failed: %w", err)
	}
	if current.Valid && current.Int64 > schemaBaselineVersion {
		return fmt.Errorf("database schema version %d is newer than this server supports", current.Int64)
	}
	if current.Valid && current.Int64 == schemaBaselineVersion {
		return nil
	}
	if !current.Valid || current.Int64 < 1 {
		if err := applyMigration(db, 1, applySchemaBaseline); err != nil {
			return err
		}
		current = sql.NullInt64{Int64: 1, Valid: true}
	}
	if current.Int64 < 2 {
		if err := applyMigration(db, 2, retireAppVersionTable); err != nil {
			return err
		}
	}
	return nil
}

func applyMigration(db *sql.DB, version int, apply func(migrationExecutor) error) error {
	tx, err := db.Begin()
	if err != nil {
		return fmt.Errorf("begin migration %d failed: %w", version, err)
	}
	defer func() { _ = tx.Rollback() }()
	if err := apply(tx); err != nil {
		return err
	}
	if _, err := tx.Exec(`INSERT INTO schema_migrations(version) VALUES(?)`, version); err != nil {
		return fmt.Errorf("record migration %d failed: %w", version, err)
	}
	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit migration %d failed: %w", version, err)
	}
	return nil
}

// retireAppVersionTable removes the pre-1.0.9 database-backed release catalog.
// Release history is now the GitHub Releases source of truth; deleting this
// table also prevents a stale admin record from being served as an update.
func retireAppVersionTable(db migrationExecutor) error {
	if _, err := db.Exec(`DROP TABLE IF EXISTS app_version`); err != nil {
		return fmt.Errorf("remove legacy app version table failed: %w", err)
	}
	return nil
}

func applySchemaBaseline(db migrationExecutor) error {
	tableStmts, indexStmts := splitSchemaByKind(baseSchemaSQL)

	// ① 建表：新库一次建全，老库为空操作
	for _, stmt := range tableStmts {
		if _, err := db.Exec(stmt); err != nil {
			return fmt.Errorf("apply schema (tables) failed: %w", err)
		}
	}
	// ② 补列：老库缺的新列在这里加上，索引才引用得到
	if err := addColumns(db); err != nil {
		return err
	}
	// ③ 建索引：此时无论新库老库，列都齐了
	for _, stmt := range indexStmts {
		if _, err := db.Exec(stmt); err != nil {
			return fmt.Errorf("apply schema (indexes) failed: %w", err)
		}
	}
	return nil
}

// splitSchemaByKind 把建表脚本拆成「建表/其它」与「建索引」两组。
//
// 判定只看语句开头是不是 CREATE [UNIQUE] INDEX：schema.sql 全是 DDL，
// 不存在把索引语句嵌在别处的情况。
func splitSchemaByKind(sqlText string) (tables []string, indexes []string) {
	for _, stmt := range splitSQLStatements(sqlText) {
		t := strings.TrimSpace(stmt)
		if t == "" {
			continue
		}
		upper := strings.ToUpper(t)
		if upper == "PRAGMA JOURNAL_MODE=WAL" {
			continue
		}
		if strings.HasPrefix(upper, "CREATE INDEX") || strings.HasPrefix(upper, "CREATE UNIQUE INDEX") {
			indexes = append(indexes, stmt)
		} else {
			tables = append(tables, stmt)
		}
	}
	return tables, indexes
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
	// preview_path：三档缩略图里的中间尺寸（长边 1080）。老照片为 NULL，scanPhoto 回退原图。
	{"photo", "preview_path", "TEXT"},
	// deleted_at：进回收站的时刻，回收站保留期与剩余天数都依赖它。
	// 老数据为 NULL，清理逻辑用 COALESCE(deleted_at, created_at) 兜底。
	{"photo", "deleted_at", "DATETIME"},
	// upload_idempotency_key：弱网重试时识别同一张上传，避免产生重复照片。
	{"photo", "upload_idempotency_key", "TEXT"},
	// unclassified_name：「未归类」虚拟相册的自定义名称（管理员要求它也能改名）。
	// 存在 pair 上而非 album 表——它不是真实相册行（album_id=0）。
	{"pair", "unclassified_name", "TEXT"},
}

// addColumns 幂等补列：已存在则跳过，不存在才 ALTER，保证重复启动不报错。
func addColumns(db migrationExecutor) error {
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
func columnExists(db migrationExecutor, table, column string) (bool, error) {
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
