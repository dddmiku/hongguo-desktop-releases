; 红果免费短剧 桌面版 · 安装程序（NSIS 3，Unicode）
;
; 为什么自己写而不用上游的安装包：
;   上游的 setup.exe 里带的是**未打补丁的原版** exe 和后端，装完等于把补丁覆盖掉。
;   我们自己重新打包，把打好补丁的 exe 与后端一起装进去。
;
; 安装位置默认与上游一致（$LOCALAPPDATA\红果免费短剧），这样覆盖安装时
; 快捷方式、卸载项、用户数据（%APPDATA%\cn.guoban.desktop-companion）都不用变。
; 用户可在「选择安装位置」页改成任意路径。
;
; 静默安装：hongguo-setup.exe /S [/D=C:\路径]

Unicode true

; 载荷 350MB（主要是内置的 Python + JRE），用整块 LZMA 压比默认 zlib 小很多。
SetCompressor /SOLID lzma
SetCompressorDictSize 64

!include "MUI2.nsh"
!include "LogicLib.nsh"
!include "FileFunc.nsh"
!include "nsDialogs.nsh"
!include "WinMessages.nsh"

!define APP_NAME      "红果免费短剧"
!define APP_ID        "hongguo-desktop-companion"
!define PUBLISHER     "dddmiku"
!define VERSION       "1.0.9"
!define REPO_URL      "https://github.com/dddmiku/hongguo-desktop-releases"
!define UNINST_KEY    "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APP_NAME}"
!define WEBVIEW2_GUID "{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}"

Name "${APP_NAME}"
OutFile "..\dist\hongguo-${VERSION}-setup.exe"
; 常规安装位置：%LOCALAPPDATA%\Programs\<应用名>（Chrome / VS Code / 各种
; Electron 应用都装这里）。不用 $LOCALAPPDATA\<应用名> —— 那会把应用目录和
; 其它 Local 数据混在一起，看起来像乱丢文件。
InstallDir "$LOCALAPPDATA\Programs\${APP_NAME}"
InstallDirRegKey HKCU "Software\${APP_ID}" "InstallDir"
RequestExecutionLevel user
ShowInstDetails show
ShowUninstDetails show

VIProductVersion "1.0.9.0"
VIAddVersionKey "ProductName"     "${APP_NAME}"
VIAddVersionKey "FileDescription" "${APP_NAME} 安装程序"
VIAddVersionKey "FileVersion"     "${VERSION}"
VIAddVersionKey "ProductVersion"  "${VERSION}"
VIAddVersionKey "CompanyName"     "${PUBLISHER}"
VIAddVersionKey "LegalCopyright"  "${REPO_URL}"

!define MUI_ABORTWARNING
!define MUI_ICON   "app.ico"
!define MUI_UNICON "app.ico"

!define MUI_WELCOMEPAGE_TITLE "安装 ${APP_NAME}"
!define MUI_WELCOMEPAGE_TEXT  "即将把 ${APP_NAME} 安装到你的电脑。$\r$\n$\r$\n本安装包为本地维护分支构建，已包含：$\r$\n  · 3 倍速 / 清晰度可选 / 完整键盘快捷键$\r$\n  · 跳集不再「媒体准备失败」$\r$\n  · 自动连播不弹控制栏$\r$\n  · 缓存自动清理$\r$\n  · 观看进度与收藏同步到手机账号$\r$\n  · 已关闭官方在线更新通道$\r$\n$\r$\n安装包未做 Windows 发布者代码签名，可能出现「未知发布者」提示。$\r$\n请核对来源与文件摘要。"
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
Page custom ShortcutPageCreate ShortcutPageLeave
!insertmacro MUI_PAGE_INSTFILES

!define MUI_FINISHPAGE_TITLE "安装完成"
!define MUI_FINISHPAGE_TEXT  "${APP_NAME} 已安装完成。$\r$\n$\r$\n首次启动会自动解压内置的运行环境，可能需要 10–30 秒，请耐心等待窗口出现。"
!define MUI_FINISHPAGE_RUN
!define MUI_FINISHPAGE_RUN_TEXT "立即运行 ${APP_NAME}"
!define MUI_FINISHPAGE_RUN_FUNCTION LaunchApp
!define MUI_FINISHPAGE_LINK "项目主页 / 问题反馈"
!define MUI_FINISHPAGE_LINK_LOCATION "${REPO_URL}"
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

!insertmacro MUI_LANGUAGE "SimpChinese"

; ---------- 自定义页：桌面快捷方式 ----------
Var DesktopCheck
Var DesktopState
Function ShortcutPageCreate
  !insertmacro MUI_HEADER_TEXT "快捷方式" "选择是否在桌面创建快捷方式。"
  nsDialogs::Create 1018
  Pop $0
  ${If} $0 == error
    Abort
  ${EndIf}
  ${NSD_CreateLabel} 0 0 100% 24u "安装程序会在开始菜单创建「${APP_NAME}」快捷方式。$\r$\n你也可以同时创建一个桌面快捷方式："
  Pop $0
  ${NSD_CreateCheckBox} 0 34u 100% 12u "在桌面创建快捷方式"
  Pop $DesktopCheck
  ${NSD_SetState} $DesktopCheck ${BST_CHECKED}
  nsDialogs::Show
FunctionEnd

Function ShortcutPageLeave
  ${NSD_GetState} $DesktopCheck $DesktopState
FunctionEnd

Function LaunchApp
  ; 用 explorer 启动：以普通用户身份、带正确的环境，避免 NSIS 进程上下文影响 WebView2。
  ExecShell "open" "$INSTDIR\${APP_ID}.exe"
FunctionEnd

; ---------- 工具：等待旧版本退出 ----------
; 用「改名探测」判断 exe 是否被占用 —— 比解析 tasklist 输出可靠（不受系统语言影响）。
; 改名成功说明没被占用，立刻改回来。
Function WaitAppClosed
  ${If} ${Silent}
    Return
  ${EndIf}
  ${IfNot} ${FileExists} "$INSTDIR\${APP_ID}.exe"
    Return
  ${EndIf}
  retry:
    ClearErrors
    Rename "$INSTDIR\${APP_ID}.exe" "$INSTDIR\${APP_ID}.exe.hqlock"
    ${If} ${Errors}
      MessageBox MB_RETRYCANCEL|MB_ICONEXCLAMATION \
        "${APP_NAME} 正在运行，安装程序需要替换程序文件。$\r$\n$\r$\n请先从托盘菜单完全退出（右键托盘图标 → 退出程序），然后点「重试」。$\r$\n$\r$\n也可以点「取消」，退出程序后重新运行本安装包。" \
        IDRETRY retry
      Abort "安装已取消：程序仍在运行。"
    ${Else}
      Rename "$INSTDIR\${APP_ID}.exe.hqlock" "$INSTDIR\${APP_ID}.exe"
    ${EndIf}
FunctionEnd

; ---------- 安装 ----------
Section "Install"
  ; 1) 确保旧版本已退出（覆盖安装时）
  Call WaitAppClosed

  ; 2) 记下用户数据目录（卸载时询问是否一并删除）
  SetOutPath "$INSTDIR"
  SetOverwrite on
  File /r "payload\*.*"

  ; 3) 卸载程序（放在解包之后写，避免被 payload 里的同名文件覆盖）
  Delete "$INSTDIR\uninstall.exe"
  WriteUninstaller "$INSTDIR\uninstall.exe"

  ; 4) 快捷方式
  CreateDirectory "$SMPROGRAMS"
  CreateShortCut "$SMPROGRAMS\${APP_NAME}.lnk" \
    "$INSTDIR\${APP_ID}.exe" "" "$INSTDIR\${APP_ID}.exe" 0 SW_SHOWNORMAL "" "${APP_NAME} 桌面播放器"
  ${If} $DesktopState == ${BST_CHECKED}
    CreateShortCut "$DESKTOP\${APP_NAME}.lnk" \
      "$INSTDIR\${APP_ID}.exe" "" "$INSTDIR\${APP_ID}.exe" 0 SW_SHOWNORMAL "" "${APP_NAME} 桌面播放器"
  ${EndIf}

  ; 5) 注册表
  WriteRegStr HKCU "Software\${APP_ID}" "InstallDir" "$INSTDIR"
  WriteRegStr HKCU "Software\${APP_ID}" "Version"    "${VERSION}"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayName"     "${APP_NAME}"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayVersion"  "${VERSION}"
  WriteRegStr HKCU "${UNINST_KEY}" "Publisher"       "${PUBLISHER}"
  WriteRegStr HKCU "${UNINST_KEY}" "URLInfoAbout"    "${REPO_URL}"
  WriteRegStr HKCU "${UNINST_KEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "${UNINST_KEY}" "UninstallString" '"$INSTDIR\uninstall.exe"'
  WriteRegStr HKCU "${UNINST_KEY}" "QuietUninstallString" '"$INSTDIR\uninstall.exe" /S'
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayIcon"     "$INSTDIR\${APP_ID}.exe"
  WriteRegStr HKCU "${UNINST_KEY}" "MainBinaryName"  "${APP_ID}.exe"
  WriteRegDWORD HKCU "${UNINST_KEY}" "NoModify" 1
  WriteRegDWORD HKCU "${UNINST_KEY}" "NoRepair" 1

  ; 估算占用（KB）—— 让「应用和功能」里能显示大小
  ${GetSize} "$INSTDIR" "/S=0K" $0 $1 $2
  IntFmt $0 "0x%08X" $0
  WriteRegDWORD HKCU "${UNINST_KEY}" "EstimatedSize" "$0"

  ; 6) WebView2 运行时（本机缺了才装）
  Call EnsureWebView2
SectionEnd

; ---------- WebView2 运行时 ----------
Function EnsureWebView2
  ; 机器级（x64 的 32 位视图）与用户级各查一次；pv 非空且非 0.0.0.0 即已安装。
  ReadRegStr $0 HKLM "SOFTWARE\WOW6432Node\Microsoft\EdgeUpdate\Clients\${WEBVIEW2_GUID}" "pv"
  ${If} $0 == ""
    ReadRegStr $0 HKCU "Software\Microsoft\EdgeUpdate\Clients\${WEBVIEW2_GUID}" "pv"
  ${EndIf}
  ${If} $0 != ""
  ${AndIf} $0 != "0.0.0.0"
    DetailPrint "WebView2 运行时已安装（$0），跳过。"
    Return
  ${EndIf}
  DetailPrint "未检测到 WebView2 运行时，正在静默安装…"
  ; 这个 exe 是微软官方引导程序（Microsoft Edge Update Setup，带微软签名），
  ; 我们只做转发，不修改它。
  nsExec::ExecToLog '"$PLUGINSDIR\MicrosoftEdgeWebview2Setup.exe" /silent /install'
  Pop $0
  DetailPrint "WebView2 引导程序退出码：$0（0 或 3010 表示成功）"
FunctionEnd

; ---------- 初始化：引导程序解包 + 继承旧版安装路径 ----------
Function .onInit
  InitPluginsDir
  File /oname=$PLUGINSDIR\MicrosoftEdgeWebview2Setup.exe "webview2\MicrosoftEdgeWebview2Setup.exe"

  ; 已经装过本维护版：InstallDirRegKey 会读我们自己的键，这里不用管。
  ; 装的是**上游原版**时没有我们这个键，但卸载项里有它当初的 InstallLocation。
  ; 不继承的话，老用户会在默认位置多装一份，变成两个安装、两套缓存。
  ReadRegStr $0 HKCU "Software\${APP_ID}" "InstallDir"
  ${If} $0 == ""
    ReadRegStr $0 HKCU "${UNINST_KEY}" "InstallLocation"
    ${If} $0 != ""
      ; 上游写的是带引号的路径，去掉首尾引号。
      ; 注意不能用 StrCpy $0 $0 -1 —— NSIS 里负长度是「按字符数截断」而不是
      ; 「去掉末尾」，会把路径最后**一个字符**也吃掉（实测把「红果免费短剧」
      ; 变成了「红果免费短」，于是多装出一份）。这里显式按长度取子串。
      StrCpy $1 $0 1
      ${If} $1 == '"'
        StrLen $2 $0
        IntOp $2 $2 - 2
        StrCpy $0 $0 $2 1
      ${EndIf}
      StrCpy $INSTDIR $0
      DetailPrint "沿用已有安装目录：$INSTDIR"
    ${EndIf}
  ${EndIf}

  ; 桌面快捷方式默认勾选。静默安装（/S）时自定义页不会执行，
  ; 变量是空的，所以必须在这里给默认值，否则 /S 装完桌面上没有图标。
  StrCpy $DesktopState ${BST_CHECKED}
FunctionEnd

; ---------- 卸载 ----------
Section "Uninstall"
  ; 1) 先确保程序已退出，否则文件删不掉
  ClearErrors
  Rename "$INSTDIR\${APP_ID}.exe" "$INSTDIR\${APP_ID}.exe.hqlock"
  ${If} ${Errors}
    ; 静默卸载（/S）下绝不能弹框：NSIS 的 MessageBox 在 /S 时依然会弹，
    ; 而后台调用方没有窗口可以点，进程就永久卡住（实测 /S 卸载挂死）。
    ; 静默模式改为「直接放弃删除 exe」，其余文件照删，并留下提示。
    ${If} ${Silent}
      DetailPrint "程序正在运行，跳过程序文件删除；退出后请重新运行卸载程序。"
      Goto after_exe
    ${EndIf}
    MessageBox MB_RETRYCANCEL|MB_ICONEXCLAMATION \
      "${APP_NAME} 正在运行，卸载程序无法删除程序文件。$\r$\n$\r$\n请先从托盘菜单完全退出，然后点「重试」。" \
      IDRETRY un_retry
    Abort "卸载已取消：程序仍在运行。"
    un_retry:
      ClearErrors
      Rename "$INSTDIR\${APP_ID}.exe" "$INSTDIR\${APP_ID}.exe.hqlock"
      ${If} ${Errors}
        Abort "卸载已取消：程序仍在运行。"
      ${EndIf}
  ${EndIf}
  Rename "$INSTDIR\${APP_ID}.exe.hqlock" "$INSTDIR\${APP_ID}.exe"
  after_exe:

  ; 2) 问一次是否连观看记录一起删（默认保留 —— 删了找不回来）
  ;    静默模式一律保留用户数据（不弹框、也不删）。
  ${IfNot} ${Silent}
    MessageBox MB_YESNO|MB_ICONQUESTION|MB_DEFBUTTON2 \
      "是否同时删除本机的观看记录与收藏？$\r$\n$\r$\n这些数据保存在 $APPDATA\cn.guoban.desktop-companion。$\r$\n$\r$\n选「否」＝保留（以后重装还能接着看）；$\r$\n选「是」＝一并删除，无法恢复。" \
      IDNO keep_userdata
      RMDir /r "$APPDATA\cn.guoban.desktop-companion"
      DetailPrint "已删除用户数据：$APPDATA\cn.guoban.desktop-companion"
    keep_userdata:
  ${EndIf}

  ; 3) 快捷方式与注册表
  Delete "$SMPROGRAMS\${APP_NAME}.lnk"
  Delete "$DESKTOP\${APP_NAME}.lnk"
  DeleteRegKey HKCU "${UNINST_KEY}"
  DeleteRegKey HKCU "Software\${APP_ID}"

  ; 4) 程序文件
  RMDir /r "$INSTDIR\backend"
  RMDir /r "$INSTDIR\licenses"
  Delete "$INSTDIR\${APP_ID}.exe"
  Delete "$INSTDIR\${APP_ID}.exe.blobcaps.json"
  Delete "$INSTDIR\uninstall.exe"
  RMDir "$INSTDIR"

  ; 5) 自启动计划任务（如果注册过）
  nsExec::ExecToLog 'schtasks /Delete /TN "HongguoDesktopPatch" /F'
SectionEnd
