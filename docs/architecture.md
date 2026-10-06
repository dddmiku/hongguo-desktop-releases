# 架构、打包与提速原理

## 组件

| 组件 | 位置 | 说明 |
| --- | --- | --- |
| Tauri 外壳 | `hongguo-desktop-companion.exe` | Rust 2.11，内嵌前端资源，含托盘/老板键 |
| 本机后端 | `backend/desktop_bootstrap.py` | 分配随机端口，拉起签名服务与 uvicorn |
| API 服务 | `backend/server.py` | FastAPI：`/search` `/rank` `/episodes` `/stream` `/desktop/hls` |
| 签名服务 | `backend/sign/unidbg-sign.jar` | JVM，`127.0.0.1:9099` |
| 前端 | exe 内嵌 `/assets/index-*.js` | React + hls.js（Vite 产物） |
| 数据目录 | `%APPDATA%\cn.guoban.desktop-companion` | `guoban.sqlite3`、`stream-cache`、`desktop-hls-*` |

## 内嵌前端资源定位（PE 逆向）

exe **无 Authenticode 签名**，运行期**不做可执行文件自校验**
（`bundle-manifest.json` 只是发行审计记录，程序不读它）。PE 段：
`.text` / `.rdata` / `.data` / `.pdata` / `.rsrc` / `.reloc`。

Tauri 2 把前端资源以 **Brotli 流**放在 `.rdata`，由一张 `(u64 指针, u64 长度)` 表引用：

| 文件偏移 | 内容 |
| --- | --- |
| `0xc8a4e8` | 资源表起始（每条目 16 字节） |
| `0xc41b28` / `0xc41b42` | `/assets/index-Cfv6YY8_.css` 的键 / Brotli 流 |
| `0xc419e6` / `0xc442a1` | `/index.html` 的键 / Brotli 流 |
| `0xc44398` / `0xc443b1` | `/assets/index-Bm6fM3_d.js` 的键 / Brotli 流 |
| `0xc8a500` / `0xc8a520` / `0xc8a540` | 上述三者的压缩流长度字段 |

`.rdata` 的文件偏移与 RVA 相差 `0x1a00`（RVA `0xc3f000` ← rawptr `0xc3d600`）。

### 为什么可以原地替换

- 解码器只读长度字段声明的字节数，流后面的其它常量不受影响。
- 重新压缩（`quality=11, lgwin=22`）后余量充足：

  | 文件 | 原长度 | 新长度 | 余量 |
  | --- | --- | --- | --- |
  | `app.js` | 287031 | 264867 | 22164 |
  | `app.css` | 10068 | 9478 | 590 |
  | `index.html` | 247 | 229 | 18 |

- `tools/repack.py` 写入前会校验「压缩后不超过原长度」并做解码回读一致性检查。

## 清晰度链路

1. 前端「清晰度」下拉 → `localStorage["guoban:quality"]`，并触发 HLS 会话重建。
2. `hqWithQual()` 在 `/desktop/hls` URL 上追加 `quality=<档位>`（`auto` 不追加）。
3. `server.py::_desktop_source(series_id, episode, quality)` 透传给
   `_ensure_decrypted(vid, quality)`。
4. `offline_dl._pick_track()` 支持 `1080p/720p/540p/480p/360p/纯数字`；
   缺失时回退到「不超过请求的最高一档」；`auto` 用原有 `desktop-resolution-v1` 策略。
5. `desktop_hls_service.py` 校验档位白名单（非法值 400），据此重建会话。

## 提速原理

解密后的片源是 **HEVC**；本机 WebView2 不支持 HEVC
（`MediaSource.isTypeSupported('video/mp4; codecs="hvc1.1.6.L93.B0"') === false`），
必须转 H.264。优化点是**只转一次**：

- `server.py::_desktop_source` 把 `_ensure_decrypted()` 的结果交给
  `desktop_encode.encode_h264()`，得到 `*.desktop-h264-v1.mp4` 缓存
  （按 `vid` 缓存，与清晰度档位一一对应），再交给 HLS 编码器。
- `desktop_hls.encode_hls()` 检测到源是 H.264 时走 **stream-copy**（`_copy_hls`），
  不再重新编码；其它编码仍走原有 libx264 转码路径。

实测（同一集 720p，63 秒）：

| 路径 | 耗时 | 产物 |
| --- | --- | --- |
| HEVC → HLS 转码 | 3.83 s | 78.4 MB / 24 段 |
| H.264 缓存 → HLS remux | **0.16 s** | 78.4 MB / 24 段 |

GPU 编码收益有限（`h264_nvenc` 仍需解码 HEVC）：p1 约 3.2 s、p4+3Mbps 约 2.9 s，
对比 libx264 ultrafast 约 4.3 s。瓶颈在 HEVC **解码**，故未改用 GPU 编码，
以免在无 N 卡的机器上失效。

## 注意事项

- 应用运行中无法覆盖 exe，部署前先退出应用。
- 前端改动必须重新 `repack.py`（前端不在磁盘，而在 exe 内）。
- 后端改动需清理 `backend/__pycache__/*.pyc`，否则可能命中旧字节码。
