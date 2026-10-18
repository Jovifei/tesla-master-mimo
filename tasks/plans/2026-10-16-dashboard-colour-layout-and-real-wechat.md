# 车辆页精确对齐与真实微信验收

## 授权、起点与边界

Jovi明确授权新增 `https://api.teslalink.joviluma.com` 为小程序 `wxc84899ab6146f1be` 的request合法域名，继续真实微信验收；扫码由Jovi完成。他再次指出车辆颜色、参数位置和布局不一致，当前视觉验收重新打开为CHANGES_REQUIRED。

当前C工作树起点 `8312b6577a4f4b593c84cd3c2f52f2b5f9dc0565`，tree `cfc8e89236182242ed5ff0df66f243e1c2c82bde`，PR19仍open/Draft、未合并。该提交的440测试/16CI作业资格保留，但不作为新补丁或Jovi视觉验收的替代。

原App以只读 `E:/project/tesla_master/app_mimo` 为准，head `92cbf11ac0fd688f7eb3ba1cfa610910d79637fb`；本轮开始1870文件SHA256清单已冻结，Dashboard/components无Owner代码差异。产品仅写C工作树；下载、生成资源与证据写 `E:/Claude_allow/Download/matelink-mini-app-exact-20261016`。不改生产、main、原App安装、已有数据或网络全局设置。

## 已确认根因与规格

1. Mini仅按3/Y/S/X选择四张白车PNG，虽API/缓存已保留车色与轮毂，最终图像消费者却未使用。App链为Dashboard→VehicleHeroImage→CarImageResolver，使用model/paint/wheel/trim；97原资源约10.3MB，不能无预算直接塞入主包。
2. Dashboard InfoCard是顶部18dp图标与11sp标签同行，下方14sp数值/单位；历史TelemetryMetricCell是另一种合同。Mini错误复用后者，造成标签与参数位置不同。
3. App hero的SOC为45sp Mono整体百分数并带电量标签，Mini隐藏了标签且数字较小。App图框165dp/图155dp，卡间距16dp；原卡圆角的最终CSS级联已接近App，不因读到旧规则而重复改错。
4. 其它字段顺序、状态/空调与地图条件按完整Dashboard组件规格核实后实施；不凭印象重设计。未知/0/负值、单位、scope和缓存资格保持原有回归。

## 执行清单

- [x] 核对当前分支/PR、原App引用与Owner新清单；记录用户再次纠正。
- [x] 公开DNS只读核实三域名已有A记录，NS为burt/leia.ns.cloudflare.com；不新建DNS或改NS。
- [x] 定位固定白车与InfoCard布局错用根因；分别只读审查避免混写。
- [x] 确定原图复用与内部包体预算；97原图六分包，新增颜色/车型/轮毂解析器和实际页面消费者回归。
- [x] 按App Dashboard字段布局实现独立组件，保留历史页MetricValue合同；新增隔离手选与默认外观预览。
- [ ] 本轮真实车色/自定义照片信息若可取得，按实际配置验收；不把白色fixture当实车。
- [ ] 真实微信域名刷新/请求→登录/关联→车单/字段/返回/重开；分别记录HTTP、身份、车辆、手机证据。
- [ ] 新源码全量Mini/typecheck/build、必要共享回归、固定提交及同SHA完整CI；生成新候选、逐文件哈希和并排复核图。
- [x] Owner1870文件及HEAD复核无漂移；未完成项/回退边界见本轮报告。

## 平台状态与人工步骤

浏览器站点安全策略直接阻止自动访问 `mp.weixin.qq.com`，尚未进入扫码页。禁止更换浏览器、CDP、接口等绕过该拦截。已请Jovi手动保存唯一新增request项；以随后官方DevTools真实wx.request结果确认生效，不将人工回复当网络验证。

现有代码只用HTTPS request，无socket/uploadFile/downloadFile/UDP/TCP调用；PDF/导出写本机文件。其它服务器域名与DNS预解析/预连接暂不新增。后续授权WebView业务域名或服务端微信配置如存在具体缺口，再依据实际错误处理；当前不扩大成新域名/部署/订阅发送或上传发布。

## 结果记录

执行中。源码资格、DevTools合成渲染、真实账号/车、手机体验及发布资格继续分开。视觉通过必须对应具体App组件和字段位置，不能仅凭入口能打开或总体颜色接近。

### 平台进展

- Jovi回复已添加并保存request域名；随后表单截图再次确认只填API的request项，其余未使用网络类型和两项预优化留空。
- 官方DevTools、SDK3.17.3，域名检查开启，未登录真实 `wx.request` 返回 `/api/v1/cars` HTTP401和 `request:ok`。此项只证明HTTPS/合法域名请求通达，不代表身份、车辆或手机验收。证据：`E:/Claude_allow/Download/matelink-mini-app-exact-20261016/native-domain-first.json`。
- 当前主项目一次精确 `cleanCompileCache` 后刷新，更多页恢复真实渲染；未清storage/session/auth。已显示2026-08-21版条款、隐私链接和未勾选确认框，请Jovi本人阅读同意后点击微信登录。未代为确认协议或调用接口绕过确认。
- 97张原版PNG的体积预算与分包原生加载仍在独立验证；不能把JS模块下载成功替代主包Image实际显示成功。

### 2026-10-18 用户选择与外观规格

Jovi选择“暂不登录，继续页面修正”，因此真实登录/车辆/手机不在本轮自动继续；不替他勾选协议，也不调用登录接口绕过该选择。后续消息推送表单仅提供配置解释：当前无接收校验/解密端点、订阅模板/授权/服务端发送链，不填写虚构URL、不部署、不发送通知。

外观规则：账号已返回的model/paint/wheel/trim优先匹配原App97张车图。实际Go cars合同不返回model_year/variant/VIN，年款未知保持未知；共享颜色不能覆盖明确旧款轮毂。手动入口位于车图相机按钮，提供车型/款式/年款/颜色/轮毂，九种款式的可选笛卡尔积恰好覆盖97张已有原图。未知资料时默认黑色Model Y焕新版 `myj_PX02_WY19P`，明确标为外观预览，不创建车辆身份或遥测。年款2008..2100仅作为手动资料，与款式独立。

手动偏好按origin/account/stableUID隔离，schema1且source=user_configured；未登录预览另有preview namespace，绝不认领到登录车辆。恢复自动只删除同scope的已知schema偏好，失败保留原值；已确认账号注销清理只覆盖本账号已知偏好，保留preview/其它账号/未来schema。相机入口不冒充自定义照片库。

验证包括实际React页面/API normalizer/缓存/配置消费者及原生资源组件；原生组件保留require.async作用域，并等待真实Image bindload。全部原PNG逐字节保留，六分包及主包分别执行内部预算，构建/CI也执行97SHA、尺寸、透明格式和包体检查。

本轮原生发现并修复：初次onShow清图取消effect后相同配置不重启；原生host样式未继承Taro class；WXSS属性选择器导致实时badge颜色无效；小写y/Model Y可匹配图却使手选款式列表空而崩溃。分别采用页面激活代际、native host几何、状态class、canonical model；独立复审复验与消费者回归保留。

年款来源边界已对照Tesla官方Model Y 2025+ Service Manual的VIN解码表：上海/柏林第十位为生产日历年份，Texas/Fremont为model year。因此本轮不新增一个把所有VIN年份伪装成销售年款/购买年份的接口，也不向第三方提交VIN。来源：https://service.tesla.com/docs/ModelY/ServiceManual/2025/en-au/air/GUID-BB4CE449-3F8E-4905-AF4A-96DFA87535B5.html 。
