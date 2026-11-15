# KTV 真机验证报告

> 生成时间：2026-11-15 13:31:42　|　耗时约 3.4 分钟
> 结论：**通过**

## 环境

| 项 | 值 |
| --- | --- |
| 设备 | NX507J |
| 变体 | debug（com.local.ktv.qa） |
| APK | app-debug.apk　44.74 MB |
| 屏幕 | Physical size: 1080x1920；Physical density: 480 |
| 包信息 | versionCode=3 targetSdk=25 |

## 页面检查

| # | 页面 | 截图 | qa_open | 崩溃 | 备注 |
| --- | --- | --- | --- | --- | --- |
| 1 | home | [查看](shots/01-home.png) | — | 无 | 正常 |
| 2 | singers | [查看](shots/02-singers.png) | 命中 | 无 | 正常 |
| 3 | local | [查看](shots/03-local.png) | 命中 | 无 | 正常 |
| 4 | settings | [查看](shots/04-settings.png) | 命中 | 无 | 正常 |
| 5 | tuning | [查看](shots/05-tuning.png) | 命中 | 无 | 正常 |
| 6 | fullscreen | [查看](shots/06-fullscreen.png) | 命中 | 无 | 正常 |

## 焦点日志（KeyTrace 末 4 行 / 页）

### home

```
(本页无 KeyTrace 记录)
```

### singers

```
11-15 13:33:29.632 I/KeyTrace: qa_open 已打开 singers (delay=1500)
11-15 13:33:31.841 I/KeyTrace: qa_open 已打开 singers (delay=1500)
11-15 13:33:35.128 I/KeyTrace: qa_open 已打开 singers (delay=7000)
11-15 13:33:37.346 I/KeyTrace: qa_open 已打开 singers (delay=7000)
```

### local

```
11-15 13:33:50.980 I/KeyTrace: qa_open 已打开 local (delay=1500)
11-15 13:33:53.147 I/KeyTrace: qa_open 已打开 local (delay=1500)
11-15 13:33:56.487 I/KeyTrace: qa_open 已打开 local (delay=7000)
11-15 13:33:58.656 I/KeyTrace: qa_open 已打开 local (delay=7000)
```

### settings

```
11-15 13:34:12.257 I/KeyTrace: qa_open 已打开 settings (delay=1500)
11-15 13:34:14.386 I/KeyTrace: qa_open 已打开 settings (delay=1500)
11-15 13:34:17.758 I/KeyTrace: qa_open 已打开 settings (delay=7000)
11-15 13:34:19.887 I/KeyTrace: qa_open 已打开 settings (delay=7000)
```

### tuning

```
11-15 13:34:33.272 I/KeyTrace: qa_open 已打开 tuning (delay=1500)
11-15 13:34:35.378 I/KeyTrace: qa_open 已打开 tuning (delay=1500)
11-15 13:34:38.745 I/KeyTrace: qa_open 已打开 tuning (delay=7000)
11-15 13:34:40.879 I/KeyTrace: qa_open 已打开 tuning (delay=7000)
```

### fullscreen

```
11-15 13:34:53.857 I/KeyTrace: qa_open 已打开 fullscreen (delay=1500)
11-15 13:34:55.999 I/KeyTrace: qa_open 已打开 fullscreen (delay=1500)
11-15 13:34:59.316 I/KeyTrace: qa_open 已打开 fullscreen (delay=7000)
11-15 13:35:01.499 I/KeyTrace: qa_open 已打开 fullscreen (delay=7000)
```

