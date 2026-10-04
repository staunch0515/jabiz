# 财务应用：运维（FIN-NF-005、FIN-NF-007）

`deploy/finance/docker-compose.yml` 的安装（`install.md`）上的备份、恢复、演练与监控。

## 1. 要保护的数据

| 数据 | 位置 | 备份方式 |
|---|---|---|
| 数据库 | 卷 `finance_finance-db` | 基础备份（`pg_basebackup`）+ WAL 归档，可恢复到任一时点 |
| WAL 归档 | 卷 `finance_finance-wal` | `backup.sh wal` 复制到备份目录 |
| 上传与生成的文件（对账单、单据 PDF、导出包） | 卷 `finance_finance-files` | 随每次基础备份打包；文件只增不改，恢复时只补缺失的 |
| 密钥（首次本地启动生成的签名、完整性、二次验证密钥；或 `.env`） | 卷 `finance_finance-secrets`、`deploy/finance/.env` | 随基础备份打包；`.env` 与完整性、二次验证密钥另存保险处 |

## 2. 备份

数据库每个 WAL 段写满或至多 300 秒（`archive_timeout`）即归档到 WAL 卷（写完并落盘后才取正式文件名；已归档的同一段再次归档时内容相同即算完成）。`deploy/finance/backup.sh`：

- `backup.sh base`：基础备份（等到所需的 WAL 都已归档才结束）、文件卷与密钥卷的打包，写入 `BACKUP_DIR/base-<UTC 时刻>/`，
  完成后写 `complete`；随后复制 WAL；只保留最新 `BACKUP_KEEP`（7）个基础备份，并删去最旧那个之前的 WAL。
- `backup.sh wal`：把新归档的 WAL 段复制到 `BACKUP_DIR/wal/`（先复制到临时目录，全部到达才移入，中断的复制不会被当作已复制）。
- 两种运行互相等待（`BACKUP_DIR/.lock`）；没有完成的基础备份目录在下一次 `base` 时删除；WAL 按所保留的各基础备份中最早的起点清理（不看时间线）。

`BACKUP_DIR` 默认 `deploy/finance/backups/`（不提交）；放在另一块盘或另一台机器的挂载上，或另行同步到异地。备份目录含密钥，只有属主可读。

建议的计划（root 的 crontab，路径按安装位置改）：

```cron
15 2 * * *   BACKUP_DIR=/backup/finance /opt/jabiz/deploy/finance/backup.sh base >> /var/log/finance-backup.log 2>&1
*/5 * * * *  BACKUP_DIR=/backup/finance /opt/jabiz/deploy/finance/backup.sh wal  >> /var/log/finance-backup.log 2>&1
```

RPO（NF-005：15 分钟）：服务器整体丢失时，丢失的是尚未归档的 WAL（≤ 5 分钟）加尚未复制到备份目录的段（≤ 5 分钟的复制间隔），合计 ≤ 10 分钟；
只是数据库进程或容器故障时 WAL 仍在卷上，不丢已确认的过账。文件卷只在基础备份时打包：两次基础备份之间上传、而服务器整体丢失时丢失的文件，
其数据库记录仍在（`sys_file` 指向不存在的内容），需重新上传；单据 PDF 可由其流程的存档记录查出并重新签发。

监控备份：`pg_stat_archiver` 的 `failed_count` 增加、`last_archived_time` 超过 10 分钟未变、`backup.sh` 非 0 退出，都应告警。

## 3. 恢复

`deploy/finance/restore.sh --yes [BASE_DIR] [TARGET_TIME]`：

- 停止应用与数据库，**删除现有数据库卷**（如可能还需要，先复制该卷），把基础备份解到新卷，补齐 WAL（归档卷中还在的与备份目录中的），
  写入恢复设置后启动数据库；数据库重放 WAL 到 `TARGET_TIME`（不给即到所有 WAL 的末尾）后自动提升为可写，脚本去掉恢复设置、补回缺失的文件与密钥，再启动应用。
- `BASE_DIR` 默认最新的完整基础备份；`TARGET_TIME` 必须晚于该基础备份结束的时刻（如 `'2026-10-04 09:30:00+00'`），否则选更早的基础备份。
- 恢复到某一时点之后，数据库进入新的时间线。以后再恢复时，默认跟随最新的时间线；要回到别的时间线，用 `RECOVERY_TIMELINE=<编号>`，
  并选在该时间线历史上的基础备份作 `BASE_DIR`（恢复之后做的基础备份在新时间线上）。
- 脚本最后列出数据库日志中重放停止处（`recovery stopping`、`redo done`、最后完成的事务时刻），据此确认恢复到了预期的时点。
- 恢复后：核对试算表（与故障前的报表或期末关账产物对照）、`INTEGRITY_VERIFY` 核验封存、通知用户自恢复时点之后的工作需要重做。

RTO（NF-005：4 小时）：恢复时间主要是解基础备份与重放 WAL，随数据量和距上次基础备份的 WAL 量增长；每日基础备份时重放至多一天的 WAL。

## 4. 恢复演练

`tools/finance/ops/drill.sh restore`（需要 Docker、JDK 21 与 `frontend/node_modules`）用上面两个脚本在一次性的安装（Compose 项目 `finance-drill`）上演练：
基础备份前后各过账五笔日记账，记下试算表与时刻（故障点），之后再过账一笔；然后删除数据库卷与 WAL 卷（服务器丢失，只剩备份目录），
恢复到故障点，比较恢复后的试算表与记下的，并确认故障点之后那一笔不在。

| 2026-10-04，开发环境（4 vCPU） | 结果 |
|---|---|
| 恢复（`restore.sh`：解基础备份、重放 WAL、提升） | 8 s |
| 从丢失到应用重新应答 | 25 s |
| 恢复后的试算表等于故障点的、故障点之后的过账不在 | 是 |

演练的数据量很小；全量（NF-001，约 8 GB/年）的恢复时间由使用方在正式硬件上按同一步骤演练并记入本表，至少每年一次，以及每次改变部署之后。

## 5. 监控（FIN-NF-007）

沿用平台（`docs/design/13-observability-ops.md`，决策 D16）：

- 健康：`GET /actuator/health`（容器的健康检查即用它）。不开放匿名的指标端点。
- 指标、链路、日志：OTLP 推送，默认关闭。设置 `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT`、
  `MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED=true` 与 `MANAGEMENT_OTLP_METRICS_EXPORT_URL`、`MANAGEMENT_OPENTELEMETRY_LOGGING_EXPORT_OTLP_ENDPOINT`
  （写入 `.env`）即推送到任何 OTLP 后端（Grafana、Elastic 等）。指标包括每个流程、模板、数据视图的耗时与结果（`outcome`），
  4xx 拒绝与 5xx 错误分开；查询超时为 503 `QUERY_TIMEOUT`。日志为 ECS JSON（compose 已设 `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`）。
- 建议告警：健康检查失败；5xx 比例；`jabiz.process` p95 超过 NF-002 的目标（`perf.md` §8）；`jabiz.outbox.delivery` 的 `FAILED`；
  §2 的备份告警；数据库磁盘使用。

日志中没有敏感数据：平台遮蔽敏感字段、`@Sensitive` 组件与 `input_summary`，观测标签只有名称；遮蔽字段（税号、银行账号）在读接口与日志中只以遮蔽形式出现。
`tools/finance/ops/scan-logs.py <日志…>` 检查这一点：读出数据库中的全部税号与银行账号（`tin`、`*_tin`、`account_number`、`*_account_number` 列，用 PG* 环境变量连接），
在日志中查找它们以及税号形状的文本（12-3456789、123-45-6789），只报告位置与种类、不打印值，有发现即退出码 1。
CI 的 `e2e` 作业在端到端测试之后用它扫描应用的日志；生产上可每日对前一天的日志运行（FIN-NF-007 的验收：一天的日志中查不到）。
