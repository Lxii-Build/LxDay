# Android 诊断日志

## 存储位置

日志仅保存在 Android app 私有目录：

```text
/data/user/0/com.linxi.diary/files/
├── logs/runtime-YYYY-MM-DD.log
└── crash/YYYYMMDD_HHmmss.txt
```

该目录不能被第三方文件管理器任意读取。用户通过设置页“导出诊断日志”主动生成并分享 ZIP；不会自动上传任何内容。

## 运行日志

实现：`android/app/src/main/java/com/linxi/diary/util/Logs.kt`

级别：`DEBUG`、`INFO`、`WARN`、`ERROR`。

格式：

```text
2026-08-09T10:30:12.123+08:00 INFO  Linxi/Nav [main] MainTabs mounted
```

规则：

- 每日一个文件。
- 单文件最大 4MB，超限后轮转，最多保留 3 个同日轮转文件，不清空故障前历史。
- 保留最近 7 天；崩溃目录最多保留最近 20 个文件。
- 写盘失败不影响业务线程；同一事件仍写入 logcat。

## 隐私与脱敏

实现：`util/LogSanitizer.kt`；单元测试：`src/test/.../LogSanitizerTest.kt`。

日志和导出包不得暴露 token、邀请码、SSID、认证 URL 查询参数、日记正文或其他私密正文。导出前会对已写入的键值模式再次脱敏。

## 崩溃日志

实现：`util/CrashHandler.kt`。崩溃堆栈写入私有 `files/crash/`，并与最近运行日志一并被导出。

注意：native GPU/驱动级崩溃可能在 Java 崩溃处理器之前终止进程；此时运行日志末尾记录的最后一个阶段可用于定位。

## 导出流程

实现：`util/DiagnosticExporter.kt`。

1. 设置页点击“导出诊断日志”。
2. 应用将 `files/logs` 与 `files/crash` 打包进私有缓存 ZIP。
3. 每次最多导出 64 个条目、总输入 32MB、单文件 8MB；超大文件截断并写入标记。
4. 通过 `FileProvider` 和系统 Sharesheet 提供只读 `content://` URI；同一时间只允许一个导出任务。
5. 临时 ZIP 在下次导出时清理超过 24 小时的文件。
