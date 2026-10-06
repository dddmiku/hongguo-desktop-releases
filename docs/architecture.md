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
（`bundle-manifest.json` 只是发行审计记录，程序不读它）。

Tauri 2 把前端资源以 **Brotli 流**放在 `.rdata`，资源表每条目 **32 字节**：

```
u64 key_ptr    // 镜像基址 + RVA，指向资源路径字符串（如 /assets/index-XXXX.js）
u64 key_len
u64 blob_ptr   // 指向 Brotli 压缩流
u64 blob_len   // 压缩流字节数
```

`tools/tauri_assets.py` 不写死偏移，而是：

1. 解析 PE 段表得到「文件偏移 ↔ RVA」映射；
2. 在文件里正则找出 `/assets/index-*.js`、`/assets/index-*.css`、`/index.html`；
3. 用「镜像基址 + 该字符串 RVA」作为 u64 搜索引用，定位资源表条目；
4. 校验 `key_ptr/key_len` 与 `blob_len` 合理后，Brotli 解压得到原始文件。

因此上游换文件名（`index-CEHZDRVO.js` 等）或改偏移都不影响。

### 为什么可以原地替换

- 解码器只读长度字段声明的字节数，流后面的其它常量不受影响。
- 重新压缩（`quality=11, lgwin=22`）后余量充足。1.0.9 实测：

  | 文件 | 原长度 | 新长度 | 余量 |
  | --- | --- | --- | --- |
  | `index-CEHZDRVO.js` | 291248 | 268779 | 22469 |
  | `index-BgVmhdUf.css` | 10441 | 9802 | 639 |
  | `/index.html` | 248 | 228 | 20 |

- `tools/repack.py` 写入前校验「压缩后不超过原长度」并做解码回读一致性检查。

## 前端补丁为什么能跨版本

上游用 esbuild/terser 压缩，**变量名每版都会变**。因此补丁不匹配字面变量名，而是：

1. **语义探针**：先用正则反查本次构建的标识符，例如
   - 媒体元素：`(\w+)\.volume=Math\.max\(0,Math\.min\(1,\1\.volume`
   - 播放器 refs：`(\w+)\.current\.seekTarget\?\?`
   - 倍速状态变量：`\w+\.defaultPlaybackRate=(\w+),\w+\.playbackRate=\1`
   - 组件内 onRate：`rate:\w+,onRate:(\w+),duration:`（组件形参，而非调用处）
2. **带捕获组的正则**回填原变量名做替换；
3. 每处替换都断言命中次数，任何一处不符预期就**整体失败**，绝不产出半成品。

## 清晰度链路

1. 前端「清晰度」下拉 → `localStorage["guoban:quality"]`，触发 HLS 会话重建。
2. `hqWithQual()` 在 `/desktop/hls` URL 上追加 `quality=<档位>`（`auto` 不追加）。
3. `server.py::_desktop_source(series_id, episode, quality)` 透传给 `_ensure_decrypted`。
4. `offline_dl._pick_track()` 支持 `1080p/720p/540p/480p/360p/纯数字`；缺失时回退到
   「不超过请求的最高一档」；`auto` 用原有 `desktop-resolution-v1` 策略。
5. `desktop_hls_service.py::prepare` 白名单校验 `quality`（非法值 400），再重建会话。

## 提速原理

解密后的片源是 **HEVC**；本机 WebView2 不支持 HEVC
（`MediaSource.isTypeSupported('video/mp4; codecs="hvc1.1.6.L93.B0"') === false`），
必须转 H.264。优化点是**只转一次**：

- `server.py::_desktop_source` 把 `_ensure_decrypted()` 的结果交给
  `desktop_encode.encode_h264()`，得到 `*.desktop-h264-v1.mp4` 缓存（按 vid 缓存），
  再交给 HLS 编码器。
- `desktop_hls.encode_hls()` 检测源为 H.264 时走 `_hq_copy_hls` **stream-copy**，
  不再重新编码；其它编码仍走上游 libx264 路径。

实测（同一集 720p，63 秒）：

| 路径 | 耗时 | 产物 |
| --- | --- | --- |
| HEVC → HLS 转码 | 3.83 s | 78.4 MB / 24 段 |
| H.264 缓存 → HLS remux | **0.16 s** | 78.4 MB / 24 段 |

GPU 编码收益有限（`h264_nvenc` 仍需解码 HEVC）：p1 约 3.2 s、p4+3Mbps 约 2.9 s，
对比 libx264 ultrafast 约 4.3 s。瓶颈在 HEVC **解码**，故未改用 GPU 编码，
以免在无 N 卡的机器上失效。

## 编码池：为什么会「媒体准备失败」

`desktop_hls_service.py::HlsJobs` 是 HLS 会话池，上游默认
`max_jobs=4, max_workers=2`，且 `create()` 在槽位占满时**立刻**抛 503：

```python
active = sum(not job.done.is_set() for job in self.jobs.values())
if len(self.jobs) >= self.max_jobs or active >= self.max_workers:
    raise HTTPException(503, "Desktop encoder is busy; retry shortly")
```

切换下一集时，旧会话还在收尾（未 `done`、也未被标记 `cancelled`），
于是新一集必吃 503。前端只在 200 ms 后重试一次，仍失败 →
`播放中断，请重试当前集。`

改成有界排队，并把「已取消、尚未收尾」的任务排除在占用之外：

```python
deadline = time.monotonic() + self.queue_wait        # 6s
while True:
    with self.guard:
        self._expire()
        live   = sum(not job.cancelled.is_set() for job in self.jobs.values())
        active = sum(not job.done.is_set() and not job.cancelled.is_set()
                     for job in self.jobs.values())
        if live < self.max_jobs and active < self.max_workers:
            ... 启动任务; return job
    if time.monotonic() >= deadline:
        raise HTTPException(503, "Desktop encoder is busy; retry shortly")
    time.sleep(0.15)
```

`queue_wait` 必须**小于**前端 POST 的 10 秒超时，否则服务端还在等、
客户端已经放弃。

同时把取消信号一路透传下去，让旧任务真正停下而不是占着槽位：

```
prepare(quality) -> HlsJobs.create -> _run
  -> source_loader(series_id, episode, quality, cancelled=job.cancelled.is_set)
    -> _desktop_source -> encode_h264(decrypted, cancelled)
```

`source_loader` 用 `try/except TypeError` 回退，兼容只收 3 个参数的旧实现。

## 打包白屏：index.html 的资源引用

上游用 Vite 构建，每次发版换哈希文件名
（`/assets/index-XXXXXXXX.js`）。安装包里的 `index.html` 与 exe 内嵌资源
可能不是同一批；直接照抄会导致脚本 404、界面全白：

```
Failed to load module script: Expected a JavaScript-or-Wasm module script
but the server responded with a MIME type of "text/html".
```

`tools/repack.py::align_index_html()` 在压回前按扩展名把
`<script src>` / `<link href>` 重写到 exe 内**真实存在**的键名，
版本无关。`verify_repack.py` 用同一变换后再比对，否则会误报不一致。

## 部署清单必须完整

`deploy.ps1` / `restore.ps1` / `auto_patch.py` 的同步清单必须包含
补丁涉及的全部后端文件：

```
server.py  desktop_hls.py  desktop_hls_service.py  desktop_encode.py
```

漏掉 `desktop_encode.py` 时，`server.py` 传了 `cancelled` 形参而旧
`encode_h264` 不接受 → `TypeError` → 同样表现为「媒体准备失败」。
这是本轮踩到的第二个同类坑。

## 已修的两个坑（勿回退）

1. **快路径与目录创建顺序**：`_hq_copy_hls` 自己会建目录，因此注入点必须在
   `encode_hls` 的 `directory.mkdir(...)` **之前**并直接 `return`；否则目录已存在，
   原路径的 `exist_ok=False` 会抛错，表现为「媒体准备失败」。
2. **键盘白名单取反**：上游判断是
   `... || ![" ","ArrowLeft",...].includes(key) || (preventDefault(), ...)`，
   替换白名单时**必须保留开头的 `!`**；漏掉会把逻辑反转，导致快捷键全部失效、
   空格仍会激活焦点按钮。

## 注意事项

- 应用运行中无法覆盖 exe，部署/注入前先完全退出应用。
- 前端改动必须重新 `repack.py`（前端不在磁盘，而在 exe 内）。
- 后端改动需清理 `backend/__pycache__/*.pyc`，否则可能命中旧字节码。