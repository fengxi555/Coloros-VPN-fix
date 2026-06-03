# ColorOS VPN Fix

用于修复 ColorOS 分身用户 VPN UID 范围覆盖问题的 LSPosed/libxposed 模块。

在部分 ColorOS 系统中，分身用户里的应用没有被加入主用户 `VpnService` 网络对应的 UID range。结果是 VPN 在主用户里看起来正常，但分身应用可能绕过 VPN，或者出现联网异常。本模块在 `system_server` 侧修正 Android framework 生成 VPN UID range 的逻辑，让检测到的 ColorOS 分身用户也被 VPN 覆盖。

本项目不是 VPN 应用、SOCKS 代理或流量转发器，只修改系统侧 VPN UID range。

## 功能

- 使用现代 libxposed API 101。
- 默认作用域仅为 `system`。
- 在 `system_server` 中 Hook `com.android.server.connectivity.Vpn`。
- 优先通过 ColorOS/Android `UserManager` 内部接口发现 alive users。
- `UserManager` 不可用时回退扫描 `/data/user`。
- 只针对 ColorOS 分身常见用户 ID 范围 `900-999`。
- VPN 未限制应用时，为分身用户扩展非系统应用 UID 范围。
- VPN 使用 `allowedApplications` 时，按包名为目标分身用户映射 UID range。
- 支持可用时的 SDK sandbox UID 映射。
- 提供状态页面和可选诊断日志页面。

## 使用要求

- Android/ColorOS 设备。
- LSPosed、Vector 或其他兼容 libxposed API 101 的框架。
- 模块作用域选择 `system`。
- 启用模块后需要重启一次。

## 使用方法

1. 构建或安装 APK。
2. 在 LSPosed/Vector 中启用模块。
3. 作用域只勾选 `system`。
4. 重启设备。
5. 打开 `ColorOS VPN Fix` 查看激活状态。

## 诊断日志

日志默认关闭。

开启日志后，模块会优先通过日志 Provider 写入应用私有文件 `clone-vpn-fix.log`。如果日志已开启但 Provider 暂时不可用，模块才会回退到 `Settings.Global` 里的有限 bridge 缓冲。

系统侧 fallback 缓冲最多保留 512 条短日志，只用于临时桥接，不会无限写入系统设置。进入日志页或导出日志前，会把可用 fallback 日志同步到应用私有日志文件。

私有日志文件达到 4 MiB 后会轮转为 `clone-vpn-fix.log.bak`。导出日志时会同时包含 `.bak` 和当前日志文件。

日志 Provider 只允许应用自身、Android `system` UID 或 root 调用。
