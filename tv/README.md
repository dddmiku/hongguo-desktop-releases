# 红果客厅 TV

Android TV / 电视盒子客户端。1.1.0 接手原 TV 项目，沿用 `cn.hongguo.tv` 包名与正式签名，可覆盖升级。

新版采用墨蓝与暖金主题，精选、片库、追剧、历史和搜索有独立入口。精选突出一部作品，首屏保留更多片单；片库支持真实体裁与题材筛选、排序和翻页。遥控器左右在行内移动、上下跨行，返回时保留页面与焦点。剧集详情支持直接续播、从第一集看和任意选集。

完整历史使用账号隔离的 SQLite，保留真实集数、毫秒位置、观看时间及作品元数据，不按 30/100 条删除。界面每页 24 条，可检索所有历史；云端逐页读取，本机已有记录先显示，失败和断网不清空。旧版没有日期的记录标注“观看时间未知”，不会伪造当天时间。

进度先落本机，后台补传历史与独立续播两份记录；两份都成功才确认。重试保护手机更新的真实观看时间，用户回看较低集数也按最近观看处理。账号切换保留各自片库，上传确认不会清除之后的新进度。

播放器：确定暂停/继续，左右快进退，上键打开播放设置，下键选集。画质、倍速、比例、自动连播和追剧均能用遥控器操作。字幕式控制提示只在操作时显示；短于五秒的位置也精确保留，退出前立即保存。

## 构建

需要 JDK 11、Gradle 7.6.4、Android SDK 33。工程使用纯 Java 与系统 View，仅 JNA 是第三方 Java 依赖。

```powershell
gradle :app:assembleRelease
```

Gradle 产物在 `app/build/outputs/apk/release/hongguo-tv-1.1.2.apk`。正式签名使用本机 `keystore.properties` 和 `keystore/hongguo-tv.jks`，均排除在源码仓库之外。不要换签名或先卸载旧版。

安装包、入口图标和 TV banner 必须是 PNG；系统安装器不能使用固有尺寸无效的 shape 图标。LEANBACK_LAUNCHER 与 LAUNCHER 保持独立 intent-filter。签名上下文由 Application 统一初始化，直接启动账号、历史或播放器也可使用。

## 验证

```powershell
gradle :app:assembleDebug :app:assembleDebugAndroidTest
python tools/verify-player-lifecycle.py --install --full
python -m unittest discover -s tools -p test_publish.py
```

`.qa` 使用独立应用 ID 与数据，不操作已安装正式版的账号。设备测试包含真实 SQLite 1500 条分页、搜索、账号隔离、进度冲突、过期确认、删除隐藏，以及真实内容、遥控器焦点、解码播放、暂停与精确续播。新增返回首页、历史、追剧时的延迟取流失败回归、空剧集退出和九轮实际解码返回验收。单独复现生命周期用 `python tools/verify-player-lifecycle.py --install`；报告只覆盖写到 `_work/crash-qa/`。

## 发布

先确认正式包、设备验收和源码提交，再执行：

```powershell
python tools/publish.py --source-commit <已推送的40位源码提交>
```

TV Release 标签为 `tv-v1.1.2`，安装资产使用 `hongguo-tv-1.1.2.APK`。发布器先上传草稿并核对资产大小和 SHA-256，再公开。已公开版本不替换资产；发现修复应递增版本号。

更新清单继承原 PC 平台、兼容迁移字段和顶层版本，只更新 `platforms.android-tv`。TV 自己的版本不与 PC 或旧兼容桥编号比较。后续 PC 发布继续继承线上 TV 平台，避免冲掉电视更新入口。

源码与验证边界见 `docs/architecture.md`、`docs/verification.md`。
