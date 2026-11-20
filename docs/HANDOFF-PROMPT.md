# 交接提示词

> 直接复制下面整段发给接手的 AI。适合开新会话时作为第一条消息。

---

你是「智汇日程」这个 Android 项目的接手开发者。项目在
`D:\WorkBuddy\outputs\smart-schedule-android`，包名 `com.terry.smartschedule`。

**先读 `docs/DEVELOPER-GUIDE.md`** —— 那是交接主文档，环境、代码地图、数据库迁移流程、
抓取链路、已知限制都在里面。`README.md` 是逐版迭代记录（含踩过的坑），
`dist/USER-GUIDE.md` 是给最终用户看的说明书。

## 这个项目是什么

一个**纯本地**的 Android 日程应用：从微信 / QQ 的通知里自动认出日程并建出来，
到点用闹钟提醒。没有服务端、没有账号体系、没有网络权限（清单里没有 `INTERNET`）。

## 开工前必设的环境

```bash
export JAVA_HOME="D:/WorkBuddy/toolchain/jdk21"      # 必须正斜杠，反斜杠 gradle 会静默失败
export ANDROID_HOME="D:/WorkBuddy/toolchain/android-sdk"
export MSYS_NO_PATHCONV=1                            # 否则 adb pull 路径会被转换
adb=/d/leidian/LDPlayer9/adb.exe                     # 雷电模拟器，不在标准 SDK 路径下
```

`apksigner.bat` 同样只认正斜杠的 `JAVA_HOME`。

## 出包规矩

- 发布到 GitHub Releases **只上传 release 变体**（`./pack.sh release`）；
  debug 只用于本机调试，不发布。
- release 和 debug 签名不同，不能覆盖安装，切换会丢日程数据 —— 而这个应用还没有导出 / 导入。
- 每次出包先升 `app/build.gradle.kts` 里的 `versionCode` / `versionName`，
  `pack.sh` 会自动读出来命名并追加 `dist/HISTORY.md`（那个文件是脚本维护的，别手改）。
- `dist/` 里的历史包全部保留，不要删。

## 硬约定

1. **判断类逻辑必须抽成不依赖 Android 的纯函数**，配 JUnit 单测。
   不要写进 Activity 或 Service 里 —— 这个项目所有"该不该建 / 什么时候响 / 改哪一条"
   都是 `object`，就是为了能在 JVM 上跑。
2. **注释写"为什么"**，写清踩过什么坑。不要写 `// 遍历列表` 这种废话。
3. **不引入新依赖**。现在只有 androidx + material + coroutines，没有 Room、没有 Retrofit、
   没有 DI。这是故意的：构建快、单测跑得快、不出幺蛾子。
4. **加数据库字段走六步**：`CREATE TABLE` 加列（带 DEFAULT）→ `DB_VERSION` +1 →
   `onUpgrade` 加 `if (oldVersion < N)` 分支 → `Event.toValues()` → `Repo.toEvent()` →
   检查有没有 SELECT 白名单。
5. 改完必须 `./gradlew testDebugUnitTest lintDebug`：**354 条全绿、0 error** 才算完事。

## 当前状态（2026-11-20）

- **v2.0** / versionCode 64 / DB v10 / 354 条单测 / lint 0 error
- 已有功能：通知抓取建日程、手动粘贴识别、四档提醒方式、分类、检索、
  系统日历同步、改期 / 取消 / 换地点 / 「时间有变」识别、改期没认准时的人工确认、
  应用内改期待办、前台即时刷新、换地点后已弹提醒原地更新、自动改期撤旧提醒、
  截断版/完整版通知合并、入库前冲突确认（时间区间重叠 / 名称+地点重合）、
  左侧多选批量删除、右侧一键归档 + 「归档日志」页（恢复/删除/多选批量恢复/批量删除/删除全部）、
  设置页「清理重复日程」
- **v1.36 删掉了「按剩余时间改写提醒」那套（`Urgency.kt`）**，这是明确的要求，不要再加回来
- **v1.34 删掉了云端大模型解析**，整个应用没有任何网络能力，也不许重新引入

## 已知缺口（按价值排序，别当 bug 修）

1. **没有导出 / 导入** —— 卸载或换签名重装 = 日程全丢。这是目前最大的缺口。
2. 改期 / 取消 / 换地点只在通知链路生效，手动粘贴按新日程处理（有意的）。
3. 没有周报 / 统计，数据都在库里，做聚合就行。
4. **通知撤回检测做不了**：`onNotificationRemoved` 的 `reason` 分不清"撤回"和"用户读完了"
   （微信读消息也会取消通知）。别再尝试这条路。
5. **跨推送的上下文窗口没做**：单条通知里已经读 MessagingStyle 历史消息；
   把多次推送拼起来需要先扩 Revise 的"上下文旧时间"解析，风险高，暂缓。

## 怎么验证（重要）

不要只信单测。`tools/notify-sim` 是一个伪装成微信的最小工程，
`applicationId` 就是 `com.tencent.mm`，能造出真实结构的通知：

```bash
./gradlew -p tools/notify-sim assembleFakewxDebug
adb install -r tools/notify-sim/app/build/outputs/apk/fakewx/debug/app-fakewx-debug.apk
adb shell am start -n com.tencent.mm/com.terry.notifysim.PostActivity \
  --es title 测试群 --es body "原定明天下午四点的会议将提前至3点"
```

跑完 `adb uninstall com.tencent.mm`，否则装不了真微信。

**前置**：设置里「读取微信/QQ 通知」的总开关要打开，否则一条都不会处理。

看处理结果别只看界面，直接查库最准：

```bash
adb shell "sqlite3 /data/data/com.terry.smartschedule/databases/schedule.db \
  \"select datetime(at/1000,'unixepoch','+8 hours'),outcome,detail from capture_log order by _id desc limit 5;\""
```

## 排查铁律（每条都是真踩过的）

- `dumpsys notification` 里已经撤掉的通知还会留一阵，判断通知在不在要**截图看真实通知栏**。
- `dumpsys alarm` 的 "Alarm Stats" 段是历史统计，grep 到 Receiver 名字不代表有待触发的闹钟。
- `adb shell input text` **传不了中文**；`am start --es body "中文"` 可以。
- debug 包也关掉了 `debuggable`（Terry 的要求），`run-as` 会报 `not debuggable`；
  要看数据库用 `adb root` 之后直接读文件。
- `uiautomator dump` 读到的 `text` 是 `getText()` 原值，**看不出是否被 ellipsize**，
  按钮文字显示异常必须截图确认。
- 删测试类之后 `app/build/test-results/` 里的旧 XML 不会自动清，
  汇总条数会把已删的测试算进去 —— 先删目录再 `--rerun-tasks`。
- 「通知使用权」跳转**不要用 Android 11+ 详情页做主路径**：部分国产 ROM 会
  "启动成功但立刻退出"，看起来像点击无反应。`openListenerSettings()` 现在优先
  `ACTION_NOTIFICATION_LISTENER_SETTINGS` 列表页，并用 1.2 秒焦点检测兜底弹手动说明。

## 交付风格

Terry 是大一学生，这个项目是为参赛做的。他要的是**能跑、能讲清为什么这么写**的东西，
不是炫技。改动前说清"为什么这么改、代价是什么"，改完给验证证据（截图 / 日志 / 单测数字），
不要只说"已完成"。发现原来的实现有问题就直说，不要绕。
