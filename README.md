# 红果桌面版 · 本地维护分支

红果短剧电脑版 / 红果桌面版（基线 **1.0.9**）的本地二次维护分支。
发布与反馈入口：**https://github.com/dddmiku/hongguo-desktop-releases**

本分支是上游安装包的**二次维护**，不依赖上游源码：直接对已安装的 exe 与后端
重新注入补丁，**上游每次发版后都能自动重新注入**。

当前基线：**1.0.9**（原版 exe sha256 `be60068affc6f6da…`）。

## 新增能力

| 功能 | 说明 |
| --- | --- |
| 3 倍速 | 倍速档位扩展为 `0.75 / 1 / 1.25 / 1.5 / 2 / 2.5 / 3` |
| 清晰度可选 | 工具栏下拉：`自动（最高）/ 1080p / 720p / 540p / 480p` |
| 清晰度提速 | 已转码过的档位再次切换约 **1.5 秒**（首次约 10 秒，约 7 倍） |
| 键盘快捷键 | 见下表 |
| 空格键修复 | 点过按钮后按空格不再重复触发该按钮 |
| 切集不再失败 | 连续切下一集不再出现「媒体准备失败 / 播放中断」（见下） |

## 键盘快捷键

| 按键 | 功能 |
| --- | --- |
| `空格` / `K` | 播放 / 暂停 |
| `←` / `→` | 后退 / 前进 5 秒 |
| `<` `,` / `>` `.` | 后退 / 前进 10 秒 |
| `PageUp` / `PageDown` | 后退 / 前进 60 秒 |
| `Home` / `End` | 片头 / 片尾 |
| `0`–`9` | 按百分比跳转（`5` = 50%） |
| `↑` / `↓` | 音量 +/− |
| `[` / `]` | 降低 / 提高倍速 |
| `M` / `D` | 静音开关 |
| `J` / `N` | 下一集 |
| `F` / `T` | 全屏 / 剧场模式 |
| `S` | 循环播放开关 |

## 跳集「媒体准备失败」的修复

**症状**：点「下一集」后画面停在
`播放中断，请重试当前集。／第 N 集 · 媒体准备失败`，重试也未必好。

**根因（已复现）**：上游编码池在槽位占满时**立刻**返回 503
（`Desktop encoder is busy`，默认只有 2 个 worker）。切集瞬间旧任务
仍在收尾，新一集于是必吃 503；前端只在 200 ms 后重试一次，仍然失败。

**修法**：

| 层 | 改动 |
| --- | --- |
| `desktop_hls_service.py` | 编码池改为**有界排队**（`queue_wait=6s`）；把「已取消、尚未收尾」的任务排除在占用之外；`max_jobs` 4→8、`max_workers` 2→3 |
| `desktop_hls_service.py` | 把取消信号透传给取源/转码，切集时旧任务立刻停下 |
| `desktop_encode.py` | `encode_h264(source, cancelled=None)`，转码中途可取消 |
| `server.py` | `_desktop_source(...)` 继续把取消信号传给 `encode_h264` |
| `app.js` | 开场 POST 改为最多 10 次、指数退避重试（仅在 503 且未中止时） |
| `tools/repack.py` | 打包时把 `index.html` 的资源引用**对齐到 exe 内真实键名**（上游换哈希文件名会导致白屏） |
| `tools/deploy.ps1` `restore.ps1` `auto_patch.py` | 同步/备份清单补齐 `desktop_encode.py`（此前漏同步，也会导致「媒体准备失败」） |

**实测**（1.0.9，真实进程）：

| 场景 | 结果 |
| --- | --- |
| 连续 POST 5 次切集（不释放旧会话） | 5 成功 / **0 个 503**，五个会话全部 `complete` |
| 应用内连续点「下一集」8 次 | 14 个请求 **0 个 4xx/5xx**，全程无中断提示 |
| 基线（上游原版）同样测试 | 第 3 个起即 503 → 复现故障 |

## 自动连播时不再弹出控制栏 / 鼠标

**症状**：自动播到下一集时，下方控制栏会闪一下弹出来，鼠标光标也跟着出现。

**根因**：控制栏有 2.5 秒自动隐藏机制，但每次新一集开始播放都会被强制
设为可见：

```
新一集 playing -> z(!0)   // 强制可见
2.5s 后        -> z(!1)   // 才隐藏
```

即使手没碰鼠标，这次 `playing` 也会触发，所以每集切换都会弹一下。

**有两条弹出路径，都要挡**（第一次只挡了一条，所以没修好）：

| 路径 | 触发时机 |
| --- | --- |
| 新一集首次 `playing` | 播放器无条件把控制栏设为可见 |
| **旧一集 `pause` / `ended`** | 同一处理链里也会设为可见 |

实测时序（修复前）：

```
22:01:35 media:pause   | 81.29/81.29
22:01:35 media:ended   | 0.00/?
22:01:35 chrome | player-chrome visible   <-- 这里就弹了（第二条路径）
22:01:38 chrome | player-chrome
22:01:40 chrome | player-chrome visible   <-- 又一次
```

**修法**：自动连播时置一个标志位，两条路径都跳过；
进入正常隐藏周期或用户主动操作即清除。
用标志位而不是定时窗口，因为切集耗时不确定（要下载/转码）。

手动操作（点视频、按空格、拖进度、按方向键）**照常**显示控制栏。

实测（修复后，用户自然播放、无任何人工干预）：

```
22:05:28 media:pause   | 67.69/67.69
22:05:28 media:ended   | 0.00/?
22:05:28 media:emptied                  <-- 没有 chrome visible
22:05:46 media:pause   | 54.29/54.29
22:05:46 media:ended   | 0.00/?
22:05:46 media:emptied                  <-- 又没有
```

连续两次自动连播，控制栏均未弹出。

## 缓存自动清理（不再无限膨胀）

**症状**：`%APPDATA%\cn.guoban.desktop-companion\stream-cache` 一直涨。
实测 **45.35 GB / 903 个 1080p 文件**；更早一次实测是 28.82 GB / 243 个，
确认持续膨胀。

**根因**：上游后端只写不清。每看一集，永久留下两份文件：

| 文件 | 内容 |
| --- | --- |
| `<vid>_<档位>.mp4` | 解密后的源（HEVC） |
| `<vid>_<档位>.desktop-h264-v1.mp4` | 转码产物（给 WebView2 播） |

**三层清理**：

| 层 | 做法 | 作用 |
| --- | --- | --- |
| 看完即删 | 播放器 `onEnded` 调 `/desktop/cleanup?series_id=&ep=` | 主要手段，按 vid 删该集两份文件 |
| 数量上限 | 每次取源后只保留最近 8 个缓存文件 | 兜底，异常退出也不失控 |
| 字节上限 | `HONGGUO_CACHE_MAX_BYTES`（默认关闭） | 可选 |

> 播放器一次只看一集、预取最多提前 2 集，所以被淘汰的必然是看过的集数。
> 清理**只删匹配 `*.mp4` 的普通文件**，不扫目录、不删目录。

**实测**（确定性验证）：

```
BASELINE  假缓存已建: 7693523524815686680_1080p.desktop-h264-v1.mp4 4096 bytes
MODIFIED  接口返回: {"removed":1}
MODIFIED  假缓存已删: True
[PASS]
```

前端真实调用（浏览器性能记录，用户正常观看时自然产生）：

```
hls?series_id=7693487608646618174&ep=4&quality=1080p
cleanup?series_id=7693487608646618174&ep=2     <-- 第 2 集播完自动触发
hls?series_id=7693487608646618174&ep=5&quality=1080p
```

连续观看期间缓存稳定在 **6 个文件（3 集 × 2）**，不再随集数增长。

## 验证与回滚

```powershell
python tools/smoke_test.py           # 冒烟：语法 / import / 注入点 / 补丁链幂等（41 项）
python tools/smoke_test.py --live    # 上面 + 安装目录一致性
python tools/rebrand_exe.py <exe> --check   # 内嵌署名是否已换成本分支
python verify/repro/pool_test.py     # 编码池：BASELINE vs MODIFIED
python verify/repro/pool_three.py    # 三态：BASELINE / MODIFIED / ROLLBACK
bash verify/ROLLBACK.sh --all <应用目录>   # 用内嵌基线还原后端
```

`tools/smoke_test.py` 是 2026-10-07 一次事故的产物：当时把清扫函数放在文件末尾、
调用放在中部，`server.py` 一导入就 `NameError`，后端直接起不来，而补丁脚本自己
「成功」退出。现在它把这类问题变成一条自动断言。

它还有一项**补丁链幂等**检查（`4b`）：对已含全部步骤的 `server.py` 再跑一遍
补丁函数，文件必须逐字节不变，且 `auto_patch._fully_patched()` 必须认可它是完整的。
这一项防的是「新增补丁步骤对已部署文件永久不生效」——见下。

`verify/VERIFICATION.txt` 记录完整证据（命令、原始输出、哈希、未覆盖项）。
`verify/ROLLBACK.sh` 自带 base64 内嵌基线，即使仓库被裁剪也能还原。

## 补丁链必须逐步幂等（重要）

补丁函数与 `tools/auto_patch.py` 都**不能**用「整文件是否已含某标记」来决定跳过。

2026-10-07 加了「删除上游重复 `/img` 死代码」这一步，但安装目录的 `server.py`
早就含 `encode_h264(decrypted` 与 `_hq_cleanup_episode`，旧判据直接 `return` ——
死代码永久留在线上，`smoke_test --live` 一直报「不一致」，而工具还报「已打补丁」。

现在的规则：

| 位置 | 判据 |
| --- | --- |
| `patch_server` 等 | **每个步骤用各自的标记**判断是否需要执行，互不牵连 |
| `auto_patch._fully_patched()` | **全部**步骤就位才允许跳过；产出无变化时区分「已全就位（正常跳过）」与「上游结构变了（真失败）」 |
| `patch_live_backend` 末尾 | 单独重建上游基线里**没有**的模块（`desktop_account*.py`），逐文件循环覆盖不到它们 |

另外两个坑：

- `patch_backend.main()` 会把 `_v109/extracted/backend` 的 `.txt` 原样拷进 `src`，
  所以改 `requirements-windows.txt` 这类文件**必须做成补丁步骤**，只改 `src` 会被覆盖。
- `sub_once` 的 repl 要传 `lambda m: repl`，否则注入含 `\w` 的代码会报 `bad escape`。

## 历史同步的行为约定（2026-10-07 定）

| 场景 | 行为 |
| --- | --- |
| 多端进度 | **取更靠前的**：手机 520 / PC 500，合并后显示 520（本机条目就地抬高） |
| 进历史页 / 切回前台 | 自动拉云端并合并，不需要手动点「历史」 |
| 手机端条目很多 | 每轮只并进最新的一批（上限 120），已并入的会落盘，下轮自然轮到下一批 |
| 合并结果 | 同时写进 React 状态与本机 sqlite（`hqPersistMerged`），重启不丢 |
| 换号 | 按条目级 `fromUid` 隔离：本机原有记录保留，上一个号带来的记录丢弃 |
| 进度上报 | 成功/失败都写 `account-log.jsonl`（`progress_ok` / `progress_failed`），便于事后排查 |

## 上游更新后如何自动重新注入

```powershell
python tools/auto_patch.py status    # 查看当前 exe 是否为补丁版本
python tools/auto_patch.py apply     # 检测到原版则自动重新注入
python tools/auto_patch.py install   # 注册登录自启动（Windows 计划任务）
python tools/auto_patch.py uninstall # 取消自启动
```

`apply` 的流程：

> **后端补丁是对「安装目录里的现有文件」打，不是用仓库里的旧基线覆盖。**
> 上游发新版时后端也会更新；若用旧基线覆盖等于把后端降级。
> 每个被改动的文件会留下 `<文件名>.orig-<哈希>` 备份便于回滚。
> 已含补丁的文件自动跳过（幂等）。

1. 比对 exe 哈希与 `state.json` 记录；
2. 若已打补丁则跳过；若检测到原版（上游更新或重装）则继续；
3. **直接从 exe 内嵌资源取出上游原版前端**（无需重新下载安装包）；
4. 套用 `patches/patch_frontend.py`（版本无关，自动探测变量名）；
5. 压回 exe 内嵌资源表；
6. **重打署名**（`tools/rebrand_exe.py`）：把内嵌 `tauri.conf.json` 里的
   `author` 段与更新端点换成本分支自己的仓库；
7. 套用 `patches/patch_backend.py` 到后端；
8. 原子替换，并在 `_backup/` 留下原始备份。

### 署名重打标（为什么单独一步）

上游的仓库 / 邮箱 / B 站地址写在**两处**，两处都要换：

| 位置 | 形态 | 处理方式 |
| --- | --- | --- |
| 前端 `app.js` 的 `kR`/`MR`/`OR` | Brotli 压缩的内嵌资源 | `patches/patch_frontend.py` 第 16 步（跟着资源一起压回去） |
| 内嵌 `tauri.conf.json` 的 `author` 段 + 更新端点 | 编译进 `.rdata` 的字符串字面量 | `tools/rebrand_exe.py` **等长原地覆盖** |

第二处不能用资源那套改：它不是资源、也没有绝对指针引用，编译器把它当成
`lea rax,[rip+disp]` + `mov qword [..], <长度立即数>`。长度是**编译期立即数**，
所以字面量后面紧跟的字符串没有任何指针指向 —— **不能移动任何字节**。
`rebrand_exe.py` 因此把新 JSON 用 CRLF 空行补齐到与原字节数**完全一致**再覆盖：
长度立即数不用改，后面所有偏移不变，尾部空白是合法 JSON（原本就带一个尾随 CRLF）。

`auto_patch apply` 每次都会调它，并且**前端标记全中也要单独判一次署名**
（`is_rebranded()`）——否则上游发版后署名永远补不回来。

> 应用运行时 exe 被占用，替换 exe 这一步会失败（后端此时已注入成功，
> 下次会自动重试）。建议在软件内更新**并完全退出**后运行，
> 或直接依赖登录自启动任务——本机已确认该软件自身没有注册开机自启，
> 登录时通常未运行，任务有干净的执行窗口。
>
> 若上游改了前端结构，`patch_frontend.py` 的探针会失败并整体中止
> （不会产出半成品），需要人工适配一次。

## 目录结构

```
base/            上游 1.0.9 原始文件（补丁唯一输入，保证可复现）
patches/         版本无关的补丁脚本
  patch_frontend.py    倍速 + 清晰度 + 快捷键 + 空格修复
  patch_backend.py     清晰度透传 + H.264 缓存直通（提速）
src/             补丁后的源码
tools/           构建 / 校验 / 部署 / 自动重注入
  tauri_assets.py      PE 内嵌资源定位、解压、原地回写
  repack.py            把 src/frontend 压回 exe
  rebrand_exe.py       内嵌 tauri.conf.json 署名等长重打标
  verify_repack.py     回读 exe 并与 src 比对
  auto_patch.py        更新后自动重新注入（含计划任务）
  smoke_test.py        补丁链冒烟（41 项）
  build_installer.py   打安装包（可复现：上游原版 + 我们的补丁）
  extract_icon.py      从 exe 里抽出图标给 NSIS 用
  deploy.ps1           部署到本机安装目录
  restore.ps1          还原为原始安装
installer/hongguo.nsi  安装包脚本（NSIS 3，Unicode）
docs/architecture.md   逆向、打包与提速原理
```

## 重新构建

```powershell
python patches/patch_frontend.py                    # base/frontend/app.js -> src/frontend/
python patches/patch_backend.py                     # base/backend -> src/backend/
node --check src/frontend/app.js                    # 语法校验
python tools/repack.py <原版 exe> dist/hongguo-desktop-companion.exe
python tools/rebrand_exe.py dist/hongguo-desktop-companion.exe
python tools/verify_repack.py
powershell -File tools/deploy.ps1                   # 先退出应用
```

## 打安装包（发给别人用）

```powershell
python tools/build_installer.py            # 一步到位：重建载荷 + 校验 + 打包
python tools/build_installer.py --check    # 只重建并校验载荷，不打包
```

产物：`dist/hongguo-1.0.9-setup.exe`（约 99 MB）。

**载荷不是从本机安装目录拷的**，而是从上游原版重建：

| 来源 | 内容 |
| --- | --- |
| 上游原版安装目录（`_v109/extracted`） | 本仓库不跟踪的大件：`backend/python`、`backend/jre`、`backend/sign`、`capture/`、`frida/`、各类 `*.json` 配置 |
| 本仓库 `src/backend/` 覆盖上去 | 补丁后的 `server.py` / `desktop_hls*.py` / `downloader.py` / `safeguards.py`，以及新增的 `desktop_account*.py` 与固定版本的 `requirements-windows.txt` |
| `dist/hongguo-desktop-companion.exe` | 已打补丁并重打署名的 exe |

这样分发包里不会混进 `.hq-bak-*`、`__pycache__`、`hls-work`、`*.orig-*`
这类运行时垃圾。卸载程序由 NSIS 现场生成，**不用**上游那个（它会去连上游的更新地址）。

### 安装包行为

| 项 | 值 |
| --- | --- |
| 默认安装目录 | `%LOCALAPPDATA%\Programs\红果免费短剧` |
| 升级既有安装 | 自动继承旧版安装位置（读卸载项的 `InstallLocation`），不会装出第二份 |
| 权限 | `RequestExecutionLevel user`，**不需要管理员**，不弹 UAC |
| 快捷方式 | 开始菜单 + 桌面（安装页可取消勾选；静默安装默认建桌面图标） |
| WebView2 | 先查注册表；缺了才静默调用微软官方引导程序（本机已装则跳过） |
| 卸载 | 问一次是否删观看记录（默认**保留**）；静默卸载一律保留 |
| 静默安装 | `hongguo-1.0.9-setup.exe /S`，可用 `/D=C:\路径` 指定目录 |
| 签名 | 未做代码签名，会有「未知发布者」提示（和上游一样） |

> 安装包里的 WebView2 引导程序是**微软官方原件**（`Microsoft Edge Update Setup`，
> 带微软签名），我们只做转发，没有改动。

## 实测结果（1.0.9，WebView2 远程调试，真实按键事件）

| 项目 | 结果 |
| --- | --- |
| 倍速档位 | `0.75/1/1.25/1.5/2/2.5/3`；选 3 后 `video.playbackRate === 3`，切集后保持 |
| `[` / `]` | 3 → 2.5 → 3，与工具栏下拉同步 |
| 清晰度档位 | `auto/1080p/720p/540p/480p` |
| 清晰度实际生效 | 切 720p 后 `video` 分辨率为 `1280x720`；切 1080p 为 `1920x1080` |
| 首次切 720p | 约 10.6 秒（下载 + 解密 + HEVC→H.264 转码） |
| 再切 720p | 约 **1.5 秒**（命中 H.264 缓存，stream-copy） |
| 空格键 | 焦点在按钮上时按空格：按钮 `click` 次数为 **0**，且正常切换播放/暂停 |
| 跳转 / 循环 | `>` 前进 10 秒、`PageDown` 前进 60 秒、`S` 切换循环 |

## 为什么首次切换仍要几秒

解密后的片源是 **HEVC**，而本机 WebView2 **不支持 HEVC**
（`MediaSource.isTypeSupported('video/mp4; codecs="hvc1…"') === false`），
必须转成 H.264 才能播放——这一步无法跳过。优化点是**只转一次**：

| 路径 | 耗时 |
| --- | --- |
| HEVC → HLS 转码（上游原实现） | 3.83 s |
| H.264 缓存 → HLS remux（本分支） | 0.16 s |

细节见 [docs/architecture.md](docs/architecture.md)。