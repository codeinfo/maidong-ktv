# 音源更新说明（参考 2.1.12 → i 歌霸音源）

> 分支：
>
> `android-4.4.4`
>
> （1.1.0 基线，minSdk 19）　|　日期：2026-11-15　|　构建：
>
> `assembleDebug`
>
>  通过



***

## 一、结论

已把 2.1.12 的 **i 歌霸（iGeba）音源**移植到当前 1.1.0 分支，形成三级音源路由：

**i 歌霸（已登录） → 本地镜像 MEDIA\_BASE\_URL → 内置签名接口**

改动 6 个新文件 + 4 个既有文件 + 1 个资源文件，`assembleDebug` 编译打包通过（debug APK 42.8 MB，新增资源压缩后 4.6 MB）。

必须提前知道的两件事（本次实测，不是推测）：



1. **i 歌霸的目录接口（**`cloud.php`**）已经被服务端关掉**，任何 `VER` 值、任何 UA 都返回 `{"Result":-1,"Description":"请使用鸿蒙客户端."}`。所以 **不能**照 2.1.12 那样从线上同步整套曲库，只能继续用本地 Muse 曲库。

2. **地址接口（**`telnet.php`**）是好的**：匿名 `GET_SONGADDR` 返回 `Rights.Code=0 / "未登录"`，空账号 `DO_LOGIN` 返回 `Code=-3 / "用户名不能为空."` —— 协议正常，只差一个登录账号。



***

## 二、为什么不照 2.1.12 整层替换



| 项   | 2.1.12                               | 当前版本（本次做法）                                            |
| --- | ------------------------------------ | ----------------------------------------------------- |
| 曲库  | 内置 `igeba_catalog.db`（81801 首）+ 线上同步 | 保留 Muse 曲库（避免动目录 / 搜索 / UI 一大片）                       |
| 编号  | 每首歌自带 `sourceSongNumber`（8 位）        | 本地库是 **7 位**（如 `7280266.ts`），无编号；改为**按歌名 + 歌手匹配**内置索引 |
| 播放栈 | Media3 + FFmpeg（minSdk 21）           | 不动，仍是 ijk（minSdk 19）                                  |
| 账号  | iGebaAccount + 会话加密存储                | 一并移植（取地址必须登录）                                         |

关键事实：Muse 的编号是 7 位，i 歌霸的编号**全部是 8 位**（81801 首均为 8 位，抽查 `7280266 / 4148483 / 7432478 / 7652241` 在 i 歌霸库里全部不存在），两套编号体系不通用，**只能靠歌名 + 歌手映射**。



***

## 三、改动清单

### 新增源码（`app/src/main/java/com/local/ktv/`，共 941 行）



| 文件                      | 行数  | 作用                                                                              |
| ----------------------- | --- | ------------------------------------------------------------------------------- |
| `IgebaApiClient.kt`     | 99  | 移植 2.1.12，只保留 `GET_SONGADDR` 取地址；补了 `code/msg` 错误字段解析；走 `TlsCompat` 以防日后切 https |
| `IgebaSessionVault.kt`  | 92  | 会话 AES-GCM + AndroidKeyStore RSA 包裹，存 `noBackupFilesDir`；API 19 可用              |
| `IgebaAccount.kt`       | 286 | 登录 / 退出 / 会话持久化 / 失败自动恢复（60 秒限流）+ 短信验证码注册                                       |
| `IgebaAccountDialog.kt` | 170 | 登录 / 注册对话框，纯代码构建视图（不依赖布局资源）                                                     |
| `IgebaSongIndex.kt`     | 169 | 内置编号索引的只读查询 + 归一化匹配 + 命中统计                                                      |
| `SongSourceRouter.kt`   | 125 | 音源路由、地址缓存、失败失效与重取、设置页展示文案                                                       |

### 修改的既有文件



| 文件                         | 改动                                                                                           |
| -------------------------- | -------------------------------------------------------------------------------------------- |
| `Song.kt`                  | 新增 `sourceSongNumber` / `sourceName` 字段并写入 `toJson`/`fromJson`；`getDownloadUrl()` 改走路由       |
| `SongOkDownloadManager.kt` | `buildDownloadUrl()` 改走路由（原来只认镜像 + 旧接口）；i 歌霸来源跳过 TS 解密；重试时调用 `SongSourceRouter.invalidate()` |
| `MainActivity.kt`          | ① 播放取地址改走路由；② 下载改为后台线程现取地址（原先本地曲库条目直接提示「没有下载地址」）；③ 设置菜单新增「🎧 音源」页：账号状态 / 登录、音源顺序、镜像配置、编号匹配统计 |
| `KtvApplication.kt`        | 启动时 `IgebaSongIndex.init()` + `IgebaAccount.initialize()`                                    |

### 新增资源

`app/src/main/assets/igeba_song_index.db`（10.0 MB，压缩后 4.6 MB）

由 2.1.12 内置的 `igeba_catalog.db`（21.5 MB）抽出 `number / name / singer / duration` 并预生成匹配键：



* 81801 首、58383 个不同歌名，同名最多 35 首（因此必须做歌手消歧）

* 归一化规则（生成与查询必须一致）：NFKC（全角→半角）→ 小写 → 去括号内容（第二把键）→ 只保留字母与数字



***

## 四、匹配与回退规则



```
取地址（后台线程）
  ├─ 已登录 i歌霸？
  │    ├─ 歌曲有 sourceSongNumber → 直接取地址
  │    └─ 否则按「歌名 + 歌手」查内置索引
  │         ├─ 歌手键完全一致 → 命中
  │         ├─ 歌手键互相包含 → 命中
  │         ├─ 只有 1 个候选 → 命中
  │         └─ 多个候选且歌手对不上 → 放弃（宁可回退，不放错歌）
  │    → GET_SONGADDR，拿到 http(s) 地址就用
  ├─ 本地镜像 MEDIA_BASE_URL 已配置 → `<base>/<filename>`
  └─ 内置签名接口（旧链路）
```



* 命中结果写回 `Song.sourceSongNumber` / `sourceName`，随已点列表一起持久化。

* 下载失败 / 地址失效时 `invalidate()`：清缓存 + 清旧 token，若上一跳是 i 歌霸且勾选了「自动恢复登录」，先尝试 relogin 再重取。

* 想让「本地镜像永远优先」，把 `SongSourceRouter.IGEBA_FIRST` 改成 `false`（一行）。

**i 歌霸返回的是 MKV 封装**（首字节不是 TS 的 `0x47`），旧的 `TsDecryptor.isEncrypted()` 会把正常文件判成加密并触发解密→失败删除。已按来源跳过 TS 解密；文件仍按原 `filename`（`.ts`）落盘，ijk/MediaPlayer 按内容识别容器，无需改文件名。



***

## 五、怎么用



1. 安装 `app/build/outputs/apk/debug/app-debug.apk`（架构 armeabi-v7a，Android 4.4+）。

2. 设置 → **🎧 音源** → 「i 歌霸账号」→ 登录（可勾选「加密保存密码，允许下载失败时自动恢复登录」）。

3. 回到列表点一首未下载的云端歌 → 播放 / 下载会自动取地址。

4. 看日志确认走了哪条音源（`adb logcat -s SongSource IgebaSongIndex SongDownload`）：

* `音源=i歌霸 歌曲=xxx 编号=30000188`

* `音源=本地镜像 …` / `音源=内置接口 …`

* `i歌霸取地址失败 歌曲=xxx 编号=…：<原因>`（未登录 / 无权限 / 未返回地址）

1. 设置页「i 歌霸编号匹配」显示运行期 `已匹配 N 首 / 未匹配 M 首`，可直接看出你这份曲库的映射覆盖率。



***

## 六、已验证 / 未验证

已验证（本机）：



* `:app:compileDebugKotlin` 通过；`:app:assembleDebug` 通过，APK 内 `assets/igeba_song_index.db` 已正确打包（10,481,664 字节）。

* 索引可用：按 `稀客` 归一化查询返回 3 个候选（杨千嬅 / 陈奕迅 / 赵泳鑫），歌手消歧逻辑成立；带括号歌名的第二把键生效（`2007(鸡精)订做HI歌串烧` → `2007订做hi歌串烧`）。

* 接口连通性：`telnet.php` 的 `GET_SONGADDR` / `DO_LOGIN` 协议字段与 2.1.12 一致且可达；`cloud.php` 目录接口已拒绝非鸿蒙客户端。

未验证（需要在设备上做，我这边没有账号也没有 4.4 设备）：



* 真实账号登录、Cookie 持久化、断网重启后的会话恢复；

* `GET_SONGADDR` 在有效账号下的返回值与权限码；

* MKV 文件在 4.4 的 ijk 上的实际播放（尤其是硬解回退路径）；

* 曲库映射覆盖率 —— 取决于你这份 Muse 曲库的歌名 / 歌手格式，需看设置页统计。

## 七、风险



1. **目录接口被限**是硬约束：新歌、新编号无法从线上同步，索引停留在 2.1.12 那份快照（2026-11-12 前后）。若服务端恢复或改协议，只需替换 `assets/igeba_song_index.db`。

2. **映射靠歌名**：Muse 与 i 歌霸的命名差异（别名、繁体、加「(Live)」、歌手署名不同）会造成漏匹配；漏匹配只是回退到镜像 / 旧接口，不会放错歌（多候选且歌手不符时直接放弃）。

3. **账号权益**：`Rights.Code` 非<u> 0/1 时地址无效，表现为回退；</u>`RatedSpeed`<u>（匿名样本 675840）提示有速率限制，大批量下载可能受限</u>。

4. 曲库侧文件名仍按 `.ts` 落盘，若后续切到 Media3（minSdk 21）需同步改扩展名与解码路径。

## 八、回滚



```
cd E:\ktv
# 仅回滚本次音源改动（你的 4.4 WIP 不受影响）
git checkout -- app/src/main/java/com/local/ktv/Song.kt `
  app/src/main/java/com/local/ktv/SongOkDownloadManager.kt `
  app/src/main/java/com/local/ktv/MainActivity.kt `
  app/src/main/java/com/local/ktv/KtvApplication.kt
Remove-Item app/src/main/java/com/local/ktv/Igeba*.kt, app/src/main/java/com/local/ktv/SongSourceRouter.kt
Remove-Item app/src/main/assets/igeba_song_index.db
```

（本次改动未提交，全部留在工作区，便于你先审再决定。）