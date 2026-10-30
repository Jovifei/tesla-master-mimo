# 地理统计DAO SQL隔离样本

父任务在冻结树执行`python docs/audits/2026-10-26/full-data-chain/verify_geo_sql.py`。脚本直接从AggregateDao.kt的`@Query`提取真实SELECT，没有重写其计算/过滤逻辑。数据全部为合成、SQLite内存库；最小表仅含待测SQL使用字段及nullable receipt字段。**这不是Android Room迁移/设备/provider测试，也没有读取用户库。**

实际7项条件，4失败、3对照通过，退出1；结果`geo-sql-results.json`。

| 条件 | 实际 | 判定 |
|---|---|---|
| 对照：合格充电/行程点 | 1个充电、2个行程点 | PASS |
| 同国家保留合格行程锚点，隔离充电不得画点 | 仍返回隔离充电点1条 | FAIL，S03 |
| 同国家仍有合格行程，隔离行程不得画点 | 应1条却2条 | FAIL，S03 |
| 对照：国家汇总排除所有隔离行程 | 空集合 | PASS，证明仅隔离国家会被空态挡住，不声称它必能绘图 |
| 上海本地2026年应包含UTC 2025-12-31 20:00 | 真实年份SQL漏掉该国家 | FAIL，S04 |
| receipt缺距离、非空列为占位0，不应作为已知0总距离 | SQL仍返回0.0 | FAIL，S03字段可用性丢失 |
| 对照：明确观察distance=0 | 0.0保留 | PASS |

现代writer已有能量资格化，未知量随后仍存非空0；因此样本不用于宣称每个拒绝的正raw能量都穿透。地图样本刻意保留另一个合格行程/地区锚点，避免用“只有quarantine”的不可达页面状态制造假P1。

S01/S02/S03/S05另由指定模型独立复核，收敛结果见10-statistics-review.md；其中Timeline能量旁路已撤回，实际生产链由UHR先资格化。SQL失败数量是条件数，不是4个互不相关Bug。
