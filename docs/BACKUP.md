# 林曦日记 · 备份与恢复演练

相册原图和 SQLite 关系数据同等重要。必须同时备份三个 Docker 命名卷：`/app/data`、`/app/uploads`、`/app/uploads-private`；只备份 `lxday.db` 会遗漏 WAL 中已提交事务，漏掉私密媒体卷则会留下无法打开的照片记录。

## 一致性备份

在仓库根目录（`docker-compose.yml` 所在位置）执行：

```bash
mkdir -p /srv/lxday-backups
./scripts/backup-docker-volumes.sh /srv/lxday-backups
```

脚本会短暂停止 `app`、完整打包三个卷、生成 SHA-256 清单，然后自动恢复容器。把备份目录同步到另一台受控机器或加密对象存储；不要把 `.env`、密钥库或备份包上传到公开仓库。

建议每天一次，并保留至少 14 个可恢复时间点。备份保留/异地副本由服务器管理员的计划任务负责；脚本不会擅自创建系统 cron。

## 恢复演练

每次备份完成后、至少每月一次执行：

```bash
./scripts/verify-backup-restore.sh /srv/lxday-backups/lxday-backup-YYYYMMDDTHHMMSSZ.sha256
```

它先校验 SHA-256，再将备份解压到**临时命名卷**并启动隔离容器访问 `/readyz`。生产卷、运行中的容器和编排文件都不会被修改；通过后临时容器与卷会自动删除。

## 真正事故恢复

先停止线上容器并保留故障卷，再按备份包恢复到新卷、用隔离演练确认 `/readyz` 通过，最后才切换生产卷。此步骤会影响真实数据，必须在维护窗口执行并由管理员确认；不要直接在没有验证的情况下覆盖现有卷。
