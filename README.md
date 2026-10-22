# CHARGE GUARD · 充电守护

无需部署 Home Assistant（HA），只需一部 Android 手机，就能控制米家智能插座的开关，并根据手机电量自动管理充电供电，无需额外搭建 HA 或常驻电脑服务。

目前支持米家智能插座 3（**ZNCZ301KK / `cuco.plug.v3`**）。

## 功能

- **电量循环保护**：默认电量降至 30% 时开启供电，充至 80% 时关闭供电，循环运行。
- **三种控制路线**：自动（局域网优先、云端备选）、仅局域网、仅云端。
- **手动开关**：随时查看插座状态，在保护停止时手动开启或关闭。
- **小米账号接入**：通过小米官方页面使用手机号 / 验证码登录，获取设备列表并选择目标插座。
- **后台保护**：支持后台运行、状态通知和停止保护。

## 开始使用

1. 在米家 App 完成插座配网和账号绑定，把手机充电器接到目标插座。
2. 从 [GitHub Releases](https://github.com/balalalittlefairy/CHARGE_GUARD/releases/latest) 下载并安装 APK。
3. 在「设置 → 小米账号」登录与插座绑定的小米账号，获取设备并选择目标插座。
4. 刷新插座状态，手动测试开启和关闭，确认选择的是正确插座。
5. 在「保护」页调整电量阈值和控制路线，保存后到首页点击「开始保护」。允许通知，并按手机系统要求开放后台运行。

## 保护规则

可手动调整阈值范围。电量降至设定的开启阈值时恢复供电，充至设定的关闭阈值时停止供电，循环进行；可随时停止保护。

## 从源码构建

使用 JDK 17 和 Android SDK 35。通过 Android Studio 打开项目并配置 SDK，或在本机 `local.properties` 中设置 `sdk.dir`。

```powershell
.\gradlew.bat assembleDebug
```

macOS / Linux 使用 `./gradlew assembleDebug`。首次构建需要联网下载 Gradle 和依赖；APK 输出至 `app/build/outputs/apk/debug/app-debug.apk`。

GitHub Release 当前提供调试签名 APK。不同签名的包无法直接覆盖安装；卸载会清除本地配置。

## 开源协议

代码使用 [MIT 协议](LICENSE)。
