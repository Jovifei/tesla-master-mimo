# 部署与运行资料

配置文件是候选，不是现网事实。部署前应只读核对实际容器镜像摘要、环境配置、TLS 证书、数据库版本、备份和回退路径；把核对结果与待部署的完整源码 SHA 绑定。仅在明确授权后执行生产变更。

| 事项 | 入口 |
| --- | --- |
| JourVolt 服务与反向代理 | `deploy/jourvolt-dev-mock/`、`deploy/nginx/`、`deploy/scripts/` |
| 本地隔离调试 | [本机 Docker 调试](JOURVOLT-LOCAL-DOCKER-DEBUG.md) |
| 服务器密钥与账号权限 | [密钥设置规程](JOURVOLT-ECS-SECRET-SETUP.md) |
| TeslaMate 个人归档 | [归档桥](TESLAMATE-ARCHIVE-BRIDGE.md)、`deploy/teslamate-home-docker/` |
| 对外试运行前核对 | [试运行门](JOURVOLT-REAL-PILOT-PREFLIGHT.md) |

部署检查按“固定提交 → 隔离测试 → 备份与恢复演练 → 候选镜像／配置 → 人工发布门 → 实际线上行为”逐层记录。不要因为模板写了版本、HTTP health 返回 200 或 CI 通过，就报告服务已经升级、Tesla 官方授权成功、Fleet 自然事件已到或手机已更新。生产数据迁移、历史回填与可逆性由具体版本单独验收。

旧的 2026-08-28 服务器拓扑和阶段 IP／主机资源记录只留在仓库外归档与 Git 历史；当前配置以正在运行的实例为准，不能照旧文档直接执行。
