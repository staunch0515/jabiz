# 13 可观测性与运行

ROADMAP 阶段 11 的横切部分：指标、链路追踪、结构化日志，本地一体化观测环境，以及"一条命令启动整套系统"。
约束性细则见【决策 D16】。示范业务见 11 §5a。

## 1. 原则

- **标准件，不自研协议**：Micrometer Observation（Spring Boot 4 自带）→ OpenTelemetry（`spring-boot-starter-opentelemetry`）→ OTLP。
  后端只推送 OTLP；采集、存储、展示交给外部（本地为 `grafana/otel-lgtm`）。
- **默认不外发**：没有配置端点时不导出任何遥测（OTLP 指标默认关闭，链路与日志只在配置了端点时导出）。测试、`platformCheck` 与未配置的部署不受影响。
- **标签只有名字**：平台观测的低基数标签只取流程名、版本、数据视图、实体、模板、消费者、任务名与结果；**从不**放主键、操作人、字段值
  （它们可能是个人信息或秘密，10 §6）。R2DBC 的 span 不记录绑定值（`management.observations.r2dbc.include-parameter-values=false`）。
- **不开放新的匿名端点**：actuator 仍只暴露 `health`、`info`；指标以 OTLP 推送，不开放 `/actuator/prometheus`、`/actuator/metrics`。

## 2. 平台自有观测

runtime `com.jabiz.runtime.observability.PlatformObservations` 把平台的工作单元包装为 Micrometer `Observation`：
每个都同时是一个计时器指标和（开启链路时）一个 span，嵌套在请求或外层单元之内（父观测取自 Reactor Context）。

| 观测名 | 位置 | 低基数标签 |
|---|---|---|
| `jabiz.process` | `ProcessExecutor.run` / `executeChild`（子流程嵌套在父流程内，名为 `sub-process X`） | `process` `version` |
| `jabiz.dataset.read` / `jabiz.dataset.query` / `jabiz.dataset.commit` | `DatasetEntityManager.findById` / `query` / `commitBatch` | `dataset` `entity` |
| `jabiz.query.template` | `AdvancedQueryExecutor.page` / `all`（模板 API、`RunTemplate` 与导出） | `template` |
| `jabiz.query.export` | `ReportExporter.export`（19 §4；其中的模板执行即 `jabiz.query.template`） | `template` `format` |
| `jabiz.outbox.delivery` | `OutboxDeliverer.deliver` | `consumer` `event` `result`（`CONSUMED` / `DUPLICATE` / `FAILED`） |
| `jabiz.job.run` | `JobRunner.run`（调度线程，阻塞式观测） | `job` `result`（`SUCCEEDED` / `REPLAYED` / `FAILED` / `LOCKED`） |
| `jabiz.file.upload` | `FileUploadService.upload`（14 §1） | `policy` |
| `jabiz.file.serve` | 读取文件内容（14 §5、15 §4） | `channel`（`admin` / `public`） |
| `jabiz.public.query` | 公开模板接口（15 §5；其中的模板执行即 `jabiz.query.template`，嵌套在内） | `template` `result`（`ok` / `not_modified`） |
| `jabiz.public.rate_limited` | 公开接口的限流拒绝（15 §5；只计数，不记客户端地址） | 无 |
| `jabiz.file.sweep` | 清扫的存储部分（`FILE_PURGE_ORPHANS` 提交后，14 §6；删除数量记入日志） | `result` |

另外每个都有 `outcome`（`success`、`rejected`＝映射为 4xx 的领域异常、`error`＝其他、`cancelled`）与 `status`（映射的 HTTP 状态，成功为 `none`）。
同一观测名的标签键集合固定（指标后端的要求）。映射沿用 `ProblemStatuses`，与 API 响应一致。

Spring Boot 自动提供：WebFlux 服务端请求（`http.server.requests`）、R2DBC 语句（加入 `r2dbc-proxy` 后）、R2DBC 连接池、JVM 指标。
`jabiz.*` 与 `http.server.requests` 发布直方图桶（`JabizDefaultProperties`），由后端计算 p95 等分位。

一次下单的链路（本地实测）：`http post /api/processes/{name}/{version}` → `process ORDER_PLACE` →
`query Warehouse` / `query Product` / `query StockLevel` → `commit SalesOrder` / `commit SalesOrderLine` / `commit StockLevel` → 各条 R2DBC 语句。

## 3. 日志

- 请求日志的 MDC 一直有 `requestId`（01 §5）；开启链路后 Micrometer Tracing 另加 `traceId`、`spanId`。
- 结构化日志用 Spring Boot 内置的 `logging.structured.format.console=ecs`（JSON，一行一条，含 MDC）；本地开发保持原文本格式。
- 日志经 OpenTelemetry 的 Logback appender 以 OTLP 发往日志端点：`OtlpLogExport` 在配置了
  `management.opentelemetry.logging.export.otlp.endpoint` 时把 appender 加到根日志器并安装到 OpenTelemetry SDK。
- 日志内容的遮蔽规则不变（10 §6：平台不记录请求体；`EntityInstance` 等的 `toString()` 只有字段名）。

## 4. 配置

| 属性（环境变量形式亦可） | 作用 | 默认 |
|---|---|---|
| `management.otlp.metrics.export.enabled` / `.url` / `.step` | OTLP 指标 | 关闭 |
| `management.opentelemetry.tracing.export.otlp.endpoint` | OTLP 链路（HTTP，如 `http://lgtm:4318/v1/traces`） | 未设置即不导出 |
| `management.tracing.sampling.probability` | 采样率 | 0.1（compose 中 1.0） |
| `management.opentelemetry.logging.export.otlp.endpoint` | OTLP 日志 | 未设置即不导出 |
| `logging.structured.format.console` | 控制台 JSON 日志（`ecs`） | 文本 |
| `spring.application.name` | `service.name` | `app`（compose 中 `jabiz`） |

## 5. 本地一体化环境（Docker Compose）

仓库根目录 `docker compose up -d --build`：

| 服务 | 内容 | 端口 |
|---|---|---|
| `db` | PostgreSQL 16（本地演示账号 `app`/`app`，仅限本机） | 5436 |
| `app` | 由根目录 `Dockerfile` 从源码构建：后端 jar 内含前端（12 §7），同一端口提供页面与接口 | 8080 |
| `lgtm` | `grafana/otel-lgtm:0.34.0`（Grafana、Prometheus、Tempo、Loki、OTel Collector），预置仪表盘 `jabiz platform` | 3000 |
| `pgadmin` | profile `tools`，按需 `docker compose --profile tools up -d` | 5050 |

- **密钥不入库**【D16】：`app` 的入口脚本 `docker/app-entrypoint.sh` 在未提供 `JABIZ_JWT_SECRET`、`JABIZ_INTEGRITY_KEY`、`JABIZ_MFA_KEY`、`JABIZ_BOOTSTRAP_ADMIN_PASSWORD` 时，
  首次启动生成随机值并保存在卷 `jabiz-secrets` 中（重启后不变），管理员密码在日志中打印一次；也可在 `.env` 中自行指定（`.env.example`）。
  这只适用于本地演示；部署时由密钥管理注入环境变量（10 §2、§7）。
- 镜像以非 root 用户运行，健康检查为 `/actuator/health`。
- 仪表盘（`docker/grafana/jabiz-platform.json`）：HTTP 请求率与 p95、流程吞吐与结果、被拒绝的流程、模板与数据视图 p95、Outbox、任务、连接池、堆内存、警告与错误日志。
- 演示数据：`tools/demo/seed.sh`（经 API 执行流程，不写 SQL）。

## 6. 测试

- `ObservabilityIT`（app）：下单成功与被拒绝（422）各产生 `jabiz.process` 观测及对应计时器；数据视图查询与提交嵌套在流程之内；
  子流程 `LEDGER_POST` 嵌套在 `ORDER_SHIP` 之内；模板有观测；全部 `jabiz.*` 标签中不出现主键、订单号、操作人。
- 端到端（手工，见阶段 11 PR）：compose 启动 → 登录 → 下单 → Grafana 中可见指标、链路与日志。
