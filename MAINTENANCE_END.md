# 红果旧仓库停止维护与迁移说明

自 **2026 年 11 月 7 日**起，旧仓库 [hongguo-desktop-releases](https://github.com/dddmiku/hongguo-desktop-releases) 停止维护。后续功能开发、问题修复、版本发布和反馈处理分别在以下两个独立仓库进行：

- **Windows PC 客户端**：[hongguo-desktop](https://github.com/dddmiku/hongguo-desktop) · [下载新版](https://github.com/dddmiku/hongguo-desktop/releases/latest) · [反馈问题](https://github.com/dddmiku/hongguo-desktop/issues)
- **Android TV / 电视盒子客户端**：[hongguo-tv](https://github.com/dddmiku/hongguo-tv) · [下载新版](https://github.com/dddmiku/hongguo-tv/releases/latest) · [反馈问题](https://github.com/dddmiku/hongguo-tv/issues)

旧仓库保留历史源码、旧发行包和最终迁移入口，供已有用户下载、查阅及升级；不会继续发布常规更新，请到对应的新仓库获取新版并反馈问题。

**PC 用户**：在旧客户端点击“检查更新”，按提示下载并安装。最终 1.9.15 兼容迁移包会安装新客户端 1.2.2；兼容包编号仅供旧更新器比较，不是新 PC 的版本号。安装后，应用内更新自动查 PC 新仓库。

**TV 用户**：在旧客户端点击“更新”，下载提示中的 `hongguo-tv-1.1.3.APK` 并按系统提示覆盖安装，也可从新 TV 仓库手动下载。原包名和签名保持一致，账号、完整历史、追剧及偏好原位保留。无需先卸载；升级后应用内更新直接使用 TV 新仓库。

从此 PC 与 TV 各自拥有独立源码、发行包、更新清单和版本线。感谢大家此前的使用与反馈，后续维护请关注对应的新仓库。

旧更新入口继续保持可读，供停留在旧版本的用户完成第一次升级。清单中的 PC 下载地址直接指向新 PC 仓库，TV 下载地址指向新 TV 仓库。部分更早 PC 下载器会自行拼接旧仓库固定文件名，因此保留同名兼容下载；该文件与新仓库 PC 安装包逐字节一致，安装后即使用新版。完成迁移后，后续更新只在各自新仓库进行，不需持续更新旧仓库。
