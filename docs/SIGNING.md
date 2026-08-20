# 安卓签名与发布指南

> 本文解决一个具体问题：**让每次构建出的 APK 签名完全一致**，从而可以覆盖安装升级。
> 半年后你大概会忘记密钥放在哪、Secret 叫什么、指纹应该是多少——所以都写在这里。

## 1. 为什么以前每次签名都不一样

`android/app/build.gradle.kts` 里 release 构建直接复用了 debug 签名：

```kotlin
signingConfig = signingConfigs.getByName("debug")   // ← 旧代码
```

而 CI runner 每次都是一台全新机器，`~/.android/debug.keystore` **由 Android 工具链在首次构建时自动随机生成**。
于是每次构建都是一把新钥匙 → 签名指纹每次都不同 → 新包装不上（Android 拒绝用不同签名覆盖同包名应用）。

现在改成：**正式构建用固定的 PKCS#12 密钥库**，密钥库经 GitHub Secret 注入；
**debug 构建用仓库内固定的 debug 密钥库**，让本地与 CI 的调试包也能互相覆盖。

## 2. 密钥物料在哪

生成于 2026-08-20，存放在**仓库之外**：

```
C:\Users\Administrator\Downloads\lxday-signing\
├── lxday-release.p12              ← 正式签名密钥库（最重要，务必离线备份）
├── lxday-release.p12.base64       ← 上面文件的 base64，用于粘贴到 GitHub Secret
├── release-store-password.txt     ← 密钥库口令（32 位随机）
├── release.cert.pem               ← 正式证书（留档/校验指纹）
├── lxday-debug.p12                ← 固定 debug 密钥库（已复制进仓库 android/keystore/）
├── debug.cert.pem
└── README-如何配置.md
```

正式密钥库参数：RSA 4096 / SHA-256 / 有效期至 **2056-08-12** / 别名 **`Lx-Day`** /
Subject `C=CN, O=Lxii, OU=LxDay, CN=LxDay Release`。

> **`lxday-release.p12` 丢失的后果**：以后发布的所有 APK 都无法覆盖安装已装版本，
> 用户只能卸载重装（**本地数据全部丢失**）。请另存一份到 U 盘或网盘。

### 为什么是 PKCS#12 而不是 JKS

本机没有 JDK，也就没有 `keytool`，密钥是用 OpenSSL 3.5.4 生成的，产出格式为 PKCS#12。
这不是妥协：PKCS#12 是当前的标准格式，AGP 与 JDK 21 原生支持（JKS 反而是已过时的私有格式）。
`storeType = "PKCS12"` 已在 `build.gradle.kts` 中显式声明。

PKCS#12 的惯例是 **key 口令与 store 口令相同**，故两个 Secret 填同一个值。

## 3. 需要配置的 4 个 GitHub Secret

仓库 → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**

| Secret 名 | 值 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | 用记事本打开 `lxday-release.p12.base64`，**Ctrl+A 全选复制**整段粘贴。**不要手动换行** |
| `ANDROID_KEYSTORE_PASSWORD` | `release-store-password.txt` 里那 32 个字符（开头 `UHOp`、结尾 `oFfr`，可据此核对） |
| `ANDROID_KEY_ALIAS` | `Lx-Day` |
| `ANDROID_KEY_PASSWORD` | 与 `ANDROID_KEYSTORE_PASSWORD` **完全相同** |

配置错误的两种典型表现，工作流都会明确报错而不是静默出一个错签名的包：
- base64 带了换行 / 被截断 → 「密钥库解码失败」
- 口令填错 → 「口令不匹配」

## 4. 预期指纹（用于验证「签名统一」真的成立）

**正式签名**
```
SHA-256: 59:4A:B3:8F:AA:AE:A3:B6:28:E9:0A:A2:55:95:62:66:A1:0C:40:08:76:14:C2:43:E4:AA:DE:35:32:05:76:4F
SHA-1:   6B:E9:51:7A:F4:57:D3:D7:11:28:5D:B0:57:2F:00:75:BA:A1:92:00
```

**固定 debug 签名**
```
SHA-256: FD:FD:F9:98:CE:CF:86:BD:DB:99:49:34:14:BA:EB:96:64:EC:6C:B0:52:47:F9:65:70:0D:C3:75:5B:36:97:12
```

CI 已把这件事自动化，不需要你手工核对：

- **`构建安卓 APK`** 工作流会把实际指纹打印到 Job Summary，可直接与上面对照。
- **`发布 Release`** 工作流会**断言**指纹等于上面的正式指纹，**不一致就中止发布**。
  这道关口的意义是：万一以后误配了另一把密钥，不会悄悄发出一个用户装不上的版本。

验收方法：连跑两次 Release 构建，两次指纹一致——这才叫签名统一。

## 5. 发布一个新版本

跑 **`发布 Release`** 工作流，填 `version`（形如 `v1.2.3`）即可。工作流会：

1. 校验 tag 未被占用（不覆盖已发布版本）
2. 由 tag 推导版本号：`v1.2.3` → `versionName=1.2.3`、`versionCode=10203`
   （规则 `major*10000 + minor*100 + patch`，保证单调递增且与版本名一一对应）
3. 用固定正式签名构建 APK
4. 断言签名指纹
5. 构建并推送服务端镜像，导出镜像 tar
6. 创建 Release，附件含 APK、`mapping.txt`、镜像 tar

> **历史 bug 提醒**：此前 `release.yml` 完全没有传 `VERSION_CODE`，
> 导致每个发行版的 `versionCode` 都回退成默认值 **1**。
> Android 要求 versionCode 递增才允许升级，所以旧的发行版之间**根本无法互相升级**。
> 现已由 tag 自动推导。

## 6. 关于 `mapping.txt`

Release 构建开启了 R8 混淆（`isMinifyEnabled = true`）。没有 `mapping.txt`，
线上崩溃堆栈全是混淆后的符号，无法还原成可读行号——等于拿不到有效崩溃日志。
现在 Release 工作流会把它作为 Release 附件上传，请与对应版本一起留存。

## 7. 首次切换签名时的一次性影响

你手机上现有的 APK 是用**旧的随机 debug 签名**打的，与新的固定签名不同，
因此**需要先卸载再安装一次**。此后所有版本签名恒定，可以直接覆盖升级，不会再遇到这个问题。

## 8. 仓库内的防误提交规则

`.gitignore` 中：

```gitignore
*.p12
!android/keystore/lxday-debug.p12
*.p12.base64
release-store-password.txt
```

即：**默认忽略所有 `.p12`**，只精确放行仓库内那个固定的 debug 密钥库。
即便以后有人把 `lxday-release.p12` 拷进仓库目录，也会被 git 忽略而不会被提交。
（debug 密钥按安卓社区惯例是公开的，口令固定为 `android`，提交进仓库是标准做法。）
