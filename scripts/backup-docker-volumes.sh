#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

# 对运行中的 Compose 部署做一致性备份。SQLite 使用 WAL，直接复制单个 .db
# 可能丢掉仍在 -wal 里的已提交事务；本脚本短暂停止 app 后完整打包三个卷。
# 用法：./scripts/backup-docker-volumes.sh [/absolute/backup/directory]

backup_dir="${1:-$PWD/backups}"
compose=(docker compose)
stamp="$(date -u +%Y%m%dT%H%M%SZ)"

mkdir -p "$backup_dir"
backup_dir="$(cd "$backup_dir" && pwd)"

container_id="$("${compose[@]}" ps -q app)"
if [[ -z "$container_id" ]]; then
  echo "app 容器不存在或未创建；请在 docker-compose.yml 所在目录执行。" >&2
  exit 1
fi

volume_for() {
  local destination="$1"
  docker inspect --format '{{range .Mounts}}{{if eq .Destination "'"$destination"'"}}{{.Name}}{{end}}{{end}}' "$container_id"
}

db_volume="$(volume_for /app/data)"
public_volume="$(volume_for /app/uploads)"
private_volume="$(volume_for /app/uploads-private)"
if [[ -z "$db_volume" || -z "$public_volume" || -z "$private_volume" ]]; then
  echo "未找到 app 的三个命名卷，拒绝执行不完整备份。" >&2
  exit 1
fi

was_running="$("${compose[@]}" ps --status running -q app)"
restart_app() {
  if [[ -n "$was_running" ]]; then
    "${compose[@]}" up -d app >/dev/null
  fi
}
trap restart_app EXIT

if [[ -n "$was_running" ]]; then
  "${compose[@]}" stop app
fi

archive_volume() {
  local label="$1"
  local volume="$2"
  local archive="lxday-${label}-${stamp}.tar.gz"
  docker run --rm \
    -v "${volume}:/source:ro" \
    -v "${backup_dir}:/backup" \
    alpine:3.20 \
    tar -C /source -czf "/backup/${archive}" .
  printf '%s\n' "$archive"
}

db_archive="$(archive_volume data "$db_volume")"
public_archive="$(archive_volume uploads "$public_volume")"
private_archive="$(archive_volume uploads-private "$private_volume")"

manifest="lxday-backup-${stamp}.sha256"
(
  cd "$backup_dir"
  sha256sum "$db_archive" "$public_archive" "$private_archive" > "$manifest"
)

echo "备份完成：$backup_dir"
echo "校验清单：$backup_dir/$manifest"
echo "请定期运行 ./scripts/verify-backup-restore.sh $backup_dir/$manifest 做隔离恢复演练。"
