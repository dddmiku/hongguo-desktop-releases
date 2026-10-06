$ErrorActionPreference = "Stop"
$root = "d:\Users\dddmiku\AppData\Local\红果免费短剧"
$bak  = "C:\Users\dddmiku\Desktop\codex\hongguo-desktop-maintain\_backup\orig"
$files = @("server.py", "desktop_hls.py", "desktop_hls_service.py", "desktop_encode.py",
           "desktop_account.py", "desktop_account_api.py", "downloader.py",
           "requirements-windows.txt")
# 上游基线里没有、由我们新增的文件。还原时要删掉，否则会残留账号模块，
# server.py 还原后还会 import 它们（原版没有这段，但文件留着是隐患）。
$added = @("desktop_account.py", "desktop_account_api.py")

Get-Process hongguo-desktop-companion,msedgewebview2 -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 4

Copy-Item "$bak\hongguo-desktop-companion.exe" "$root\hongguo-desktop-companion.exe" -Force
foreach ($f in $files) {
  if (Test-Path "$bak\backend\$f") {
    Copy-Item "$bak\backend\$f" "$root\backend\$f" -Force
  }
}
foreach ($f in $added) {
  $fp = "$root\backend\$f"
  if ((Test-Path $fp) -and -not (Test-Path "$bak\backend\$f")) {
    Write-Host ("移除新增模块       : " + $f)
    Remove-Item $fp -Force -ErrorAction SilentlyContinue
  }
}
Get-ChildItem "$root\backend\__pycache__" -Filter "*.pyc" -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue

Write-Host ("exe                : " + (Get-FileHash "$root\hongguo-desktop-companion.exe" -Algorithm SHA256).Hash)
foreach ($f in $files) {
  $fp = "$root\backend\$f"
  if (Test-Path $fp) {
    Write-Host ("{0,-22}: {1}" -f $f, (Get-FileHash $fp -Algorithm SHA256).Hash)
  } else {
    Write-Host ("{0,-22}: (不存在)" -f $f)
  }
}
