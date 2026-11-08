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
| `jabiz.job.run` | `JobRunner.run` / `execute`（调度线程，阻塞式观测） | `job` `result`（`SUCCEEDED` / `REPLAYED` / `FAILED` / `PARTIAL` / `LOCKED`） |
| `jabiz.job.item` | 逐项任务的一项（11 §4.1；每项流程的 `jabiz.process` 嵌在其下，选取即 `jabiz.query.template`） | `job` `result`（`SUCCEEDED` / `REPLAYED` / `BUSY` / `FAILED`）；不放项键、主键或输入 |
| `jabiz.file.upload` | `FileUploadService.upload`（14 §1） | `policy` |
| `jabiz.file.serve` | 读取文件内容（14 §5、15 §4） | `channel`（`admin` / `public`） |
| `jabiz.public.query` | 公开模板接口（15 §5；其中的模板执行即 `jabiz.query.template`，嵌套在内） | `template` `result`（`ok` / `not_modified`） |
| `jabiz.public.rate_limited` | 公开接口的限流拒绝（15 §5；只计数，不记客户端地址） | 无 |
| `jabiz.external.call` | `JdkExternalHttp.send`（阻塞步骤的外部调用，06 §2.3；阻塞式观测） | `service` `outcome`（`2xx` `3xx` `4xx` `5xx` `timeout` `refused` `error`），没有 `status` |
| `jabiz.external.job` | `ExternalJobExecutor` 的一次尝试（处理器开始到记录完，11 §4a；其中的外部调用即 `jabiz.external.call`） | `job_type` `outcome`（`SUCCEEDED` `WAITING_RETRY` `FAILED` `CANCELLED` `DISCARDED` `error`），没有 `status` |
| `jabiz.external.usage`（计数器，不是观测） | 记录的尝试的用量，按数量增加（11 §4a；用量名由应用声明，低基数） | `job_type` `metric` |
| `jabiz.external.job.held`（仪表，不是观测） | 声明了 `enabledBy` 的作业类型被其业务参数暂停时为 1，否则为 0（每次轮询更新，11 §4a、D52 第 4 条）。每个这样的**键**使每个实例每次轮询多一个 `jabiz.dataset.query` 观测与一条 SELECT（几个类型共用一个键只读一次）；一个 JVM 中有几个执行器时（测试），仪表反映第一个的 | `job_type` |
| `jabiz.inbound.receive` | 入站 Webhook 的一次请求（11 §2.5；未知提供方不观测，嵌套其中的 `INBOUND_RECEIVE` 即 `jabiz.process`） | `provider` `result`（`ACCEPTED` / `DUPLICATE` / `IGNORED` / `REJECTED` / `FAILED`）`reason`（`none`、拒绝原因如 `no_match`、`too_old`、`too_large`，或意外错误的 `error`） |
| `jabiz.inbound.rate_limited` | 入站 Webhook 的限流拒绝（只计数，不记客户端地址） | 无 |
| `jabiz.file.sweep` | 清扫的存储部分（`FILE_PURGE_ORPHANS` 提交后，14 §6；删除数量记入日志） | `result` |
| `jabiz.file.fetch` | 服务器下载链接中的图片（`POST /api/files/fetch` 的下载与存储、外部作业的 `remoteImages()`，14 §12、D75） | `outcome`（`fetched` 或错误码；不含主机与链接） |
| `jabiz.process.admission` | 根流程在热门命名锁之前的排队（06 §4.2，D45；`ProcessAdmission`，嵌在 `jabiz.process` 之内，计时即排队的时间） | `family` `process` `result`（`ADMITTED` / `QUEUE_FULL` / `WAIT_TIMEOUT` / `CANCELLED`），没有 `outcome` 与 `status`；不放准入键、主键、操作人 |
| `jabiz.process.admission.waiting` / `.active`（仪表，不是观测） | 当前在队列中等待的、已取得许可的根流程数，按族合计 | `family` |
| `jabiz.keys.undeclared-sealed`（仪表，不是观测） | 密钥轮换（D78 第 8 条）：最近一次扫描（启动或任务 `jabiz.key-rotation` 的一次运行）发现的、未声明为密封字段的字段中旧密封密钥下的值的个数；没有旧密封密钥时为 0 | 无 |
| `jabiz.geoip.ranges` / `jabiz.geoip.stale`（仪表，不是观测） | 地址库（`ClientCountries`，10 §18、D74）：使用中的库的范围数（读不出为 0），文件比 `jabiz.geoip.max-age` 旧或启动时读不出为 1；与启动检查 `GEOIP` 同一判断，但运行中变旧也看得到。没有配置地址库不登记 | 无 |
| `jabiz.process.rate_limited` | 流程接口的限流拒绝（10 §15.10；只计数，不记操作人与客户端地址） | `process` `scope`（`user` / `address` / `instance`） |
| `jabiz.auth.rate_limited` | 登录类接口按地址限流的拒绝（10 §4.1，D47；只计数，不记客户端地址） | `kind`（`password` / `oidc_callback` / `second_factor` / `refresh`） |
| `jabiz.auth.hashing.busy` | 口令哈希的并发上限拒绝了一次请求（10 §4.2，D47；只计数） | 无 |
| `jabiz.process.statements`（分布，不是观测） | 根流程在其事务中、提交之前发出的语句数（阶段 16p，D46：热门锁的容量由锁内的往返次数决定；16p-2 起含提交前写出缓冲的那一条） | `process` |

另外每个都有 `outcome`（`success`、`rejected`＝映射为 4xx 的领域异常、`error`＝其他、`cancelled`）与 `status`（映射的 HTTP 状态，成功为 `none`），
以下除外：`jabiz.external.call`、`jabiz.external.job`（只有 `outcome`，见上表）、`jabiz.process.admission`（只有 `family` `process` `result`）、
计数用的 `jabiz.process.rate_limited`、`jabiz.public.rate_limited`、`jabiz.inbound.rate_limited`、`jabiz.auth.rate_limited`、`jabiz.auth.hashing.busy`（只有表中的标签），以及不是观测的 `jabiz.external.usage`、`jabiz.external.job.held` 与准入的两个仪表。
同一观测名的标签键集合固定（指标后端的要求）。映射沿用 `ProblemStatuses`，与 API 响应一致。

Spring Boot 自动提供：WebFlux 服务端请求（`http.server.requests`）、R2DBC 语句（加入 `r2dbc-proxy` 后）、R2DBC 连接池、JVM 指标，
以及磁盘的剩余与总量（`disk.free` / `disk.total`，标签 `path`）：平台缺省测量工作目录与上传文件的目录（`jabiz.files.local.root`）所在的文件系统
（`management.metrics.system.diskspace.paths`，`JabizDefaultProperties`；容器中工作目录所在的即宿主的镜像与日志存储），供 §7 的磁盘告警。
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

## 7. 告警（阶段 17zu）

告警规则与接收人是平台提供的文件，以 Grafana 的预置（provisioning）载入 LGTM 容器，不在界面上手工建：

- `docker/grafana/alerting/jabiz-alerts.yaml`：文件夹 `jabiz`、组 `jabiz-platform` 的规则，联系点 `jabiz-oncall`（邮件）与路由。
  应用的规则写在同一目录（或挂载到它旁边）的另一个文件中，用自己的组与文件夹，格式相同。
- 部署相关的值写成占位符 `$JABIZ_ALERT_*`，容器启动时由 `docker/grafana/alerting/render.sh` 以容器的环境变量填入（Grafana 不展开规则查询中的
  环境变量）；未设置或含字母、数字与 `@._+,;-` 以外字符的值使容器启动失败，而不是载入一条永不触发的规则。`docker-compose.yml` 给出缺省值：

| 变量 | 作用 | 缺省 |
|---|---|---|
| `JABIZ_ALERT_EMAIL` | 告警的收件人（值班的人；几个地址以 `;` 分隔）。另需 Grafana 的 SMTP：`GF_SMTP_ENABLED`、`GF_SMTP_HOST`、`GF_SMTP_USER`、`GF_SMTP_PASSWORD`、`GF_SMTP_FROM_ADDRESS` | `oncall@example.com`（Grafana 要求带域名的地址） |
| `JABIZ_ALERT_SERVICE` | 应用的 `spring.application.name`（指标的 `job` 标签） | `jabiz` |
| `JABIZ_ALERT_DISK_PERCENT` | 磁盘用量的阈值（%） | 80 |
| `JABIZ_ALERT_REFRESH_GRANTED_MIN` / `_RATIO` | 一小时内补发多于这么多次、且占刷新请求的比例高于这个值时告警（D73） | 10 / 0.02 |
| `JABIZ_ALERT_REFRESH_REVOKED_MAX` | 一小时内因重用而吊销的会话多于这么多时告警 | 20 |

平台的规则（每条的 `labels.area` 指向下表的一行，`severity` 为 `page`（立即处理）或 `warning`）：

| uid | 条件 | 持续 | 处理 |
|---|---|---|---|
| `jabiz-metrics-absent` | 5 分钟没有收到应用的任何指标 | — | 应用停了或连不上收集器：看容器状态与日志 |
| `jabiz-platform-check-failed` | 日志中出现 `Platform check failed` | — | 实例拒绝启动：日志列出 `类别 \| 定位 \| 描述` |
| `jabiz-process-errors` | 流程以 503 以外的错误结束（`outcome="error"`） | — | 按 `process` 看链路与日志（不是业务拒绝） |
| `jabiz-admission-overload` | 热门锁的队列满或排队超时（`QUEUE_FULL` / `WAIT_TIMEOUT`） | 5 分钟 | 06 §4.2：调用者收到 503 `PROCESS_BUSY` |
| `jabiz-outbox-failed` | 事件的消费者失败 | 15 分钟 | 事件等待并重试；按 `consumer` 看错误 |
| `jabiz-job-failed` / `jabiz-job-item-failed` | 定时任务 `FAILED` / `PARTIAL`，或逐项任务的项失败 | — | 修好原因后在任务页面重跑（D77） |
| `jabiz-job-locked` | 任务两小时都发现锁被占用 | 2 小时 | 另一实例可能卡住 |
| `jabiz-inbound-rejected` | 入站 Webhook 被拒（签名、过旧、过大）或失败 | — | 11 §2.5：按 `provider` 与 `reason` |
| `jabiz-external-call-errors` | 外部服务的调用 5 % 以上失败 | 15 分钟 | 看服务方的状态；有副作用的调用照旧带幂等键 |
| `jabiz-external-job-failed` | 外部作业的尝试最终失败 | — | 11 §4a |
| `jabiz-refresh-reuse-granted` | 被换下的刷新令牌在宽限内再出现异常频繁（D73） | — | 客户端缺陷（不先保存新令牌）或令牌被盗：看最近的版本与登录记录 |
| `jabiz-refresh-reuse-revoked` | 因重用而结束的会话异常多 | — | 同上；可能有人在重放令牌 |
| `jabiz-geoip-stale` / `jabiz-geoip-empty` | 地址库比 `jabiz.geoip.max-age` 旧或读不出 / 没有范围（D74） | 1 小时 / 15 分钟 | 下载新的库替换文件（运行中自动重读，不必重启） |
| `jabiz-keys-undeclared-sealed` | 轮换中（配置了旧的 `JABIZ_SEAL_KEY`），未声明为密封字段的字段里仍有旧密钥下的密封值（仪表 `jabiz.keys.undeclared-sealed`，D78 第 8 条） | — | 不要撤旧密钥；在应用中声明这些字段（`SealedFields`），让任务 `jabiz.key-rotation` 处理完（`docs/guide/key-rotation.md`） |
| `jabiz-disk-usage` | 应用测量的某个文件系统用量高于阈值 | 10 分钟 | 清理旧镜像与日志、扩容；上传文件的目录满了上传会失败 |

数据库主机（WAL 归档、备份、数据库所在磁盘）不由应用测量，由数据库的托管方或主机监控告警。

**试发**：`tools/ops/alert-fire-test.sh`（只对测试环境；`GRAFANA_USER` / `GRAFANA_PASSWORD` 必填）：

- `rules`：全部规则已载入且求值没有错误；
- `notify`：经联系点 `jabiz-oncall` 发一封测试告警，Grafana 未送达即失败（再确认值班的人收到了）；
- `fire [uid…]`：以应用的服务名推送满足各条规则的假数据（实例与名称为 `alert-test`），等到每条都进入 pending 或 firing；
  `jabiz-metrics-absent` 无法伪造，停掉应用 5 分钟来试；按一小时计算的规则在一小时内自行恢复；
- `disk <目录>`：在该目录所在的文件系统上放一个临时文件，使用量到阈值以上 1 %，等到磁盘告警进入 pending 后删除。规则已在 pending 或 firing 时拒绝执行。

测试：`tools/test/alert-render.test.sh`（CI）检查填入、遗漏与非法的值、应用自己的文件。规则的指标名与标签、
联系点的投递、各条规则的触发与 `disk` 的填充都对真实的 LGTM 容器与应用核对过（17zu 的 PR）。

## 8. 恢复核对与演练（阶段 17zv）

备份与时间点恢复由备份工具或数据库托管方完成（pgBackRest、WAL-G、托管服务）；平台负责**核对恢复出的副本**，并提供计时与记录的演练脚本。

**核对模式**：应用以 `jabiz.ops.verify-restore.enabled=true` 启动时（`RestoreVerificationProperties`，优先级最高，生产的配置不能把它们打开）
邮件、推送、定时任务、事件投递（连同 Webhook）与外部作业一律关闭，只在本机回环地址的空闲端口上提供服务；启动检查照常（不通过即拒绝启动）。
启动后 `RestoreVerifier` 以系统身份依次核对，记录日志，`jabiz.ops.verify-restore.report` 设置时写出 JSON 报告，随后停止应用（全部通过退出码 0，否则 1）：

| 核对 | 内容 |
|---|---|
| `last-operation` | 最后提交的操作的时间；设置了 `jabiz.ops.verify-restore.target-time`（恢复到的时刻 T）时，晚于 T 即失败（恢复越过了 T），否则给出与 T 的差（恢复点）。核对自己的操作（请求号以 `verify-restore-` 开头，如 `INTEGRITY_VERIFY`）不计，最先核对 |
| `integrity` | 运行 `INTEGRITY_VERIFY`（21 §2.3）：封存链完整，`JABIZ_INTEGRITY_KEY` 是生产的那一把 |
| `key-rotation` | 只在 `jabiz.ops.verify-restore.rekey=true` 时：把 `KEY_ROTATION_REKEY` 跑到底（D78），报告重新加密与解不开的个数 |
| `mfa-secrets` | 每个二次验证的当前版本都能以配置的 `JABIZ_MFA_KEY`（及旧密钥）解开（报告密钥的标识，不报告密钥） |
| `sealed-secrets` | `SealedFields` 声明的每个密封值都能以配置的 `JABIZ_SEAL_KEY`（及旧密钥）解开（D78）；没有密钥时，只要没有密封值也通过 |
| `files` | `sys_file` 登记的每个文件的原件与变体都在文件存储中，原件大小一致（文件卷与库是同一时刻的） |
| `template <id>` | 应用的核对：`jabiz.ops.verify-restore.templates` 列出的 SQL 模板（逗号分隔，无参数），返回 0 行即通过，如"借贷不平的期间""透支的钱包"。应用不写 Java |

报告只有数量与时间，不含任何值、用户、文件名。核对模式只写 `INTEGRITY_VERIFY` 自己的记录。

**演练**：`tools/ops/restore-drill.sh`（环境变量见脚本头部）：

1. 可选 `DRILL_RESTORE_COMMAND`：备份工具恢复到 T 的命令，脚本计时；用 pgBackRest 时改设 `DRILL_PGBACKREST`（运行 pgBackRest 的方式）与 `DRILL_PGBACKREST_PG_PATH`（新的空数据目录），
   脚本自己组成 `restore --type=time --target=T --target-action=promote --archive-mode=off`（17zz3）：**恢复出的副本绝不归档**——否则它会把自己的 WAL 推进生产的仓库；
   手写的 pgBackRest 恢复命令缺 `--archive-mode=off` 即拒绝。恢复出的库启动时也要有备份的设置（`archive-get` 要用加密口令）。`DRILL_RESTORE_ONLY=true` 只恢复、写记录后停止，
   启动副本后再不带恢复设置运行一次以核对；
2. 以生产同版本的镜像（`DRILL_IMAGE`，`DRILL_DOCKER_ARGS` 给出只通向恢复的库的内部网络）或 jar（`DRILL_JAR`）在核对模式下启动一次，
   设置来自 `DRILL_ENV_FILE`：库、恢复出的文件目录、从离线副本取来的生产 `JABIZ_INTEGRITY_KEY` / `JABIZ_MFA_KEY` / `JABIZ_SEAL_KEY`，以及应用自己让副本远离线上服务的开关（支付的测试模式等）；
3. `DRILL_ROTATE=true` 时在同一副本上演练密钥轮换（D78，`docs/guide/key-rotation.md`）：再启动两次，先以新密钥、生产密钥作旧密钥并重新加密到底，
   再以新密钥单独核对（封存密钥保留旧的那把：封存块从不重签）；这会写入副本；
4. 写出演练记录（Markdown）：T、恢复与核对的用时（RTO）、恢复点、每次启动每项核对的结果，留一栏写问题。核对失败时退出码 1。

测试：`RestoreVerificationIT`（完好的副本各项通过；文件缺失、另一把 MFA 密钥、越过 T、不存在的模板与返回行的模板各自失败；核对自己的操作不算越过 T，同一副本可再核对）、
`RestoreVerificationPropertiesTest`（生产打开的开关被关掉；未开启时不变）。脚本对真实的库演练过：`pg_dump` 的副本通过，换一把 MFA 密钥则 `mfa-secrets` 失败（17zv 的 PR）。

