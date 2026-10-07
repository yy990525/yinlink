# 吟链

Android 手机投屏到 Windows 的局域网应用。当前版本：**0.8.0 测试版**。

## 功能

- H.264 硬件编码与实时推流，支持声音同步。
- 保持手机画面比例，使用模糊背景补齐全屏空白。
- FPS 档位与 1–120 自定义上限，投屏中调整清晰度。
- 经手机授权的键鼠控制、主动剪贴板互传。
- 截图图集、大图预览、本地保存与 MP4 录制。
- 随机/固定配对码、二维码、局域网发现及连接历史。
- Windows 托盘、开机启动、置顶与迷你窗口。

## 目录

| 路径 | 内容 |
| --- | --- |
| `android/` | 原生 Android 手机端与数据模型测试 |
| `desktop/` | Windows 启动器、接收服务、播放器界面与测试 |
| `scripts/` | Android 与 Windows 构建脚本 |

## 运行与构建

需要 Java 17、Android SDK 35。Windows 打包需要 .NET Framework C# 编译器和带分发许可证的 Node.js。

```powershell
# 电脑端开发模式
cd desktop
node server.cjs
# 服务和音频缓冲测试
node --test test/*.test.cjs
```

```powershell
# 在项目根目录执行
./scripts/build-android.ps1 -SdkRoot "Android SDK 目录" -JdkRoot "JDK 17 目录"
./scripts/build-windows.ps1 -NodeExecutable "Node.js 路径"
```

Android 构建脚本创建本地测试签名；密钥不随源码提交。需要覆盖安装时，请保留自己的签名密钥和构建目录。

## 使用与验证

安装、连接和功能操作见 [使用说明](使用说明.md)，测试证据与实测范围见 [验证记录](验证记录.md)。

Android 音频采集需要 Android 10+；受保护媒体可能拒绝采集。当前普通投屏模式不支持真实息屏后继续投屏，息屏入口显示不可用。实际手机帧率、听感、发热和端到端延迟仍需真机验证。

当前版本用于可信局域网，不提供公网中继或传输加密。第三方 QR 与 ZXing 组件的许可证保留于各自目录。
