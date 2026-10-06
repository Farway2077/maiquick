# 舞萌快捷更新（MaiQuick）

安卓手机独立运行。首次粘贴玩家二维码字符串绑定，之后点「更新成绩」；也可以添加桌面快捷按钮，点一下就开始更新。无需电脑、外部后台、Root、VPN、安装证书或额外 Termux App。

## 手机安装和使用

1. 把 `artifacts/maiquick-0.1.0-arm64.apk` 传到手机，打开安装。适用于 **Android 8 及以上、64 位 ARM 安卓系统**。
2. 首次打开会解压内置环境，只需等待一次。建议预留 **300 MB** 可用空间。
3. 点击「绑定并开始使用」，粘贴水鱼 **128 位成绩导入 Token**，以及舞萌公众号玩家登录二维码识别后的 **SGWCMAID 开头字符串**。二维码需要在有效期内完成首次绑定。
4. 绑定成功后点「更新成绩」。以后直接点更新，无需再复制二维码。
5. 想要从桌面一键更新，点击「添加桌面『更新成绩』按钮」，按手机桌面的提示添加。

不需要水鱼用户名和密码。Token 没有预置到 APK，首次填写后在手机加密保存。应用启动时不会读取剪贴板，只有点击粘贴按钮时才读取。

## 能更新什么

此工具使用玩家长期加密 UID 获取常规谱面的达成率和 DX 分，再直传水鱼。取分方式参考水鱼使用指南介绍的 UID / 友人对战方式。

- 能更新 BASIC、ADVANCED、EXPERT、MASTER 和 Re:MASTER 的达成率及 DX 分。
- **无法获取新获得的 FC/AP/FS 标记**；已有水鱼记录里的 FC/FS 会保留。
- 保留水鱼里已有的更高达成率、DX 分；不会删除旧成绩。
- 宴谱、尚未出现在水鱼曲目表里的谱面会跳过并显示数量。
- 仅在水鱼返回完整成功回执后显示更新完成；账号变更、读旧成绩失败、异常分数、部分接受、网络错误会停止或提示，避免错误覆盖。

UID 可长期复用，原始二维码不会保存。更换舞萌账号或清除应用数据后才需要重新绑定；机台接口以后发生变化可能需要更新应用。

## 常见问题

- **首次绑定失败**：生成新二维码再复制一次；检查 Token 是否完整、是否在水鱼网站同意用户协议。二维码不是水鱼 Token。
- **取分失败**：通常是网络、维护或上游访问限制。可先用「绑定 / 修改 Token → 检查本机运行环境」确认本地环境和水鱼连接，再稍后重试；通常不必重新绑定。
- **Token 更新了**：设置里选「只修改水鱼 Token」，同一水鱼账号的 UID 绑定会保留。
- **更新后缺少新 FC/FS**：这是 UID 取分方式的限制，须通过公众号完整成绩导入方式补充。
- **桌面不支持快捷按钮**：直接打开应用点击「更新成绩」。

Token 和 UID 使用 Android Keystore 的 AES-GCM 密钥加密保存在应用私有目录，关闭 Android 备份。凭据通过私有子进程标准输入传递，不写入命令行或运行日志。水鱼 Token 只发送至 `https://www.diving-fish.com`；机台读取使用 `maimai-ffi`，应用不把账号交给第三方 Bot。

## 已完成的验证

- Python 测试覆盖曲目映射、分数精度、FC/FS 和高分保留、长期 UID 复用、错误账号阻断、失败不上传、拒绝重定向、部分回执等。
- Android 14 x86_64 模拟器实测内置 PRoot、Linux Python、机台读取库加载、水鱼公开 API 和加密持久化。
- 人工查看主界面、首次绑定表单并检查崩溃日志。
- 在模拟器添加桌面快捷按钮，确认点击后自动更新，并用无效测试 Token 验证失败提示和按钮恢复。
- **没有用户的有效玩家二维码，因此尚未验证用户实际账号的首次绑定及真实成绩上传。ARM64 包尚未在真实手机上运行验证。**

## 原理与构建

Java 原生界面负责加密保存凭据与本地执行；`mobile/engine.py` 使用 `maimai-ffi==0.7.1` 获取加密 UID、成绩，使用水鱼公开曲目接口与 Import-Token 接口上传。APK 内嵌官方 Python 3.12 Docker 镜像中的 Linux 用户环境和 Termux PRoot，运行时不启动服务器、不下载可执行代码。

为支持 Android 私有目录中的本地 Linux 用户环境执行，应用 target SDK 为 28；这是侧载安装工具，不是 Google Play 上架包。APK 为 ARM64，x86_64 调试包用于模拟器验证。

```powershell
python -m pip install httpx
python scripts/build_runtime.py --arch all
# 自动同步 engine.py 资产
Copy-Item mobile/engine.py app/src/main/assets/engine.py -Force
.\gradlew.bat -PruntimeAbi=arm64-v8a assembleRelease
```

生成可签名 release 包时需本机 `signing.properties`，里面配置 `storeFile`、`storePassword`、`keyAlias`、`keyPassword`。此文件和签名私钥不会进入源码包。

测试依赖：`python -m pip install -r requirements-dev.txt`。测试命令：`python -m pytest tests -q -p no:cacheprovider`；安卓检查构建 `assembleDebug assembleDebugAndroidTest`，安装后运行 `adb shell am instrument -w fun.maiquick.test/fun.maiquick.RuntimeChecks`。

`artifacts/maiquick-0.1.0-source.zip` 提供应用源码、构建脚本和本地运行库的对应源码；不含账号凭据、签名私钥或机器配置。`artifacts/SHA256SUMS.txt` 提供安装包与源码包校验值。详细验证记录见 `artifacts/QA.md`。

来源：

- [水鱼项目与使用指南](https://github.com/Diving-Fish/maimaidx-prober)
- [UID 导入方式说明](https://maimai.diving-fish.com/manual/docs/update-records/thirdparty/)
- [水鱼成绩接口](https://maimai.diving-fish.com/manual/docs/developer/zh-api-document/)
- [maimai.py ArcadeProvider](https://maimai.turou.fun/providers/arcade)
- [maimai-ffi 0.7.1](https://pypi.org/project/maimai-ffi/0.7.1/)
- [Termux PRoot](https://github.com/termux/proot)

第三方组件与版权见 `THIRD_PARTY_NOTICES.md` 及 APK 运行环境内的许可文件。
