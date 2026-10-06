# 红果桌面版 · 本地维护分支

在上游发行版 `waligoraamodio288-rgb/hongguo-desktop-releases`（红果短剧电脑版 / 红果桌面版）
基础上做本地二次维护，新增播放器能力：

1. **3 倍速播放** —— 倍速档位扩展为 `0.75 / 1 / 1.25 / 1.5 / 2 / 2.5 / 3`。
2. **清晰度可选** —— 播放器工具栏新增「清晰度」下拉：
   `自动（最高）/ 1080p / 720p / 540p / 480p`；切换后由后端按所选档位重新选轨。
3. **清晰度切换提速** —— 已转码过的清晰度再次切换 **约 0.2 秒**（原约 4.3 秒，约 22 倍）。
4. **常用键盘快捷键** —— 见下表。

> 仅供本地维护用途。红果客户端本体、第三方源码与商标归各自权利方所有。

## 键盘快捷键

| 按键 | 功能 |
| --- | --- |
| `空格` / `K` | 播放 / 暂停 |
| `←` / `→` | 后退 / 前进 5 秒 |
| `<` `,` / `>` `.` | 后退 / 前进 10 秒 |
| `PageUp` / `PageDown` | 后退 / 前进 60 秒 |
| `Home` / `End` | 跳到片头 / 片尾 |
| `0`–`9` | 按百分比跳转（`5` = 50%） |
| `↑` / `↓` | 音量 +/− |
| `[` / `]` | 降低 / 提高倍速 |
| `M` / `D` | 静音开关 |
| `J` / `N` | 下一集 |
| `F` | 全屏 |
| `T` | 剧场模式 |
| `S` | 循环播放开关 |

## 目录结构

```
base/             上游原始基线（补丁的唯一输入，可复现）
  frontend/         app.js / app.css / index.html（从 exe 内嵌资源取出）
  backend/          server.py / desktop_hls.py / desktop_hls_service.py
patches/          补丁脚本
  patch_frontend.py    倍速档位 + 清晰度 UI/状态/请求参数 + 快捷键
  patch_backend.py     清晰度透传 + H.264 缓存直通（提速）
src/              补丁后的源码
tools/            构建 / 校验 / 部署
  repack.py            把 src/frontend 压回 exe 内嵌资源表
  verify_repack.py     回读 exe 内嵌资源并与 src 比对
  deploy.ps1           部署到本机安装目录（打印前后哈希）
  restore.ps1          还原为原始安装
docs/architecture.md   逆向、打包与提速原理
dist/                 打包产物
```

## 重新构建

```powershell
# 依赖：Python 3（brotli）、Node.js
python patches/patch_frontend.py   # base/frontend/app.js -> src/frontend/app.js
python patches/patch_backend.py    # base/backend/* -> src/backend/*
node --check src/frontend/app.js   # 语法校验（ES module，可用 .mjs）
python tools/repack.py             # -> dist/hongguo-desktop-companion.exe
python tools/verify_repack.py      # 回读校验
powershell -File tools/deploy.ps1  # 部署（先退出应用）
```

还原：`powershell -File tools/restore.ps1`

## 实测结果（本机 1.0.4，WebView2 远程调试）

| 项目 | 结果 |
| --- | --- |
| 倍速档位 | `0.75/1/1.25/1.5/2/2.5/3`；选 3 后 `video.playbackRate === 3`，切集后保持 |
| `[` / `]` | 3 → 2.5 → 3，与工具栏下拉同步 |
| 清晰度档位 | `auto/1080p/720p/540p/480p`；切 720p 后 `video` 分辨率变为 `1280x720` |
| 清晰度切换（首次） | 约 4.3 秒（需下载 + 解密 + HEVC→H.264 转码） |
| 清晰度切换（已缓存） | **约 0.2 秒**（复用已转码缓存，stream-copy） |
| 跳转 | `>` 前进 10 秒、`PageDown` 前进 60 秒、`0`/`9` 跳首尾 |
| 循环 / 静音 / 播放暂停 | `S` / `D` / `K` 均生效 |

## 为什么首次切换仍要几秒

解密后的片源是 **HEVC**，而本机 WebView2 **不支持 HEVC**（`MediaSource.isTypeSupported('video/mp4; codecs="hvc1…"') === false`），
因此必须转成 H.264 才能播放——这一步无法跳过。优化点是**只转一次**：

- 首次切到某清晰度：下载 + 解密 + 转码（约 4.3 秒），并缓存 H.264 结果；
- 之后切回同一清晰度：直接用缓存做 HLS remux（约 0.2 秒）。

实测（同一集 720p，63 秒）：

| 路径 | 耗时 |
| --- | --- |
| HEVC → HLS 转码（原实现） | 3.83 s |
| H.264 缓存 → HLS remux（现实现） | 0.16 s |

细节见 [docs/architecture.md](docs/architecture.md)。
