-- 林曦日记 · SQLite 建表脚本（幂等，服务端启动自动执行；单容器无需外部数据库）
PRAGMA journal_mode=WAL;

CREATE TABLE IF NOT EXISTS "user" (
  id                   INTEGER PRIMARY KEY AUTOINCREMENT,
  username             TEXT,
  email                TEXT,
  nickname             TEXT    NOT NULL,
  avatar_url           TEXT,
  avatar_thumbnail_url TEXT,
  gender               INTEGER NOT NULL DEFAULT 0,
  signature            TEXT,
  birthday             DATE,
  password_hash        TEXT    NOT NULL,
  status               INTEGER NOT NULL DEFAULT 1,
  -- token_ver：JWT 撤销版本号。签发时写进 claims，鉴权时与库中当前值比对，不一致即失效。
  -- 用户 token 有效期长达 720h，若不可撤销则改密/封禁后旧 token 仍能整月全功能访问。
  token_ver            INTEGER NOT NULL DEFAULT 0,
  created_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_nickname ON "user"(nickname);
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_username ON "user"(username) WHERE username IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_email    ON "user"(email)    WHERE email IS NOT NULL;

CREATE TABLE IF NOT EXISTS pair (
  id               INTEGER PRIMARY KEY AUTOINCREMENT,
  user_a_id        INTEGER NOT NULL DEFAULT 0,
  user_b_id        INTEGER NOT NULL DEFAULT 0,
  invite_code      TEXT    NOT NULL,
  anniversary_date DATE,
  status           INTEGER NOT NULL DEFAULT 1,
  unbind_time      DATETIME,
  created_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_invite_code ON pair(invite_code);
CREATE INDEX IF NOT EXISTS idx_pair_user_a ON pair(user_a_id);
CREATE INDEX IF NOT EXISTS idx_pair_user_b ON pair(user_b_id);

CREATE TABLE IF NOT EXISTS todo (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  pair_id        INTEGER NOT NULL,
  creator_id     INTEGER NOT NULL,
  assignee_id    INTEGER NOT NULL,
  title          TEXT    NOT NULL,
  note           TEXT,
  remind_at      DATETIME,
  remind_type    INTEGER NOT NULL DEFAULT 0,
  repeat_type    INTEGER NOT NULL DEFAULT 0,
  weekdays       INTEGER NOT NULL DEFAULT 0,
  remind_enabled INTEGER NOT NULL DEFAULT 1,
  status         INTEGER NOT NULL DEFAULT 0,
  completed_at   DATETIME,
  created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_todo_pair_status ON todo(pair_id, status);
CREATE INDEX IF NOT EXISTS idx_todo_assignee_remind ON todo(assignee_id, remind_at);

-- ---------- 相册 ----------
-- 四张表都带 pair_id：归属校验只查一次本表即可，不必为每次读写多跳一次 join
-- （photo.pair_id 是相对 album 的冗余，但正是它让「照片未归类(album_id=0)」时仍能判定归属）。

CREATE TABLE IF NOT EXISTS album (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  pair_id        INTEGER NOT NULL,
  name           TEXT    NOT NULL,
  cover_photo_id INTEGER,
  created_by     INTEGER NOT NULL,
  status         INTEGER NOT NULL DEFAULT 1,
  created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_album_pair_status ON album(pair_id, status);

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
CREATE INDEX IF NOT EXISTS idx_photo_pair_album_status ON photo(pair_id, album_id, status);
CREATE INDEX IF NOT EXISTS idx_photo_pair_taken ON photo(pair_id, taken_at);

CREATE TABLE IF NOT EXISTS photo_comment (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  photo_id   INTEGER NOT NULL,
  pair_id    INTEGER NOT NULL,
  user_id    INTEGER NOT NULL,
  content    TEXT    NOT NULL,
  status     INTEGER NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_photo_comment_photo ON photo_comment(photo_id, status);

CREATE TABLE IF NOT EXISTS photo_like (
  photo_id   INTEGER NOT NULL,
  user_id    INTEGER NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (photo_id, user_id)
);
-- __NEXT_SCHEMA__

CREATE TABLE IF NOT EXISTS status_history (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  pair_id         INTEGER NOT NULL,
  user_id         INTEGER NOT NULL,
  battery         INTEGER NOT NULL DEFAULT 0,
  charging        INTEGER NOT NULL DEFAULT 0,
  screen_on       INTEGER NOT NULL DEFAULT 0,
  locked          INTEGER NOT NULL DEFAULT 1,
  foreground_pkg  TEXT,
  foreground_name TEXT,
  ssid            TEXT,
  network         TEXT    NOT NULL DEFAULT 'wifi',
  ts              DATETIME NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_pair_user_ts ON status_history(pair_id, user_id, ts);
CREATE INDEX IF NOT EXISTS idx_hist_pair_user_ts ON status_history(pair_id, user_id, ts);

CREATE TABLE IF NOT EXISTS diary (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  pair_id    INTEGER NOT NULL,
  author_id  INTEGER NOT NULL,
  title      TEXT    NOT NULL,
  content    TEXT    NOT NULL,
  diary_date DATE    NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_diary_pair_date ON diary(pair_id, diary_date);

CREATE TABLE IF NOT EXISTS diary_image (
  id       INTEGER PRIMARY KEY AUTOINCREMENT,
  diary_id INTEGER NOT NULL,
  url      TEXT    NOT NULL,
  sort_no  INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_diary_image ON diary_image(diary_id);

CREATE TABLE IF NOT EXISTS push_token (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id    INTEGER NOT NULL,
  platform   TEXT    NOT NULL,
  channel    TEXT    NOT NULL,
  token      TEXT    NOT NULL,
  status     INTEGER NOT NULL DEFAULT 1,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_channel ON push_token(user_id, channel);

CREATE TABLE IF NOT EXISTS app_setting (
  k          TEXT PRIMARY KEY,
  v          TEXT,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- __NEXT_SCHEMA2__

CREATE TABLE IF NOT EXISTS admin_user (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  username      TEXT    NOT NULL,
  password_hash TEXT    NOT NULL,
  email         TEXT,
  role          TEXT    NOT NULL DEFAULT 'admin',
  must_change   INTEGER NOT NULL DEFAULT 0,
  status        INTEGER NOT NULL DEFAULT 1,
  -- token_ver：同 user.token_ver，改密/重置/禁用/删除时 +1，令旧后台 token 立即失效。
  token_ver     INTEGER NOT NULL DEFAULT 0,
  last_login_at DATETIME,
  created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_admin_username ON admin_user(username);

CREATE TABLE IF NOT EXISTS app_version (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  platform     TEXT    NOT NULL DEFAULT 'android',
  version_name TEXT    NOT NULL,
  version_code INTEGER NOT NULL DEFAULT 0,
  apk_url      TEXT,
  notes        TEXT,
  force_update INTEGER NOT NULL DEFAULT 0,
  status       INTEGER NOT NULL DEFAULT 1,
  created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ver_platform_code ON app_version(platform, version_code);

CREATE TABLE IF NOT EXISTS admin_audit_log (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  admin_id   INTEGER NOT NULL DEFAULT 0,
  admin_name TEXT,
  action     TEXT    NOT NULL,
  detail     TEXT,
  ip         TEXT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_audit_admin_created ON admin_audit_log(admin_id, created_at);
-- 审计日志按「操作人/动作/时间区间」筛选（后台系统日志页），补索引避免全表扫。
CREATE INDEX IF NOT EXISTS idx_audit_created ON admin_audit_log(created_at);
CREATE INDEX IF NOT EXISTS idx_audit_action ON admin_audit_log(action);
CREATE INDEX IF NOT EXISTS idx_audit_name ON admin_audit_log(admin_name);

CREATE TABLE IF NOT EXISTS notify_template (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  code       TEXT    NOT NULL,
  title      TEXT    NOT NULL,
  body       TEXT    NOT NULL,
  enabled    INTEGER NOT NULL DEFAULT 1,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_tpl_code ON notify_template(code);

CREATE TABLE IF NOT EXISTS notify_record (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  template_code TEXT,
  title         TEXT    NOT NULL,
  body          TEXT    NOT NULL,
  target        TEXT    NOT NULL DEFAULT 'all',
  sent_count    INTEGER NOT NULL DEFAULT 0,
  created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS request_log (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  method     TEXT    NOT NULL,
  path       TEXT    NOT NULL,
  status     INTEGER NOT NULL DEFAULT 0,
  latency_ms INTEGER NOT NULL DEFAULT 0,
  ip         TEXT,
  ua         TEXT,
  request_id TEXT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_reqlog_created ON request_log(created_at);
CREATE INDEX IF NOT EXISTS idx_reqlog_path ON request_log(path);
CREATE INDEX IF NOT EXISTS idx_reqlog_status ON request_log(status);


