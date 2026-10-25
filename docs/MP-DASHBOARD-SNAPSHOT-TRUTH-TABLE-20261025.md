# 仪表盘快照徽标真值表 — App build70 vs 小程序 0.4.0（B3，2026-10-25）

基线：App `android/.../domain/telemetry/SnapshotEvidence.kt` + `DashboardScreen.SnapshotBadge`（3665155a，只读）；Mini `miniprogram/src/components/dashboard/presentation.ts::dashboardSnapshot`（5aa336c5）。仅源码对比，无实机验收。

## 输入维度

| 维度 | App | Mini |
|---|---|---|
| 来源 | 规范化 source（`live_mqtt` / `fleet_api` / `mqtt_latest` / `database_latest` / `teslamate_api` / `mock_fixture`，其余→UNAVAILABLE） | 原始 wire source（live 集合含 `telemetry_mqtt`/`fleet_telemetry*`/`fleet_native` 等更多值） |
| 观测时间 | `observedAt` 解析失败即按 null；fresh = 不超前 5s 且滞后 ≤120s | 同阈值（+5000ms / ≤120000ms）；解析失败→"时间未知" |
| 车辆状态 | `isAsleep`（state=asleep）在徽标层覆盖 freshness | asleep/suspended → 车辆休眠 |
| 混合来源 | 字段源集合>1 或 顶级源≠某字段源 → " · 混合来源" | 同规则 |

## 真值表（输入 → App 徽标 / Mini 徽标）

| 来源 | 观测时间 | fresh | 车辆状态 | 混合 | App 徽标 | Mini 徽标 | 判定 |
|---|---|---|---|---|---|---|---|
| live_mqtt | 有效 | 是 | 在线 | 否 | 实时数据（绿） | 实时数据 | 一致 |
| live_mqtt | 有效 | 否 | 在线 | 否 | **最近状态（灰）** | 历史快照 | 词不一致（RECENT 缺位），语义均"非实时" |
| live_mqtt | 缺失/坏 | — | 在线 | 否 | **最近状态** | 来源未知/时间未知 | 一致不标实时 ✓；词不同 |
| fleet_api | 缺失 | — | 在线 | 否 | **实时数据（LIVE）** | 时间未知（unknown） | **分歧 A**：App 缺时间仍 LIVE；Mini 拒绝标实时。Mini 处于安全侧 |
| fleet_api | 有效 | 是 | 在线 | 否 | 实时数据 | 实时数据 | 一致 |
| fleet_api | 有效 | 否 | 在线 | 否 | 最近状态 | 历史快照 | 词不一致，语义一致 |
| mqtt_latest | 任意 | — | 在线 | 否 | 最近状态（恒定） | 历史快照 | 词不一致，语义一致 |
| telemetry_mqtt / fleet_telemetry* | 有效 fresh | 是 | 在线 | 否 | **数据不可用（else 分支）** | 实时数据 | **分歧 B**：App 判定输入为规范化 source，wire 值 `telemetry_mqtt` 若未经规范化到达徽标会落 UNAVAILABLE；Mini 直接认作实时。需后端/App 接收层核实规范化时机后再定 |
| database_latest / teslamate_api / teslamate_archive | 任意 | — | 在线 | 否 | 历史快照 | 历史快照 | 一致 |
| 未知/未标明 source | 任意 | — | 在线 | 否 | 数据不可用 | 来源未知 | 一致不标实时 ✓；词不同 |
| 任意 | 未来时间 | 否 | 在线 | 否 | 最近状态（fresh 判定失败） | 时间待核对 | Mini 细化词，安全侧 |
| 任意 | 任意 | — | asleep | 否 | 车辆休眠 | 车辆休眠 | 一致 |
| 任意 | 任意 | — | 任意 | 是 | 各词+" · 混合来源" | 同 | 一致 |
| （缓存态） | — | — | — | — | （无独立态，走 source 判定） | 缓存快照 | Mini 独有态，仅离线渲染时出现 |

## 风险结论

1. **无任何路径把"缺时间"或"未知来源"标成实时**——Mini 满足硬性要求；分歧 A 中反而是 App 宽松（fleet_api 缺时间→LIVE），Mini 不向该行为看齐。
2. 分歧 B 需要证据：App 接收层是否在徽标判定前把 `telemetry_mqtt` 等规范化为 `live_mqtt`。若无规范化，App 对这些源显示"数据不可用"而 Mini 显示"实时数据"——Mini 可能**过宽**，须核实后端 wire 值与 App DTO 归一化代码后再改；本次不改徽标代码。
3. RECENT（"最近状态"）与 UNAVAILABLE（"数据不可用"）两个独立态在 Mini 缺位：当前全部落入"历史快照/来源未知/时间未知"。语义均为"非实时"，无虚假实时风险；若要对齐词表，作为独立小改动另行提交审核。
4. Mini "缓存快照"为离线渲染专用态，App 无对应（App 离线时仍显示最后 evidence），不构成虚假实时。

## 待审独立小改动（本次未实施）

- Mini `dashboardSnapshot` 补 `recent` / `unavailable` 两态词表（映射分歧 A/B/未知源的展示词），需先裁决分歧 B。
