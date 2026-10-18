# Dashboard 与车辆外观修正

Jovi指出小程序固定白车及参数位置与App不同，本轮重新按原App的Dashboard组件实现；旧提交8312的测试和CI不作为本补丁资格。当前阶段只完成源码与开发工具范围，整个App功能等价、真实账号、车辆及手机验收继续按矩阵分别记录。

## 车辆首页

以原App的Header、ActivityHUD、VehicleHeroImage、InfoCard和DashboardScreen为准：来源/时间、可收起驾驶/充电HUD、165dp图框、45sp整体SOC、续航与目标电量、锁/插枪/空调/哨兵、开口提醒、里程/位置、可用GPS地图、内外温度、四轮胎压、7天电量趋势及充电三列。InfoCard标签与图标在上、同字号值与单位在下；行内卡片间距12dp。历史页MetricValue和全局样式不改。

首页使用已有snapshot只读合同，保留同响应单位、0/false/负功率及逐轮观测。仅HTTP404/501允许回退status；401/429/5xx/网络错误不掩盖。观测包时间不冒充逐字段时间，缓存不保存位置、坐标、围栏。field_sources可能含服务端旧标签，只能表达混合来源，不能证明逐字段新鲜度。

## 外观识别与手选

账号返回的车型/颜色/轮毂/trim参与匹配，兼容3/Y/S/X及原App九种款式。未知颜色不替换成白色，共享颜色不覆盖已知旧款轮毂。实际cars合同不提供年款，未据VIN或年份猜测焕新；Tesla官方说明上海/柏林VIN年份为生产年份，与销售年款及购买年份不等同。

相机入口打开可滚动的外观选择：车型、普通/焕新等款式、年款、颜色、轮毂；所有可选组合恰好覆盖原App97张原图。未知资料时默认黑色Model Y焕新版，明确标为预览，不创建车辆ID或遥测。已知车型缺颜色时，编辑器保留该车型。手动年款独立保存，不改变款式。

偏好按API来源、账号、稳定车辆UID隔离；未登录预览单独存储且不迁移到账号车辆。保存、恢复自动及注销清理只操作已知本scope记录；配额/存储失败保留旧值，未知schema不覆盖、不删除。此入口提供外观选择，并非跨端自定义照片同步。

## 资源与验证

97张PNG的SHA、720×405尺寸、RGBA透明格式均保留；六个资源分包避免全量图进入主包。原生组件保留require.async作用域，实际Image加载成功才完成；失败可重试，并拒绝账号/车辆/页面代际改变后的迟到结果。npm test/build和CI均执行资源完整性及内部包体预算检查；预算为主包2MiB、每分包1.75MiB、合计12MiB，不作为平台限额声明。

本地全量48文件612项Mini测试、3项原图完整性测试、类型检查和构建通过。真实页面消费者涵盖单位、空值、负值、离线重开、切账号/车辆、手选保存/恢复、年款独立、预览隔离、存储失败、车型别名及首次show/重开默认图。独立复审发现的y/Model Y编辑器崩溃已修复并复验。

开发工具证据位于 `E:/Claude_allow/Download/matelink-mini-app-exact-20261016/`；固定源码身份、完整同SHA CI及最终候选包以该目录交付清单为准，不能用旧8312资格替代。

官方DevTools SDK3.17.3原生验证已通过：默认黑色焕新Y、灰色普通Y、黑色普通3、红色焕新Y的Image资源键及真实bindload均匹配；通过原生车辆Picker依次切换，并取上下屏截图。相机按钮打开真实可滚动外观选择框，将红色Y手动改为黑色焕新3后保存，组件实际加载 `m3h_PBSB_W38A`。上述账号、传输和车辆数据全部为明确标识的合成fixture；结束恢复request方法、只移除本次fixture scope，不清原有业务存储。

## 平台与保留边界

Jovi已手动保存request合法域名；真实无凭据wx.request得到HTTP401/request:ok，证明HTTPS/合法域名通达。Jovi随后选择暂不登录，本轮未替他同意协议或登录：真实账号、Tesla关联、车辆数据、Android/iOS微信手机、体验版/正式发布未验收，未上传或发布。

消息推送配置解释已提供。当前未接微信回调校验/解密、订阅模板/用户授权/服务端发送/微信回执；没有提供一个虚构可用URL，也未发送通知。其它App深层数据及页面缺口保留在完整矩阵。

原E App只读，1870文件哈希与HEAD92cb基线无漂移；本轮未改生产、数据库、原App安装或main。

参考：[Tesla VIN年份定义](https://service.tesla.com/docs/ModelY/ServiceManual/2025/en-au/air/GUID-BB4CE449-3F8E-4905-AF4A-96DFA87535B5.html)、[微信消息推送](https://developers.weixin.qq.com/miniprogram/dev/framework/server-ability/message-push)、[微信订阅消息](https://developers.weixin.qq.com/miniprogram/dev/framework/open-ability/subscribe-message)。
