# 2026-10-29 原归档绑定安全续期协议（实现规格）

Jovi 已明确接受独立确认弹层，原页面布局不变。本规格只允许续期当前用户拥有的既有同车辆、同来源绑定；不支持新增来源、不提取手机完整会话、不直接 SQL 手工延期。状态/代码须分别测试后才部署。

## 传输及身份

- Helper 使用固定生产 HTTPS API，禁重定向；App 只用既有 requiredCloudMode 认证客户端。Bearer 仅留手机，归档 credential 仅交给 helper。任何真实凭据不进入工具输出、URL、日志、仓库或截图。
- Helper 从现有受保护生产 Compose 仅提取 vehicle_id/source_instance_id/source_vehicle_id，不读取旧 ARCHIVE_TOKEN。创建请求不证明所有权，不能因此旋转任何绑定。
- AppLink 精确为 https://auth.teslalink.joviluma.com/archive/enroll?handle=<opaque_handle>，只含公开 handle。弹层读取 owner 预览并展示既有车辆名称、来源指纹、确认码、到期时间；用户与 helper 的确认码比较后本人点击批准。

## 端点（/api/v1/archive/enrollments）

1. POST base，body {vehicle_id, source_instance_id, source_vehicle_id}。仅校验格式，不发现车辆；创建 10 分钟请求，高熵 handle/device_secret 各至少 192/256bit，server 只存 device_secret SHA256。返回 data {handle,device_secret,verification_code,expires_at,interval_seconds:5}。公开创建需严格 body limit、IP/全局速率和存量上限，不能泄漏某来源是否存在。
2. GET /{handle}，App 会话认证；直接本地校验 user+vehicle+source 对应的原绑定存在且未撤销，不用 requireVehicle/Vehicles。返回 data {handle,status,verification_code,expires_at,vehicle_name,source_label:"TeslaMate",scope_fingerprint,binding_state}；无VIN、用户ID、token、原始来源值。
3. POST /{handle}/decision，App认证，body {approve:bool,verification_code}。事务锁 request+原binding，再次校验 owner/scope/未撤销；批准仅记录 approved_user_id/binding_id/原token_hash与approved状态，不旋转；拒绝置rejected。scope不可由body改写。重复同人批准幂等，错人或到期拒绝。
4. POST /{handle}/claim，body {device_secret}，只device proof认证；禁止query secret。pending返回202+state，轮询最少5秒；approved时事务锁request+原binding，校验未撤销、scope及原token_hash仍一致，CAS旋转既有绑定的upload-only credential并续30天。将新credential用既有tokenCipher加密保存短期issued回执，返回 data {state:"issued",credential,expires_at,scope_fingerprint}，从不返回完整用户会话。
5. issued状态重复claim返回完全同一credential，不重复旋转；每次仍核对binding未撤销/替换以及issued_token_hash一致。到期/错secret/换scope/另一次旋转必须拒绝，不回放旧token。
6. POST /{handle}/ack，body {device_secret}。仅issued且当前binding仍为issued_token_hash可置installed并清issued密文；重复ACK幂等；返回installed不回传credential。

## 持久状态与错误

- 新增独立 enrollment 表，pending→approved→issued→installed 或 rejected；包含handle、secret_hash、请求scope、verification_code、expires_at、approved owner/binding/version hash、issued token hash及加密回执、最早下次轮询时间。禁止明文credential/refresh token落库。不得改动原历史或cursor。
- GET/decision/claim/ack路径严格解析；固定错误类，数据库/网络/原始请求正文不可回显。JSON body ≤4KiB、credential response不缓存。过期返回410、拒绝/撤销/版本变更403、错设备证明401，速率429+Retry-After；无任何原车/原配置自动发现或Tesla请求。
- 请求容量/速率有界；过期请求的临时回执可按有界清理保留审计，不对历史表做删除。issued回执丢失/过期需重新走owner批准，不恢复旧过期token。

## App 与 Helper

- 新独立弹层/导航分派只处理精确AppLink；不复用 /oauth/callback、不自动批准、不允许任意origin或动态回调URL。ViewModel绑定account/source/generation/vehicle，退出/换车/后台/迟到不能批准旧请求；后端仍是最终owner校验。
- Helper保持device_secret在允许下载目录的受ACL保护private文件；控制台只输出handle/确认码和安全阶段。批准后以秘密POST claim，credential仅在内存和受保护生产配置中处理，绝不print。
- 安装前核对同scope指纹、原生产桥标签/镜像/游标挂载，旧重复桥已no+stop；暂停生产桥后原子更新既有私有Compose中的ARCHIVE_TOKEN并定向recreate单桥（不重建其他服务）。不改变原scope/sourceDB/state，凭据不通过用户手填。
- 响应丢失：重领issued同credential；配置/容器安装失败：保留请求秘密和issued回执用于重试，不ACK、不推进cursor、不恢复旧token。安装ACK只代表已安装；自然源记录取得匹配云端回执和cursor前进后才称上传恢复。

## 必须验证

- 无认证/跨用户/车辆/scope/撤销/批准后被另一次重绑替换的拒绝；公开handle不能claim。
- 错secret、到期、拒绝、过快轮询、body上限/路径变形；无token/log/错误泄漏。
- 并发claim只旋转一次，丢响应幂等重领相同credential，ACK/撤销/取消/版本竞态。
- 原scope旧token失效、新token可上传，历史计数/来源身份/本机cursor保持；真实PG测试仅独立临时数据库。
- App恶意链接、账号/车/source切换、退出、迟到响应不授权；明确本人批准正例。
