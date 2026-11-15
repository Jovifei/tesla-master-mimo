# MateLink 首次登录、待机能耗与设置体验修复记录（2026-09-01）

## 状态

- `LOCAL PASS`：Android Debug/Release 单测、构建、Release lint；JourVolt API 与 Adapter 的 Go 测试和 vet。
- `DEVICE PASS`：同签名 `com.matelink` 使用 `adb install -r` 升级，保留正式包数据；Dashboard、行程、充电、待机、设置、高德向导均可打开。
- `TELEMETRY PILOT PASS`：`NOT PERFORMED`。没有把真实车辆遥测、行程或充电事件写成已验收。

## 用户现象与根因

### 首次登录数据暂时为空

Tesla OAuth 只负责建立 JourVolt 会话并取得当前车辆快照，不会凭空产生历史行程、充电或待机记录。云端 Fleet API 还需要车辆重新联网并继续收集观测；持续位置、胎压和事件采集还受 Fleet Telemetry 配对/配置状态影响。该流程不是普通用户的订阅付费拦截。

此前首次连接提示过于简短，旧兼容服务还会显示“旧版服务器”，用户无法判断是登录失败还是数据尚未准备。

### 待机能耗显示无法加载

Android `VampireViewModel` 原先直接请求 `/api/matelink/v1/cars/{id}/standby`，而云端兼容路由没有实现该可选端点，HTTP 404 被页面直接显示成英文错误 `Standby data unavailable`。这不是车辆本身返回的待机耗电结论。

## 修复内容

- 首次连接弹窗说明实时数据、位置、历史数据和不支持字段的区别；明确“等待车辆/收集中”不是登录失败或订阅要求，后续会在数据源继续收集观测后逐步出现。数据状态页仍保留来源和最近观测时间。
- 云端兼容服务为待机接口返回结构化的 `collecting` 空结果；Android 对 404 或空结果转入 `UnifiedHistoryRepository`，从同一车辆的本机行程/充电历史构造停车候选窗口。
- 本机待机候选只使用相邻已观测行程的结束/开始时间，排除中间发生充电的窗口；没有实测容量或 Telemetry 能耗覆盖时，kWh 和功率保持 `null`，不补造数值。
- 待机无记录时显示中文/英文的“历史正在收集”空态，近 7 天、近 30 天等筛选在小屏上支持横向滚动；不再把可预期的缺失数据显示为通用错误。
- 高德 Key 配置改为 3 步向导：创建 Android Key、复制包名/SHA1 绑定 MateLink、粘贴并验证。保留隐私同意、加密保存、草稿/已保存 Key 验证、取消和地图预览流程；引导图使用 Material 图标，不记录或展示 Key 内容。
- 高级网络展开区按云端/自托管模式显示不同说明，并用服务器地址、认证信息、安全选项卡片分组。云端登录不会再被误导为必须填写自托管 TeslaMate。
- Android 版本升至 `versionCode 15` / `versionName 1.4.3`。设置页提供可见的“本次更新”卡片和对话框，说明上述修复；Release 的 Git SHA 仍表示构建时 HEAD，不冒充未提交变更的提交号。

## 验证证据

- Android 定向回归：待机 404 分类、本机停车窗口派生、充电重叠排除、AMap 三步模型、首次登录文案、设置模式/版本说明均通过。
- Android 全量 `:app:testDebugUnitTest --rerun-tasks` 通过；`:app:compileDebugAndroidTestKotlin` 通过；`:app:assembleDebug`、带正式参数的 `:app:assembleRelease`、`:app:lintRelease` 通过。Release lint 为 0 Error、0 MissingTranslation。
- Go `go test ./... -count=1`、`go vet ./...` 通过；自托管 Adapter `go test ./... -count=1` 通过。
- 签名候选：`E:\Claude_allow\Download\matelink-1.4.3-release-signed-readiness-standby-amap-settings-20260901.apk`，SHA-256 `0C92E0040F192F7229E0710F0C8119A3D63CEDCC03527F3374CBA87A0A968DC1`，`com.matelink`，v2 签名通过。
- 真机 OnePlus 7 Pro `6e4fa92f`：升级前 `1.4.2`，升级后 `1.4.3`；`firstInstallTime` 保持 `2026-08-31 22:36:47`，证明使用覆盖升级而非卸载重装。升级后冷启动、Dashboard、行程、充电和待机空态可见，无新的 `com.matelink` FATAL。
- 当前真机待机空态已显示“待机历史正在收集”，而不是“无法加载数据”；设置页可见云端说明和 1.4.3 更新卡片；高德页可完成三个步骤的导航和现有验证入口。

## 边界与后续

- 服务器新兼容待机路由尚未重新部署到 ECS；当前 Android 已兼容旧服务器 404，因此候选包可先用于设备验证。后续部署必须按现有 API 构建闭包同步规则执行，并单独复核 PostgreSQL/其他容器边界。
- 真正的历史记录仍从登录后车辆联网、手机本地采集和/或 Fleet Telemetry 配置开始逐步积累。没有真实事件上报前，不宣称胎压趋势、行程通知或待机功率完整通过。
- 本轮未修改 iOS；本次主分支同步提交仅包含 Android、兼容服务、测试和工程记录，保留真实 Telemetry Pilot 的未完成边界。
