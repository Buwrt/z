# 钉钉自动打卡（DingClock）

一个只在自己手机上使用的 Android 小工具：借助 **Root（优先）** 或 **Shizuku** 的 shell 权限，在设定的时间自动打开钉钉，找到「上班打卡 / 下班打卡」按钮并点击，然后把结果通知给你。

> 不是钉钉官方功能，不修改钉钉本身，也不伪造定位。它只是模拟你在屏幕上点一下，是否需要打卡、能不能打卡仍由钉钉和公司考勤规则决定。

## 功能

- 上班、下班两个时间点独立开关，可自由修改时间
- 周一到周日任选重复日期
- 支持提前/延后若干秒、随机延迟若干秒，避免每天卡点太机械
- Root 优先，没有 Root 时自动回退到 Shizuku；也可以强制只用其中一种
- 精确闹钟 + 开机恢复 + WorkManager 每 30 分钟兜底补跑
- 打卡按钮关键词、钉钉包名、备用点击坐标都可配置，适配不同钉钉版本
- 前台服务执行，通知栏实时显示进度，结束后给出成功/失败通知
- 内置日志，出问题能直接看到每一步做了什么
- 附送一个模拟钉钉 App（`mock`），可以在不产生真实考勤记录的情况下测试流程

## 工作原理

```text
AlarmManager（精确闹钟）
        │
        ▼
AlarmReceiver ──► ClockService（前台服务）
        │                    │
        │                    ├─ 唤醒屏幕
        │                    ├─ am start 启动钉钉
        │                    ├─ uiautomator dump 读取界面 XML
        │                    ├─ 找到「工作台 → 考勤打卡 → 上班/下班打卡」
        │                    ├─ input tap 点击按钮
        │                    └─ 校验「打卡成功 / 已打卡」等文字
        ▼
WorkManager 兜底（闹钟被系统拦截时）
```

所有 `am`、`input`、`uiautomator`、`dumpsys` 命令都通过以下两种方式之一执行：

| 方式 | 实现 | 说明 |
| --- | --- | --- |
| Root | `su -c "<command>"` | 需要 Magisk / KernelSU 等已授权本应用 |
| Shizuku | Shizuku UserService（`app_process`） | 需要安装 Shizuku 并授权本应用 |

> Shizuku 13 已把 `newProcess` 改为私有 API，本项目改用官方推荐的 **UserService + AIDL** 方案，在 shell/root 身份进程中执行命令。

## 编译

要求：JDK 17、Android SDK 34、Gradle 8.9（仓库自带 wrapper）。

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew :mock:assembleDebug
```

产物：

- `app/build/outputs/apk/debug/app-debug.apk`
- `mock/build/outputs/apk/debug/mock-debug.apk`

也可以直接用 Android Studio 打开根目录。仓库配置了 GitHub Actions：推送到 `main` 会自动跑单元测试并构建 APK；推送 `v*` 标签会额外创建 Release 并附上 APK。

## 安装与授权

1. 安装 `app-debug.apk`。
2. 打开应用，先点 **「测试 Root 权限」**：
   - 弹出 Magisk 授权框时选择「允许」；
   - 如果显示 `uid=0(root)`，说明 Root 可用，后续会自动优先走 Root。
3. 没有 Root 的话，安装并启动 [Shizuku](https://shizuku.rikka.app/)，点 **「申请 Shizuku 权限」** 并允许。
4. 依次点：
   - **授予精确闹钟权限**（Android 12+ 必须，否则只能退化为普通闹钟）
   - **忽略电池优化**（强烈建议，否则国产 ROM 可能杀后台）
   - **授予通知权限**（Android 13+，用于查看打卡结果）
5. 设置上班/下班时间、重复日期，点 **「保存并启用」**。

### 关于锁屏

自动打卡需要手机处于**已解锁**状态。如果设置了密码/指纹，定时触发时脚本只能唤醒屏幕并尝试 `wm dismiss-keyguard`，有安全锁的机器无法真正解锁。建议：

- 打卡时间段内让手机保持解锁（例如放在工位上、设置较长的自动锁屏时间）；或
- 使用「智能解锁 / 可信设备」；或
- 接受在锁屏时打卡失败，并用通知提醒自己手动补卡。

## 使用建议

### 第一次使用

1. 先把「上班按钮关键词」「下班按钮关键词」保持默认（`上班打卡,打卡` / `下班打卡,打卡`）。
2. 用 **测试上班 / 测试下班** 按钮跑一次，观察日志。
3. 如果日志里出现「当前页面没有找到匹配…」，看日志中的「可见文本」，把钉钉实际按钮文字加到关键词里。
4. 实在找不到按钮时，可以填「备用坐标 x,y」：先手动打开钉钉到打卡页，用开发者选项的「指针位置」读出按钮坐标，填进去作为最后手段。

### 关键词与排除词

- 关键词按逗号分隔，命中任意一个即可；越靠前优先级越高（完全相等会加分）。
- 默认排除词包含「考勤打卡、打卡记录、打卡成功、已打卡、补卡」等，避免点到入口或结果提示。
- 钉钉改版后按钮文字可能变化，例如变成「极速打卡」，把它加进关键词即可。

### 补跑机制

- 闹钟触发后先把下一次闹钟排好，避免一次失败导致计划中断。
- 开机、改时间、改时区后会自动重排闹钟，并检查是否落在补跑窗口内。
- WorkManager 每 30 分钟检查一次，只在「打卡点已过、仍在 2 小时窗口内、今天还没成功」时补跑。
- 刚失败过的任务 10 分钟内不会反复重试，避免每半小时骚扰。

## 常见问题

**Q：日志显示「没有可用的 Root 或 Shizuku 权限」？**

先点「测试 Root 权限」。Root 不可用时检查 Magisk 里是否已授权；Shizuku 用户确认 Shizuku 服务正在运行且已授权本应用。

**Q：日志能读到界面，但点不到按钮？**

看日志的「可见文本」，确认钉钉当前是否停在打卡页。必要时修改关键词，或填写备用坐标。部分 ROM 的 `uiautomator dump` 需要屏幕处于亮屏状态。

**Q：打卡成功但通知没出现？**

Android 13+ 需要通知权限；部分 ROM 还需要在系统设置里允许「通知」和「后台弹出界面」。

**Q：会不会重复打卡？**

同一天同一任务成功（或已执行但结果未确认）后会跳过后续触发；测试模式不写入「今天已完成」记录。

**Q：升级钉钉后失效？**

钉钉界面结构变化可能导致关键词或导航路径失效。更新关键词/坐标，或在 Issue 里附上日志中的「可见文本」。

## 目录结构

```text
app/   主应用（Kotlin，minSdk 26，targetSdk 34）
  src/main/aidl/.../IUserService.aidl   Shizuku UserService 接口
  src/main/java/.../engine/             Root/Shizuku shell、UI 解析、自动化流程
  src/main/java/.../schedule/           闹钟、补跑、开机恢复
  src/main/java/.../service/            前台服务
  src/main/java/.../ui/                 设置界面
  src/test/                             NextRunPlanner 单元测试
mock/  模拟钉钉，用于端到端测试（包名 com.buwrt.mockding）
.github/workflows/android.yml           CI：测试 + 构建 + Release
```

## 测试（不碰真实钉钉）

安装 mock APK 后，用 root/adb 触发一次测试：

```bash
adb shell am start -n com.buwrt.dingclock/.debug.DebugRunActivity \
  --es task CLOCK_IN \
  --es package com.buwrt.mockding \
  --ez test true
```

或者直接在应用里把「钉钉包名」临时改成 `com.buwrt.mockding`，点测试按钮。

## 免责声明

- 本项目仅供个人学习与自动化技术研究，**请遵守公司考勤制度和当地法律法规**。
- 使用本工具产生的考勤结果、劳动纠纷、账号风险由使用者自行承担。
- 作者不对钉钉版本更新、手机 ROM 差异、Root/Shizuku 环境导致的失败负责。
- 请勿将本工具用于代打卡、伪造考勤等违规用途。

## License

MIT，见 [LICENSE](LICENSE)。
