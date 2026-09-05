# Android 签名与发行

Android 更新能否覆盖安装，取决于包名、`versionCode` 和签名证书都保持兼容。正式签名材料必须保存在仓库之外的受控密码库/离线备份中，仓库只保留固定 debug 密钥（用于本地和 CI 调试包一致）。

## 正式签名

正式构建使用 PKCS#12 密钥库。以下四个 GitHub Actions Secret 只在仓库设置中配置，值不要写入文档、日志或提交：

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 正式 `.p12` 文件的单行 Base64 |
| `ANDROID_KEYSTORE_PASSWORD` | 密钥库口令 |
| `ANDROID_KEY_ALIAS` | 密钥别名 |
| `ANDROID_KEY_PASSWORD` | 私钥口令 |

密钥库文件、Base64 文本和口令应分别备份。任何人拿到密钥库或口令都可以发布同包名更新；不要把它们放在工作区、Issue、聊天或公开 Artifact 中。

`android/app/build.gradle.kts` 通过 `KEYSTORE_FILE`、`KEYSTORE_PASSWORD`、`KEY_ALIAS` 和 `KEY_PASSWORD` 接收 CI 参数。缺少正式密钥时，本地构建可以回退到仓库内 debug 签名，但发行工作流必须在解码和指纹检查失败时停止。

## 工作流检查

`release.yml` 会：

1. 拒绝已存在的 tag；
2. 从 `vMAJOR.MINOR.PATCH` 推导 `versionName` 和 `versionCode`；
3. 运行服务端测试、Android 单元测试和 Release lint；
4. 使用固定正式签名构建 APK，并检查 SHA-256 指纹；
5. 构建带版本 tag 的服务端镜像；
6. 创建 GitHub Release，并保留 APK、`mapping.txt` 和镜像离线归档。

`versionCode` 规则是 `major * 10000 + minor * 100 + patch`，minor 和 patch 必须不超过 99。发行 tag 一旦占用不能覆盖，修复应创建新的版本号。

## 本地检查签名

使用 Android SDK 的 `apksigner` 检查构建包，不要把证书或口令输出到日志：

```bash
apksigner verify --verbose app/build/outputs/apk/release/app-release.apk
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

正式包的指纹应与受控密码库中的记录一致；不要把完整指纹以外的私密密钥材料提交到仓库。

## 签名轮换

如果正式密钥疑似泄露，先暂停发行和撤销暴露的 CI Secret，再评估 Android Play App Signing/现有安装基线。直接换证书会让已安装用户无法覆盖升级，必须先制定迁移方案并使用新的包名或平台支持的密钥升级机制。

## 提交前

- [ ] `.p12`、`.jks`、密码文件和 Base64 文件没有进入 Git。
- [ ] `android/local.properties` 没有进入 Git。
- [ ] Release workflow 使用固定签名，而非 runner 临时 debug keystore。
- [ ] APK 的 versionCode 高于上一版。
- [ ] APK、镜像和 `mapping.txt` 与同一个 tag 对应。
