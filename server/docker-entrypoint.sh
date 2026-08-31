#!/bin/sh
set -eu

# 与仓库根一体化镜像保持相同的升级语义：旧 root 运行版本创建的卷在首次
# 切换到 uid 10001 时自动修复；服务进程自身始终以非 root 身份运行。
for dir in /app/data /app/uploads /app/uploads-private; do
    mkdir -p "$dir"
    marker="$dir/.lxday-permissions-v1"
    if [ ! -e "$marker" ]; then
        chown -R app:app "$dir"
        touch "$marker"
        chown app:app "$marker"
    fi
done

exec su-exec app "$@"
