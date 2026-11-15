# F2 充电链路修订（2026-10-27）

发现版本：2.1.53/build72候选。修复候选：2.1.54/build73，App分支codex/app-release-data-fixes-20261027。父任务实施与运行测试；没有部署后端或安装手机。

- CHG-P1-01：明确相位1..3且非fast的合格聚合才进AC集合；processed且非DC仍UNKNOWN。共享qualifiedChargeMode贯通列表/图表，旅途/Geo由各组接同三态。
- CHG-P1-02：有效手工/单次账单先于车级免费权益；免费0需独立sessionFreeVerified，普通DC不会因车辆标志改成免费。
- CHG-P1-03：raw合并先校source/instance/record/精确物理窗口，Charge持久化改为真正remote candidate先于显示融合，保留原收到字段。
- CHG-P1-04：Current PageSource使用同一生产repository，补scope/generation/取消/写入与发布fence、有限超时、稀疏状态不误当停止；时间排序替盲reverse。快照增强保留历史points/起点SOC/range及完整坐标pair。provider-only没有plug-in时间就不造start_date。Current曲线接同timestamp/value/x/sourceIndex/gap投影，参数格布局不改。
- 进行中计数与全窗口能量分开：current_energy_receipt仅收明确Fleet API current plug counter、字段/单位/方法/质量/时间都校验；不会使history batteryInputKwh得到一个完整窗口值。旧raw scalar遇explicit unknown仍拒绝。来源依据：[Tesla官方Available Data](https://developer.tesla.com/docs/fleet-api/fleet-telemetry/available-data)对charge_state.charge_energy_added的会话计数语义；这是报告值，不声称测量了全窗输入或效率。此增量协议需要对应后端候选，当前生产未部署。
- CHG-P2-01/02：离线时长/坐标的实际helper新RED在runner07，尚待对应GREEN；温度同response元数据进入receipt，不借后来status单位。
- CHG-P2-03：覆盖/短会话母集收口待下一窗口，不把未知当0。
- SYS01/02/04：分时设置进入同cost estimate接口，按App本地时区、假设均匀功率进行时段加权，保持ESTIMATE标识且不能覆盖已知账单。时间/重叠/缺时段/非法价格拒绝；关闭分时沿用默认费率。真实逐时电量未提供就不宣传为逐时账单。峰谷非法数值被拒，重置保留币种。

## 已执行证据

父runner groups-red-03真实49/36fail，groups-red-green-05 77/17fail，06 86/31fail（整批含新增RED，不可当同一测试集横比）；HistoryWindow3与原付费/UNKNOWN控制在05/06均GREEN。06 Current消费者5/4fail、Tariff4/3fail；GREEN源码已写，07执行中。Go current snapshot3例先RED后GREEN；unknown-start1例实际RED后GREEN；后端full Go结果另附，不混同生产。首轮编译失败单独保留不当产品RED。
