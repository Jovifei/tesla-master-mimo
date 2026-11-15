# 上线前逐页数据链路审查

Jovi要求逐页逐参数审查获取、计算、保存与展示，继续禁止修改页面及参数布局。本轮先审查与验证，不据审查请求自行改产品源码、提交、安装或操作生产。因原目录有其他任务并发写入，Jovi已明确授权新worktree；现用`E:\project\_worktrees\matelink-release-data-audit-20261026`，分支`codex/release-data-audit-20261026`。从`cdfe0b26`复制当前候选954文件，前后/目标哈希一致；不改原目录。

## 验收与证据

覆盖NavGraph的39个路由及实际可见组件；每个展示位置必须映射到真实来源/配置/用户输入，明确公式、单位、0/null/负值/覆盖、账号/车/来源/时间窗、Room与离线重开、UI消费者及现有测试。纯静态标题/设置项不要求车端采样，但要注明实际来源。

按`SOURCE_OK`（源码链可追）、`BUG`（源码可复现缺陷）、`SOURCE_DATA_MISSING`（缺真实采样/来源能力）、`UNVERIFIED`（缺设备/提供方证明）区分。源码和单测通过不能声称用户真车每格都有数据；不能填假0、假曲线、当前值补过去、固定容量伪能耗。

## 分工与覆盖

使用Jovi指定的GPT-6.1 Sol/xhigh，最多3子agent同时工作，父任务串联覆盖及负面复核。每个子任务只写自己的审查文档，不写产品/测试、不执行构建或设备操作。

- [x] 01 登录与首页：TeslaLogin、Dashboard及组件。
- [x] 02 充电：Charges、ChargeDetail、CurrentCharge。
- [x] 03 行程与驻车：Drives、DriveDetail、DriveCurves、ParkedDetail，含路线/曲线。
- [x] 04 分析：Battery、Efficiency、Range、Vampire、TemperatureTrend、TpmsTrend。
- [x] 05 统计：Stats、CountriesVisited、RegionsVisited、Cost、Mileage、Timeline。
- [x] 06 长途与报告：Trips、CreateTrip、TripDetail、AnnualReport、AnnualReportPDF、Export。
- [x] 07 父任务：More、Settings、TariffConfig、TpmsSettings、AmapSetup、AmapPreview、About、DataReadiness、Updates、Vehicle3d、WhereWasI、SentryHistory。

## 执行步骤

- [x] 阅读AGENTS/相关lessons、已有底座说明和Obsidian检索入口；确认旧记录不能覆盖当前候选。
- [x] 冻结范围，按39路由建立分工；图谱工具不可用，使用受限精确源码检索替代。
- [x] 七组完成39路由、503条字段/位置检查记录（含1条历史移除项），合并来源/公式/保存/展示与缺失原因。
- [x] 两轮独立反证收敛误报；Kotlin审查11条件8fail/3pass，DAO实际SQL7条件4fail/3pass；证据层级明确，不冒充实车。
- [x] 冻结954文件无漂移；完成README总审与FIX-PLAN授权清单。Hub后续上报未成功，绑定/连接错误已记录，停止自动重试。

证据目录：`docs/audits/2026-10-26/full-data-chain/`。上一轮本地1356/变体通过为继承证据，不能替本轮逐字段审查。若沙箱限制验证或Hub上报，保留证据，按实际限制报告，不声称已通过。

## Review

源码审查完成，发布结论NO_GO。每一检查位置有来源/公式或直传/保存/scope/UI引用及证据边界；实际失败需修复，缺真实采样不能伪造。原目录及候选产品未改；新增修复待Jovi授权，不自动安装/发布。详见新根审查目录README.md。
