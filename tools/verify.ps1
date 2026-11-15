<#
KTV 4.4 真机验证流水线

一条命令完成「构建 → 装机 → 唤醒解锁 → 逐页截图 → 日志断言 → 出报告」。

用法（在仓库根目录或任意位置执行）：
    pwsh -File tools\verify.ps1                       # debug 包，跑默认页面集合
    pwsh -File tools\verify.ps1 -Pages home,singers   # 只跑指定页
    pwsh -File tools\verify.ps1 -Variant release      # 验证正式包（无 qa_open，只跑首页）
    pwsh -File tools\verify.ps1 -SkipBuild            # 复用现有 APK，只跑设备侧

依赖：adb（E:\Android\Sdk 或 ANDROID_HOME）、JDK 17、真机已连 adb。
退出码：0 全部通过 / 1 有失败项 / 2 环境缺失。
#>
param(
    [string]$Serial = "NX507J",
    [ValidateSet("debug", "release")][string]$Variant = "debug",
    [string]$Pages = "home,singers,local,settings,tuning",
    [switch]$SkipBuild,
    [int]$SettleSeconds = 8,
    [string]$OutDir = ""
)

$ErrorActionPreference = "Continue"
$Repo = Split-Path -Parent $PSScriptRoot

$Adb = "E:\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path -LiteralPath $Adb)) {
    $sdkRoot = $env:ANDROID_HOME
    if (-not $sdkRoot) { $sdkRoot = $env:ANDROID_SDK_ROOT }
    if ($sdkRoot) { $Adb = Join-Path $sdkRoot "platform-tools\adb.exe" }
}
if (-not (Test-Path -LiteralPath $Adb)) {
    Write-Output "[ABORT] 找不到 adb，请确认 E:\Android\Sdk 或 ANDROID_HOME"
    exit 2
}

$javaHome = $env:JAVA_HOME
if (-not $javaHome -or -not (Test-Path -LiteralPath $javaHome)) {
    $javaHome = "C:\Users\nanye\tools\jdk17\jdk-17.0.21+9"
}
if (-not (Test-Path -LiteralPath $javaHome)) {
    Write-Output "[ABORT] 找不到 JDK 17"
    exit 2
}
$env:JAVA_HOME = $javaHome
$env:PATH = (Join-Path $javaHome "bin") + ";" + $env:PATH

$Pkg = "com.local.ktv"
if ($Variant -eq "debug") { $Pkg = "com.local.ktv.qa" }
$Apk = Join-Path $Repo ("app\build\outputs\apk\" + $Variant + "\app-" + $Variant + ".apk")

$pageList = @($Pages -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne "" })

if ($Variant -eq "release" -and $pageList.Count -gt 1) {
    Write-Output "[WARN] 正式包不响应 qa_open（BuildConfig.DEBUG 为 false），只跑首页"
    $pageList = @("home")
}

if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path $Repo ("docs\reports\verify\" + (Get-Date -Format "yyyyMMdd-HHmmss"))
}
$ShotsDir = Join-Path $OutDir "shots"
New-Item -ItemType Directory -Force -Path $ShotsDir | Out-Null

$failures = New-Object System.Collections.ArrayList
$pageResults = New-Object System.Collections.ArrayList
$homeHash = ""
$seenHashes = @{}
$startedAt = Get-Date

function AdbShell([string]$Command) {
    return ((& $Adb -s $Serial shell $Command 2>&1) | Out-String).Trim()
}

Write-Output "==== KTV 验证流水线 ===="
Write-Output ("仓库   : " + $Repo)
Write-Output ("设备   : " + $Serial + " / " + $Variant + " / " + $Pkg)
Write-Output ("输出   : " + $OutDir)

# ---------- 1. 设备在线检查 ----------
$devices = (& $Adb devices 2>&1) -join "`n"
if ($devices -notmatch [regex]::Escape("$Serial`tdevice")) {
    Write-Output "[FAIL] 设备 $Serial 未就绪：`n$devices"
    exit 2
}
Write-Output "[OK] 设备已连接"

# ---------- 2. 构建 ----------
if (-not $SkipBuild) {
    $task = "assembleDebug"
    if ($Variant -eq "release") { $task = "assembleRelease" }
    Write-Output ("[..] 构建 " + $task)
    Push-Location $Repo
    $buildLog = (& ".\gradlew.bat" $task --no-daemon --console=plain "-Dkotlin.compiler.execution.strategy=in-process" 2>&1) | Out-String
    $buildCode = $LASTEXITCODE
    Pop-Location
    if ($buildCode -ne 0) {
        $buildLog -split "`n" | Select-Object -Last 25 | ForEach-Object { Write-Output ("      " + $_) }
        Write-Output "[FAIL] 构建失败"
        exit 1
    }
    $tail = ($buildLog -split "`n" | Where-Object { $_ -match "BUILD SUCCESSFUL" })
    Write-Output ("[OK] " + ($tail | Select-Object -Last 1).Trim())
}
if (-not (Test-Path -LiteralPath $Apk)) {
    Write-Output ("[FAIL] 没有产物：" + $Apk)
    exit 1
}
$apkMB = [math]::Round((Get-Item -LiteralPath $Apk).Length / 1MB, 2)
Write-Output ("[OK] 产物 " + $apkMB + " MB")

# ---------- 3. 装机 ----------
Write-Output "[..] 安装"
$installOut = ((& $Adb -s $Serial install -r $Apk 2>&1) | Out-String).Trim()
if ($installOut -match "Success") {
    Write-Output "[OK] 安装成功"
} else {
    Write-Output ("[FAIL] 安装失败：" + $installOut)
    $failures.Add("安装失败：" + $installOut) | Out-Null
    exit 1
}

# ---------- 4. 唤醒 / 解锁护栏 ----------
$focus = AdbShell "dumpsys window windows | grep -a -E 'mCurrentFocus'"
if ($focus -match "Keyguard") {
    Write-Output "[..] 检测到锁屏，尝试唤醒"
    AdbShell "input keyevent 224" | Out-Null
    Start-Sleep -Milliseconds 900
    AdbShell "input keyevent 82" | Out-Null
    AdbShell "input swipe 540 1800 540 700" | Out-Null
    Start-Sleep -Seconds 1
    $focus = AdbShell "dumpsys window windows | grep -a -E 'mCurrentFocus'"
    if ($focus -match "Keyguard") {
        Write-Output "[FAIL] 仍处于锁屏（可能设置了密码），请在设备上手动解锁后重跑"
        exit 1
    }
}
Write-Output "[OK] 屏幕已解锁"

# ---------- 5. 设备信息 ----------
$wmSize = AdbShell "wm size"
$wmDensity = AdbShell "wm density"
$pkgLine = (AdbShell ("dumpsys package " + $Pkg + " | grep -a -E 'versionName|versionCode'")) -split "`n" | Select-Object -First 2
$deviceInfo = (($wmSize + "`n" + $wmDensity).Trim())
Write-Output ("[OK] 屏幕：" + (($wmSize -split "`n") -join " | "))

# ---------- 6. 逐页截图与断言 ----------
$index = 0
foreach ($page in $pageList) {
    $index = $index + 1
    Write-Output ("[..] 页面 " + $page)

    AdbShell ("am force-stop " + $Pkg) | Out-Null
    AdbShell "logcat -c" | Out-Null

    $pageStart = (AdbShell "date +%m-%d_%H:%M:%S").Trim()
    $pageStart = $pageStart.Replace("_", " ")
    if ($page -eq "home") {
        AdbShell ("am start -n " + $Pkg + "/com.local.ktv.MainActivity") | Out-Null
    } else {
        AdbShell ("am start -n " + $Pkg + "/com.local.ktv.MainActivity --es qa_open " + $page) | Out-Null
    }
    Start-Sleep -Seconds $SettleSeconds

    $shotName = ("{0:00}-{1}.png" -f $index, $page)
    $shotPath = Join-Path $ShotsDir $shotName
    AdbShell "screencap -p /sdcard/_verify_shot.png" | Out-Null
    $pullOut = ((& $Adb -s $Serial pull /sdcard/_verify_shot.png $shotPath 2>&1) | Out-String).Trim()
    AdbShell "rm /sdcard/_verify_shot.png" | Out-Null

    $shotOK = $false
    $shotHash = ""
    $shotSize = ""
    if (Test-Path -LiteralPath $shotPath) {
        $bytes = [IO.File]::ReadAllBytes($shotPath)
        $isPng = ($bytes.Length -ge 24) -and ($bytes[1] -eq 0x50) -and ($bytes[2] -eq 0x4E) -and ($bytes[3] -eq 0x47)
        $w = 0
        $h = 0
        if ($isPng) {
            $w = ([int]$bytes[16] * 16777216) + ([int]$bytes[17] * 65536) + ([int]$bytes[18] * 256) + [int]$bytes[19]
            $h = ([int]$bytes[20] * 16777216) + ([int]$bytes[21] * 65536) + ([int]$bytes[22] * 256) + [int]$bytes[23]
            $shotSize = ([string]$w + "x" + [string]$h)
        }
        # 黑屏（全屏播放器）压缩后很小，不能按体积判断，按 PNG 尺寸判断
        if ($isPng -and $w -ge 640 -and $h -ge 480 -and $bytes.Length -gt 5KB) {
            $shotOK = $true
            $shotHash = (Get-FileHash -LiteralPath $shotPath -Algorithm MD5).Hash
        }
    }

    $crashLog = AdbShell ("logcat -d -t 80 | grep -a -E 'FATAL EXCEPTION|AndroidRuntime'")
    $hasCrash = $false
    if ($crashLog -and ($crashLog -match "FATAL EXCEPTION")) { $hasCrash = $true }

    # 只取本页启动之后产生的日志（按设备时间戳裁剪，避免串入上一页）
    $traceRaw = AdbShell "grep -a KeyTrace /sdcard/MaidongKTV/logs/app.log | tail -40"
    $traceLines = @($traceRaw -split "`n" |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_.Length -ge 14 -and $_.Substring(0, 14) -ge $pageStart })
    $traceText = ($traceLines -join "`n")

    $qaOK = $null
    if ($page -ne "home" -and $Variant -eq "debug") {
        $qaOK = $false
        foreach ($line in $traceLines) {
            if ($line -match ("qa_open 已打开 " + [regex]::Escape($page) + " ")) { $qaOK = $true }
        }
    }

    $note = ""
    if (-not $shotOK) { $note = $note + "截图缺失或过小；" }
    if ($hasCrash) { $note = $note + "检测到 FATAL；" }
    if ($qaOK -eq $false) { $note = $note + "qa_open 未生效；" }
    if ($page -ne "home" -and $homeHash -ne "" -and $shotHash -eq $homeHash) {
        $note = $note + "截图与首页完全一致（页面未真正切换）；"
    }
    if ($shotHash -ne "" -and $seenHashes.ContainsKey($shotHash)) {
        $samePage = $seenHashes[$shotHash]
        if ($samePage -ne $page) {
            $note = $note + ("截图与 " + $samePage + " 页完全相同（未切换）；")
        }
    }
    if ($shotHash -ne "" -and -not $seenHashes.ContainsKey($shotHash)) {
        $seenHashes[$shotHash] = $page
    }
    if ($note -eq "") { $note = "正常" } else { $failures.Add(($page + "：" + $note)) | Out-Null }

    if ($page -eq "home" -and $shotOK) { $homeHash = $shotHash }

    if ($traceText -eq "") { $traceText = "(本页无 KeyTrace 记录)" }

    $pageResults.Add([pscustomobject]@{
        Page = $page
        Shot = $shotName
        ShotOK = $shotOK
        Size = $shotSize
        Crash = $hasCrash
        QaOpen = $qaOK
        Note = $note
        Trace = $traceText
    }) | Out-Null

    if ($note -eq "正常") { Write-Output ("[OK] " + $page) } else { Write-Output ("[FAIL] " + $page + "：" + $note) }
}

# ---------- 7. 报告 ----------
$pass = ($failures.Count -eq 0)
$verdict = "未通过"
if ($pass) { $verdict = "通过" }

$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine("# KTV 真机验证报告")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("> 生成时间：" + $startedAt.ToString("yyyy-MM-dd HH:mm:ss") + "　|　耗时约 " + [math]::Round(((Get-Date) - $startedAt).TotalMinutes, 1) + " 分钟")
[void]$sb.AppendLine("> 结论：**" + $verdict + "**" + $(if ($pass) { "" } else { "（失败 " + $failures.Count + " 项）" }))
[void]$sb.AppendLine("")
[void]$sb.AppendLine("## 环境")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("| 项 | 值 |")
[void]$sb.AppendLine("| --- | --- |")
[void]$sb.AppendLine("| 设备 | " + $Serial + " |")
[void]$sb.AppendLine("| 变体 | " + $Variant + "（" + $Pkg + "） |")
[void]$sb.AppendLine("| APK | " + (Split-Path -Leaf $Apk) + "　" + $apkMB + " MB |")
[void]$sb.AppendLine("| 屏幕 | " + (($deviceInfo -replace "`n", "；")) + " |")
foreach ($pl in $pkgLine) {
    if ($pl.Trim()) { [void]$sb.AppendLine("| 包信息 | " + $pl.Trim() + " |") }
}
[void]$sb.AppendLine("")
[void]$sb.AppendLine("## 页面检查")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("| # | 页面 | 截图 | qa_open | 崩溃 | 备注 |")
[void]$sb.AppendLine("| --- | --- | --- | --- | --- | --- |")
$n = 0
foreach ($r in $pageResults) {
    $n = $n + 1
    $shotCell = "缺失"
    if ($r.ShotOK) { $shotCell = "[查看](shots/" + $r.Shot + ")" }
    $qaCell = "—"
    if ($null -ne $r.QaOpen) {
        $qaCell = "未命中"
        if ($r.QaOpen) { $qaCell = "命中" }
    }
    $crashCell = "无"
    if ($r.Crash) { $crashCell = "有" }
    [void]$sb.AppendLine("| " + $n + " | " + $r.Page + " | " + $shotCell + " | " + $qaCell + " | " + $crashCell + " | " + $r.Note + " |")
}
[void]$sb.AppendLine("")
[void]$sb.AppendLine("## 焦点日志（KeyTrace 末 4 行 / 页）")
[void]$sb.AppendLine("")
foreach ($r in $pageResults) {
    [void]$sb.AppendLine("### " + $r.Page)
    [void]$sb.AppendLine("")
    [void]$sb.AppendLine('```')
    [void]$sb.AppendLine($r.Trace.Trim())
    [void]$sb.AppendLine('```')
    [void]$sb.AppendLine("")
}
if (-not $pass) {
    [void]$sb.AppendLine("## 失败项")
    [void]$sb.AppendLine("")
    foreach ($f in $failures) { [void]$sb.AppendLine("- " + $f) }
    [void]$sb.AppendLine("")
}

$reportPath = Join-Path $OutDir "verify-report.md"
[IO.File]::WriteAllText($reportPath, $sb.ToString(), (New-Object System.Text.UTF8Encoding $false))
Write-Output ("[OK] 报告 " + $reportPath)

if ($pass) {
    Write-Output "==== 全部通过 ===="
    exit 0
} else {
    Write-Output ("==== 有失败项：" + $failures.Count + " ====")
    exit 1
}
