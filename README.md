# jabiz

元数据驱动的业务应用平台：开发者声明实体、语义类型、规则、状态机、数据视图、SQL 模板和流程，
平台负责数据正确性、安全、事务、校验、只追加的完整历史、页面生成、可观测性和测试。

```bash
docker compose up -d --build                 # 数据库 + 后端（内含前端）+ Grafana LGTM
docker compose logs app | grep "Sign in"     # 首次启动生成的管理员密码
open http://localhost:8080                   # 登录；Grafana: http://localhost:3000
```

| 文档 | 内容 |
|---|---|
| [快速开始](docs/guide/quickstart.md) | 一条命令启动、本地开发、测试 |
| [新增一个业务对象](docs/guide/new-business-object.md) | 从建表到后台可用的教程（示例：`Supplier`） |
| [设计文档](docs/design/00-overview.md) | 平台总览与各部分设计；[已确认的决策](docs/design/09-decisions.md) |
| [路线图](docs/ROADMAP.md) | 各阶段目标、验收标准与状态 |
| [开发用时记录](docs/demo/dev-time-log.md) · [压测报告](docs/perf/phase-11-load-test.md) | 阶段 11 的示范业务与性能 |
| [CLAUDE.md](CLAUDE.md) | 在本仓库工作的规则（技术栈、分层、编码约定、命令） |

仓库结构：`backend/`（Gradle：`core` 纯 Java 核心、`runtime` 响应式运行时、`ext-geo` 扩展语义类型、`app` 启动模块与示范业务）、
`frontend/`（React 19 + Ant Design ProComponents，由元数据生成页面）、`docker/`（镜像入口脚本与 Grafana 仪表盘）、
`spec/`（前后端共享校验用例）、`tools/demo/`（演示数据脚本）。
