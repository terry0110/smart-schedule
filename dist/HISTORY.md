# 迭代包历史

每一次迭代的 debug 包都留在这个目录里，**不要删**。

回滚、对比、定位"哪一版开始出的问题"都要靠它们。索引由 `./pack.sh` 自动追加，
同一版本同一变体重复打包不会重复记一行。

| 版本 | versionCode | 变体 | 时间 | 文件 | 大小 |
|---|---|---|---|---|---|
| 1.0 | 1 | debug | 2026-10-23 14:49 | `smart-schedule-v1.0-debug.apk` | 5.7M |
| 1.3 | 3 | debug | 2026-10-23 20:13 | `smart-schedule-v1.3-debug.apk` | 5.8M |
| 1.6 | 6 | debug | 2026-10-24 01:00 | `smart-schedule-v1.6-debug.apk` | 5.8M |
| 1.7 | 7 | debug | 2026-10-24 01:21 | `smart-schedule-v1.7-debug.apk` | 5.8M |
| 1.8 | 8 | debug | 2026-10-24 01:55 | `smart-schedule-v1.8-debug.apk` | 5.8M |
| 1.9 | 9 | debug | 2026-10-24 02:15 | `smart-schedule-v1.9-debug.apk` | 5.8M |
| 1.10 | 10 | debug | 2026-10-24 09:47 | `smart-schedule-v1.10-debug.apk` | 5.8M |

## 归档说明

- **v1.0 / v1.3 / v1.6** 来自微信收到的三个未命名包（`smart-schedule-debug.apk`、
  `smart-schedule-debug(1).apk`、`smart-schedule-debug(2).apk`），按包内 `versionCode`
  核对后重命名归档，原始文件未改动。
- **v1.7 / v1.8** 来自微信收到的同名包，名字本来就对，直接复制。
- **v1.9** 是本地产物，md5 与微信目录里那份完全一致。
- v1.0 ～ v1.9 六个包的签名 SHA-256 全是
  `e0fb6d2d66e1bf319cffcecdbe2ea219313f352df14278cf909e78e44190e2de`（本机 debug.keystore），
  没有被重打包或损坏。
- 「时间」是文件系统时间，不完全等于构建时刻，同一版本可能相差几分钟。

**未留存的版本**：v1.1、v1.2、v1.4、v1.5 —— 那几次没有保存产物，补不回来。

## 各版本一句话

| 版本 | 改了什么 |
|---|---|
| 2.1 | 归档恢复与批量恢复修复、列表列头、结束时间选择框、完成统一归档 |
| 2.0 | 总开关与状态文案改为「读取通知」；授权入口改为优先打开通知使用权列表页 |
| 1.62 | 应用名统一为「智汇日程」；发布只上传 release 变体，debug 仅本机调试 |
| 1.61 | 重连状态实时广播刷新；修正通知使用权详情页的 ComponentName 参数 |
| 1.60 | 「重新连接」改成可用引导：requestRebind 只是请求，连不上直接引导关开通知使用权 |
| 1.59 | 设置页精简：按标注删除监听/诊断/常驻/日志/隐私说明段落，连接中状态收成一行 |
| 1.58 | 隐私与数据新增独立开关「日程里保存解析原文」，默认开启，关闭后新日程不再存原文 |
| 1.57 | 修复"明明有时间却按全天"：多段通知带钟点优先；区间连接号也算时间上下文 |
| 1.56 | 持续时间识别补齐：开始/结束句式、破折号连接号；列表时间下显示「至21:00」 |
| 1.55 | 通知匹配再收紧：判重按会话回退标题、短消息拼整体、无原定时间不盲认改期目标 |
| 1.54 | 改期通知带出新时长：「改到六点到九点」的结束时间一起更新，上下午推的也跟着走 |
| 1.53 | 去重不再吞掉持续时间：通知按段判重；重复命中时补结束时间；清理重复时继承区间 |
| 1.52 | 通知链路再修两处：删掉改期判定前的重复粗筛；中文数字时间标题不再抢事件名 |
| 1.51 | 通知抓取识别中文数字时间：正文只写「明天下午六点到九点」不再被当闲聊丢掉 |
| 1.50 | 手动录入也保存结束时间：预览面板按原文时长落到实际开始时间，不再退化成"开始到当天结束" |
| 1.49 | 冲突区间按原文优先级：有结束时间按区间、只写开始到当天结束、只有日期按全天 |
| 1.25 | 把「全屏」的文案改成符合事实：只有息屏/锁屏才弹整页，亮屏时系统会降级成横幅 |
| 1.24 | 删除/清空日程后，通知栏里那条到点提醒立刻撤掉（自己记账清扫，不再等一小时超时） |
| 1.23 | 提醒方式三档可选：全屏 / 横幅（不打断游戏）/ 静音 |
| 1.22 | 提醒修成"真的能叫到人"（全屏+闹钟音量+震动，排程与补发重写）；只有时间的通知不再建 |
| 1.21 | 对抗性输入撞出 4 个 bug：HTML 标签 / 标签式前缀 / 标题挖地点 / 群聊拼接 |
| 1.20 | 认得出「27号」这种没写月份的日期 |
| 1.19 | 标题彻底不参与识别 + 首页多选批量删除 |
| 1.18 | 群名里的日期不再当日程日期（`12.31` 这类群名） |
| 1.17 | 通知来源收窄为白名单：只分析微信 / QQ / TIM |
| 1.16 | 模拟器实测：会话名(2条新消息)不再当标题 + 游戏营销推送过滤 |
| 1.15 | （模拟器调试中间版本，未单独发布说明） |
| 1.14 | 昵称里的数字不再当日期 + 流量/话费短信过滤 + 闲聊不建日程 |
| 1.13 | 待确认日程可在通知栏直接确认/删除 |
| 1.12 | 手动添加/编辑也做去重（同标题+同时间直接拦掉） |
| 1.11 | QQ 通知不再把发送人号码当标题（MessagingStyle 的标题是会话名） |
| 1.10 | 常驻通知跟着真实状态刷新 + 重连只发一次请求（连点会打断系统绑定） |
| 1.9 | 修下载通知误报（`v1.8` 被当 1 月 8 日）+ 修重连卡在"系统节流" |
| 1.8 | 日程支持编辑（点卡片进编辑面板） |
| 1.7 | 修"服务连上了但通知没反应"（MessagingStyle 消息体没读） |
| 1.6 | 退到后台禁止截屏（FLAG_SECURE） |
| 1.5 | 安全加固（API Key 加密、关备份、禁明文 HTTP、锁屏脱敏） |
| 1.4 | 修"退出重进还是显示服务没连上" |
| 1.3 | 主动找问题：中文数字时间、重复建日程、异常回调保护 |
| 1.2 | 换成亚克力材质 |
| 1.1 | 修识别不到 / 日期错照生成 / 状态栏重叠 |
| 1.0 | 初版：手动粘贴 + 通知监听双入口，本地规则解析 + 大模型兜底 |
| 1.11 | 11 | debug | 2026-10-24 10:09 | smart-schedule-v1.11-debug.apk | 5.8M |
| 1.12 | 12 | debug | 2026-10-24 10:57 | smart-schedule-v1.12-debug.apk | 5.9M |
| 1.13 | 13 | debug | 2026-10-24 11:08 | smart-schedule-v1.13-debug.apk | 5.9M |
| 1.14 | 14 | debug | 2026-10-24 12:06 | smart-schedule-v1.14-debug.apk | 5.9M |
| 1.15 | 15 | debug | 2026-10-24 12:13 | smart-schedule-v1.15-debug.apk | 5.9M |
| 1.16 | 16 | debug | 2026-10-24 12:21 | smart-schedule-v1.16-debug.apk | 5.9M |
| 1.17 | 17 | debug | 2026-10-24 12:47 | smart-schedule-v1.17-debug.apk | 5.9M |
| 1.18 | 18 | debug | 2026-10-24 14:06 | smart-schedule-v1.18-debug.apk | 5.9M |
| 1.19 | 19 | debug | 2026-10-24 14:31 | smart-schedule-v1.19-debug.apk | 5.9M |
| 1.20 | 20 | debug | 2026-10-24 15:50 | smart-schedule-v1.20-debug.apk | 5.9M |
| 1.21 | 21 | debug | 2026-10-24 16:17 | smart-schedule-v1.21-debug.apk | 5.9M |
| 1.22 | 22 | debug | 2026-10-24 21:00 | smart-schedule-v1.22-debug.apk | 5.9M |
| 1.23 | 23 | debug | 2026-10-24 21:37 | smart-schedule-v1.23-debug.apk | 5.9M |
| 1.24 | 24 | debug | 2026-10-24 23:34 | smart-schedule-v1.24-debug.apk | 5.9M |
| 1.24 | 24 | release | 2026-10-24 23:58 | smart-schedule-v1.24-release.apk | 4.8M |
| 1.25 | 25 | debug | 2026-10-25 14:47 | smart-schedule-v1.25-debug.apk | 5.9M |
| 1.25 | 25 | release | 2026-10-25 14:47 | smart-schedule-v1.25-release.apk | 4.8M |
| 1.26 | 26 | debug | 2026-10-28 14:44 | smart-schedule-v1.26-debug.apk | 5.9M |
| 1.27 | 27 | debug | 2026-10-28 14:52 | smart-schedule-v1.27-debug.apk | 5.9M |
| 1.28 | 28 | debug | 2026-10-29 23:55 | smart-schedule-v1.28-debug.apk | 4.8M |
| 1.29 | 29 | debug | 2026-10-30 00:02 | smart-schedule-v1.29-debug.apk | 4.8M |
| 1.30 | 30 | debug | 2026-11-18 19:17 | smart-schedule-v1.30-debug.apk | 4.8M |
| 1.31 | 31 | debug | 2026-11-18 20:35 | smart-schedule-v1.31-debug.apk | 4.8M |
| 1.32 | 32 | debug | 2026-11-18 20:46 | smart-schedule-v1.32-debug.apk | 4.8M |
| 1.33 | 33 | debug | 2026-11-18 21:06 | smart-schedule-v1.33-debug.apk | 4.8M |
| 1.34 | 34 | debug | 2026-11-18 23:11 | smart-schedule-v1.34-debug.apk | 4.8M |
| 1.35 | 35 | debug | 2026-11-19 13:37 | smart-schedule-v1.35-debug.apk | 4.8M |
| 1.36 | 36 | debug | 2026-11-19 14:47 | smart-schedule-v1.36-debug.apk | 4.8M |
| 1.37 | 37 | debug | 2026-11-19 18:56 | smart-schedule-v1.37-debug.apk | 4.9M |
| 1.38 | 38 | debug | 2026-11-19 20:42 | smart-schedule-v1.38-debug.apk | 4.9M |
| 1.39 | 39 | debug | 2026-11-19 21:09 | smart-schedule-v1.39-debug.apk | 4.9M |
| 1.40 | 40 | debug | 2026-11-19 21:37 | smart-schedule-v1.40-debug.apk | 4.9M |
| 1.41 | 41 | debug | 2026-11-19 21:47 | smart-schedule-v1.41-debug.apk | 4.9M |
| 1.42 | 42 | debug | 2026-11-19 22:10 | smart-schedule-v1.42-debug.apk | 4.9M |
| 1.43 | 43 | debug | 2026-11-19 22:29 | smart-schedule-v1.43-debug.apk | 4.9M |
| 1.44 | 44 | debug | 2026-11-19 23:10 | smart-schedule-v1.44-debug.apk | 4.9M |
| 1.45 | 45 | debug | 2026-11-20 00:02 | smart-schedule-v1.45-debug.apk | 4.8M |
| 1.46 | 46 | debug | 2026-11-20 08:14 | smart-schedule-v1.46-debug.apk | 4.8M |
| 1.47 | 47 | debug | 2026-11-20 11:08 | smart-schedule-v1.47-debug.apk | 4.8M |
| 1.48 | 48 | debug | 2026-11-20 12:05 | smart-schedule-v1.48-debug.apk | 4.8M |
| 1.49 | 49 | debug | 2026-11-20 16:24 | smart-schedule-v1.49-debug.apk | 4.8M |
| 1.50 | 50 | debug | 2026-11-20 16:57 | smart-schedule-v1.50-debug.apk | 4.8M |
| 1.51 | 51 | debug | 2026-11-20 17:05 | smart-schedule-v1.51-debug.apk | 4.8M |
| 1.52 | 52 | debug | 2026-11-20 17:23 | smart-schedule-v1.52-debug.apk | 4.8M |
| 1.53 | 53 | debug | 2026-11-20 17:40 | smart-schedule-v1.53-debug.apk | 4.8M |
| 1.54 | 54 | debug | 2026-11-20 18:09 | smart-schedule-v1.54-debug.apk | 4.8M |
| 1.55 | 55 | debug | 2026-11-20 18:51 | smart-schedule-v1.55-debug.apk | 4.8M |
| 1.56 | 56 | debug | 2026-11-20 19:00 | smart-schedule-v1.56-debug.apk | 4.8M |
| 1.57 | 57 | debug | 2026-11-20 19:21 | smart-schedule-v1.57-debug.apk | 4.8M |
| 1.58 | 58 | debug | 2026-11-20 21:31 | smart-schedule-v1.58-debug.apk | 4.8M |
| 1.59 | 59 | debug | 2026-11-20 21:42 | smart-schedule-v1.59-debug.apk | 4.8M |
| 1.60 | 60 | debug | 2026-11-20 21:56 | smart-schedule-v1.60-debug.apk | 4.8M |
| 1.61 | 61 | debug | 2026-11-20 22:07 | smart-schedule-v1.61-debug.apk | 4.8M |
| 1.62 | 62 | debug | 2026-11-20 22:15 | smart-schedule-v1.62-debug.apk | 4.8M |
| 1.62 | 62 | release | 2026-11-20 22:23 | smart-schedule-v1.62-release.apk | 4.8M |
| 2.0 | 64 | release | 2026-11-20 22:58 | smart-schedule-v2.0-release.apk | 4.8M |
| 2.1 | 65 | release | 2026-11-21 01:10 | smart-schedule-v2.1-release.apk | 4.8M |
