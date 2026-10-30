# 父任务独立验证与外部合同核对

本轮为上线审查，不修改产品源码。测试源码放在本审查目录`testsrc/`，只有显式传入`audit.init.gradle`时加入测试源集；正常App构建不会包含这组预期失败的审查断言。

## 实际运行

在原E盘`android/`执行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.matelink.releaseaudit.ReleaseDataChainAuditTest' --init-script '..\docs\audits\2026-10-26\full-data-chain\audit.init.gradle' --offline --console=plain --max-workers=1 '-Pkotlin.compiler.execution.strategy=in-process'
```

结果：实际编译并执行11项，8项AssertionError、3项通过，0测试框架/编译错误；Gradle退出1，`BUILD FAILED in 3m29s`。这不是网络失败或构建缓存故障。测试只用合成对象和临时Room，没有调用车辆或生产。

| 审查条件 | 当前实际结果 | 关联发现 |
|---|---|---|
| 明确12元第三方充电账单不应因车辆免费标志变0 | 实际0.0，失败 | 02 CHG-P1-02 |
| 只有80kW功率、无phase/fast证据的归档不能推AC | aggregate→processed index消费实际AC，失败 | 02 CHG-P1-01 |
| A→B→A往返保留真实终点A | 实际只剩A→B，失败 | 03 D05 |
| 无能量/功率/计数采样且整数SOC相同不能生成能耗数 | 实际0.116kWh、145Wh/km，失败 | 03 D01 |
| server发出的telemetry_mqtt来源不应一律UNAVAILABLE | 实际UNAVAILABLE，失败 | 01 H06 |
| 从TeslaLogin冷启动登录后token失效应重定向登录 | 实际false，失败 | 01 H04 |
| UI保存的胎压profile应被worker使用的scope key读到 | 实际null，失败 | 07 SYS-04 |
| 峰电价输入拒绝NaN | 实际UiState保留NaN，失败 | 07 SYS-02 |
| 对照：普通可信12元账单、不启用免费标志 | 12.0，通过 | 费用正常路径保留 |
| 对照：非法/缺失坐标不能产生路线 | 空列表，通过 | 地图资格正常路径保留 |
| 对照：已知fleet_api来源和新鲜时间 | LIVE，通过 | freshness正常路径保留 |

说明：profile测试使用真实SettingsDataStore及已验证vehicle context，分别按当前UI/worker实际绑定的正/负key调用；它证明跨调用键不一致，不冒充直接运行Android Worker。其他失败直接调用实际投影/导航/路线/聚合函数或实际Tariff VM，不是Python重写公式。

原始证据：`audit-invariants.log`、`audit-invariants.xml`、`audit-invariants.json`；精确测试代码：`testsrc/com/matelink/releaseaudit/ReleaseDataChainAuditTest.kt`。上一轮1356项/变体绿结果是继承证据，不能替这8个新审查条件背书。

能耗0.116/145一项是来源与展示合同失败，不是乘法算错：默认常数未有本车采样/校准依据，不能冒充本行程实际能耗。若Jovi允许理论参考值，需要独立的模型来源和明确估计标识，不并入实测统计；该用例按当前数据真实性门拒绝无依据的“实际记录”数值。

这次运行发生在原目录；之后复制到独立E盘工作树，被测实现文件全部不在隔离前变化的3文件中，内容一致。未冒充在新worktree重新运行11项。新树另执行真实DAO SQL的7项条件（4失败、3对照），详见11-geo-sql-verification.md；两个证据层不能混称18项Android实机测试。

## 官方合同核对（2026-10-26）

- [Tesla官方超充说明](https://www.tesla.com/support/charging/supercharging)：免费超充适用Tesla自有站点，不能从“DC”或车辆级免费标志推所有会话0费用，且可能仍有相关附加费。这里只用于核对资格边界，不推断Jovi实际权益、账单或中国具体费率。
- [Open-Meteo历史天气API官方文档](https://open-meteo.com/en/docs/historical-weather-api)：不传timezone时默认GMT，temperature_unit默认celsius。项目OpenMeteoApi不传timezone，WhereWasI按本地targetTime.hour找返回hourly，存在错小时；修复应统一Instant/响应时区，不能仅改数值单位标签。

未引用网页中的示例天气/费用当真实车辆证据。其他静态发现仍按各组文档的源码层级记录；未运行39页真机或真实Fleet全链验收。
