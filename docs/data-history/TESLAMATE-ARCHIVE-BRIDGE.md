# TeslaMate 个人归档桥接契约

该链路仅适用于 Jovi 已明确绑定的个人 TeslaMate 数据源：

```text
TeslaMate PostgreSQL (read-only)
        -> local archive bridge
        -> POST /api/v1/cars/{vehicle_id}/history/archive/import
        -> MateLink user + vehicle archive
```

归档请求必须携带：

- `source=teslamate`
- `source_instance_id`：用户为该数据库实例固定的非秘密标识
- `source_vehicle_id`：已核验的源车辆标识
- `chunk_id`：批次重试标识
- 每条记录的 `source_record_id`

行程保留完整 `route`、起止地址和里程字段；充电保留 `charge_points`、地址、费用和能量字段。服务端按用户、目标车辆和原始来源身份生成幂等记录 ID；跨来源不自动覆盖 Fleet Telemetry 原始记录。

桥接器只在云端返回成功后推进本地 JSON 断点。网络失败、鉴权失败或服务端拒绝时保留断点，下一轮重试；日志不得包含 token、VIN、精确位置或原始响应正文。

该接口的通过只证明 TeslaMate 原始归档进入云端，不证明 Fleet Telemetry 实时采集、虚拟钥匙或 Tesla 上游授权已经通过。
