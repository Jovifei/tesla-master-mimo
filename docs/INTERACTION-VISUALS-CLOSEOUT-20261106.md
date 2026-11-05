# 交互视觉阶段收尾与独立接收

仅限 Jovifei/tesla-master-mimo。App 新分支 codex/app-interaction-visuals-20261104；Mini 新分支 codex/miniprogram-interaction-visuals-20261104。保留原开发分支、main、Owner 工作区、手机与生产。

## 已接入而非设计占位

车辆连接、Tesla 官方授权、可选微信绑定、四轮位置/趋势点选、A/B 端点焦点、驻车已记录充电区间、开口状态展开、行程/充电空态与原品牌加载生命周期都连接到原页面现有状态。没有改动主导航、指标顺序、真实地图、官方登录动作、会话键和数据结构。

App 对照源码 9be68e7cd7ccf1e4473ce899166e9f5e7352655e；该源码最终结果仍必须核对自己的完整 CI，不使用旧绿色替代。Mini 收尾提交需同样检查自身完整 SHA、tree、parent 与所有 Actions。

## 本轮视觉复核修复

此前 App 连接插图使用既有新版 Model Y，Mini 新增连接/充电空态组件仍引用另一张旧版 Model Y。现在只为这些新增组件复用仓库已有 myj_PPSW_WY19P.png 的同一 Git blob，720×405、82,000 bytes，SHA-256 450d729623ffdb4aa7f2936afd6b0522f918f0b1c8de1f81cdb90baf71a56d54。原车型目录、首页车型选择、外观设置和所有现有图片不变；插图明确不代表当前绑定车辆。位置俯视图仍是两端同形的中性示意，不伪称已识别具体车型。

新增 VehicleArtworkParity.test.tsx 通过实际 ConnectionVisual 与 EmptyHistoryVisual 消费者检查同一离线图片、说明标签、不触发网络/存储及 catalogue 字节不变；静态视觉校验同时固定哈希与尺寸。不是只测试资源文件存在。

## 验证要求

Mini 使用原锁文件安装，运行严格类型、全部资源/消费者测试、生产 WeChat 构建、四时区回归和 46 张真实 React 组件浏览器截图。检查中文实际字形、320/412 视口、浅/深色、1.8 倍字体、点选与还原、作用域清空、减少动态效果、后台暂停。最终读取 JUnit XML 与 PNG，浏览器证据不冒充微信原生/真机验收。

App 的完整 Android Debug/Release、lint、R8、API35，及 Go/隔离 PostgreSQL/race/TLS/Web 由同 SHA 的永久工作流执行。此前一次性 API 兼容容器因无实际 MQTT 依赖启动失败，App 测试脚本补齐内部网络上的真实 Mosquitto，并在删除容器前保留合成服务日志与退出状态。没有删除失败断言、接受跳过或更改生产桥接器。

## 本地接收 prompt

继续同一 MateLink 远端交互视觉阶段；本地仅独立接收、复审、测试与构建，不接手业务实现。先 App 后 Mini，分别使用远端最终回执给出的完整 SHA，不以浮动分支尖端替代。

1. 仅访问 Jovifei/tesla-master-mimo。保留原 E 盘、Owner dirty/untracked 文件和所有用户数据；用两个新的干净 worktree。核对 HEAD/tree/parent、基线祖先和完整差异。App 基线 984196d8c1a6deaff124eaa8dd6c45f55c40e700；Mini 基线 55d49b921f541c5c1d793c88e64dfaac51ee32b2。
2. 按各自源码的 docs/LOCAL-RECEIVE-INTERACTION-VISUALS-20261104.md（App）和 docs/LOCAL-RECEIVE-INTERACTION-VISUALS-20261105.md（Mini）复跑完整验证。保留 Release 配置保护，不索要或回传令牌/签名秘密。独立解析 XML，并按目录区分 Debug/Release。
3. 在隔离模拟器核验真实点击、字体缩放、浅深色、零/未知/旧观测、切车切号、A/B 非轨迹说明、授权状态优先级和停充缺失边界；Mini 另用微信开发者工具核验原生胶囊、导航与图片渲染。不得自动上传或发布。
4. 仅在另行获得设备安装授权后做相同包名/证书的覆盖安装；不得卸载、清数据或用旧 APK 冒充已验证数据回滚。生产/API、DDL、历史修复、自然 Fleet/TPMS/通知和真实 Tesla/微信操作都是单独门。
5. 回传一份脱敏 GitHub 证据：两个 full SHA/tree/parent、命令/退出码、测试 totals/fail/error/skip、产物哈希、截图类别和确实观察到的失败。不得修改业务源码、弱化断言、覆盖另一分支后端或合并 main。失败回报原远端阶段。

## 退出与回滚边界

源码层可保留旧分支与固定提交；这不等于数据库回滚。新增视觉不写用户数据、不自动唤醒车辆、不制造轨迹、不推断具体车门；未知保持未知、有效零保留。全产品逐屏像素等同、全量加密备份恢复、真实账号授权和自然数据验收不在本次合成视觉交付中宣称完成。
