#!/usr/bin/env bash
set -Eeuo pipefail

# 只恢复到临时命名卷并启动隔离容器验证 /readyz；绝不触碰生产卷。
# 用法：./scripts/verify-backup-restore.sh /absolute/path/lxday-backup-*.sha256

manifest="${1:?请传入 backup-docker-volumes.sh 生成的 .sha256 清单}"
manifest="$(cd "$(dirname "$manifest")" && pwd)/$(basename "$manifest")"
backup_dir="$(dirname "$manifest")"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"

if [[ ! -f "$manifest" ]]; then
  echo "找不到校验清单：$manifest" >&2
  exit 1
fi
(
  cd "$backup_dir"
  sha256sum --check "$(basename "$manifest")"
)

container_id="$(docker compose ps -q app)"
if [[ -z "$container_id" ]]; then
  echo "app 容器不存在；请在 docker-compose.yml 所在目录执行，以取得待验证镜像。" >&2
  exit 1
fi
image="$(docker inspect --format '{{.Config.Image}}' "$container_id")"
if [[ -z "$image" ]]; then
  echo "无法读取当前 app 镜像。" >&2
  exit 1
fi

db_volume="lxday-restore-${stamp}-data"
public_volume="lxday-restore-${stamp}-uploads"
private_volume="lxday-restore-${stamp}-uploads-private"
check_container="lxday-restore-${stamp}"
cleanup() {
  docker rm -f "$check_container" >/dev/null 2>&1 || true
  docker volume rm "$db_volume" "$public_volume" "$private_volume" >/dev/null 2>&1 || true
}
trap cleanup EXIT

for volume in "$db_volume" "$public_volume" "$private_volume"; do docker volume create "$volume" >/dev/null; done

archive_for() {
  local label="$1"
  local archive
  archive="$(awk -v prefix="lxday-${label}-" '$2 ~ ("^" prefix) { print $2; exit }' "$manifest")"
  printf '%s/%s\n' "$backup_dir" "$archive"
}
restore_archive() {
  local archive="$1"
  local volume="$2"
  [[ -n "$archive" && -f "$archive" ]] || { echo "缺少备份包：$archive" >&2; exit 1; }
  docker run --rm -v "${volume}:/target" -v "${backup_dir}:/backup:ro" alpine:3.20 \
    tar -C /target -xzf "/backup/$(basename "$archive")"
}

restore_archive "$(archive_for data)" "$db_volume"
restore_archive "$(archive_for uploads)" "$public_volume"
restore_archive "$(archive_for uploads-private)" "$private_volume"

docker run -d --name "$check_container" \
  -e JWT_SECRET=restore-verification-not-a-real-secret \
  -v "${db_volume}:/app/data" \
  -v "${public_volume}:/app/uploads" \
  -v "${private_volume}:/app/uploads-private" \
  "$image" >/dev/null

for _ in $(seq 1 30); do
  if docker exec "$check_container" wget -q -O- http://127.0.0.1:7740/readyz | grep -qx ready; then
    echo "恢复演练通过：镜像 $image 可读取恢复后的数据库与媒体卷。"
    exit 0
  fi
  sleep 2
done

docker logs "$check_container" >&2 || true
echo "恢复演练失败。" >&2
exit 1
