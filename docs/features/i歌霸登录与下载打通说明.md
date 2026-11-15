# i歌霸登录与下载打通说明

> 分支 `android-4.4.4`（1.1.0，minSdk 19）　|　设备 NX507J（Android 4.4.2 / API 19 / armeabi-v7a）
> 日期 2026-11-15

---

## 一、现象与结论

现象：装上 i歌霸曲库 + 音源后，**点歌就卡在「00:00 / 00:00」**，播放不出来，也看不到下载进度；同时「找不到 i歌霸登录」。

排查后确认是**两个独立故障叠加**，都不是账号问题：

| # | 故障 | 证据 | 影响 |
|---|---|---|---|
| 1 | 缓存地址被「本地镜像」劫持 | 日志 `丢弃不可信缓存地址 来源=本地镜像 地址=http://192.168.31.10:8000/220046950.mkv` | 每首歌都先去连一台没开机的内网镜像，连不上就失败，永远走不到 i歌霸 |
| 2 | i歌霸媒体机拒收 okdownload 的开放 Range | 日志 `ServerCanceledException: Response code can't handled on internal 403 with current offset 0`；curl 实测 `Range: bytes=0-` → **403**、`bytes=0-65535` → **206** | 即使地址正确也下不动，首包就 403 |

顺带确认的**好消息**：i歌霸服务端允许**游客取地址**（`UserId=0`，`Rights.Code=0/"未登录"` 但 `FileUrl` 正常返回），所以**没有账号也能点歌下载**，只是被服务端限速（`RatedSpeed=675840 B/s ≈ 660 KB/s`）。登录不是使用前置条件。

---

## 二、改动清单

### 2.1 故障一：只信任 i歌霸的缓存地址
`SongSourceRouter.resolve()` 原来命中 `song.downloadUrl` 就短路，不管来源是什么。现在：
- 只有 `sourceName == "i歌霸"` 的缓存地址才直接用；
- 镜像、内置签名接口的缓存地址一律丢弃并重新解析（镜像随时可能下线、签名地址有时效）。

### 2.2 故障二：i歌霸走自研分段下载
新增 `IgebaMediaDownloader.kt`：
- 每次请求显式给结束位：`Range: bytes=<start>-<start+4MB-1>`（媒体机只认这种，开放区间回 403）；
- 复用 `.download` 半成品做续传；服务端若返回 200（忽略区间）则从头重写，不追加脏数据；
- 25 秒无进展判停滞；支持取消；
- `Content-Range` 用字符串手动解析，**不用 `getContentLengthLong()`**——它在 API 19 上不存在，会直接 `NoSuchMethodError` 崩（这是本项目已经踩过的坑）。

`SongOkDownloadManager` 按来源分流：`i歌霸 → IgebaMediaDownloader`，其余来源（镜像/内置接口）继续用 okdownload，互不影响。

### 2.3 镜像探活
本地镜像（`gradle.properties` 的 `MEDIA_BASE_URL`）配了但服务没起时会白等，现在取地址前先发一个 3 秒的 `HEAD` 探活，探不通就跳过并记日志：
`本地镜像不可达，跳过：http://192.168.31.10:8000`。

### 2.4 现场可诊断：落盘日志
这台 4.4 机器的 `adb logcat` 读不到应用日志（只剩 `open /proc/kmsg failed`），排障全靠猜。新增 `AppLog`：
- 关键链路同步写 `/sdcard/MaidongKTV/logs/app.log`（超 2 MB 滚 `app.log.1`）；
- 地址只记「协议+主机+路径」，**不落 rid/uid 签名参数、不落密码与 Cookie**；
- 已打点：启动会话状态、取地址请求/结果、音源选择、下载开始/失败原因/完成、`DO_LOGIN` 的 Code/Reason/UserId。

```powershell
# 现场取日志
adb -s NX507J shell "cat /sdcard/MaidongKTV/logs/app.log | tail -40"
```

### 2.5 登录逻辑完善
- **游客通道**：`IgebaApiClient.address()` 未登录时传 `UserId=0`，不再抛「请先登录 i歌霸账号」；
- **会话过期自愈**：本地还留着会话但服务端回「未登录（Code=0）」时，作废会话并按游客重取一次，保证仍能下载；
- **启动即恢复登录**：存过账号且会话为空时，`KtvApplication` 在后台先跑一次 `autoLogin()`，不用等第一次点歌才发现掉线；
- **登录失败原因直给**：服务端原文「用户不存在 / 密码错误」会翻译成可操作提示（去注册 / 核对密码），不再只弹「登录失败」；
- **入口可见性**：首次启动弹一次提示「游客也能点歌（限速），登录入口在 设置 → 🎧 音源」（带 SharedPreferences 标记，只弹一次）。**注意设置里「🎧 音源」是第 10 项，需要往下翻**。
- **下载失败引导登录**：未登录且下载失败时，弹框给「去登录 / 继续游客」两个选择，不再只是干巴巴的 toast。

### 2.6 顺带修正
- 下载完成后先过 `SongFileValidator`（EBML/ftyp/TS 包同步 + 时长）再重命名，截断文件与预览片会清理重试，不会把坏文件当成品。

---

## 三、设备实测记录（本次实跑）

```
08:15:43  KtvApplication  启动：i歌霸会话=游客 自动登录=无保存账号
08:15:43  SongSource      丢弃不可信缓存地址 歌曲=搀扶 来源=本地镜像 地址=http://192.168.31.10:8000/220046950.mkv
08:15:43  IgebaApi        GET_SONGADDR 编号=20046950 userId=0（游客）
08:15:43  IgebaApi        取地址成功 编号=20046950 userId=0 rights=0/未登录 voice=0
08:15:43  SongSource      音源=i歌霸 歌曲=搀扶 编号=20046950
08:15:43  SongDownload    i歌霸下载 歌曲=搀扶 目标=220046950.mkv 第1次
08:16:34  IgebaMedia     下载完成 bytes=54234623 total=54234623
08:16:34  SongDownload    i歌霸下载完成 文件=/storage/emulated/0/MaidongKTV/video/cloud-song/220046950.mkv 大小=54234623
```

| 验收项 | 结果 |
|---|---|
| 未登录取地址 | 通过（`Rights.Code=0`，地址可用） |
| 分段下载 | 通过，51.7 MB MKV，约 51 秒下完（≈1 MB/s，游客限速档） |
| 文件校验 | 通过（MKV EBML 头正确，大小与 `Content-Range` 总数一致） |
| 落盘路径 | `/sdcard/MaidongKTV/video/cloud-song/220046950.mkv` |
| 播放 | 通过，歌词与画面正常滚动（见随附截图） |
| 崩溃 | 无（`getNoBackupFilesDir`、`getContentLengthLong` 两个 API 级别坑都已规避） |

接口侧实测（PC 上 curl，用于对照）：

```
GET_SONGADDR UserId=0 → {"Rights":{"Code":0,"Reason":"未登录","RatedSpeed":675840},...,"FileUrl":"http://218.95.37.159:1882/g8file.php?path=/202506/230000188.mkv&rid=…&uid=0","VoiceChannel":"0"}
Range: bytes=0-        → HTTP 403（媒体机拒绝开放区间）
Range: bytes=0-65535   → HTTP 206，content-range: bytes 0-65534/49090742，实收 65535 字节
DO_LOGIN 错误账号      → {"Code":-1,"Reason":"用户不存在",...}（证明请求字段格式被服务端接受）
```

---

## 四、如何拿账号 / 何时该登录

- **不登录也能用**：点歌、下载、播放全部可用，代价是服务端限速（实测约 660 KB/s 起，`RatedSpeed` 由服务端下发）。
- **注册入口**：设置 → 🎧 音源 → i歌霸账号 → 注册账号（手机号 + 短信验证码，`SND_VERIFY` / `DO_REG` 已实现）；也可用 i歌霸官方客户端注册后再来这里登录。
- **登录能改善什么**：服务端会按账号下发更高权限（`Rights.RatedSpeed`、`PlayRights.WithoutAd` 等），游客档被限速且带广告位字段。
- 登录失败先看 `app.log` 里的 `DO_LOGIN … Code/Reason`：
  - `Reason=用户不存在` → 账号没注册成功或不是这个手机号；
  - `Reason=密码错误` → 密码不对；
  - 有 `HTTP …` → 网络/被墙，跟账号无关。

---

## 五、回滚

改动均未提交，全部在工作区：

```powershell
cd E:\ktv
git checkout -- app/src/main/java/com/local/ktv/SongSourceRouter.kt `
  app/src/main/java/com/local/ktv/SongOkDownloadManager.kt `
  app/src/main/java/com/local/ktv/IgebaApiClient.kt `
  app/src/main/java/com/local/ktv/IgebaAccount.kt `
  app/src/main/java/com/local/ktv/KtvApplication.kt `
  app/src/main/java/com/local/ktv/MainActivity.kt
Remove-Item app/src/main/java/com/local/ktv/AppLog.kt, `
  app/src/main/java/com/local/ktv/IgebaMediaDownloader.kt
```

设备侧已下载的样例文件（`220046950.mkv`，51.7 MB）可留作离线曲目，也可直接删。
</content>
