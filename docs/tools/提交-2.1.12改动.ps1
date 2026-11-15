# 一键提交本次 2.1.12 对齐改动（i歌霸音源/曲库 + P0/P1 模块 + 歌手头像库 + TV 交互控件 + 设置页头像清理）
# 用法：在仓库根目录（E:\ktv）执行
#   powershell -ExecutionPolicy Bypass -File docs\tools\提交-2.1.12改动.ps1
# 说明：只 add 下面显式列出的文件，不会把你尚未提交的其它 WIP 一起带进来。
# 前置：本机需要可用的 git（当前这台机器 PATH 里没有 git，可在装有 git 的环境执行本脚本）。

$ErrorActionPreference = 'Stop'
if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    Write-Host '未找到 git。请先在装有 git 的环境执行，或安装 Git for Windows 后重试。'
    exit 1
}

$files = @(
  'app/src/main/java/com/local/ktv/IgebaApiClient.kt',
  'app/src/main/java/com/local/ktv/IgebaSessionVault.kt',
  'app/src/main/java/com/local/ktv/IgebaAccount.kt',
  'app/src/main/java/com/local/ktv/IgebaAccountDialog.kt',
  'app/src/main/java/com/local/ktv/IgebaCatalog.kt',
  'app/src/main/java/com/local/ktv/SongSourceRouter.kt',
  'app/src/main/java/com/local/ktv/SongFileValidator.kt',
  'app/src/main/java/com/local/ktv/AppLog.kt',
  'app/src/main/java/com/local/ktv/IgebaMediaDownloader.kt',
  'app/src/main/java/com/local/ktv/DownloadSpaceBudget.kt',
  'app/src/main/java/com/local/ktv/IgebaDownloadRecovery.kt',
  'app/src/main/java/com/local/ktv/OwnedSongCacheCleaner.kt',
  'app/src/main/java/com/local/ktv/LegacyStorageUpgrade.kt',
  'app/src/main/java/com/local/ktv/SongSearchTokens.kt',
  'app/src/main/java/com/local/ktv/SingerPortraitStore.kt',
  'app/src/main/java/com/local/ktv/SingerAvatarLoader.kt',
  'app/src/main/java/com/local/ktv/TvListNavigation.kt',
  'app/src/main/java/com/local/ktv/CircularVolumeView.kt',
  'app/src/main/java/com/local/ktv/LoopingStatusTextView.kt',
  'app/src/main/java/com/local/ktv/TvUiScale.kt',
  'app/src/main/java/com/local/ktv/Song.kt',
  'app/src/main/java/com/local/ktv/SongOkDownloadManager.kt',
  'app/src/main/java/com/local/ktv/MainActivity.kt',
  'app/src/main/java/com/local/ktv/KtvApplication.kt',
  'app/src/main/java/com/local/ktv/SongLibrary.kt',
  'app/src/main/java/com/local/ktv/AppPaths.kt',
  'app/src/main/java/com/local/ktv/SingerGridAdapter.kt',
  'app/src/main/java/com/local/ktv/TvFocusStyler.kt',
  'app/src/main/java/com/local/ktv/SettingsListAdapter.kt',
  'app/src/main/res/layout/activity_main.xml',
  'app/src/main/res/layout/dialog_tuning.xml',
  'app/src/main/assets/igeba_catalog.db',
  'docs/analysis/2.1.12差异对照与补充清单.md',
  'docs/features/音源更新说明-i歌霸.md',
  'docs/features/i歌霸登录与下载打通说明.md',
  'docs/features/发布构建与签名.md'
)

foreach ($f in $files) {
    if (-not (Test-Path -LiteralPath $f)) { Write-Host "缺少文件：$f"; exit 1 }
}

git add -- $files
git commit -m 'feat(ige8): 对齐 2.1.12 —— i歌霸音源与曲库、P0/P1 补齐、歌手头像库、TV 交互控件' `
           -m '音源: IgebaApiClient/IgebaSessionVault/IgebaAccount(+Dialog)/SongSourceRouter，UA=Indy Library；下载改自研分段。' `
           -m '曲库: 内置 igeba_catalog.db(81801 首/4807 歌手)，首启解压到 /sdcard/MaidongKTV/database。' `
           -m '补齐: DownloadSpaceBudget / IgebaDownloadRecovery / OwnedSongCacheCleaner / LegacyStorageUpgrade / SongFileValidator / SongSearchTokens。' `
           -m 'UI: SingerPortraitStore+SingerAvatarLoader(头像库，设置页可清理)、TvListView/TvListNavigation、CircularVolumeView、LoopingStatusTextView、TvUiScale。'

if ($LASTEXITCODE -ne 0) { Write-Host '提交失败，请检查 git 输出。'; exit $LASTEXITCODE }
git --no-pager log -1 --stat