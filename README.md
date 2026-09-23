# 布布床灯控制 (Smart Bed Light Control)

<p align="center">
  <b>基于 Android BLE 的智能床底灯控制与后台保活伴侣</b>
</p>

---

## 📖 项目简介

**布布床灯控制** 是一款专为智能床底灯设计的 Android 控制客户端。通过低功耗蓝牙 (BLE) 与目标床灯控制器建立连接，支持单次点亮/关闭床底灯，并具备独特的**长效常亮保活机制**（通过前台服务与精准定时心跳，突破硬件默认的 300 秒自动关灯限制，实现持续长亮），同时提供实时的连接状态与开灯倒计时显示。

- **应用包名**：`me.marukon.smartbed`
- **目标硬件 MAC**：`CC:D4:C4:85:78:AF`
- **开源仓库**：[https://github.com/Marukon/smartbedlightcontrol](https://github.com/Marukon/smartbedlightcontrol)

---

## ✨ 核心功能

1. **蓝牙低功耗 (BLE) 快速连接**
   - 自动扫描与定向直连目标设备。
   - 具备连接防抖、状态广播通知与异常断开自动恢复机制。

2. **床灯模式控制**
   - **单次控制**：点击一键打开或关闭床底灯（遵循硬件原生自动熄灭逻辑）。
   - **持续常亮模式**：突破硬件默认 300 秒自动休眠，以 301 秒周期调度预热与指令下发，并在发送后 5 秒智能断开释放连接，兼顾设备常亮与省电。

3. **后台持久保活 (Foreground Service)**
   - 持续开灯状态下启动常驻前台通知服务（Foreground Service Connected Device 类型）。
   - 结合 CPU 唤醒锁 (`WakeLock`) 与电池白名单优化，防止系统息屏后进程被系统回收中断。

4. **实时交互与状态同步**
   - 实时显示当前蓝牙连接状态（未连接 / 连接中 / 已连接 / 断开中）。
   - 动态计算并呈现当前轮次的开灯剩余倒计时（`MM:SS`）。
   - 按钮智能互斥与显示状态切换，避免冲突指令下发。

---

## 🛠️ 技术栈

| 模块 | 技术选型 |
| :--- | :--- |
| **开发语言** | Kotlin 2.2.10 |
| **系统版本** | Min SDK 26 (Android 8.0) / Target SDK 34 (Android 14) |
| **构建工具** | Gradle 9.4.1 + Android Gradle Plugin (AGP) 9.2.1 |
| **JDK 版本** | Java 17 / Java 21 |
| **UI 架构** | Android Jetpack (AppCompat, Material Components) |
| **核心机制** | BluetoothGatt, BroadcastReceiver, Foreground Service, WakeLock |
| **自动化集成** | GitHub Actions (CI/CD 自动编译、签名、打包、Telegram 部署) |

---

## 🚀 本地开发与构建

### 1. 环境要求
- [Android Studio](https://developer.android.com/studio) (建议 2024.1+ 或更高版本)
- JDK 17 或 JDK 21
- Android SDK Platform 34

### 2. 编译步骤
```bash
# 克隆仓库
git clone https://github.com/Marukon/smartbedlightcontrol.git
cd smartbedlightcontrol

# 编译 Debug 版本
./gradlew :app:assembleDebug

# 编译 Release 版本
./gradlew :app:assembleRelease

# 检查依赖版本更新 (gradle-versions-plugin)
./gradlew dependencyUpdates
```

编译生成的 APK 位于：
- `app/build/outputs/apk/release/`

---

## 🤖 GitHub Actions CI/CD

项目内置了以下自动化工作流：
1. **[Build and Deploy Release APK](.github/workflows/build-release.yml)**：向 `main` 分支推送或手动触发时，自动编译 Release APK，支持密钥自动签名、Artifacts 归档与 Telegram 文件推送。
2. **[Check Dependency Updates](.github/workflows/dependency-check.yml)**：支持在 GitHub Actions 页面纯手动触发（`workflow_dispatch`），按需扫描项目中所有依赖（AGP、Gradle Wrapper、AndroidX、三方库）的新版本情况，并在 GitHub Step Summary 中直观呈现报告，同时支持报告文件上传与 Telegram 提醒推送。

### 可选 Secrets 配置
若需要自动签名及推送至 Telegram 频道/群组，可在 GitHub 仓库的 `Settings -> Secrets and variables -> Actions` 中配置以下变量：

| Secret 名称 | 说明 |
| :--- | :--- |
| `SIGNING_KEY` | Android 签名 keystore 文件的 Base64 编码字符串 |
| `ALIAS` | 签名密钥别名 (Key Alias) |
| `KEY_STORE_PASSWORD` | Keystore 访问密码 |
| `KEY_PASSWORD` | Key 访问密码 |
| `TELEGRAM_BOT_TOKEN` | *(可选)* Telegram 机器人 Token |
| `TELEGRAM_CHAT_ID` | *(可选)* Telegram 接收 APK 的目标 Chat ID |

*注：若未配置签名密钥，CI 仍会成功编译出未签名的标准 Release APK 并归档上传。*

---

## 📄 开源许可

本项目基于开源许可协议分发，仅供学习与家庭智能设备控制使用。
