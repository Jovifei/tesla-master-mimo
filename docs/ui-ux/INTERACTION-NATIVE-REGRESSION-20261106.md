# 同 SHA 原生回归失败与修复

实际 API35 run `42158928737` / job `127699803870`，源码 `15b3a8f30c3bb5fad50a651ea95a8b971dd4ac2e`：下载 artifact `13476405864`，SHA-256 `9d96fcf83782d46c940bc4bee3b37ed3797a8439163aea2b1e22ef1bf3305afe`。实际 JUnit XML 107 项、4 failure、0 error、2 项既有 skip。六项本轮 InteractionEvidenceAndroidTest 均通过；全轮仍是 FAIL，不能用新增测试通过代替。

三个 NoDataFullUiQualificationTest 在导航前被已有首轮说明弹窗阻断。合成 fixture 未提供 data-readiness 端点，真实仓库为它返回 legacy compatibility/version 0；准备函数只预置通知权限解释和费用说明，没有初始化当前账号/服务/车辆的 readiness 已读状态。Dashboard 在异步完成该读取时显示首次说明；旧测试是否抢先点击使结果依赖时序。修复仅在 androidTest 合成准备中调用现有 DataReadinessStore，用实际 compatibilityDataReadiness(101) 为当前隔离账号/origin/car/version 写入并核对已读。没有改变生产提示、OS 权限或 readiness 资格；其他账号/车辆/版本不被标记。原导航与真实下拉动作、请求次数界限以及拒绝其他弹窗断言全部保留。

另一个驻车测试用全树包含 `4.00 kWh/hour` 查找唯一节点，但实际页面同时有逐次充电值和总览估算 `≈4.00 kWh/hour`。修复改为实际未合并原生文本的精确逐条断言：`40m · 4.00 kWh · 6.00 kWh/hour` 和 `15m · 1.00 kWh · 4.00 kWh/hour`。既验证每次自己的时长、电量与速率，又保留会话数 2、1.7 pp/hour、六个遥测标签及缺失说明；没有挑第一个节点、接受任意数量或删断言。

所有修改只在隔离测试及本记录。本修复必须跑自身完整 CI 和真实 API35；旧失败产物保留，不通过无修改重跑掩盖。自然车况、真实手机与生产均未触及。
