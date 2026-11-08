# 能耗、驻车与充电完整修复及仓库整合阶段

Jovi于2026-10-08明确授权源码修复、保留数据签名安装、确认废旧产物清理与验证后合并main。远端承担整个代码阶段，本地仅接收、独立测试编译安装和集中回传。不要交只读结论、下一读取点或文档作为源码完成。

## 计划与验收
- [ ] 从阶段分支5677f2c2继续，保留phone14ca与APIbb09已交付修复，整合两个底座而非互相覆盖。原Owner main有dirty，不碰Owner页面修改。原PR12不盲合。
- [ ] 完成行驶、驻车、充电真实字段解析→持久化→API→Room→列表/详情/统计的来源、单位及质量合同，简洁复用现有实现，不能孤立helper。先审查实际字段覆盖再决定可算范围，未知null不填0。
- [ ] 修复行驶完整净能量/距离*100=kWh/100km；摘要按同一有效集合总能量/总距离加权，缺失需显示覆盖范围。保留有效0、负净回收，不把正放电计数当扣回收净能量。API数值与功率积分来源分开。
- [ ] 时间采样积分用真实毫秒梯形法；按边界排序、去重复、拒绝非有限值及异常间隔，不把长gap截30秒冒充真实覆盖、不对未知起止无条件接受覆盖，防乱序重复累计超过时长。功率测量位置/符号核验；来源驱动功率不能标电池总净消耗。
- [ ] 优先语义明确报告/同车有效观测，EnergyRemaining差值只能标电池能量变化推算，防温度/BMS重估/期间充电。只有SOC且无可信可用容量不输出kWh。允许参考TeslaMate同车校准效率，但不硬编码任意容量、车型系数或由剩余续航伪装实测；输出明确estimated/provenance。
- [ ] 驻车显示kWh和平均W=能量/时长h*1000。资格包括同owner/car/source/观测时刻、整个间隔无充电行驶混入及有效端点。只有相邻行程SOC不得臆造独立驻车能量。可算EnergyRemaining差值时标推算；含充电拆段或完整电池侧能量平衡，缺证据保持未知。
- [ ] 充电起止SOC与电池侧充入kWh、AC输入kWh区分；计数器按session起点/有效差值、重置及完整边界处理。损耗/效率只在同完整AC时段、输入>0、兼容计量边界时推算；负差/时间错位不归0，不把充入量等同存量增加。
- [ ] 回归0/null、负回收、非有限、单位、毫秒/间隔/重复乱序、计数重置、跨owner/car/source/session、驻车含充电、能量回升、距离/时长0、AC/DC及加权摘要。完整Go/隔离PG16、Android Debug/Release/lint/R8及固定SHA交接，修复CI失败后复审。
- [ ] 修复现有push工作流触发目标阶段分支：当前history-memory-review.yml只列旧两个分支，有workflow_dispatch但非唯一触发方式。你能写文件和commit/push即可调整本阶段push触发并读取已有run/job/log。不以缺dispatch或本聊天无shell宣称无法执行；不提供运行中证据时不能称后台ACTIVE。禁扩大GitHub权限或塞入凭证。
- [ ] 输出精确源码SHA/tree/parent、实际CI、全部接线与交接文件。范围代码已经授权；本地以固定SHA独立编译同签install-r，保留com.matelink数据及登录，核真实已有自然记录计算与显示。不能造行程、唤醒或以mock当自然验收。
- [ ] 远端整合到可合并分支并清理已跟踪的废旧接收/构建产物，给精确删除清单/引用检查。保留有效测试/fixtures、Room schemas/迁移、合法配置、签名、回滚、关键验收经验。修改.gitignore避免复入无关产物；用户源码与未知历史不删。本地清理确认可重建缓存，复验后合并main并同步本地，合并不代生产部署。

## 官方与开源调研依据（2026-10-08）
- Tesla https://developer.tesla.com/docs/fleet-api/fleet-telemetry/available-data ：EnergyRemaining标称剩余kWh；LifetimeEnergyUsed累计放電損耗计数；LifetimeEnergyUsedDrive仅Semi；DCChargingEnergyIn电池侧AC/DC充入，ACChargingEnergyIn AC充电器侧输入、DC忽略。invalid保留未知。官方具备字段不证明本项目旧archive实际收到。
- TeslaMate https://docs.teslamate.org/docs/faq/ ：整段耗电根据充电记录校准估算；至少两次合格充电才有初始估算。
- 源SQL https://github.com/teslamate-org/teslamate/blob/main/grafana/dashboards/drives.json ：续航差*同车校准效率得kWh，再除距离转换；可借鉴方法及质量门，不直接复制代码或将其标实测。若复用源码先检查精确版本及许可证。
- TeslaLogger https://github.com/bassmaster187/TeslaLogger/blob/master/wallbox.md ：区分外部输入计量与充入能量的方法；不为本任务配置新设备。

## 本地已定位候选，不代现场根因
phone14ca的DriveEnergyResolver仅接受API>0，Calculator仅输出>0；未传duration无条件认可覆盖，长间隔coerceAtMost30秒；需实际测试修复有效0/负能量和缺口。StandbyEnergy只用SOC与容量且正耗电，不能直接当精准驻车实测。远端自行全链审查实施，不要求本地逐文件改。

## 安全与状态
不扩旧六SOC/7113回填，不改网络、不清数据、不唤醒、不制造Fleet/通知事件；生产DDL/bridge升级/历史写仍独立授权门。任务进行中，当前本文件是执行规格，非源码交付。旧五分钟heartbeat保持暂停，本轮主动检查真实执行，无须创建新聊天或任务。
