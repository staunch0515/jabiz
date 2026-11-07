# F-F13 上线的运维材料：实施计划（S3）

来源：S1 的上线准备材料（平台 D78 密钥轮换、13 §7 告警、13 §8 恢复核对与演练、17zx 设置检查）。本计划把 QuizBuks 的运维文档与部署接到这些平台工具上。

## 要改的

| 位置 | 改动 |
|---|---|
| `operations.md` §1.1 | "轮换"一栏：四把密钥都可以轮换（D78，`docs/guide/key-rotation.md`）。JWT / MFA / SEAL 旧值放进 `*_PREVIOUS`，`jabiz.key-rotation` 完成后删去（JWT 在保留期后）；INTEGRITY 的旧值永久保留；每次轮换后更新离线副本 |
| `operations.md` §1.5 | LGTM 的开关（`COMPOSE_PROFILES=observability`）、OTLP 的地址、告警收件人与 Grafana 的 SMTP、`QB_GRAFANA_PORT` |
| `operations.md` §2.3 | 备份的配置与操作引用 S1 的备份配置说明（pgBackRest + R2；尚未合入本线，先写占位） |
| `operations.md` §2.4 | 第 2、3 步改为 `tools/ops/restore-drill.sh`（上线前的一次 `DRILL_ROTATE=true`）；QuizBuks 自己的核对是 `DRILL_TEMPLATES` 中只在有问题时返回行的模板；保留第 1 步（托管方的恢复）与第 4 步（与生产在 T 时的数字比较）；演练记录即脚本的 Markdown |
| `queries/qb/admin/verify-unbalanced-periods.sql`、`verify-overdrawn-wallets.sql`（新） | 借贷不平的月份（按记账时间，UTC）、账本中为负的钱包（2110、2120、2130、2160 按对象）。2110 与钱包余额之差直接用每日核对的 `qb.system.recon-totals`（同样只在有差异时返回行，另含 2160 与账本合计），不另写一份 |
| `operations.md` §5 | 平台指标的行改为引用平台规则（13 §7）；QuizBuks 自己的规则在 `deploy/quizbuks/alerting/quizbuks-alerts.yaml`；试发用 `alert-fire-test.sh`；数据库主机仍是托管方的告警 |
| `deploy/quizbuks/alerting/quizbuks-alerts.yaml`（新） | 文件夹 `quizbuks` 的 8 条规则：`qb-reconcile-diff`、`qb-reconcile-missing`、`qb-transfer-failed`、`qb-stripe-webhooks`、`qb-stripe-calls`、`qb-attempt-submit-busy`、`qb-journal-export-rejected`、`qb-ai-paused`。只有规则组，经平台的 `jabiz-oncall` |
| `wallet.ReconcileGauge`（新）、`Reconciliation` | 仪表 `qb.reconcile.differences`：核对提交后（`afterCommit`）设为该次的差异数；平台的指标只有任务结果，没有核对状态。依赖 `micrometer-core` |
| `deploy/quizbuks/server/compose.yaml` | 服务 `lgtm`（profile `observability`，挂载 `./alerting`，以 `render.sh` 启动，`JABIZ_ALERT_*` 与 `GF_SMTP_*`，Grafana 只绑 `127.0.0.1`，卷 `lgtm`），网络 `telemetry` |
| `.github/workflows/quizbuks-deploy.yml` | 上传 `alerting/`（平台规则、`render.sh`、QuizBuks 规则），有变化且容器在运行时重启它 |
| `deploy/quizbuks/server/env-check.rules`（新） | 平台 `tools/ops/env-check.sh` 的 QuizBuks 规则：§1.4 的测试开关 `absent`、本地 `.env` 的占位开关、QuizBuks 测试用的封存密钥、监控已开启 |
| `launch-checklist.md` | "安全"：设置检查；"运维"：`alert-fire-test.sh` 的 `rules` / `notify` / `fire` / `disk`，演练记录 = 脚本的 Markdown |
| `DEPLOY.md` | "2a. 监控与告警"：开启、规则怎样到服务器、访问 Grafana、试发、设置检查 |
| `troubleshooting.md` T13 | 状态改为已修复（F-F6）；运维引用平台运维要点 §5"刷新令牌的重用宽限" |

## 测试

- `RestoreChecksIT`：干净的账本上三个模板都没有行；手工加一条越过余额触发器的借方行后，各自返回期望的行。
- `ReconcileIT`：仪表在 OK 后为 0、DIFF 后为差异数。
- `tools/quizbuks/test/alerts.test.sh`（CI）：与平台文件一起由平台 `render.sh` 渲染；每条规则的形式；没有联系点与路由；每个占位在服务器 compose 中有缺省；workflow 上传这些文件。
- `tools/quizbuks/test/env-check-rules.test.sh`（CI）：规则的语法；有平台的 `env-check.sh` 时，正式的 `.env` 通过、各种错误各自失败。
- `:quizbuks:platformCheck`。

## 限制

- `alert-fire-test.sh rules` 只看文件夹 `jabiz`，`fire` 只伪造平台的规则：QuizBuks 的规则在 Grafana 中看健康状态；规则对真实 LGTM 的求值本次没有运行（这里没有 Grafana）。
- 指标与仪表的名字按平台的约定（计时器 `_milliseconds_count`，仪表无后缀）写，未对真实的 LGTM 核对。
- `tools/ops/env-check.sh` 与 `docs/guide/platform-operations.md` 在 `1.3/platform`（17zx），还没有合并到本线：文档先引用，合并后即可用。

删除的功能：无。改写的运维文字（新工具取代原来的手工步骤，不是删除功能）：§1.1 的"轮换"一栏、§2.4 第 2、3 步、§5 的告警表、T13 的状态。
