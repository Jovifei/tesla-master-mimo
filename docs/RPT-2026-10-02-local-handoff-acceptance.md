# 2026-10-02 远端 OOM 修复本地接收与兼容验收

状态：LOCAL_TESTS_PASS / REMOTE_SOURCE_BUILD_REVIEW_DONE；PR #11 保持 Draft，未合并、未部署。

## 接收与身份

- 仓库 Jovifei/tesla-master-mimo，原 main f0dcd447dfcfdaf3a479f78eebd4077cd2e52a45。
- 接收分支 fix/20261002-history-metadata-memory，HEAD 16a31c3f8c59f79164e9f9d964c7963887615bf2。GitHub PR API 核验 open/Draft/base main/head 一致。
- 用户交接包 SHA256SUMS 的36个条目全部校验通过；17个仓库文件与隔离 checkout 文本一致，只有 Windows CRLF 与包内 LF 差异。没有重复应用补丁。
- 当前修改位于独立工作树；原主目录的任务记录保持，原 com.matelink 数据、登录、归档和密钥未改。

## 本地执行证据

| 门禁 | 当前结果 | 边界 |
| --- | --- | --- |
| Go 全量 + 隔离 PostgreSQL16 | 274/274，0失败、0跳过 | 合成数据，临时本地容器，未连接生产数据库 |
| Go vet / mod verify / build | PASS | 对接收 HEAD 执行 |
| Linux 定向 race | PASS | 专用临时容器；Windows 缺 cgo 的失败记录保留，不冒充通过 |
| Web 分页测试 | 13/13 PASS | 实际模块测试 |
| Web npm ci / tsc / Vite 构建 | PASS | 仍有727.39kB构建包的500kB警告；不是依赖安全审计 |
| Android 定向回归 | 20/20 PASS | 最终完整运行另行覆盖错误状态修正 |
| Android Debug/Release | 各564项，0失败/错误 | Debug0跳过，Release8跳过 |
| Android Release Lint / APK | 0错误/241警告；原签名Release PASS | 最终源码 android-final2.log，未安装到手机 |
| 生产 / 真机 | NOT_RUN | 合并、部署、安装需要单独授权 |
| 2/4/8/16混合并发性能矩阵 | NOT_RUN | p95/p99、RSS、数据库CPU/IO尚未采集，不能推断生产容量 |

本地大历史测试为413条行程、632055点、53条充电的合成夹具：TotalAlloc 元数据+EXISTS每操作2284B、20条摘要151896B、旧全量历史一次546324568B。此指标不是峰值RSS、整个登录请求开销或线上降幅。

候选源为16a31c3加本地未提交的三个Android文件。最终差异文件 SHA256 为0EA381F1472E78FD1FAA141487D8C98800D9121308EE4641BB758F76F1714A78；APK保留2.1.22/build41作为评审候选，SHA256 CAC12269A531F7DC0FE7F19F158556B25B9504AA54CDE8733631F434DD9F22D9，证书9AB144E824ABF26A5941819ABB06831288C36A8BFE622657E3DC9D88281FC774。没有宣称此包已提交、已安装或生产已更新。

本地证据目录：E:/Claude_allow/Download/matelink-oom-local-20261002。包含Go原始JSON、Linux race、Web日志、最终Android日志、APK校验与最终兼容差异。远端同SHA CI36978859071已通过GitHub API核实success；本地输出与继承CI分开。

只读线上 /healthz 本轮返回 build_sha=781c4025a720a257fdd6d2f8ee9a309655bfa020/status=ok，仍为旧部署。未读取本轮内核/重启趋势，不把健康响应写成 OOM 已修复。

## 独立复审与最小兼容修改

新服务端每页最多100条，而 Android TimelineViewModel 和 WhereWasIViewModel 仍调用默认 show=50000 的单次历史请求。101条起的记录会被遗漏，正常历史/同步已分页不能覆盖这两个调用方。

问题已交同一 ChatGPT Project 分析。远端建议完整分页；本地复用现有 UnifiedHistoryRepository.load，而非增加第二套分页器。它使用50条页、账号/车辆读作用域和 Room 缓存，并保留原详情ID。

- TimelineViewModel：通过统一历史仓库读取全部摘要，成功时显示本地/部分同步提示；失败时发布空界面状态，防止换账号后继续显示旧账号记录。
- WhereWasIViewModel：按日期窗使用统一历史仓库；失败、部分或仅缓存状态明确提示，不凭缺失历史推断停放位置。
- HistoryRecoveryTest：101条行程和101条充电分三页收齐；既有失败页、重复页、取消和数据隔离测试保留。
- 两个界面都继续传播 CancellationException。

远端已读取实际 Android diff、定向 Gradle 输出和本地 Go 日志；最终Android运行及校验已记录为第2轮输出，远端返回DONE，仅接受源码、回归、完整构建和签名校验。真机、混合并发、合并和生产仍未验收。

## 待批准的后续动作

1. 最终测试/签名包检查及差异哈希已完成；远端源码/构建复审DONE。
2. Jovi已明确GitHub为交接桥梁，授权本地修正后提交远端继续审核；提交本地兼容改动及接收记录，推送同一修复分支。单独审核合入main。
3. 单独授权受控部署：可恢复备份、API实际SHA、Web静态资源兼容、配套Android交付和回退；保留数据库/来源绑定/密钥/全量历史。
4. 执行混合并发性能矩阵、上线内存/重启/502观察。Fleet首事件、真实行程/充电和长期观察保持独立。

分层存储ADR仍为PROPOSED；追加点、重请求预算、冷归档、本地完整落盘ACK、TTL或历史删除都尚未实施。
