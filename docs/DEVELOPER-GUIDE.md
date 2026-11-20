# 智汇日程 · 开发交接文档

> 目标读者：**接手这个项目的 AI 或开发者**。读完这一份就能直接改代码、打包、上机验证，
> 不需要再问原作者任何问题。
>
> 项目路径：`D:\WorkBuddy\outputs\smart-schedule-android`
> 当前版本：**v2.0（versionCode 64）** · 包名 `com.terry.smartschedule` · 354 个单测 · DB v10
> 最后更新：2026-11-20

---

## 0. 三十秒认识这个应用

一个**纯本地**的 Android 日程应用，核心能力是从微信 / QQ 的通知里自动认出日程并建出来，
到点用闹钟提醒。

没有服务端、没有账号体系、没有云同步。数据全部在本机 `schedule.db` 里。

### 数据怎么流动

```
微信/QQ 发通知
   ↓ NotificationListenerService（需用户手动授予"通知使用权"）
NotificationCaptureService.handleNotification()
   ↓ 六道过滤器（白名单 → 长度 → 常驻/折叠 → 文件/资费/营销 → 时间线索 → 闲聊）
   ↓ 改期/取消判定（Revise）：认出来就改/标已有日程，不再往下走
   ↓ 分段（标题 / 每条消息 / bigText）→ 逐段解析
DateTimeParser（纯 Kotlin 正则 + 推断，给出置信度）—— **纯本地，应用无 INTERNET 权限**
   ↓ 三档落地：直接建 / 进待确认 / 只记日志不建
schedule.db(events)  ──(开了日历同步且非待确认)──→ CalendarSync 写系统日历
schedule.db(events)
   ↓ ReminderPlan 算出"该在什么时候响"（纯函数，有单测）
ReminderScheduler → AlarmManager.setExactAndAllowWhileIdle
   ↓ 到点
ReminderReceiver → Notifications.postRemind()（四档提醒方式，三个渠道）
   ↓ 用户点「知道了」/「稍后 10 分钟」/ 在通知栏划掉
PendingActionReceiver → 改库状态 + 清通知 + 撤闹钟
```

**关键设计原则**（改代码前先接受这四条）：

1. **能纯函数化就必须纯函数化**。所有"判断类"逻辑（什么时候响、这条该不该建、按钮 id
   怎么算、编辑后要不要重置完成状态）都抽成了不依赖 Android 的 `object`，为了能单测。
   新增判断逻辑时照这个办，不要写进 Activity 或 Service 里。
2. **猜错了宁可不建**。误建一条错日程，用户会信任列表里的时间；漏一条能手动补。
   所有阈值、过滤器的取舍都偏这个方向。
3. **注释写"为什么"**。这套代码的注释密度很高，写的是"这行为什么必须这样""之前踩过什么坑"。
   新代码保持这个风格 —— 不要写 `// 遍历列表` 这种废话，要写"为什么这里不能用 XXX"。
4. **不引入新依赖，除非不得已**。当前只有 androidx + material + coroutines，没有 Room、
   没有 Retrofit、没有 DI 框架。这是故意的：构建快、不出幺蛾子、单测跑得快。

---

## 1. 环境与构建

### 1.1 本机工具链（已就绪，直接用）

| 组件 | 路径 / 版本 |
|---|---|
| JDK | `D:/WorkBuddy/toolchain/jdk21`（**必须 17+，不要用 JDK 26，Gradle 8.9 跑不动**） |
| Android SDK | `D:/WorkBuddy/toolchain/android-sdk`（build-tools 35.0.0） |
| Gradle | 8.9（wrapper 指向腾讯云镜像） |
| AGP | 8.7.2 |
| Kotlin | 2.0.21 |
| 模拟器 | 雷电 LDPlayer9，adb 在 `D:/leidian/LDPlayer9/adb.exe`（**不是** SDK 里的 adb） |

### 1.2 每次开新 shell 必须设的环境变量

```bash
export JAVA_HOME="D:/WorkBuddy/toolchain/jdk21"      # 正斜杠，别用反斜杠
export ANDROID_HOME="D:/WorkBuddy/toolchain/android-sdk"
export MSYS_NO_PATHCONV=1                             # adb pull/push 路径不被 Git Bash 转换
```

**这三个变量不设的后果**：gradle 会"静默失败"—— 不报错，但产物是上一次的旧包，
你会以为自己改的代码没生效，然后浪费半小时。（v1.24 真踩过。）

### 1.3 常用命令

```bash
cd "D:/WorkBuddy/outputs/smart-schedule-android"

./gradlew testDebugUnitTest        # 单测（354 条），改完必跑
./gradlew lintDebug                # 静态检查，标准是 0 error
./gradlew assembleDebug            # 只编译

./pack.sh                           # 日常出包：只出 debug，复制到 dist/ 并按版本号命名
./pack.sh release                   # 只在明确要求时用（见第 2 节铁律）
```

`apksigner.bat` 的 `JAVA_HOME` **必须正斜杠**，反斜杠会报 "JAVA_HOME is not set"——
和 gradle 相反，这点很反直觉。

---

## 2. 打包与签名（最容易出事的一块）

### 2.1 铁律

> **发布到 GitHub Releases 只上传 release 变体；debug 包只用于本机调试，不再发布。**

原因不是勤快：**debug 和 release 签名不同，不能互相覆盖安装**。从 debug 换到 release
（或反过来）必须先卸载，而卸载会**清掉日程库**——本应用还没有导出/导入功能。
所以发布口径要统一成 release，避免用户在两种签名之间来回切换。

这条约定已经写进 `pack.sh` 头部注释、`README.md` 和作者的工作区记忆里，不要违反。

### 2.2 两种包到底差什么

| 维度 | debug | release |
|---|---|---|
| 签名证书 | `C=US, O=Android, CN=Android Debug`（SDK 自带默认私钥） | `CN=Terry, OU=Personal, O=Personal, L=Guangzhou, ST=Guangdong, C=CN` |
| 签名材料 | `~/.android/debug.keystore`（JDK/SDK 自动生成） | 项目内 `keystore/release.keystore`（**不进版本库**） |
| 是否可调试 | **false**（`isDebuggable = false`，见 v1.28） | false |
| 混淆 | 无 | 无（`isMinifyEnabled = false`，故意的，见 2.5） |
| 体积 | 4.8 MB | 4.8 MB |

### 2.3 体积：为什么以前 debug 比 release 大 1.1 MB

v1.27 之前 debug 是 5.9 MB、release 4.8 MB，差 1.1 MB。根因**不是混淆**（两边都没开），
而是 **dex 拆分**：

- `debuggable=true` 时 AGP 走"每个类一个 dex 分片"的增量编译路径，产出 7 个 `classes*.dex`，
  每个 dex 有**独立的字符串池**，同一批字符串（类名、方法名、资源名）在 7 个池里各存一份。
- `debuggable=false`（v1.28 起）后合回 1 个 dex，字符串池去重生效。

实测数据（v1.27 → v1.28）：`classes*.dex` 11.1 MB → 9.2 MB，字符串条目 84,254 → 64,870(−23%)，
APK 5.9 MB → 4.8 MB。类数两边都是 6955，**代码一行没少**。

所以：如果哪天 debug 包又变回 5.9 MB，先查 `isDebuggable` 是不是被人改回 true 了，
不要怀疑"代码膨胀"。

### 2.4 签名材料在哪

```
keystore.properties          # storePassword / keyAlias / keyPassword（明文，绝对不进库）
keystore/release.keystore    # 私有 keystore，alias = smartschedule
```

两者都在 `.gitignore` 里。`app/build.gradle.kts` 的做法是：**文件存在才注册 release signingConfig**，
不存在时 `assembleRelease` 会产出 `app-release-unsigned.apk`——`pack.sh` 会检测到并打印
「这是未签名包，装不上」。

**交接时这两个文件必须一起交**。丢了 keystore 就再也签不出能覆盖安装的更新包
（Android 只认签名不认人，用户装上去看不出区别，但覆盖安装会失败）。

### 2.5 为什么不开 R8

`isMinifyEnabled = false` 是有意的。没有真机验证过的混淆规则等于埋雷：反射、协程、
Android Keystore 任何一处被裁掉都是**只在 release 包出现**的运行时崩溃，最难查。
release 的核心收益（`debuggable=false` + 私有签名）不依赖混淆，所以不开。
将来要开，前提是先有覆盖全部功能的真机回归用例。

### 2.6 版本号怎么改

只改 `app/build.gradle.kts` 里两行：

```kotlin
versionCode = 64        // 每次出包 +1，不能回退（系统拒绝降级安装）
versionName = "2.0"
```

`pack.sh` 会自己从这两行读出版本号来命名文件、追加 `dist/HISTORY.md`。
**不要手改 HISTORY.md**，那是脚本维护的账本。

### 2.7 dist/ 目录

```
dist/smart-schedule-v<版本>-<变体>.apk     # 历史包，全部保留，不要删
dist/HISTORY.md                            # 出包账本（脚本追加）
dist/USER-GUIDE.md                         # 给最终用户看的说明书
```

历史包要留着：回滚、对比、"哪一版开始出的问题"都靠它们。

---

## 3. 代码地图

```
app/src/main/java/com/terry/smartschedule/
├── App.kt                       27 行    Application：初始化 Prefs/Repo/渠道/日志 + 启保活
├── data/
│   ├── Category.kt              32 行    五类枚举：key / 展示名 / 颜色
│   ├── Event.kt                 53 行    日程实体（纯 data class，无 Android 依赖）
│   ├── EventDb.kt              228 行    SQLiteOpenHelper，DB v10，三张表
│   ├── Repo.kt                 517 行    全部数据库访问 + 系统日历镜像 + 归档（见 4.4）
│   ├── EventSearch.kt          126 行    检索：拆词/匹配/高亮区间（纯函数，SearchTest 守着）
│   ├── EventMerge.kt            60 行    入库前合并：截断版/完整版、改期前缀（EventMergeTest 守着）
│   ├── EventConflict.kt        165 行    入库前冲突检查：时间区间 / 名称+地点重合（EventConflictTest 守着）
│   ├── EventDeduper.kt         154 行    已存重复日程的分组（并查集；清理重复按钮用，EventDeduperTest 守着）
│   └── EventEdit.kt             24 行    "编辑后要不要重置完成状态"（纯函数）
├── parse/
│   ├── DateTimeParser.kt      1111 行    ★ 中文日程文本解析器，纯 Kotlin，全套正则
│   ├── SegmentParser.kt        128 行    多段文本里挑一条能用的解析结果（带钟点优先于全天）
│   ├── CategoryRules.kt         73 行    本地关键词分类器（纯函数，CategoryRulesTest 守着）
│   ├── ParseService.kt          28 行    纯本地解析调度 + 阈值常量（曾经的云端兜底已删）
│   ├── Revise.kt               517 行    ★ 认「改期/取消/换地点/时间有变」+ 挑改哪一条（ReviseTest 守着）
│   └── Prefs.kt                170 行    全部设置项（SharedPreferences）
├── sync/
│   ├── CalendarSync.kt         226 行    系统日历读写：找/建本地日历、增改删事件
│   └── CalTime.kt               23 行    全天日程 → UTC 零点换算（CalTimeTest 守着）
├── notify/
│   ├── NotificationCaptureService.kt 892 行  ★ 通知监听主链路（含监听状态广播）
│   ├── TextFilter.kt           182 行    文件/资费/营销/闲聊判别（纯函数）
│   ├── TextNorm.kt             102 行    消息去重用的归一化（纯函数，DedupeTest 守着）
│   ├── CapturePolicy.kt         29 行    "解析结果该不该建"的最后一道门
│   ├── SegmentPicker.kt         69 行    从通知 extras 里挑候选文本字段
│   ├── WatchedApps.kt           42 行    白名单：微信 / QQ / TIM
│   ├── ReminderPlan.kt          90 行    ★ "该在什么时候响"（纯函数）
│   ├── ReminderScheduler.kt    205 行    落闹钟、取消、补发、孤儿提醒清扫
│   ├── ReminderReceiver.kt      42 行    闹钟到点 → 发通知
│   ├── Notifications.kt        556 行    ★ 渠道、四档提醒、脱敏通知、改期询问、按钮 PendingIntent
│   ├── NotificationIds.kt       48 行    通知 id / requestCode 的纯算术（有单测）
│   ├── ReviseInbox.kt           67 行    应用内改期待办（纯内存，上限 5 / 3 天）
│   ├── RemindStyle.kt           98 行    四档提醒方式的枚举与解析规则
│   ├── CaptureLog.kt           205 行    抓取流水（SQLite，24h TTL + 500 行上限）
│   ├── MsgSig.kt                48 行    通知内容指纹（SHA-256，带会话维度）
│   ├── SeenStore.kt            127 行    已处理内容台账（msg_seen 表，逐段查询/插入即判重）
│   ├── ServiceHealth.kt        215 行    监听状态、重绑、电池优化、自启动设置跳转
│   ├── KeepAliveService.kt     131 行    前台服务保活（常驻低优先级通知）
│   ├── PendingActionReceiver.kt 123 行  通知按钮：确认 / 删除 / 知道了 / 稍后
│   ├── BootReceiver.kt          37 行    开机 / 应用更新后重排提醒 + 补发
│   └── NotifyAction.kt          65 行    action / extras 常量（含 LISTENER_STATE）
├── ui/
│   ├── MainActivity.kt        1482 行   ★ 列表、检索、输入框、识别、编辑面板、归档/多选、改期确认
│   ├── ArchiveActivity.kt      280 行    归档日志：恢复 / 删除 / 多选批量删除 / 删除全部
│   ├── SettingsActivity.kt     698 行    ★ 所有开关、授权引导、日历同步开关、清理重复、清库
│   ├── ReminderActivity.kt     183 行    全屏提醒页（锁屏显示 + 亮屏）
│   ├── CaptureLogActivity.kt    85 行    抓取流水查看页
│   ├── EventAdapter.kt         255 行    列表 RecyclerView 适配器（含检索高亮、结束时间小字）
│   └── SearchHighlight.kt       67 行    命中标蓝加粗 + 原文命中片段
└── util/
    ├── TimeFmt.kt               100 行    时间格式化（"明天 15:00"、区间、结束时间小字）
    ├── SecretStore.kt           87 行    Android Keystore AES 加解密（存 API Key）
    ├── ScreenGuard.kt           50 行    后台禁止截屏
    └── Insets.kt                84 行    状态栏 / 导航栏内边距
```

依赖方向：`ui → data/parse/notify → util`，`data` 不依赖 `notify`，`parse` 不依赖 `ui`。
**`DateTimeParser` 和所有纯函数对象都不允许 import android.\***（有单测跑在 JVM 上，
一 import 就编译不过，反而成了保护）。

---

## 4. 数据层

### 4.1 Event（`data/Event.kt`）

```kotlin
data class Event(
    val id: Long = 0L,
    val title: String,
    val startAt: Long,              // 毫秒时间戳；全天日程被填成当天 09:00
    val hasTime: Boolean = true,    // false = 全天
    val location: String? = null,
    val note: String? = null,       // 原始文本，最多 500 字（"这条是怎么来的"）
    val source: String = SRC_MANUAL,// manual / notify / llm(旧数据)
    val done: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val remindMinutes: Int = 10,    // -1 = 不提醒
    val remindStyle: Int = -1,      // -1 = 跟随设置；0 全屏 / 1 横幅 / 2 静音 / 3 振动
    val pending: Boolean = false,   // 待确认：不排提醒，等用户点确认
    val pendingReason: String? = null
)
```

**全天日程的约定**：`hasTime=false` 时 `startAt` = 当天 09:00，但语义是"这一天"，不是"9 点"。
判断"作废"要用当天 24:00（见 `ReminderPlan.endOfDay`），不能用 `startAt`——
否则上午十点建的"今天交材料"就永远不会提醒。这是 v1.20 修过的真实 bug。

### 4.2 数据库（`data/EventDb.kt`，DB_VERSION = 10）

```sql
events(_id, title, title_key, start_at, has_time, location, note, source, done,
       created_at, remind_minutes, remind_style, pending, pending_reason, category,
       cal_event_id, revoked, revoke_reason, archived, end_at)
  INDEX idx_event_start(start_at)
  INDEX idx_event_title_key(title_key, start_at)

capture_log(_id, at, app, outcome, detail, raw)
  INDEX idx_capture_at(at)

msg_seen(sig PRIMARY KEY, app, at)     -- 通知内容指纹台账（消息去重）
  INDEX idx_seen_at(at)
```

`revoked`：后来的通知说「这条取消了」。**只标记不删除** —— 群里那句“取消”可能是另一件事，
删错了用户找不回来。`ReminderPlan` 遇到它会直接 Skip，不再排提醒；
用户在编辑面板里保存一次就会自动清掉（他改过 = 他还认这条）。

`archived`（v9）：已归档。主列表 `active()` 不含它，归档页 `archived()` 才查；
提醒计划、入库判重、改期候选、日历同步全部排除。**不删除数据**，归档页可恢复或彻底删除。
v8→v9 迁移会把老的 `done=1` 统一置为 `archived=1`：新版本右侧框的语义从“已完成”
换成了“归档”，老勾过的日程应当直接进归档；恢复时 `unarchive` 会把 `done` 清零，
避免恢复出来的老日程带着删除线又没法取消。

`end_at`（v10）：结束时间，0 = 原文没写。解析器从「3点-5点 / 9:00-11:00」里取出来；
冲突检测按整段区间比较，0 时按当天整天处理（不是估一小时）。日历同步也用它，不再一律写 1 小时。

`cal_event_id`：系统日历里那一行的 `_id`，0 = 没同步过。**不放进 `Event.toValues()`** ——
那是整行覆盖写，编辑时内存里的 Event 未必带着最新映射，顺手覆盖会让下次编辑在日历里
多建一条重复的。只能由 `Repo.setCalId()` 单独写。

`title_key` 是 `parse/TextNorm.key(title)` 的缓存列：去重比对用归一化键而不是
title 原文（差一个句号/空格/表情就漏）。**任何写 events 的路径都必须带上它**
（`Event.toValues()` 已统一处理）；迁移时由 `backfillTitleKey` 补齐老数据。

**加字段的强制流程**（漏一步就是线上崩溃或数据错乱）：

1. `CREATE TABLE` 语句里加上新列（给 `DEFAULT`，否则老数据迁移会炸）
2. `DB_VERSION` +1
3. `onUpgrade` 里加一个 `if (oldVersion < N) { ALTER TABLE ... }` 分支
   —— **只加不删不改**，每个分支都要能独立执行（用户可能从 v1 直升到最新）
4. `Event.toValues()` 里 put 新字段
5. `Repo.toEvent()` 里读回来
6. `Repo` 里如果有 `SELECT` 白名单，也要加

**不要用 Room**。理由写在 `EventDb` 类注释里：只有一个实体，`SQLiteOpenHelper` 够用，
还省掉 kapt/ksp 的版本对齐问题。这是既定决策，不要"顺手升级"。

### 4.3 Repo（`data/Repo.kt`）

单例 `object`，所有方法 `suspend` + `withContext(Dispatchers.IO)`。UI 层直接 `lifecycleScope.launch` 里 await。

要点：

- `init(context)` 必须在任何访问前调用（`App.onCreate` 里已调，Service 被系统单独拉起时
  也会自己调一次 —— 见 `NotificationCaptureService.onCreate`）
- `writable()` 暴露给 `CaptureLog` 复用**同一个连接**。同一个库文件开两个 helper
  会导致迁移各跑各的、谁后开谁覆盖。**新增需要直连库的模块，必须走这个口子**
- `all()` 排序：待确认置顶 → 未完成 → 时间正序（**含归档**，给清库/清扫用）
- `active()` 主列表（`archived=0`）；`archived()` 归档日志（按开始时间倒序）
- `archive(id)` / `unarchive(id)`：归档停闹钟；恢复清 `done`，是否重排提醒由调用方按
  `ReminderPlan` 决定
- `duplicateGroups()` / `mergeDuplicateGroups(groups)`：设置页「清理重复日程」用，
  分组逻辑在纯函数 `EventDeduper` 里（并查集连通分量，A≈B、B≈C 时 C 不会被漏掉，
  结果也不依赖列表顺序）。删除前会按当前库重新校验：预览时没变化的条目直接沿用
  分组，变化过的要求仍与组内某条相似；被改期的条目不删。drop 独有的地点/备注会
  补到 keep；提醒/通知按真正删掉的条目撤，日历镜像走 `deleteMany` 一并处理
- `findSameEvent(title, startAt, location, excludeId, windowMs)` 是**事件级**去重入口：
  比对 `title_key + startAt±window + location`，三要素一致才算同一条；
  手动添加窗口 1 分钟，通知抓取窗口 5 分钟（推出来的时间常差几分钟）
- `deleteMany(ids)` 单事务 + `IN (...)`，按 500 一批切分（SQLite 绑定变量上限 999）

### 4.4 系统日历同步（`sync/CalendarSync.kt`，v1.34 起）

- **挂在 Repo 里，不挂在界面**：写库入口有八处，逐处加必然漏一个。
  `insert/update/delete/deleteMany/deleteAll` 统一走 `mirrorToCalendar()`
- 待确认（`pending`）的日程**不推**，确认后才进用户日历
- 已完成（`done`）的日程补推时跳过；编辑/删除按 `cal_event_id` 定位，不靠标题反查
- 没有可写日历就地建 `ACCOUNT_TYPE_LOCAL` 本地日历（必须带 `CALLER_IS_SYNCADAPTER`
  查询参数，Provider 才收 Calendars 的 insert）
- **全天事件 DTSTART 必须是 UTC 零点**（`CalTime`），写本地零点在东八区会整体错一天
- `HAS_ALARM=0`：提醒由本应用负责，挂了会和系统日历双响
- 开关在设置页；权限运行时申请；失败一律静默（日历写不进去不能让"建日程"失败）
- 同步失败/权限被撤的恢复路径：设置页「立即把已有日程补进日历」（`cal_event_id>0`
  且系统那边还存在的会跳过，不会重复建）

### 4.5 线程模型

- UI：`lifecycleScope` / `MainScope`
- 抓取：`NotificationCaptureService` 内部持有 `CoroutineScope(SupervisorJob() + Dispatchers.IO)`，
  `onDestroy` 里 `cancel()`
- **数据库调用一律在 IO 线程**，没有例外

---

## 5. 通知抓取链路（`NotificationCaptureService`）

### 5.1 六道门，顺序不能动

在 `handleNotification(sbn, force)` 里，每一步都有明确的意图：

| # | 检查 | 失败后果 | 改之前想清楚 |
|---|---|---|---|
| 0 | `ServiceHealth.lastCallbackAt = now`（无条件记） | 这是"服务到底在不在收通知"的唯一证据 | 别挪位置，要最先记 |
| 1 | 总开关 `Prefs.captureEnabled`（`force=true` 跳过） | 用户关了就不干活 | 手动扫描必须能跳 |
| 2 | 自己的通知（`pkg == packageName`） | 否则无限自喂 | — |
| 3 | **白名单** `WatchedApps.isWatched(pkg)` | 放开等于所有应用往解析器灌文本 | 见 5.2 |
| 4 | 文本长度 ≥ 4（`MIN_TEXT`） | "好的""哈哈"别浪费解析 | — |
| 5 | 常驻通知 / 纯计数折叠摘要 | 音乐播放器、下载进度 | 折叠摘要不能一律丢，有 ROM 把正文放里面 |
| 6 | **逐段去重**（L1 内存 + L2 `msg_seen`） | 旧消息不该重复建，但也不能连坐吞掉新消息 | 见下面 |
| 7 | `TextFilter.isNonScheduleNotice()` | 文件/下载/资费/营销 | 见 5.3，只看新段 |
| 8 | `looksLikeSchedule()` 粗筛 | 故意放宽，只看"像不像在说事" | 见 5.4，只看新段 |
| 9 | `TextFilter.isSmallTalk()` | "今天天气不错" | 见 5.3，只看新段 |

**改期 / 取消判定**（v1.37）夹在去重之后、粗筛之前，后面才是分段解析 → 三档落地。

| 级 | 位置 | 范围 | 挡什么 |
|---|---|---|---|
| L1 | `recentSigs` 内存 LRU（64 条，10 分钟） | 进程内 | 通知重渲染的高频重复 |
| L2 | `SeenStore.seenAt()` → `msg_seen` 表（12h TTL + 2000 行） | 跨进程/重启 | 服务被回收后系统重发通知栏旧通知 |

两级都用 `MsgSig.of(正文, 会话名)` 做签名：`TextNorm.key` 归一化（剥发送者/
群名前缀、去空白标点 emoji、全角转半角）再 SHA-256 取 16 hex。
**判重只在同一会话内比对**——两个群发同一条通知是两件事。

去重粒度是**段**，不是整条通知（v1.53）：微信/QQ 会把会话里最近几条消息一起带回来，
以前"任意一段命中就整条跳过"，旧消息会把刚来的新消息连坐吞掉。
现在先用 `seenAt` 过一遍，旧段不进入后续解析，新段照常走完整流程；
解析和落库都只看新段。

事件级 `findSameEvent` 仍然兜底：命中后如果这次带了结束时间、库里那条没有，
会把结束时间补上去（`EventMerge.canFillEnd`），不再把「六点到九点」的九点按重复丢掉。
手动扫描（force=true）跳过两级消息判重，但仍走事件级去重。
清洗库（"删除所有本地数据"）必须同时 `SeenStore.clear()`，否则删完抓不回来。

纯函数守卫在 `DedupeTest`（归一化边界、指纹稳定性、会话隔离），改 `TextNorm`
的任何正则前先看那组测试 —— `15：00` 的冒号被当成发送者前缀这种坑已经踩过。

### 5.1.1 改期 / 取消判定（v1.37，`parse/Revise.kt`）

位置：**粗筛（`looksLikeSchedule`）和闲聊判断（`isSmallTalk`）之前**，
白名单 / 长度 / 文件资费类过滤之后。这个位置改不得，理由见 13.1 第 1 条
（一开始放在后面，取消通知被当闲聊丢掉，端到端验证才发现）。

**必须早于解析**：通用解析器的前提是「一段话 = 一条新日程」，拿它去解
「原定明天四点的会提前至3点」会建出第二条 15:00 的日程，而原来那条 16:00 还在。

三种结论：

| 结论 | 触发 | 动作 |
|---|---|---|
| `Move` | 有改期动词 + 能抽出新时间 | 找目标 → 改时间（可同时改地点）→ **清旧提醒、撤旧闹钟** → 按新时间重排 |
| `Relocate` | 动词后是地址、没有时间词 | 找目标 → `Repo.relocate` 改地点（已弹出的提醒原地刷新）；时间没动，不重排闹钟 |
| `Cancel` | 有取消动词（`不取消` 不算） | 目标 `revoked=1` + 停提醒，**不删** |
| `Unclear` | 看着像改期但抽不出时间/地点 | 原文有「原定…」时发通知请用户确认；没有就只记日志 |

**地点怎么认**（v1.39）：动词表加了 `换到/移到/搬到/调到`，但**没有裸「在」**——
「在图书馆开会」是正常日程。动词后的片段必须同时满足：出现 `楼/栋/室/馆/厅/场/院/层…`
这类形态词、且**不含任何时间词**，才算地点。「号」刻意不在形态词里：`改到3号`
是改日期不是换门牌。形态判断在 `locationAfter()`，纯函数，`ReviseTest` 逐条钉着。

匹配策略（`Revise.pick`，纯函数）：旧时间 ±30 分钟内取候选；只有一条就用它，
多条要用通知原文里的字筛到唯一一条，筛不干净就返回 null。**宁可漏改，不可乱改** ——
改错一条用户很难发现（时间被悄悄挪走），漏改至少看得见。

「下午四点的会提前至3点」里那个 3 点没有上下午，旧时间在下午就推成 15:00，
并在日志里写明「上下午是按原时间推的」。

**新时段的结束时间（v1.54）**：`Move.newEndAt` 保存原文写出的结束时间（「改到六点到九点」）。
`endFor()` 把片段解析出的时长换到最终的新开始时间上 —— 日期从「原定」继承过、
上下午按旧时间推过，结束点都跟着走，不会错位。`Repo.reschedule(newEndAt=…)`
优先用新时长，原文没写结束时间才沿用旧时长；通知栏确认框、应用内改期卡片
（`ReviseInbox.Item.newEndAt` + `EXTRA_REVISE_NEW_END`）走同一套字段。

**信息不全时交给用户（v1.38 最小版，v1.39 扩到地点）**：

1. `Move` / `Relocate` 匹配不到目标，或 `Unclear` 且 `Revise.worthAsking()` 为真时，
   `askReviseUser()` 先查 `Repo.reviseCandidates()`，**有候选才发**「有一条改期需要确认」通知
2. 同时往 `ReviseInbox`（纯内存，上限 5、3 天过期）塞一张待办，主界面横幅能点开
3. `MainActivity.handleReviseIntent()` / `openReviseItem()` 复用同一批候选：
   只有一条直接确认，多条让用户选；新时间/新地点有值就弹 old → new 确认；
   两样都没有就打开编辑面板让用户自己补

**「现时间有变」类通知（v1.42）**：没有「原定」，由 `RE_TIME_CHANGED` 单独识别；
同一条通知里出现多次时取**最后一次**，并在标记后 160 字内找日期（不能按第一个逗号截断）。
这类句子没有旧时间锚点，候选匹配改用 `Revise.pickByContent`：标题 + 地点 + 原文
按字重合打分，至少重合 8 字且领先第二名 2 倍才自动改，否则走人工确认。
没有旧时间又只有钟点时返回 `Unclear`，不拿猜出来的日期去改已有日程。

**没有旧时间锚点时（v1.55）**：普通 `Move` / `Relocate` 也不能因为"两天内只有一条"
就直接认领（`Revise.pick` 的旧口径），同样走 `pickByContent` 正文重合门槛；
认不准就发确认，不静默挪走无关日程。`Cancel` 保留原口径 —— 它没有人工确认兜底，
漏取消比标错更糟，而标错可以点进编辑保存恢复。

**不落库**：参数放在 PendingIntent extras 里，进程被杀不丢；应用内卡片只是展示层，
重启后为空（通知还在，点通知照样能处理）。通知 id 固定
`NotificationIds.REVISE_ASK_ID`（900_002），避免一次群聊刷屏把通知栏塞满。
「会议提前了」这种连对象都没有的句子 `worthAsking=false`，只记日志 ——
宁可不问，也不要在通知栏制造噪音。

改期/取消/换地点成功、以及新增待办时，服务会向包内发 `ACTION_DATA_CHANGED`，
主界面在 `onStart` 注册的接收器收到就 `reload()` —— 所以**前台时也会立刻刷新**，
不需要切出去再回来（v1.39，升级点 3）。

### 5.1.2 入库前合并（v1.43，`data/EventMerge.kt`）

同一条群通知可能先以**截断版**出现（只解析出全天日期），随后再来**完整版**（带钟点）。
`findSameEvent` 比"标题 + 时间"，09:00 和 15:00 永远不相等，会建出两条。

现在 `persist` 在 insert 前调用 `Repo.findMergeTarget(event)`：

- 同一天、`TextNorm.sameTitleLoose` 宽松同题、地点不冲突（一边为空不算冲突）；
- 两条都带钟点时必须落在 5 分钟内，否则可能是两场会；
- 一条全天一条带钟点 → 合并：完整版把全天版补成具体时间，**待确认也转正**；
- 命中后更新原条目，不新建；旧提醒先撤，再按合并后的时间重排。

`TextNorm.looseKey` 会剥掉「现时间有变 / 现有变」这类改期前缀；
`sameTitleLoose` 只允许短标题 ≥6 字、长度差 ≤8 的子串关系，
避免「开会 / 开会讨论」这种短标题被误合并。

**历史数据**：库里已经存在的重复条目不会被自动删除 —— 批量删用户数据需要单独的确认设计，
入库路径只保证"以后不再建"。

### 5.1.3 入库前冲突确认（v1.46 起，`data/EventConflict.kt`）

合并之后、insert 之前，`persist` 还会和已有日程对一遍：

- **时间重叠**：按原文能确定的区间比，优先级如下：
  1. 原文写了结束时间（如「六点到九点」）→ 开始到结束；
  2. 只写了开始时间（如「六点开始」）→ 开始到当天结束；
  3. 完全没写钟点（全天日程）→ 当天 00:00 到次日 00:00。

  区间有交集 → 待确认，标题不同也算。15:00-16:00 与 16:00 首尾相接不算。
  正在进行的日程也参与。
- **名称 + 地点高度重合**：前后 3 天内、标题字符重合 ≥80%、地点归一化后相同或互相包含
  → 待确认，原因写明像哪一条。

命中后事件照常入库，但 `pending=true`、不排提醒，通知里写明冲突对象。
手动添加命中时弹「可能有冲突」，可选「仍要添加」。
已结束 / 已归档 / 已完成 / 已取消的日程不参与；待确认的日程参与。
合并截断版/完整版时也会再查一次，避免合并后撞上第三条；退回待确认会撤旧闹钟。

手动录入（首页输入 → 预览面板 → 保存）必须把 `r.endAt` 一起落库：
`saveFromSheet` 用 `r.endAt - r.startAt` 算时长，再换到用户实际选的开始时间
（`endAt = startAt + duration`）。漏掉这一步，手动建的「六点到九点」会退化成
"开始到当天结束"，冲突表现和 v1.48 一样 —— v1.50 修的就是这里。

### 5.2 白名单（`WatchedApps`）

只有 `com.tencent.mm`（微信）/ `com.tencent.mobileqq`（QQ）/ `com.tencent.tim`（TIM）。
**加应用就是往 `APPS` map 里加一行**，判断逻辑有单测（`WatchedAppsTest`）。

以前是黑名单，结果是新闻推送、电商促销、运营商账单全进来，数字太密集拦不干净——
这是历史上误报的主要来源。**不要改回黑名单**。

### 5.3 过滤器（`TextFilter`）

三个判别，都是纯函数：

- `looksLikeSenderId(text)`：`123456789` / `用户1234` / `wxid_xxx` 是**发送人**不是内容。
  保守规则：纯数字要求 ≥6 位（5 位以内可能是年份/房间号/日期），`10.25` 这种日期形状先放过
- `isNonScheduleNotice(text)`：文件/系统/资费/营销特征词 + 文件扩展名。
  **关键顺序**：先看有没有文件特征，再看有没有真实时间表达 ——只要出现"明天下午3点"，
  就一律当日程（「明天下午3点前把报告.docx发我」不能被当文件通知丢掉）
- `isSmallTalk(text)`：只有模糊日期（今天/明天/周X）+ 没有明确时间点 + 没有绝对日期 +
  没有动作词 + 没有事件词 → 判为闲聊
  - "明确时间点"包含中文数字写法（v1.51）：带时段词的「下午六点」「明晚七点半」，
    以及区间「六点到九点」。单说「一点」不算，避免把「明天一点小事」建成日程。
    之前只认阿拉伯数字，「明天下午六点到九点」会被误判成闲聊，通知完全没反应
- `RE_REAL_SCHEDULE`（文件/营销判别里的"真实时间"）用同一套中文时间信号，
  避免「明天下午六点到九点」被文件类关键词抢走

### 5.4 粗筛（`looksLikeSchedule`）

**故意放宽**：有任意数字 / 任一时间符号（`点时日月周分号`）/ 中文数字 / 事件名词之一即通过。
以前要求必须含"点时日月周分号"，结果"明天开会""后天答辩"全被挡——这是"识别不到"的主因。

### 5.5 分段与解析（`SegmentPicker` + `SegmentParser`）

`extractSegments()` 只读**正文类**字段：

- `EXTRA_MESSAGES` + `android.messages.historic`（MessagingStyle 的消息体，**群消息的正主**）
- `EXTRA_TEXT` / `EXTRA_BIG_TEXT` / `EXTRA_SUB_TEXT` / `EXTRA_SUMMARY_TEXT`
- `EXTRA_TEXT_LINES`
- **不读** `EXTRA_TITLE` / `EXTRA_CONVERSATION_TITLE` —— 微信/QQ 里这两个永远是会话名/昵称。
  群名叫 `12.31` 时，它的日期会盖掉正文写着的"明天下午3点"，所以从结构上断掉这条路（v1.19）。
  会话名只用于判重范围：`EXTRA_CONVERSATION_TITLE` 为空时回退 `EXTRA_TITLE`（v1.55），
  否则所有会话共用一个判重范围，两个群发同一条通知会互相吞。

`SegmentParser.pick()` 策略：**逐段优先、整体兜底、带钟点优先于全天**。
逐段时要求"标题像件事"（`isCleanTitle`：长度 ≥2 且不含数字，"明天下午3"这种带数字的是没挖干净的时间残渣）。
优先级（v1.57）：群里「明天开会」和「下午六点到九点」分两条发时，不能因为先碰到
「明天开会」就直接返回成全天；带钟点的结果优先，整体拼接能拿到更完整的时间。
整体兜底用**所有非空段**拼接（v1.55）：微信里「明天」「开会」各发一条时，
单段都不到 4 字，以前连拼的机会都没有；逐段解析仍然只跑 ≥4 字的段。

**结束时间的三种写法（v1.56）**：
1. 紧跟式：`六点到九点`、`6点-9点`、`6点—9点` / `6点－9点`（破折号、全角连接号都认）；
2. 开始/结束式：`六点开始，九点结束`、`六点开会，九点结束` —— 在开始时间后 40 字内
   找带「结束 / 散会 / 止」的钟点，连接词一起从标题里挖掉；
3. 明确结束钟点：`9:00-11:00`、`下午3点至5点`。

中文数字时间没有时段词时，`hasTimeContext()` 也要认区间连接号（`至`、`—`、
`-`、`~` 等），否则「明天六点至九点」会退化成全天（v1.57）。

列表时间列下方用 `TimeFmt.endLabel()` 显示「至21:00」（跨天「至次日01:00」），
通知回执/提醒用 `rangeSummary`，避免"存了结束时间但界面上看不见"。

### 5.6 三档落地（`persist`）

1. `CapturePolicy.rejectReason()` 非空 → 不建（目前只有一条：`TIME_ONLY`，
   只有时间没有日期的不建，日期纯靠猜）
2. 已存在相似日程 → 本次带了结束时间、库里那条没有时先补时长（`EventMerge.canFillEnd`），
   否则记日志"已经有一条一样的"
3. 与已有日程冲突（时间重叠 / 名称+地点高度重合）→ 进**待确认**
4. `confidence < AUTO_THRESHOLD(85)` 或 `needsConfirm` → 进**待确认**（不排提醒，发带确认/删除按钮的通知）
5. 否则直接建 + 排提醒 + 发回执

> v1.34 起没有"云端大模型兜底"这条路了：`LlmParser`、API Key 存储、设置页配置段
> 全部删除，`INTERNET` 权限也从清单里拿掉了。别再往回加 —— "纯本地"是产品的
> 核心卖点，留着联网出口它就只能写成"默认纯本地"。

### 5.7 诊断模式（`Prefs.captureVerbose`）

默认关：只记"像日程却没建出来"的记录，闲聊直接放过。
打开后**每一个**回调都留一条（仍不写正文），能区分"回调根本没进来"和"进来了被筛掉"。

排查"服务显示已连接、通知却毫无反应"时，第一步就是开它。

### 5.8 「重新连接」为什么不能保证成功（v1.60）

设置页的「重新连接监听服务」调的是 `NotificationListenerService.requestRebind()`。
它只是向系统**发一个请求**：系统可以选择不理会，很多国产 ROM 也确实会忽略。
应用没有公开 API 能自己启动一个已被系统解绑的监听服务（直接 `bindService` 也没用，
这个绑定只能由系统发起）。

所以 UI 不能把「重连成功」当成按钮的必然结果：

- 点一次后只等约 1.6 秒；没连上就弹说明，并提供「去系统设置」按钮
- 真正可靠的办法是到系统「通知使用权」页面，找到「智汇日程」，把开关**关掉、等一秒、再打开**
  —— 系统会重新绑定并回调 `onListenerConnected`
- 从系统设置返回后，`startWatch()` 观察 12 秒，给慢机型留绑定时间

不要再加"自动反复重连"：连点会打断系统正在进行的绑定，反而更连不上。

v1.61 补的两件事：

- 详情页跳转的 `EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME` 必须传 `ComponentName`
  对象，传 `flattenToString()` 字符串会被系统忽略、退回组件列表页
- 服务在 `onListenerConnected` / `onListenerDisconnected` / `onDestroy` 广播
  `NotifyAction.ACTION_LISTENER_STATE`；设置页和主界面收到后立刻刷新状态，
  设置页还会停掉轮询并提示"已连接"，不必等下一次定时刷新

v2.0 修正：详情页在部分国产 ROM 上会"启动成功但立刻退出"，用户看到的是点了没反应。
现在 `openListenerSettings()` 优先打开 `ACTION_NOTIFICATION_LISTENER_SETTINGS`
列表页（兼容性最好），详情页只在列表页打不开时兜底；调用后 1.2 秒检查
`hasWindowFocus()`，本页仍持有焦点说明根本没跳出去，直接弹出手动操作说明。

---

## 6. 解析层（`DateTimeParser`，1000 行，最需要小心的地方）

纯 Kotlin，无 Android 依赖，`DateTimeParserTest` + `NotificationCorpusTest` + 若干专项测试覆盖。

### 6.1 支持的写法

- 绝对日期：`2026-10-25` / `2026/10/25` / `2026年10月25日` / `10月25日` / `10.25` / `10-25` / `27号`
- 相对日期：今天/明天/后天/大后天/N天后、今晚/明早/明下午
- 星期：周五/星期五/礼拜天/下周三/下下周一/这个周日
- 时间：`15:00` `15：00` `15点` `15点半` `3点30分` `下午3点` `晚上8点` `中午12点半`，
  以及**中文数字**：`三点` `两点半` `下午四点二十`
- 时段修正：凌晨/早上/上午/中午/下午/傍晚/晚上
- 地点：`地点：X` / `📍X` / `在X开会` / `到X集合` / `@X`

### 6.2 Result 的语义

```kotlin
Result(title, startAt, hasTime, location, confidence,   // 0-100
       dateSource, dateHint, warnings)

val needsConfirm: Boolean get() = warnings.isNotEmpty()   // 有警告必须人工确认
```

置信度分档（`ParseService`）：

| 分值 | 含义 | 处理 |
|---|---|---|
| ≥ 85 | 可无人值守入库 | 直接建 |
| 55–84 | 需人工确认 | 进待确认 |
| < 55 | 基本是噪声 | 界面不建议保存 |

`DateSource` 可信度从高到低：`ABS`（原文写死）> `REL`（今天/明天）> `WEEK`（周X推的）>
`DAY_ONLY`（只有几号，月份是推的）> `TIME_ONLY`（只有时间，日期靠猜，**直接拒建**）> `NONE`。

### 6.3 改解析规则的正确姿势

1. **先加测试再改正则**。现有测试文件：
   `DateTimeParserTest`（基础）/ `NotificationCorpusTest`（14 条真实群通知语料）/
   `FalsePositiveTest`（误报）/ `AdversarialInputTest`（刁钻输入）/
   `DayOnlyDateTest`（只有"几号"）/ `NicknameAndCarrierTest` / `TitleNotUsedTest` / `SenderIdTitleTest`
2. **正则边界一律加负向断言**：前后不能是数字/字母/点。历史上两个著名坑：
   - `v1.8` 的 `1.8` 被当成 1 月 8 日 → 下载通知变日程
   - `(?![A-Za-z])` 只排除了字母，`12.31lyd` 会先贪婪匹配 `31` 失败、回溯成 `3`，
     前瞻看到后一位是 `1`（数字不是字母）就通过了 → 昵称变 12 月 3 日。**必须写成
     `(?![A-Za-z\d])`**
3. **跑全量单测**，语料测试比手写用例更能发现"看起来无害"的坑（`@所有人` 被当地点就是它发现的）

---

## 7. 提醒链路

### 7.1 什么时候响（`ReminderPlan`，纯函数）

```kotlin
plan(event, now) -> Plan.At(triggerAt) | Plan.Skip(reason)
```

| 情况 | 结论 |
|---|---|
| `done` | Skip「已完成」 |
| `remindMinutes < 0` | Skip「这条设了不提醒」 |
| `pending` | Skip「还待确认」 |
| 带钟点 && `startAt <= now` | Skip「已经开始了，不补提醒」 |
| 带钟点 | At(`startAt - remindMinutes*60_000`) |
| 全天 && 当天 24:00 已过 | Skip「那天已经过去了」 |
| 全天 && 未过 | At(`startAt` = 当天 09:00，**不减提前量**） |
| 算出来的 triggerAt ≤ now | **At(now + 2s)** ← 立刻响，不装没事 |

最后一条是 v1.20 的核心修复：20:47 建一条 20:52 的日程、提前 10 分钟，提醒点 20:42 已过，
老代码直接 return → 这条日程**永远不提醒**。而"越紧急的事越容易落在这一档"。

### 7.2 落闹钟（`ReminderScheduler`）

- `setExactAndAllowWhileIdle(RTC_WAKEUP, ...)`，失败退化 `setAndAllowWhileIdle`
  （有些 ROM 上 `canScheduleExactAlarms()` 说能排、真排时抛 `SecurityException`，
  排提醒失败不能把建日程流程带崩）
- `PendingIntent` 的 `requestCode = eventId.toInt()`，**同一日程的提醒共用一个**——
  所以 `snooze()` 会覆盖原闹钟（用户按"稍后"就是不想现在被打断）
- `remindedKey = "id:startAt"`（带开始时间戳，改时间自动变回未提醒），落盘在
  `Prefs.remindedKeys`

### 7.3 补发（`catchUp`）

触发时机：`BootReceiver`（开机 / `MY_PACKAGE_REPLACED`）、进入主界面。

- 从**今天零点**开始查（不是从现在——全天日程 `startAt` 是当天 09:00，下午补发会整类漏掉）
- 判断依据：`ReminderPlan.isImmediate(plan, now)`
- 三重防重复：`remindedKeys` 里有记录 / 通知栏里还挂着这条 / 用户点过"稍后"（也算响过）
- 顺手 `sweepOrphans()` 清残留

### 7.4 孤儿提醒清扫（v1.24）

两条路：

1. **主力：自己记账** `Prefs.postedReminds`（发过的日程 id）。只要"账上有、日程没了"就撤。
   不依赖 `getActiveNotifications()`（部分设备返回不全，且查不到"发过但被划掉"的）
2. **兜底：系统回看**。`getActiveNotifications()` 里属于提醒渠道的、
   id 不在存活日程里的 → 撤。还要管**锁屏脱敏版**（channel=`info`、id 同本体）——
   实测 Android 9 取消本体不会连带清掉脱敏版，会阴魂不散

判断"哪些要撤"的纯函数是 `orphanReminds(posted, alive) = posted - alive`，有 5 条单测。
渠道判断用 `Notifications.isRemindChannel(ch)` —— **加新的提醒渠道时必须同步改这个函数**，
v1.27 加振动档时漏了，v1.30 修好并加了测试。

### 7.5 四档提醒方式（`RemindStyle` + `Notifications`）

| 档位 | value | 渠道 | 全屏意图 | 响铃 | 震动 |
|---|---|---|---|---|---|
| 全屏 FULLSCREEN | 0 | `remind_v2` (HIGH) | **挂** | 有 | 有 |
| 横幅 BANNER | 1 | `remind_v2` (HIGH) | 不挂 | 有 | 有 |
| 静音 SILENT | 2 | `remind_quiet` (LOW) | 不挂 | 无 | 无 |
| 振动 VIBRATE | 3 | `remind_vibrate` (HIGH，声音 null) | 不挂 | 无 | 有 |

**value 只能追加，不能插在中间** —— 改动等于把老用户的"静音"读成"振动"。

生效方式是 `RemindStyle.resolve(perEvent, global)`：
单条选了合法值就用单条的；`-1`（跟随设置）或脏数据 → 退全局默认。
**单条的脏数据不走 `from()` 的全屏兜底**：那条日程从没选过全屏，替它做主跳到最能打扰的
一档不对。

### 7.6 全屏意图的真实边界（别再写错注释）

**`fullScreenIntent` 只在息屏 / 锁屏时真的启动 Activity。** 用户正在用手机时，系统会把它
降级成 heads-up 横幅——这是 Android 明文行为，不是能绕过的问题。

实测（Android 9，v1.24）：息屏 → `ReminderActivity` 弹到栈顶 + 屏幕点亮；亮屏解锁 → 只有
通知栏一条，栈顶还是桌面。

所以「全屏」和「横幅」在**亮屏下表现完全相同**，差别只在息屏时。要突破必须申请
`SYSTEM_ALERT_WINDOW` 悬浮窗权限（用户手动授权 + 会盖住一切），**目前没做，也不要擅自做**。

另一个相关机制：`Notifications.demoteRemind()`。全屏意图是跟着通知挂着的，通知一直在栏里，
系统每次判定"该弹"就会再弹一次（实测：提醒发出 5 分钟、用户刚看过，一按电源键又糊上来）。
所以 `ReminderActivity` 显示后立刻把通知降级成静默版 —— 语义是"一次提醒只打断一次"。

### 7.7 通知渠道（`Notifications.ensureChannels`）

| id | 名称 | 重要级 | 用途 |
|---|---|---|---|
| `remind_v2` | 到点提醒 | HIGH | 全屏 / 横幅 |
| `remind_quiet` | 到点提醒（静默） | LOW | 静音档 |
| `remind_vibrate` | 到点提醒（仅震动） | HIGH + 声音 null | 振动档 |
| `capture` | 自动记录回执 | LOW | 自动建日程的回执（**所有回执共用一条通知**） |
| `info` | 需要确认的日程 | DEFAULT | 待确认通知 + 所有锁屏脱敏版 |

> **渠道配置一旦创建就永久固定**，代码里再 `createNotificationChannel` 改会**被系统忽略**。
> 要改声音 / 震动 / 重要级，**只能换新 id**（`remind_v2` 就是这么来的）。
> 不要"顺手改一下现有渠道的参数"，那不会生效，只会让人以为改了没生效。

回执为什么共用一条 id（`CAPTURE_SUMMARY_ID = 900_001`）：每条日程一个 id 会把通知栏刷满，
被 Android 通知限流判定为刷屏，**连真正要紧的到点提醒一起被静音**（实测 muted=149/150）。

### 7.8 通知 id 规则（`NotificationIds`）

```
提醒本体       = clamp(eventId)
待确认         = clamp(eventId + 500_000)
回执           = 900_001（固定）
按钮 requestCode = clamp(eventId * 10 + slot)   // slot: 1确认 2删除 3知道了 4稍后 5全屏页
```

必须按「日程 id + 动作」唯一：撞了的话系统会把它们当同一个 PendingIntent，
点哪条都作用在第一条上，从通知栏完全看不出来。有单测（`NotificationIdsTest`）。

### 7.9 改地点后的提醒刷新（v1.40）

只换地点时时间没变，不需要重排闹钟；但如果那条提醒**已经挂在通知栏里**，
正文里的地点会停留在旧值。`Notifications.refreshRemind()` 负责原地更新：

- 先查 `getActiveNotifications()`，只有这条提醒确实还在时才更新 ——
  用户已经划掉的不复活（划掉 = 处理过了）
- 用同一个通知 id 重建，渠道按这条日程的 `RemindStyle` 选，
  不会把静音/振动档顺手改成响铃档
- `setSilent + setOnlyAlertOnce`，并且**不挂全屏意图**：
  这是内容更正，不是第二次提醒
- 调用点挂在 `Repo.relocate()` 里，服务自动改和界面手动确认两条路都覆盖
- **自动改期（时间变了）走另一条路**：`applyMove` 先 `clearRemind`、再 `cancel`、
  再按新时间 `schedule`。旧提醒的时间也错了，不能原地刷新，只能撤掉重排；
  顺序和 `MainActivity` 手动确认改期一致

实机验证（Android 9）：提醒 id=13 在通知栏时把地点改到 B栋303，
`dumpsys` 里 `android.text` 从「A栋401开评审会」变为「B栋303」，
flags 从 `0x10` 变为 `0x18`（ONLY_ALERT_ONCE）、`groupKey=silent`，没有再次响铃。

如果地点在解析时被并进了标题（「在A栋401开评审会」整句成为标题），
标题仍会带旧地址 —— 那是解析器的标题口径，不在提醒刷新范围内。

---

## 8. UI 层

### 8.1 Activity 清单

| Activity | exported | 怎么进 | 说明 |
|---|---|---|---|
| `MainActivity` | **true** | 桌面图标 | 唯一入口 |
| `SettingsActivity` | false | 主界面右上角齿轮 | `am start` 进不去 |
| `ReminderActivity` | false | 全屏提醒 / 点通知 | `excludeFromRecents`，`showWhenLocked`，`turnScreenOn` |
| `CaptureLogActivity` | false | 设置页"查看处理记录" | — |
| `ArchiveActivity` | false | 主界面顶部归档图标 | 归档日志：恢复 / 删除 / 多选批量删除 / 删除全部 |

调试时"进不去设置页"不是 bug，是 `exported=false`。要测设置页只能从界面点进去。

### 8.2 主界面结构

`activity_main.xml` + `MainActivity.kt`（1287 行，最大的文件，改之前先读完整）：

- 顶部输入框 + "识别"按钮 → `doParse()` → `DateTimeParser` → `showPreview()` 弹出编辑面板
- 列表用 `RecyclerView` + `EventAdapter`，行数据是 `Row`（标题 + 时间 + 状态 + 是否选中）
- 行内两个框（v1.44）：**最左 `cbSelectLeft` 常驻多选**，点它进入多选；
  **最右 `cbArchive` 归档**，点一下从主列表消失、进归档日志。旧的 `cbDone` / 右侧
  `cbSelect` 已隐藏，保留 id 只为兼容旧 binding
- 长按进入多选模式 → `enterSelection()` / `toggleSelectAll()` / `confirmDeleteSelected()`
- 主列表数据源是 `Repo.active()`，不再包含归档条目
- 编辑面板是 `BottomSheetDialog` + `sheet_preview.xml`（`createSheet()` 构建）
- 改期待办（v1.39）：`ReviseInbox` 有内容时列表上方出现「有 N 条改期需要确认」横幅；
  点开 → 选候选 → 确认时间/地点，或进编辑面板手动补。进程重启卡片清空（通知仍在）
- 检索（v1.33）：标题栏放大镜 → `openSearch()`；检索条占标题行位置、输入卡同步收起；
  过滤链是 `分类 chip → EventSearch.tokens` 的 AND 匹配（150ms 去抖，`commitSearch()`）

#### 检索的三个口径（改之前想清楚）

1. **归一化故意和 `TextNorm` 不同**：`TextNorm` 连标点空白一起抹（判重键，
   "开会！"="开会"）；检索只做全角转半角 + 小写 + 去空白，**保留标点** ——
   抹了标点，搜 "15:00" 会命中 "1030"，用户会觉得结果不可控
2. **多词是 AND 不是 OR**：加词必须收窄结果，OR 会让搜索越搜越多
3. **分类名参与匹配**（`matches(..., extra=分类名)`）：输"社团"能捞出整个社团类；
   命中在原文里且标题/地点行都没中的，`SearchHighlight.snippet()` 会补一段
   带省略号的上下文 —— 否则这条在列表上毫无变化，用户会以为搜错了

高亮用 `EventSearch.ranges()`（原文位置，大小写不敏感）+ Spannable 打色；
全角/半角不一致时宁可不标（区间会错位），不影响命中本身。

### 8.3 单条日程的通知方式（v1.26 + v1.27）

面板里"通知方式"由两部分组成：

- `cbFollowStyle`（勾选框"跟随设置"）：勾上 → 存 `-1`，按钮组置灰
- `tgEventStyle`（四档按钮组）：点任一档自动取消勾选，存对应 value

读写统一走三个函数，**不要绕过**：

```kotlin
styleButtonId(v)            // 存值 → 按钮 id（回显用）
styleValueOf(id)            // 按钮 id → 存值
currentEventStyle(vb)       // 面板当前状态 → 存库值（-1 表示跟随）
applyEventStyle(vb, stored) // 把已存值回显到面板（含置灰状态）
updateEventStyleDesc(vb)    // 刷新下面那行说明（和设置页共用 remind_style_desc_* 文案）
```

为什么"跟随设置"做成勾选框而不是第五个按钮：五个按钮挤一行会把文字截断。

### 8.4 设置页（`SettingsActivity`，698 行）

分区顺序：通知使用权 → 自动记录 → 系统日历同步 → 默认通知方式 → 提醒提前量 →
保活 → 诊断 → 危险操作（清空数据）。

几个必须保留的行为：

- **授权状态是系统管的**，应用只能跳转过去，不能自己开
- **"忽略电池优化"和"自启动"**在国产 ROM 上是保活的关键，两个入口都要留着
- **清空数据**只清 `Prefs` + 数据库，**不动系统授权**（清不掉也不该清）
- **日历同步开关**打开时才申请 READ/WRITE_CALENDAR 运行时权限，授权后立刻
  `Repo.syncAllToCalendar()` 补推存量日程；关掉只是停止后续同步，**不删**日历里已有的条目

### 8.5 视觉风格

亚克力（磨砂玻璃）：`res/drawable/bg_acrylic_*.xml` + `noise_tile.png` 做噪点，
配色在 `res/values/colors.xml`。改样式先看 v1.2 的 README 章节，别推翻重做。

---

## 9. 隐私与安全（别削弱）

| 机制 | 位置 | 为什么 |
|---|---|---|
| `allowBackup=false` + `dataExtractionRules` + `fullBackupContent=false` | Manifest | 默认 true 时 `adb backup` / 云备份能把数据库和加密 Key 整个导走 |
| `usesCleartextTraffic=false` + `networkSecurityConfig` | Manifest + `res/xml/` | 只放行本机回环 |
| 抓取日志不存正文 | `Prefs.logRawText` 默认 false | 只在排障时临时打开；打开后截断 80 字 |
| 日程原文可关 | `Prefs.saveEventNote` 默认 true | 隐私与数据里独立开关；关闭后新日程不再写 `Event.note`（改期/换地点/取消附带的原文也跳过），已有数据不自动删 |
| 日志 24h TTL + 500 行上限 | `notify/CaptureLog.kt` | 流水是排障工具不是存档 |
| 锁屏脱敏版通知 | `Notifications.lockScreenSafe()` | 日程标题常是隐私（"复诊""面试""交房租"） |
| 后台禁止截屏 | `util/ScreenGuard.kt` + `App.onCreate` | 切后台时 `FLAG_SECURE` |
| 提醒通知 `VISIBILITY_PRIVATE` | `Notifications` | 锁屏只显示"有一项日程到点了" |

**抓取日志为什么从 SharedPreferences 搬到 SQLite**（v1.29）：SP 是"整个 XML 一次性重写"，
没有事务没有索引；日志攒到几 MB 后每次写入都是全量序列化 + 重写文件，系统还要整个读进内存
——应用自己会变成卡顿源。现在写入是带索引的单行 INSERT，常量成本。

---

## 10. 测试

### 10.1 现状

`app/src/test/` 下 30 个文件、约 3500 行、**354 条 JUnit4 测试**，全部跑在 JVM 上（无 Robolectric、
无 Instrumented 测试）。`./gradlew testDebugUnitTest` 是唯一的质量门。

| 测试类 | 守什么 |
|---|---|
| `DateTimeParserTest` | 基础解析规则 |
| `NotificationCorpusTest` | 14 条**真实群通知语料**（最有价值，别删） |
| `FalsePositiveTest` / `PromoFilterTest` / `NicknameAndCarrierTest` | 误报 |
| `AdversarialInputTest` / `RobustnessTest` | 刁钻/畸形输入不崩 |
| `DayOnlyDateTest` | 只有"几号"的日期推断 |
| `TitleNotUsedTest` / `SenderIdTitleTest` | 会话名/发送人不许变标题 |
| `SegmentParserTest` | 多段选优：时间和事件分成两段时拼整体、两个短段拼起来也能解析、日期段在前/时间在另一段时优先带钟点、中文数字时间标题不抢事件名、只有时间仍能建 |
| `ReminderPlanTest` | 什么时候响（含"提醒点已过"那类） |
| `RemindStyleTest` | 四档的存储值稳定性、单条覆盖全局、脏值兜底 |
| `OrphanRemindTest` | 孤儿提醒该撤哪些 + 每档渠道都被认定为提醒本体 |
| `NotificationIdsTest` | id / requestCode 不撞 |
| `EventEditTest` | 编辑后何时重置完成状态 |
| `CapturePolicyTest` | 不该建的结果 |
| `WatchedAppsTest` | 白名单 |
| `SearchTest` | 检索拆词 / 多词 AND / 归一化口径 / 高亮区间 / 命中片段 |
| `CalTimeTest` | 全天日程 UTC 零点换算（东八区错一天的老坑） |
| `ReviseTest` | 改期/取消/换地点句式、地点与时间的区分、上下午推断、改哪一条、该不该弹确认、改期带出新时段结束时间 |
| `ReviseInboxTest` | 应用内改期待办：去重 / 上限 5 / 3 天过期 / 移除 |
| `TimeChangedNoticeTest` | 「现时间有变」识别、旧版+新版取最后、正文重合选目标（单候选也要过门槛）、钟点解析失败不乱改、裸「时间调整」不误判 |
| `EventMergeTest` | 截断版/完整版合并、改期前缀、全天与带钟点的同日判断、重复命中时补结束时间（两边都有则不猜） |
| `EventDeduperTest` | 存量重复清理：同次/跨天归组、链式重复不漏、顺序无关、每天例会不误合、地点冲突、归档/已完成不参与、删除前补字段与结束时间/跳过已改期条目 |
| `EventConflictTest` | 入库前冲突：有结束时间按区间、只写开始时间到当天结束、全天按整天、首尾相接不算、名称+地点重合、门牌号不同不误判、已结束/归档/完成/取消不参与、待确认参与、时间重叠优先 |
| `TimeRangeParseTest` | 时间区间解析：3点-5点、9:00-11:00、跨天 23:00-01:00、中文数字「六点到九点」、开始/结束句式、破折号/全角连接号、到5号楼不是时间；解析结果直接参与冲突（区间 / 只写开始 / 只有日期）；纯时间描述不会被误判成改期 |

### 10.2 写新测试的规矩

- **只测纯函数**。要在测试里 new 一个 Activity / 碰 Context，说明这段逻辑该抽出来了
- **测"该不该"的边界，不测实现细节**。例如 `ReminderPlan` 的测试传显式 `now`，
  不要用 `System.currentTimeMillis()`
- **每加一条正则规则，必须补一条"不该命中"的用例**。误报比漏报更难发现

---

## 11. 上机验证

### 11.1 模拟器

雷电 LDPlayer9，adb 用 `D:/leidian/LDPlayer9/adb.exe`（**不是** SDK 里的 adb）。
这版没有 `ldconsole.exe`，只能 GUI 起 `dnplayer.exe`。

```bash
export MSYS_NO_PATHCONV=1
/d/leidian/LDPlayer9/adb.exe devices
/d/leidian/LDPlayer9/adb.exe install -r dist/smart-schedule-v2.0-release.apk
```

### 11.2 装机后必须做的授权（每次重装都要重做）

1. 设置 → 通知使用权 → 允许"智汇日程"
2. 通知权限（Android 13+）
3. 「闹钟和提醒」精确闹钟权限（Android 12+）
4. 忽略电池优化 / 自启动（国产 ROM）

> `pm clear` 或卸载重装会**清掉通知使用权授权**，要在系统设置里重新开。

### 11.3 造测试通知

`tools/notify-sim/` 是独立最小工程（纯 Java、零依赖），**专门用来在模拟器上造出
"来自微信"的通知**。因为 App 判断来源用的是 `StatusBarNotification.packageName`，
这个值由系统按安装包填、改不了，所以它的 `applicationId` 直接就是 `com.tencent.mm`
（另一个变体 `com.example.notifysim` 当对照组）。

```bash
./gradlew -p tools/notify-sim assembleFakewxDebug assembleOtherDebug
/d/leidian/LDPlayer9/adb.exe install -r tools/notify-sim/app/build/outputs/apk/fakewx/debug/app-fakewx-debug.apk
/d/leidian/LDPlayer9/adb.exe shell am start -n com.tencent.mm/com.terry.notifysim.PostActivity --ei case 1
```

20 条用例在 `Cases.java`（中文硬编码，走 shell 传中文会被编码搞坏），实测报告见
`docs/v1.19-filter-test-report.html`。

**调单条文本**不用改代码重编，`PostActivity` 留了临时入口：

```bash
adb shell am start -n com.tencent.mm/com.terry.notifysim.PostActivity \
  --es title 测试群 --es body "原定明天下午四点的会议将提前至3点"
```

实测 `--es body` 的中文能正确传进通知（`dumpsys notification --noredact` 里能看到原文），
比 `adb shell input text` 可靠 —— 后者传中文会崩。

> ⚠️ `com.tencent.mm` 就是微信的包名。**跑完记得卸载**，否则装不了真微信。
> 这个工程刻意没有并进主工程，别混进交付物。

### 11.4 排查命令（⚠️ 有两个会说谎的）

```bash
# 服务是否连着
adb shell settings get secure enabled_notification_listeners
adb shell dumpsys notification --noredact | grep -A5 smartschedule

# 闹钟是否排着 —— 只看 RTC_WAKEUP #N: + when=，别 grep ReminderReceiver
adb shell dumpsys alarm | grep -A3 "RTC_WAKEUP" | grep -i "when="

# 真实通知栏（唯一可信的"通知在不在"判据）
adb shell cmd statusbar expand-notifications
adb shell uiautomator dump && adb pull /sdcard/window_dump.xml
```

**两个坑，都真踩过**：

1. **`dumpsys notification` 会说谎**：已经撤掉的通知还会在 "Notification List" 里留一阵
   （幽灵记录）。判断通知在不在，**只能看真实通知栏**。
2. **`dumpsys alarm` 会说谎**：`grep ReminderReceiver` 会命中 "Alarm Stats" 历史统计段
   （`+134ms running, 3 wakeups` / `last -14m27s`），那不是待触发的闹钟。
   要看 `RTC_WAKEUP #N:` 段里的 `when=`。

其他易踩：

- `adb shell input text` **传不了中文**（会崩或 Invalid arguments），测试文本用 ASCII
- `input text 'a b c'` 必须整体加引号，否则空格被拆成多个参数
- `uiautomator dump` 在滚动动画中会静默失败，dump 前先 `rm /sdcard/x.xml` 再重试

---

## 12. 改代码的常见任务（照着做）

### 12.1 加一档提醒方式（例如"只响铃不震"）

1. `RemindStyle`：**追加**新枚举，value 取下一个数（4），实现 `quiet` / `vibrateOnly` 之类的新谓词
2. `Notifications`：新建**独立渠道**（渠道配置不可改，只能换 id），加进 `isRemindChannel()`
3. `Notifications.postRemind()`：`channel` 的 `when` 里加分支，`setPriority` 同步
4. `strings.xml`：`remind_style_xxx` + `remind_style_desc_xxx`
5. `activity_settings.xml` + `sheet_preview.xml`：加按钮（注意一行放 4 个已经到极限，第 5 个要换布局）
6. `SettingsActivity` 的 `styleButtonId` / `styleValueOf` / `updateStyleDesc`，`MainActivity` 的同名三件套
7. 单测：`RemindStyleTest` 加值稳定性 + 语义，`OrphanRemindTest` 加"新渠道被认定为本体"
8. `USER-GUIDE.md` 的档位表 + `README.md` 新章节 + 版本号 +1 + `./pack.sh`

### 12.2 加一个被监听的应用

`WatchedApps.APPS` 加一行 `包名 to 展示名` → `WatchedAppsTest` 补一条 → 完事。
**前提**：先确认那个应用的通知结构（是否 MessagingStyle、正文在哪个 extra）。
做法是用 `tools/notify-sim` 造同样的结构验证，别靠猜。

### 12.3 给日程加一个字段

见 4.2 的六步。另外记得：`MainActivity.saveFromSheet` / `saveEdit` 两处构造 `Event` 的地方都要带上。

### 12.4 改解析规则

见 6.3。先写测试，再加负向断言，跑全量。

### 12.5 加一种改期 / 取消的说法

1. 把动词加进 `Revise.RE_MOVE` 或 `Revise.RE_CANCEL`（取消类注意负向先行断言，
   「不取消」不算）
2. `ReviseTest` 补一条：**先断言它被判成 `Move`/`Cancel`，再断言新时间对不对**
3. 用 `tools/notify-sim` 发一条真实文本，确认 `capture_log` 里是 `created` 而不是
   `skipped`（被过滤器拦了会显示原因）
4. 如果新说法里旧时间不在「原定…」后面，`oldChunkBefore` 也要跟着扩

改完必须跑全量单测：`Revise.pick` 的匹配策略被 5 条用例钉着，动一个字都可能让
「多条候选」从"不猜"变成"猜错"。

---

## 13. 已知限制（写清楚，别当 bug 修）

1. **全屏页只在息屏/锁屏时弹**。亮屏时系统降级成横幅，这是 Android 规范，不是 bug。
   要突破要 `SYSTEM_ALERT_WINDOW`，代价是需要用户手动授权 + 真的盖住一切，目前不做。
2. **只抓微信 / QQ / TIM**。短信、邮件、日历里的日程抓不到。这是有意的取舍。
3. **"没有日期只有时间"的结果一律不建**（"五点去吃饭"）。日期纯靠猜，建错比如实说更难办。
4. **没有导出 / 导入**。卸载或换签名重装 = 日程全丢。这是目前最大的功能缺口。
5. **没有周报 / 统计**。数据都在库里（`events.start_at` / `done` / `source`、
   `capture_log`），做聚合查询即可，尚未实现。
6. **抓取流水只留 24h**（且 ≤500 条），不是历史存档。
7. **本地解析是规则驱动，不是 AI**。中文表达千变万化，长尾必然漏；认不出的进"待确认"。
   v1.34 起应用没有任何联网能力（清单里没有 INTERNET 权限），这点可以直接验证。
8. **改期 / 取消只在通知链路生效**。手动往输入框粘一段「原定四点的会改到三点」，
   仍然按新日程处理 —— 手动输入的用户意图是"我要建这条"，替他改已有数据更危险。
9. **没有做"通知撤回"检测**。`onNotificationRemoved` 拿到的 `reason` 无法区分
    "消息被撤回"和"用户读完了"（微信读消息也会取消通知，同为 `REASON_APP_CANCEL`），
    据此自动删日程等于"一读消息日程就消失"。群通知又是合并重绘，新消息会把旧文本挤出，
    没法定位到具体哪条变了。所以只做语义层的改期/取消，不做通知层的事件追踪。

---

### 13.1 改期识别（v1.37）的两个坑

1. **判定必须放在粗筛和闲聊判断之前**。实测「原定明天下午三点的会在教学楼不开了」
   被 `TextFilter.isSmallTalk` 当成闲聊丢掉（它没有"具体安排"的措辞），
   「会不开了」这种连时间词都没有的会被 `looksLikeSchedule` 先拦。两种情况下
   用户看到的都是"我点了取消，日程还在"，比不做更糟。
   现在 `handleNotification` 里先算 `val revise = Revise.detect(joined)`，
   `isRevision` 为真时跳过这两道门，但**白名单、长度、文件/资费类过滤仍然照跑**。
   v1.52 修过一次回退：代码里曾多留了一份粗筛排在最前面（没有 `!isRevision`），
   把「会不开了」这类不带时间词的取消/换地点通知直接挡掉。现在只保留
   `!isRevision && !looksLikeSchedule(...)` 这一处。
2. **端到端必须用 `tools/notify-sim` 验**，不要只信单测。
   它有两个 flavor：`fakewx`（`applicationId = com.tencent.mm`，伪装微信）和
   `other`（普通包名，用来验白名单真的拦得住）。
   用法：`./gradlew :app:assembleFakewxDebug` → 装 →
   `adb shell am start -n com.tencent.mm/com.terry.notifysim.PostActivity --es title 测试群 --es body "原文"`
   （中文可以走 `--es`，实测能正确传递；`adb shell input text` 不行）。
   验完记得 `adb uninstall com.tencent.mm`，否则真微信装不上。

---

## 14. 每次改完的收尾清单

```bash
# 1. 单测 + lint
./gradlew testDebugUnitTest lintDebug      # 必须 354 条全绿、0 error

# 2. 版本号 +1（versionCode + versionName）
# 3. README.md 加一节「### vX.Y —— 一句话说清改了什么」，讲清"为什么"
# 4. dist/USER-GUIDE.md 若涉及用户可见行为，同步更新
# 5. 出包（默认只 debug）
./pack.sh
```

**不要手改** `dist/HISTORY.md`（脚本维护）。**不要删** `dist/` 里的历史包。

---

## 15. 交接清单（拿到这些东西才能完整接手）

| 项 | 位置 | 备注 |
|---|---|---|
| 源码 | `D:\WorkBuddy\outputs\smart-schedule-android` | Git 仓库，`.gitignore` 已排除签名材料 |
| release keystore | `keystore/release.keystore` | alias `smartschedule`，**丢了就发不出更新包** |
| keystore 密码 | `keystore.properties` | 明文，只在本机，**不要提交、不要外发** |
| 工具链 | `D:/WorkBuddy/toolchain/{jdk21,android-sdk}` | Gradle 8.9 由 wrapper 拉取 |
| 历史包 | `dist/*.apk` + `HISTORY.md` | 回滚与对比 |
| 设计文档 | `README.md`（逐版决策记录）、`docs/*.html`（历次测试报告） | README 值得完整读一遍，里面全是踩坑结论 |

**没有账号需要交接**：应用无服务端、无登录、无第三方后台、**无网络权限**。
v1.34 起应用没有任何外部依赖。

---

## 16. 一句话总结这个项目的脾气

> 宁可少记一条，不要记错一条；宁可多写一段注释，不要留一个"看起来能跑"的猜测；
> 所有判断逻辑都抽成纯函数并且有单测；改行为之前先想清楚 Android 的规范边界在哪。

照这个脾气改代码，就不会把已有的 354 条测试和保护机制一条条拆掉。
