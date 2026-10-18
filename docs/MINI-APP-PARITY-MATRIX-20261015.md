# MateLink App / 小程序完整入口与数据链矩阵

2026-10-18续修：Dashboard布局/车色及手动外观选择见 `RPT-2026-10-18-dashboard-exact-and-appearance.md`。下方2026-10-16域名阻断为历史记录；域名请求已通达，真实登录/手机按Jovi本轮选择暂不执行。其它数据合同和深层功能缺口继续保留。

本表以实际 Android `ui/navigation/NavGraph.kt` 的全部 `Screen` 及 `ui/screens/more/MoreScreen.kt` 为入口合同。日期为阶段标识，不把跨午夜后继续实施拆成新阶段。冻结基线 main `92cbf11ac0fd688f7eb3ba1cfa610910d79637fb`、parent `d951853e4c81ebb05c0a7ce6045bbe81014a92e8`；PR18 已合并。基线 CI 不能替最终候选 SHA 背书。

判定：MATCH 表示所列源码/合成消费者合同已验证；FIX_REQUIRED 表示缺少实现或仍有明确行为差距；PLATFORM_DIFFERENCE 必须有真实平台能力限制证据；NOT_VERIFIED 表示已有实现但尚无所需运行/平台证据。仅入口可点击、共享标题、空卡或通用统计摘要，不能使整页获得 MATCH。未移植页面必须记 FIX_REQUIRED，不能因为工作量或阶段优先级记平台差异。

矩阵是源码交叉审查，不等于手机或微信平台验收。当前所有实账号、实车和微信体验版运行结论均为 NOT_VERIFIED。新增工具/分析页由同一候选实现，最终集成后须逐行依据消费者测试和实际工具验收更新本表；下面明确保留尚未达到 App 深度的差距。

## 全部导航入口

Android 源文件均以 `android/app/src/main/java/com/matelink/` 为前缀；Mini 均以 `miniprogram/src/` 为前缀。路由参数 carId 必须解析为当前已验证 car 对象，不允许数字 ID 单独授权。

| Android Screen / 真实入口 | Android 实际消费者 | Mini 对应候选入口 | 数据 / 行为合同与当前判定 |
|---|---|---|---|
| TeslaLogin | `ui/screens/auth/TeslaLoginScreen.kt`、`NavGraph.kt` | `pages/auth/index` | MATCH（源码）：微信身份、Tesla关联各自独立；origin绑定会话、事务一次领取及撤销回归。NOT_VERIFIED：真实微信/Tesla往返。 |
| Settings | `ui/screens/settings/SettingsScreen.kt` | `pages/tools/index?kind=settings` | MATCH（源码）：账号/授权、系统明暗说明、规范化单位、微信权限及分时电价/关于入口。FIX_REQUIRED：App可配置显示单位/货币、额外显示、同步/连接测试的细分操作；固定HTTPS来源是本渠道明确安全合同，不自动认定其它控件无法实现。 |
| TariffConfig | `ui/screens/settings/TariffConfigScreen.kt` | `pages/tools/index?kind=tariff` | MATCH（源码/消费者）：币种与峰平谷价格、真实0、精确账号/origin保存重开、共享预算、切号拒绝旧草稿，IANA时区持久化且UTC测试不改8.5边界断言。MATCH（源码）：默认电价/启停、峰平谷价格及小时区间编辑、跨午夜分段、24小时无重叠校验和显式恢复App参考默认；估算不反写实际账单。NOT_VERIFIED：真实微信输入与本地时区行为。 |
| TpmsSettings | `ui/screens/settings/TpmsSettingsScreen.kt` | `pages/tools/index?kind=tpms`（设置部分） | MATCH（源码/11消费者测试）：目标/高低阈值、启停、变化阈值0.1–2bar及1–720小时、精确作用域保存/重开、坏档案保留与迟到编辑拒写。仅对真实新鲜逐轮观测作前台比较；后台通知/授权拒绝仍NOT_VERIFIED。 |
| AmapSetup | `ui/screens/map/AmapSetupGuideScreen.kt`、Settings地图入口 | `pages/tools/index?kind=location` | FIX_REQUIRED：App地图配置/可用性反馈不能用经纬度文本替代；微信地图域名/权限待核实。 |
| AmapPreview | `ui/screens/map/AmapMapScreen.kt` | `pages/tools/index?kind=location` | FIX_REQUIRED：地图预览/失败重试/位置定位交互需独立验证；未证实平台无法实现。 |
| Dashboard | `ui/screens/dashboard/` | `pages/vehicle/index` | SOURCE_MATCH：按实际Header/HUD/Hero/InfoCard顺序、间距和字段层级实现，97原图按车型/车色/轮毂匹配；支持隔离的手选及默认黑色焕新Y预览。合同保留单位、负功率/0/false、源/时间未知及TPMS逐轮时间。开发工具合成图像与页面单独取证；真实账号/车辆/手机、用户最终视觉确认NOT_VERIFIED。无跨端自定义照片同步，年款缺失允许手选且不猜焕新。 |
| More（第四Tab） | `ui/screens/more/MoreScreen.kt` | `pages/me/index`（显示“更多”） | MATCH（源码入口）：分析/报告/系统分组及已有普通行程/长途/分析/报告/设置路由；就绪清单沿用实际车辆诊断。NOT_VERIFIED：微信原生导航与返回。 |
| About | `ui/screens/about/AboutScreen.kt` | `pages/tools/index?kind=about` | MATCH（源码）：当前候选版本、服务条款/隐私入口及返回。MATCH（源码）：本版更新说明及问题反馈地址复制；NOT_VERIFIED：微信实际页面。 |
| Charges | `ui/screens/charges/` | `pages/charges/index` | MATCH（源码合同）：实际币种、输入/电池能量分离、0保留、分页/筛选/详情返回及隔离回归。NOT_VERIFIED：开发工具渲染。 |
| ChargeDetail(carId,chargeId) | `ui/screens/charges/ChargeDetail*` | 充电页内详情 | MATCH（源码/消费者）：电量→SOC/时长→功率→电压电流→温度→费用分区，同一真实响应证据及五图深链；本机手填/清除恢复原始0账单、跨账号迟到编辑拒绝、列表/统计/长途/报告/导出显示层分开。NOT_VERIFIED：真实充电与微信五图。 |
| CurrentCharge | `ui/screens/charges/CurrentCharge*` | `pages/insights/index?kind=currentCharge` | MATCH（源码消费者）：真实 `/charges/current` 进行中/空态、0kW与资格能量显示，独立于历史列表。NOT_VERIFIED：真实充电与微信生命周期。 |
| Drives | `ui/screens/drives/DrivesScreen.kt` | `pages/drives/index` | MATCH（数据与源码层级）：净能量资格、配对均耗、峰值样本、详情写回、缓存重开；顶部时间/SOC/来源及底部四指标按实际App卡片实现。NOT_VERIFIED：微信像素/窄屏字体与返回位置。 |
| DriveDetail(carId,driveId) | `ui/screens/drives/DriveDetailScreen.kt` | 行程页内详情 | MATCH（源码）：顶部时间/SOC/来源、四指标、kWh与资格依据、真实五图深链；停车详情由相邻记录/时间轴进入。NOT_VERIFIED：真实微信返回/滚动上下文。 |
| DriveCurves(carId,driveId) | `ui/screens/drives/DriveCurvesScreen.kt` | `pages/analysis/index?kind=curves&id=…` | MATCH（源码/消费者）：实际detail.drive_details→五个原生Canvas（速度、按时间积分并锚定总距离的估计距离、带符号功率、SOC、海拔）、共享光标/十字线与单图全屏；带符号/0、五图绘制和不缓存位置有消费者验证。NOT_VERIFIED：微信Canvas实渲染/触摸。 |
| ParkedDetail(olderDriveId,newerDriveId) | `ui/screens/drives/ParkedDetailScreen.kt`、`AdapterModels.kt` | `pages/analysis/index?kind=parked&olderId=…&newerId=…` | MATCH（源码）：真实端点、SOC/时间、合格能量/环境/样本/覆盖及充电重叠；无合格stored_change不显示kWh/平均W。NOT_VERIFIED：真实停车响应与渲染。 |
| Battery | `ui/screens/battery/BatteryScreen.kt`、`BatteryModels.kt` | `pages/insights/index?kind=battery` | MATCH（源码）：真实health容量/健康比例/额定能耗/范围及unsupported空态，不由SOC猜容量。NOT_VERIFIED：Go当前battery_health:null+unsupported及无健康趋势历史合同，属于具体数据缺口；可用后仍须实值/趋势验收。 |
| DataReadiness | `ui/screens/readiness/` | 车辆六步诊断 / 更多就绪入口 | MATCH（源码证据分类）：服务连接/车辆授权/采集/历史/缓存各自时间来源；NOT_VERIFIED：真实provider状态。 |
| Mileage(targetDay) | `ui/screens/mileage/` | `pages/analysis/index?kind=mileage` | MATCH（源码）：年度→月份→真实日历→日期→实际行程详情、日期筛选/分页覆盖、距离/时长/配对均耗。FIX_REQUIRED：App额外成本归因仅在实际费用/电价依据可用时实现；NOT_VERIFIED：微信日历排版/真实时区交互。 |
| Updates | `ui/screens/updates/SoftwareVersionsScreen.kt`、`BatteryModels.kt` | `pages/tools/index?kind=updates` | MATCH（源码）：真实updates版本/时间列表与前后分页；只标当前响应。NOT_VERIFIED：云当前空数组与真实软件历史数据。 |
| Stats | `ui/screens/stats/Stats*` | `pages/analysis/index?kind=stats` | MATCH（源码）：综合分析→距离/时长/净能量/充电能量真实纪录钻取→行程概览→充电概览→趋势，配对均耗/独立覆盖/币种、AC/DC/未知模式能量覆盖及温度配对分组。FIX_REQUIRED：App建议/地域/导出深联动；NOT_VERIFIED：微信图表实渲染。 |
| CountriesVisited(year) | `ui/screens/stats/CountriesVisited*` | `pages/tools/index?kind=countries` | FIX_REQUIRED：App经合法地理数据聚合，不存在云同名端点；不从中文地址子串猜国家。地理字段/归属证据缺失单列NOT_VERIFIED，入口壳不算移植完成。 |
| RegionsVisited(countryCode,countryName) | `ui/screens/stats/RegionsVisited*` | `pages/tools/index?kind=regions` | FIX_REQUIRED：国家→区域导航、地域边界/计数/年份过滤需真实行政归属数据；不能复用国家空卡冒充。 |
| WhereWasI(timestamp) | `ui/screens/wherewasi/WhereWasI*` | `pages/tools/index?kind=location` 的指定时间查询 | MATCH（源码/消费者）：明确本机日期/时刻查询已读行程/充电/相邻窗口，授权在线详情只取已有最近GPS采样并单列实际采样时间，不插值、不缓存；位置无独立时间保持未知。NOT_VERIFIED：完整历史覆盖/地理编码归属及实际微信地图。 |
| Trips | `ui/screens/trips/Trips*`、`domain/TripRepository` | `pages/insights/index?kind=longTrips` | MATCH（源码候选）：本机人工多段长途、合格行程/充电段及费用币种/覆盖，本机持久化上限20条/50段/64KiB且共享总预算。NOT_VERIFIED：App既有Room长途不自动视为已同步，真实离线微信验收待做。 |
| TripDetail(tripStartDate) | `ui/screens/trips/TripDetail*` | `pages/insights/index?kind=longTrips&tripId=…` | MATCH（源码候选）：同scope已保存段重建、顺序/费用/能耗覆盖、编辑名称/段并保存、确认删除、实际行程/充电深链。NOT_VERIFIED：原App长途与真实微信重开验收。 |
| CreateTrip | `ui/screens/trips/CreateTrip*` | `pages/insights/index?kind=longTrips&mode=create` | MATCH（源码候选）：人工选择合格历史段、命名、冲突校验、保存/重开；无生产写入。NOT_VERIFIED：实际微信表单与长段分页体验。 |
| SentryHistory | `ui/screens/sentry/SentryHistory*` | `pages/tools/index?kind=sentry` | FIX_REQUIRED：App本机事件来源/真实时间/区间；后台云未提供哨兵事件接口，不能将当前sentryMode当历史事件。 |
| TpmsTrend | `ui/screens/tpms/TpmsTrend*` | 车辆胎压卡 / `pages/tools/index?kind=tpms` | MATCH（逐轮解析/本机有界观测合同）；MATCH（源码/26消费者测试）：原生四轮离散点图、24小时/7天/30天、源点游标、600点总绘制预算、0/来源/未来/旧点资格及默认可见限额。NOT_VERIFIED：微信真实趋势触摸。本机前台观测与App Room完整历史能力不混称，已有旧样本不导入。 |
| TemperatureTrend | `ui/screens/temperature/TemperatureTrend*` | `pages/tools/index?kind=temperature` | FIX_REQUIRED：App本机观测温度时序、区间/内外温对照；历史行程均温与实时均不能冒充连续温度曲线。 |
| AnnualReport(year) | `ui/screens/reports/AnnualReport*` | `pages/tools/index?kind=report` | FIX_REQUIRED：指定年完整报告、日期范围/覆盖/地图/费用等实际分项；局部历史报告必须标局部。 |
| AnnualReportPDF(year) | `ui/screens/reports/AnnualReportPDF*` | `pages/tools/index?kind=report` 内“导出年度PDF” | MATCH（源码/结构）：实际本地PDF字节、xref、多页中文、范围/单位/覆盖说明；FIX_REQUIRED：报告内容全部App分项深度；NOT_VERIFIED：微信PDF打开/分享与中文实渲染。 |
| Export | `ui/screens/reports/Export*` | `pages/tools/index?kind=export` | MATCH（源码）：固定无位置/身份/凭据投影CSV、公式转义、实际文件写入/分享调用；MATCH（源码）：CSV/JSON、行程/充电/全部选择及日期范围，手填与原账单分列；NOT_VERIFIED：微信实际文件/分享验收。 |
| Vehicle3d | `ui/screens/vehicle3d/Vehicle3dScreen.kt` | `pages/tools/index?kind=vehiclePreview` | MATCH（源码候选）：App本身也是占位线框，Mini可旋转线框准确标示非实车模型；MATCH（源码）：双触缩放/单触平移、滑动旋转、缩放控件、车门/前备箱/充电口本机示意开关及重置；NOT_VERIFIED：微信透视渲染。 |
| Efficiency | `ui/screens/efficiency/Efficiency*` | `pages/analysis/index?kind=efficiency` | MATCH（源码）：配对加权均耗/趋势、90天夏冬、个人最小/中位/最大、速度分组、逐行百分位柱及真实行程钻取。NOT_VERIFIED：真实微信图表/样本验收。 |
| Cost | `ui/screens/cost/Cost*` | `pages/insights/index?kind=cost` | MATCH（源码候选）：实际0/币种分组、月度AC/DC/未知模式、地点Top5、同次费用+合格AC输入配对单价；缺账单电价估算单列且不覆盖实际0。NOT_VERIFIED：真实费用/电价时区与微信页面。 |
| Range | `ui/screens/range/RangeViewModel.kt` | `pages/insights/index?kind=range` | MATCH（源码候选）：同响应rated端点偏差、夏冬与≤40/>40km/h分组、日期/分页；个性化估算同App90天条件5条/100km或全局10条/300km门槛并需有效容量来源/时间/资格。NOT_VERIFIED：Go当前range_rated空与实车样本。 |
| Vampire | `ui/screens/vampire/Vampire*` | `pages/analysis/index?kind=vampire` | MATCH（源码）：真实standby合格stored_change合计、无充电且覆盖已完成的相邻窗口SOC差，不估造kWh；cloud collecting保持未知。NOT_VERIFIED：真实停车/待机数据与微信渲染。 |
| Timeline | `ui/screens/timeline/Timeline*` | `pages/analysis/index?kind=timeline` | MATCH（源码）：行程/充电/实际相邻停车按观察时间联合、种类筛选与真实详情深链；coverage分开，分页未完成不称完整日。NOT_VERIFIED：微信长列表/返回位置。 |

## More hub 的实际层级

MoreScreen 分析区真实顺序：Statistics → Battery health → Mileage → Trips（回到普通Drives）→ Long trips → Efficiency → Cost → Range → Vampire → Timeline。报告区：Annual report → Export。系统区：Data readiness → Software updates → Sentry history → Settings → About。

CurrentCharge 从充电相关入口进入；Countries/Regions/WhereWasI 从统计/里程/位置上下文进入；DriveCurves/ParkedDetail 从行程详情/相邻记录进入；TpmsTrend/TemperatureTrend 从对应观测状态进入；AnnualReportPDF 从年度报告进入；CreateTrip/TripDetail 从长途进入；AmapSetup/AmapPreview/TariffConfig/TpmsSettings 从设置进入。仅把它们全部平铺到More不是原交互层级等价。

## 数据链及资格

| 指标 | 实际字段 / 单位 / 时间 / 来源 | Mini解析→计算→缓存→消费者 | 判定与证据 |
|---|---|---|---|
| SOC、续航、里程、速度、功率、温度 | status.battery_details / odometer / driving_details / climate_details；同响应units；一般包observed_at不能赋给各字段 | api.normalizeCarStatus→vehicle_cache→车辆页；mi→km、F→C只做一次，0/false/负功率保留 | MATCH源码测试；实际来源与字段时间NOT_VERIFIED |
| 净能量与平均能耗 | energy_contract.net_energy 的method/source/quality/精确窗口，或合格旧API/power/SOC容量依据；kWh→kWh/100km | driveEnergyReceipt→resolveDriveEnergy→scope history cache→controller→真实卡片/详情 | MATCH共享合成场景及旧回归；未知型号不猜60kWh，explicit unknown立即撤销 |
| 区间统计 | drives与charges真实分页meta和独立数据资格；日期按观察事件 | analysisApi.getHistory→已有collectHistoryInterval，上限20×25/500；完整与部分分开 | MATCH有界读取合同：25页/500行上限、取消/迟到/跨账号真实请求回归通过；页面分项仍逐行FIX_REQUIRED直到消费者验收 |
| 最高速度 | speed_max + speed_sample_count>0 + observed_route_samples + 同响应单位 | api.normalizeDrive→卡片；不能借车辆当前速度或平均速度 | MATCH现有解析/消费者回归 |
| rated range与工况 | range_rated.start_range/end_range、speed_avg、outside_temp_avg；同响应length/temperature | api.normalizeDrive→history cache→range分析；云端缺字段保持null | FIX_REQUIRED高级消费者验证；字段解析由新真实响应测试验证 |
| 充电费用 / 能量 | 实际currency/currency_code/cost_currency；battery_input/ac_input/source/quality | normalizeCharge→cache→列表/详情/cost | MATCH既有资格与币种回归；新cost完整交互待验 |
| 曲线 | detail.drive_details.date/speed/power/battery_level/elevation；本响应单位及行程窗口 | analysisApi.getDriveEvidence→在线页面，绝不存轨迹数组/经纬度 | FIX_REQUIRED消费者/开发工具验收；pointsComplete=false，不宣称全历史 |
| 停车 / standby | /api/matelink/v1/cars/:id/parked/:older/:newer；stored_change端点、无驾充证据；standby云返回collecting | analysisApi.getParkedDetail/getStandby；SOC差与kWh分别；无proof平均W也未知 | FIX_REQUIRED消费者；真实停车字段缺口不能0替代 |
| 胎压 | tpms_details逐轮pressure_bar+各轮observed_at+telemetry_mqtt/collector_received_at | parseTpmsDetails→restoreTpmsSnapshot→本机前台有界history→四轮卡/趋势 | MATCH合成资格/去重/重开；实际逐轮字段NOT_VERIFIED |
| 新事件提示 | 已完成新到达id/session/source/time；首次导入抑制与持久去重 | scope事件记录→前台提示→正确详情；关闭不清历史 | MATCH合成消费者后由最终测试填写；微信后台订阅资格/模板/回执/实际收到NOT_VERIFIED |
| 安全 / 缓存 | HTTPS固定origin+account+稳定UID+session generation；每行source | session/API拒绝外源旧令牌、异常JSON/Location；三类缓存总预算、无VIN/上游凭据/轨迹 | MATCH源码红绿回归；微信底层redirect/TLS/安全域名仍NOT_VERIFIED |

## 确认的真实端点边界

`TeslamateApi.kt` 与 Go `main.go` 双向确认：cars/status/drives/drive detail/charges/charge detail/current/battery-health/updates/data-readiness，adapter capabilities/snapshot/history-context/parked/standby。小程序高级只读适配器使用现有认证requestJson及已验证车单proof，跨账号/UID/origin/代际或页面取消后拒绝返回，并不增加生产写接口。

stats/mileage/efficiency/range/cost/timeline/countries/regions/where-was-I/annual-report/PDF/export/3d 等并无云同名端点。App使用历史、Room、本机配置/文件/地理数据实现；Mini必须实现可行的对应消费者，并分别说明云未提供字段、本机前台历史尚未累积、格式导出待实现等具体差距。没有同名端点不是微信平台不支持的证据。

## 验收证据入口与未完成项

共享fixture：`fixtures/miniprogram-app-parity-v1.json`；Android `MiniAppSharedParityTest` 通过真实Moshi→资格→持久证据codec重建→列表消费者→单位格式；Mini共享fixture通过真实API→scope缓存→页面重开。数据安全红绿日志及聚焦XML位于 `E:/Claude_allow/Download/matelink-mini-parity-20261015/data-security/`。必须以最终集成测试、微信开发工具渲染/网络/缓存/域名及同SHA完整CI填入最终证据，不引用旧SHA基线绿灯代替。

正式后台/订阅/扫码登录、真实体验版、Android/iOS微信人工验收仍需各自证据。生产数据库、服务配置、旧Android安装及Owner原树此次均未由本矩阵或只读API实现更改。


## 本阶段集成与平台条件（2026-10-16）

- 本地Android完整Debug/Release各1023测试，0fail/error；Release8项原条件skip；lintRelease/assembleRelease通过。Go隔离PG16 404/0fail/0skip、Linux TLS29+部署策略、Web13通过，源码审计calls/cleanup通过。最终Mini与同SHA CI以交付报告实际计数为准。
- 官方微信开发工具已登录、AppID正确、SDK3.17.3。编译缓存刷新后四Tab及曲线在390逻辑宽度实际渲染，无运行异常；使用明确标识的合成账号/车辆和模拟传输，只证明工具UI层。原始会话/业务存储未清理，测试新建scope在结束时逐项移除。
- **BLOCKED_PLATFORM_CONFIG**：保持urlCheck=true时，真实无凭据GET `https://api.teslalink.joviluma.com/api/v1/cars` 实际返回 `request:fail url not in domain list`。真实登录、WebView关联、后续体验版及redirect/TLS验证不能用合成UI替代。需管理员对该AppID授权合法域名等具体平台配置；未上传、未发布。
- 订阅消息的主体/类目/可用模板、用户同意/拒绝、正确接收人、服务端发信/微信回执/实际收到：NOT_RUN。现有前台去重不是后台订阅消息；实现后不自动主张已送达。
- 行政归属/地域边界、完整温度与哨兵事件历史、健康趋势缺独立服务合同或本机既有档案共享途径，保留具体未完成项。需新数据/地理权限合同，不能将当前状态或地址子串伪造成完整历史。
