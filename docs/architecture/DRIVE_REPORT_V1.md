# MateLink Drive Report V1 数据契约

> 状态：冻结（A1）  
> JSON Schema：`shared/contracts/drive-report-v1.schema.json`  
> 完整 fixture：`shared/contracts/fixtures/drive-report-v1-complete.json`  
> 降级 fixture：`shared/contracts/fixtures/drive-report-v1-partial.json`

## 1. 目标

Drive Report V1 为 Android 行驶完成报告和后续 iOS 原生应用提供统一的字段、单位、数据来源和缺失值语义。

本契约不要求后端必须直接返回完全相同的 JSON；Android/iOS 可以从现有 TeslaMate 详情接口和本地持久化信息组装。但所有平台最终展示必须遵守本契约，不能各自发明计算规则。

## 2. 核心原则

1. 缺失值保持 `null`，不得转换为 `0`、`false`、免费或空曲线。
2. 真实零值只有在数据源明确提供有限、合法的零时才允许展示。
3. 每个数值使用来源标签：`direct`、`derived`、`estimated`、`cached`、`unavailable`。
4. 能耗额外区分 `api`、`power_samples`、`unavailable`。
5. 使用功率样本计算的能耗必须携带覆盖秒数和覆盖率。
6. 行程费用只允许标记为估算；当前 V1 使用用户配置的平段电价，不冒充实际充电成本。
7. 路线与时序最多 360 个点，必须由真实采样下采样得到，禁止生成模拟曲线。
8. `(0, 0)` 默认不是有效车辆位置。
9. 系统通知不包含起终点地址；报告页面地址默认遮挡。
10. 完成行程必须有合法结束时间、正时长和正距离。

## 3. 数据来源矩阵

| 报告字段 | 优先来源 | 回退 | 来源标记 | 缺失规则 |
| --- | --- | --- | --- | --- |
| 起止时间 | Drive detail | 已验证的本地 summary | direct/cached | 任一缺失则报告不可生成 |
| 持续时间 | detail `duration_min` | 由起止时间推导 | direct/derived | 必须 > 0 |
| 起终点地址 | detail address | 非空 summary address | direct/cached | 空字符串视为 unavailable |
| 行驶距离 | detail odometer distance | 正数 summary distance | direct/cached | 必须 > 0 |
| 里程表起止 | detail odometer fields | 无 | direct | 不可用则隐藏 |
| 额定续航起止 | detail rated range | 无 | direct | 不可用则隐藏 |
| 起止电量 | detail battery fields | 无 | direct | 仅接受 0..100 |
| 电量变化 | 起止电量 | 无 | derived | 任一缺失则不可用 |
| 净能耗 | 持久化 provenance | detail API 有效值 | direct/derived | 来源不明时不可用 |
| 平均能耗 | 持久化值或能耗/距离 | detail consumption | direct/derived | 距离无效时不可用 |
| 能耗覆盖 | 持久化 coverage | 无 | derived | 仅功率样本来源展示 |
| 费用 | 能耗 × 平段电价 | 无 | estimated | 能耗/电价缺一则不可用 |
| 平均速度 | detail average | 距离/时长 | direct/derived | 不得用 summary 默认零 |
| 最高速度 | detail max | 无 | direct | 缺失则不可用 |
| 平均海拔 | 有效海拔样本平均 | 无 | derived | 同时给出样本覆盖率 |
| 温度 | detail averages | 有效 position 平均 | direct/derived | 缺失则不可用 |
| 路线 | 合法 position 坐标 | 无 | direct | 少于一个点则无地图路线 |
| 速度/功率曲线 | position 时序 | 无 | direct | 少于两个有效点不画线 |

## 4. 费用语义

Drive Report V1 的费用不是 TeslaMate 的实际充电账单，而是驾驶能耗的本地估算：

```text
estimated_amount = valid_energy_kwh × configured_flat_tariff_price
```

条件：

- `tariffEnabled == true`；
- 能耗有限且不小于 0；
- 平段电价有限且不小于 0。

结果必须返回：

```json
{
  "source": "flat_tariff_estimate",
  "estimated": true
}
```

任一条件不满足时：

```json
{
  "amount": null,
  "price_per_kwh": null,
  "source": "unavailable",
  "estimated": false
}
```

## 5. 隐私规则

### 通知

允许：

- 车辆名称；
- 行驶距离；
- 行驶时长；
- “点击查看报告”。

禁止：

- 起点；
- 终点；
- 经纬度；
- 路线缩略图；
- API token、地图 Key 或服务器地址。

### 报告页面

- 起终点默认遮挡；
- 用户可在当前页面临时显示；
- 页面退出后重新遮挡；
- 日志、测试名称和截图文件名不得包含地址或坐标。

## 6. 路线和时序下采样

- 输入为空：输出为空；
- 输入点数不超过 360：保留合法点；
- 超过 360：等距选择，必须保留第一个和最后一个合法点；
- 不插值、不平滑、不生成不存在的样本；
- 坐标无效不影响同一时刻的速度/功率样本，但不能进入路线数组；
- 时序数值必须有限，非法值转换为 `null`。

## 7. 版本兼容

- `schema_version` 当前固定为 `1`；
- V1 新增可选字段时必须保持旧客户端可解析；
- 删除字段、改变单位、改变缺失语义或来源枚举必须升级 major contract；
- Android/iOS 展示层不得在未升级契约的情况下改变费用或能耗含义。

## 8. 已知边界

- 当前 summary 实体部分字段为非空且会将 API 缺失值存成零，因此 summary 零值不能单独证明真实零。
- 当前 V1 不包含实际充电成本分摊；费用仅是平段电价估算。
- 当前 V1 不承诺实时推送；检测及时性取决于 Android 同步和后续 iOS 通知方案。
- 高德地图未配置或加载失败时，路线数据仍可存在，但 UI 必须降级为无地图报告。

## 9. 自动验证

运行：

```bash
python tools/validate_drive_report_contract.py
```

验证器使用 Python 标准库，检查：

- schema 版本；
- ID、时间和时长；
- 隐私默认值；
- metric 的 value/source 一致性；
- 能耗和费用不可用状态；
- coverage 边界；
- 坐标范围及 `(0,0)`；
- 路线和时序数量上限；
- 非有限数值。

## 10. A1 审核结论

- 标准库验证器已验证完整与降级 fixture；
- 契约没有要求立即新增 Adapter endpoint；
- Android 首版优先从现有 drive detail 与本地 provenance 组装；
- iOS 必须复用相同字段、来源和缺失语义；
- 后续任何单位、费用或来源语义改变都需要契约版本升级。
