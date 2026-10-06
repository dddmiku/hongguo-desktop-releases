# 红果桌面版 · 本地维护分支

上游发行版 `waligoraamodio288-rgb/hongguo-desktop-releases`（红果短剧电脑版 / 红果桌面版）
的本地二次维护。**上游每次发版后都能自动重新注入补丁**。

当前基线：**1.0.9**（原版 exe sha256 `be60068a…`）。

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

## 验证与回滚

```powershell
python verify/repro/pool_test.py     # 编码池：BASELINE vs MODIFIED
python verify/repro/pool_three.py    # 三态：BASELINE / MODIFIED / ROLLBACK
bash verify/ROLLBACK.sh --all <应用目录>   # 用内嵌基线还原后端
```

`verify/VERIFICATION.txt` 记录完整证据（命令、原始输出、哈希、未覆盖项）。
`verify/ROLLBACK.sh` 自带 base64 内嵌基线，即使仓库被裁剪也能还原。

## 上游更新后如何自动重新注入

```powershell
python tools/auto_patch.py status    # 查看当前 exe 是否为补丁版本
python tools/auto_patch.py apply     # 检测到原版则自动重新注入
python tools/auto_patch.py install   # 注册登录自启动（Windows 计划任务）
python tools/auto_patch.py uninstall # 取消自启动
```

`apply` 的流程：

1. 比对 exe 哈希与 `state.json` 记录；
2. 若已打补丁则跳过；若检测到原版（上游更新或重装）则继续；
3. **直接从 exe 内嵌资源取出上游原版前端**（无需重新下载安装包）；
4. 套用 `patches/patch_frontend.py`（版本无关，自动探测变量名）；
5. 压回 exe 内嵌资源表；
6. 套用 `patches/patch_backend.py` 到后端；
7. 原子替换，并在 `_backup/` 留下原始备份。

> 应用运行时 exe 被占用，`apply` 会失败。建议在软件内更新**并完全退出**后运行，
> 或直接依赖登录自启动任务（登录时软件通常未运行）。

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
  verify_repack.py     回读 exe 并与 src 比对
  auto_patch.py        更新后自动重新注入（含计划任务）
  deploy.ps1           部署到本机安装目录
  restore.ps1          还原为原始安装
docs/architecture.md   逆向、打包与提速原理
```

## 重新构建

```powershell
python patches/patch_frontend.py                    # base/frontend/app.js -> src/frontend/
python patches/patch_backend.py                     # base/backend -> src/backend/
node --check src/frontend/app.js                    # 语法校验
python tools/repack.py <原版 exe> dist/hongguo-desktop-companion.exe
python tools/verify_repack.py
powershell -File tools/deploy.ps1                   # 先退出应用
```

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