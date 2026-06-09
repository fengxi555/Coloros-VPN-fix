# ColorOS VPN Fix

用于修复 ColorOS 分身用户 VPN UID 范围覆盖问题的 LSPosed/libxposed 模块。

在部分 ColorOS 系统中，分身用户里的应用没有被加入主用户 `VpnService` 网络对应的 UID range。结果是 VPN 在主用户里看起来正常，但分身应用可能绕过 VPN，或者出现联网异常。本模块在 `system_server` 侧修正 Android framework 生成 VPN UID range 的逻辑，让检测到的 ColorOS 分身用户也被 VPN 覆盖。

本项目不是 VPN 应用、SOCKS 代理或流量转发器，只修改系统侧 VPN UID range。

## 版本选择

2.6 同时提供两个构建版本：

- `API101`：推荐版本，适用于支持 libxposed API 101 的 LSPosed、Vector 等框架。
- `API100`：兼容版本，适用于只支持 libxposed API 100 的框架。

如果框架支持 API 101，优先安装 `API101`。只有框架不支持 API 101 时再安装 `API100`。

## 功能

- 在 `system_server` 中 Hook `com.android.server.connectivity.Vpn`。
- 默认作用域仅为 `system`。
- 优先通过 ColorOS/Android `UserManager` 内部接口发现 alive users。
- `UserManager` 不可用时回退扫描 `/data/user`。
- 只针对非主用户候选 ID 范围 `10-999`，主用户 `0` 不作为目标。
- VPN 未限制应用时，为分身用户扩展非系统应用 UID 范围。
- VPN 使用 `allowedApplications` 或 `disallowedApplications` 时，按包名为目标分身用户映射 UID range。
- 支持可用时的 SDK sandbox UID 映射。
- 提供白色毛玻璃风格的状态和设置界面。
- 设置页支持诊断日志开关、导出、清理和隐藏当前应用。
- 诊断日志直接写入应用私有文件，不依赖 `Settings.Global` 日志缓存。

## 使用要求

- Android/ColorOS 设备。
- LSPosed、Vector 或其他兼容 libxposed API 100/101 的框架。
- 按框架 API 版本安装对应 APK。
- 模块作用域选择 `system`。
- 启用模块后需要重启一次。

## 使用方法

1. 安装对应版本 APK：
   - API 101 框架安装 `ColorOS-VPN-fix-2.6-API101-Release.apk`。
   - API 100 框架安装 `ColorOS-VPN-fix-2.6-API100-Release.apk`。
2. 在 LSPosed/Vector 中启用模块。
3. 作用域只勾选 `system`。
4. 重启设备。
5. 打开 `ColorOS VPN Fix` 查看激活状态。

## 诊断日志

日志默认关闭。

开启日志后，模块会通过日志 Provider 直接写入应用私有文件 `clone-vpn-fix.log`，不再把诊断日志缓存到 `Settings.Global`。

私有日志文件达到 4 MiB 后会轮转为 `clone-vpn-fix.log.bak`。导出日志时会同时包含 `.bak` 和当前日志文件。

日志 Provider 只允许应用自身、Android `system` UID 或 root 调用。

## 隐藏调试属性

下面的属性仅用于排查问题，正常使用不需要设置：

```sh
setprop persist.clonevpnfix.enabled 1
setprop persist.clonevpnfix.mode non_owner
setprop persist.clonevpnfix.users ""
setprop persist.clonevpnfix.log 0
```

可用属性：

- `persist.clonevpnfix.enabled`：`1` 或 `0`，默认 `1`。
- `persist.clonevpnfix.mode`：`non_owner`、`clone` 或 `all`，默认 `non_owner`。
- `persist.clonevpnfix.users`：逗号分隔的显式用户 ID 列表；设置后会覆盖 mode。
- `persist.clonevpnfix.log`：开启额外 framework logcat/Xposed 失败提示，默认 `0`。

## 构建

可以用 Android Studio 打开项目，也可以使用本机 Gradle 构建：

```sh
gradle :app:assembleApi101Release
gradle :app:assembleApi100Release
```

也可以一次构建所有 Release 变体：

```sh
gradle :app:assembleRelease
```

构建产物位于：

- `app/build/outputs/apk/api101/release/ColorOS-VPN-fix-2.6-API101-Release.apk`
- `app/build/outputs/apk/api100/release/ColorOS-VPN-fix-2.6-API100-Release.apk`

Release 构建需要自行在仓库外配置签名。签名文件和生成的 APK 不会提交到仓库。

## 说明

- 本模块不修改 VPN 应用自身的路由逻辑、代理协议、DNS、IPv6 或 UDP 支持。
- 如果某个 VPN 应用本身不支持 IPv6 或 UDP，需要在 VPN 应用中单独修复。
- 本模块只修正系统判断哪些应用 UID 属于 VPN 网络时使用的 UID range。
