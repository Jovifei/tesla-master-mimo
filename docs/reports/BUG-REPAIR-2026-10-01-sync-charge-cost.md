# 重新登录后历史补同步与费用展示

## 真机与线上证据

原包 2.1.19/build38 的充电列表有 45 个可显示记录，最新 2026-09-29、40.6 kWh，费用未知；通知栏存在十多条历史行程通知。手机已有数据不代表 Fleet 首事件成功：线上 pairing 仍 configure_post/502，readyz awaiting_first_event。

本地库 336 条结束行程、48 次充电；云端 teslamate_archive 为 327/47。bridge 日志持续 cursor_write。实际挂载指向已删除的 trip-delivery-repair 工作树，容器内状态目录也不存在。上传一条后无法保存水位，每次又从开头重传，因此后续新增记录无法推进。已将运行状态移到 E:/project/tesla_master/runtime/matelink-archive，并按云端已确认 source record 水位 338/47 恢复。旧容器停用保留回退，凭据沿用且不输出。

重新登录会更新云会话、重新发现车辆、启动历史拉取。通知处理器根据已保存水位补发新下载记录，所以批量补下载可能导致一批历史通知。尚无重新登录前的手机网络日志，不能将原因直接归为某个过期 token；当前确认的持续归档断点是丢失状态目录。

## 费用与通知

默认电价 1.14 元/kWh，设置页可修改。费用优先级为单次人工总价、明确免费、真实账单、默认电价估算；缺失电量不估算。估算标明来源，列表以 ≈ 提示；已有人工总价继续按稳定车辆作用域保存。40.6 kWh 示例为约 46.28 元。

通知标题突出里程和时长，地址中文精简，展开显示起终点；保留历史通知和原通知水位，避免修改展示导致重复补发。

## 小程序授权

Tesla 官方链接 https://tesla.com/_ak/注册域名 可以由系统浏览器唤起 Tesla App。微信内是否能唤起仍需实测，不能承诺小程序任意打开 Tesla App。建议提供官方配对链接复制与配对状态回查，浏览器完成后回到小程序。车机已有确认可能是钥匙或共享数据许可，须读取 fleet_status 确认具体要求；部分 Intel Model S/X 使用车机 Safety 页面数据流开关。

官方说明：https://developer.tesla.com/docs/fleet-api/virtual-keys/developer-guide ，https://developer.tesla.com/docs/fleet-api/announcements 。

## 最终验收

- Android 源提交 01f0d8c，2.1.20/build39；Debug/Release 各 556 项，0 failures/errors，Release 8 项预期跳过；lintRelease、R8、签名均通过。
- APK SHA-256：22AEF949A05240FEE58551FE071A29E72B71EF3EBA5D9F96338187DA182DC290；原证书一致，同包 adb install -r 保持 firstInstallTime 2026-08-31 22:36:47。
- 原云登录、车辆、地图和历史可见，启动采样无 FATAL/ANR。
- 最新原始充电量 40.64 kWh：默认电价 1.14 时约 ¥46.33；默认改为 1.00 并保存后约 ¥40.64，返回设置仍为 1.0。验收后恢复并保存 1.14。
- 单次总价临时设 ¥50.00，列表准确采用人工费用；随后清除测试覆盖，恢复约 ¥46.33。详情标注“估算费用”，列表以 ≈ 提示，汇总说明无账单费用的估算口径。
- 真机聚合行程数 272，最新 2026-09-30 19:58。聚合行程与云端 336 条原始 drive 数量不是同一口径。
- archive bridge 测试和 Compose 配置验证通过；持续运行水位 347/48，无新的 cursor_write。
- 通知样式已实现并编译测试，下一条真实行程的新版通知外观仍待事件验收；未伪造通知，未重置通知水位。
- Fleet 首事件、七天并行及多用户 App 来源绑定流程继续保持 PENDING；此次费用/归档修复不代表整个项目完成。
