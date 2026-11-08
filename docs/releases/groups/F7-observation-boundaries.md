# F7 采集与展示边界（2026-10-27）

发现build72候选，修复build73候选；没有生产操作或新增Fleet轮询。

- SYS08：ChargingNotificationWorker按连接模式选路径：self-hosted沿用配置门与已有预算；Cloud仅消费当前origin/account/verified local car缓存观测，要求有效来源/真实观测时间且120秒内、持久watermark防重放，不启动轮询服务，不读API补缺字段。没有合格缓存则等待真实更新。
- sentry_mode=null不再等价off。null不给旧session复位，也不凭缺失trigger制造告警。处理Cloud时使用已验证local键，并在挂起读取/写入与通知前校验捕获身份。
- 胎压profile由Settings实际writer/worker统一verified local key，旧remote正ID未归属配置保留，不猜迁移；重新明确保存才绑定当前车。换来源/账号/车辆清绑定、迟到load拒绝，保存按点击时binding校验。
- 可用性边界：本机观测不是完整车机历史。提供方未提供实际Sentry触发脉冲时，App不能凭sentry_mode=true制造告警历史；设备/provider实测另列UNVERIFIED。WhereWasI/Vehicle3d未开放路由保持backlog，本次不擅开入口。

父runner06：SentryObservation1/1fail（null导致SessionEnded）；NotificationReadPlan2/1fail（Cloud被blank server挡住）；原TPMS writer负例再次实际失败。已写GREEN，runner07待回执。ReadPlan纯策略测试证明路由选择，不冒称真实Android Worker/手机通知已验。
