# MateLink 登录、高德配置与设备回归记录（2026-09-02）

## 状态

- `LOCAL PASS`：Android Debug/Release 各 468 项单测完成（Release 8 项预期跳过）、AndroidTest 编译、Debug/Release 构建和 Release lint 通过。
- `DEVICE PASS`：OnePlus 7 Pro `6e4fa92f` 使用同签名 `adb install -r` 从 1.4.3 覆盖升级到 1.4.4，未卸载或清除数据；应用可启动。
- `TELEMETRY PILOT PASS`：`NOT_PERFORMED`。没有输入 Tesla 凭据，也没有完成真实车辆 Telemetry 验证。

## 根因与修复

### 登录页被错误禁用

真机原页面显示“用户协议和隐私政策发布前，Tesla 云端登录不可用”。代码原因是 Release 构建没有注入 `MATELINK_PUBLIC_INFO_BASE_URL`，`PublicInfoLinks` 得到空地址，页面主动禁用了协议链接和登录按钮。

修复内容：

- 默认公开协议地址为 `https://auth.teslalink.joviluma.com`。
- Release 构建要求显式传入该地址，并拒绝其他 host。
- 登录页改为说明、协议确认、Tesla 云端登录、自托管设置四个面板。
- 只点击登录入口验证到官方 `https://auth.tesla.cn/oauth2/v3/authorize`，没有输入账号密码。

### 设置重新授权返回问题

重新授权会异步清理旧会话，自动登录重定向可能先于导航回调清掉 Settings 返回栈。登录页现在始终提供返回按钮：有上一级则返回，没有上一级则进入设置，并抑制返回窗口内的自动重定向。

真机验证：设置 → 重新授权 → 登录页 → 返回设置，结果通过。

### 高德配置页

高德页面改为：

- 顶部进度面板和三步流程图示。
- 步骤内容面板。
- Android 包名/SHA1 面板。
- 隐私同意和 Key 操作面板。
- 统一的返回、上一步和下一步按钮。

真机已验证三步导航、复制信息入口和 Key 输入对话框，无新增 MateLink 崩溃。

## 设备 ANR 观察

曾捕获一次：

```text
ANR in com.matelink
Reason: Input dispatching timed out ... Waited 5002ms for MotionEvent
```

ANR DropBox 采样显示进程处于 OnePlus `__refrigerator`，没有可归因的 Java/Kotlin 阻塞栈；重启后重复设置、登录和高德路径未再复现。因此当前记录为设备系统冻结/输入超时观察项，不宣称已修复应用 ANR。

## 验证产物

- APK：`E:\Claude_allow\Download\matelink-1.4.4-login-amap-panels-final-20260902.apk`
- SHA-256：`A5D85DDEFA674353223589694D9CAF1AB03F3A58156AD582FDB050D2187A1AD`
- 包名/版本：`com.matelink` / `1.4.4` / `versionCode 16`
- 设备截图：`E:\Claude_allow\Download\matelink-1.4.4-login-panels-device-final-20260902.png`
- 高德截图：`E:\Claude_allow\Download\matelink-1.4.4-amap-panel-device-final-20260902.png`

## 边界

- 本轮未修改 iOS。
- 本轮源码提交到 `main`，未部署服务器。
- 真实虚拟钥匙、Fleet Telemetry、位置/胎压/行程/充电事件仍需真实车辆验证。
