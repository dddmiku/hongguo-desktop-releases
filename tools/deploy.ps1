$ErrorActionPreference = "Stop"
$root = "d:\Users\dddmiku\AppData\Local\红果免费短剧"
$work = "C:\Users\dddmiku\Desktop\codex\hongguo-desktop-maintain"

# 需要同步的后端文件：补丁涉及的全部文件。缺一个就会出现「媒体准备失败」。
# downloader.py 与 requirements-windows.txt 也在补丁链里改过（TLS 校验 / 版本固定），
# 不同步就会出现「源码与安装目录不一致」。
$files = @("server.py", "desktop_hls.py", "desktop_hls_service.py", "desktop_encode.py",
           "desktop_account.py", "desktop_account_api.py", "downloader.py",
           "requirements-windows.txt")

Get-Process hongguo-desktop-companion -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 4

Write-Host "=== 部署前 ==="
Write-Host ("exe    : " + (Get-FileHash "$root\hongguo-desktop-companion.exe" -Algorithm SHA256).Hash)
foreach ($f in $files) {
  $fp = "$root\backend\$f"
  if (Test-Path $fp) {
    Write-Host ("{0,-22}: {1}" -f $f, (Get-FileHash $fp -Algorithm SHA256).Hash)
  } else {
    Write-Host ("{0,-22}: (不存在)" -f $f)
  }
}

Copy-Item "$work\dist\hongguo-desktop-companion.exe" "$root\hongguo-desktop-companion.exe" -Force
foreach ($f in $files) {
  Copy-Item "$work\src\backend\$f" "$root\backend\$f" -Force
}
Get-ChildItem "$root\backend\__pycache__" -Filter "*.pyc" -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue

Write-Host "=== 部署后 ==="
Write-Host ("exe    : " + (Get-FileHash "$root\hongguo-desktop-companion.exe" -Algorithm SHA256).Hash)
foreach ($f in $files) {
  $fp = "$root\backend\$f"
  if (Test-Path $fp) {
    Write-Host ("{0,-22}: {1}" -f $f, (Get-FileHash $fp -Algorithm SHA256).Hash)
  } else {
    Write-Host ("{0,-22}: (不存在)" -f $f)
  }
}
