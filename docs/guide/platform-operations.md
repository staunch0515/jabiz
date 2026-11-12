# 平台运维要点

给运维与值班的人：平台本身（不含应用）上线前与运行中要做的事。应用的手册（如 QuizBuks 的 `docs/quizbuks/operations.md`）引用本页，不重复。

## 1. 上线前：检查正式环境的设置

`tools/ops/env-check.sh <设置文件> [<应用的规则文件>]`：只读，只列出缺失或不对的变量名，从不打印值；有错误时退出码 1。平台检查：

- 四把密钥都已设置、是 Base64、至少 32 字节、**互不相同**、不是仓库中的测试密钥；有 `*_PREVIOUS` 时提示"轮换进行中"；
- `SPRING_PROFILES_ACTIVE` 不含 `dev`；`JABIZ_BOOTSTRAP_ADMIN_*` 已删去；`JABIZ_SECURITY_MFA_ADMINISTRATION` 不是 `false`；放宽登录限流的测试开关、写入缓冲的应急开关被设置时提示；
- `JABIZ_TRUSTED_PROXIES` 已设、`JABIZ_WEB_ENVIRONMENT=production`；
- 告警的收件人与 Grafana 的 SMTP（§2）。

应用的规则文件每行一条：`set 名`、`absent 名`、`equals 名 值`、`not-equals 名 值`、`warn-absent 名`、`warn-unset 名`（没设时提示）、`min-length 名 长度`（口令）、
`different 名 另一名`（两个口令不得相同）（`#` 起注释），如 QuizBuks 的 `absent QB_WALLET_DEV_CREDIT`、`equals QB_STRIPE_PLACEHOLDERS false`。
不是应用设置的文件（如备份工具的 `backup.env`）用 `env-check.sh --app-only <文件> <规则文件>`：只按规则文件检查，不套平台的规则（17zz3）。

## 2. 监控与告警

规则、联系点与阈值见设计 13 §7（`docker/grafana/alerting`，部署值 `JABIZ_ALERT_*`）。上线前在测试环境试发一次：

```
GRAFANA_USER=… GRAFANA_PASSWORD=… tools/ops/alert-fire-test.sh rules     # 全部规则求值无误
GRAFANA_USER=… GRAFANA_PASSWORD=… tools/ops/alert-fire-test.sh notify    # 值班的人收到测试邮件
GRAFANA_USER=… GRAFANA_PASSWORD=… tools/ops/alert-fire-test.sh fire      # 每条规则进入 pending/firing
GRAFANA_USER=… GRAFANA_PASSWORD=… tools/ops/alert-fire-test.sh disk /var/lib/docker   # 真的把磁盘填到阈值（测试服务器）
```

`jabiz-metrics-absent` 以停掉应用 5 分钟来试。应用自己的规则写在 `docker/grafana/alerting` 下自己的文件中（同一格式，自己的组与文件夹）。
应用的规则一起试发：`ALERT_FOLDERS="jabiz <应用的文件夹>" ALERT_FAKES=<应用的假数据文件> tools/ops/alert-fire-test.sh rules|fire`（文件格式见 13 §7"试发"）。

## 3. 备份与恢复演练

备份由备份工具或托管方负责；每季度（上线前一次）以 `tools/ops/restore-drill.sh` 演练：恢复到 T、以核对模式核对、写出带 RTO 与恢复点的记录
（设计 13 §8）。加 `DRILL_ROTATE=true` 同时演练密钥轮换。演练记录与问题归档。

## 4. 密钥轮换

`docs/guide/key-rotation.md`（决策 D78）：定期轮换与泄露时的步骤、每把旧密钥保留多久、应用要声明的密封字段。

## 5. 各功能的设置、观测与处理

| 功能 | 设置（缺省） | 看什么 | 常见情况与处理 |
|---|---|---|---|
| 刷新令牌的重用宽限（D73，17zi） | `jabiz.security.refresh.reuse-grace`（30 秒，0–120，0 为关闭） | `jabiz.auth.refresh.reuse{result}`；告警 `jabiz-refresh-reuse-granted` / `-revoked` | 补发异常增多：多半是客户端没有先保存新令牌（看最近发布的前端），其次是令牌被盗；被吊销的会话增多同理。宽限不要调大来"修"登出 |
| 地址库（D74，17zl） | `jabiz.geoip.database`（文件路径）、`jabiz.geoip.max-age`（45 天）、`jabiz.geoip.required` | 启动检查 `GEOIP`；仪表 `jabiz.geoip.stale` / `ranges`；告警 `jabiz-geoip-stale` / `-empty` | 每月下载新的 DB-IP Lite CSV 替换文件即可，运行中一分钟内自动重读，不必重启；新文件读不出时继续用旧的并记 WARN |
| 服务器下载链接中的图片（D75，17zr） | `jabiz.files.fetch.*`：`max-concurrent`（16）、每人 2 个、`max-redirects`（3）、`connect-timeout`、`allowed-ports` | `jabiz.file.fetch{outcome}`；`jabiz.process.rate_limited` | 503 `PROCESS_BUSY` 多：同时下载已满（调 `max-concurrent` 前先看连接池）；`FILE_FETCH_*` 错误是链接本身的问题（内网地址、非图片、过大），不是故障 |
| 手动补跑定时任务（D77，17zs） | `jabiz.jobs.manual-run.max-age`（90 天）；权限 `job.run`（受保护，授予须另一位管理员审批） | 管理系统"定时任务"页；`sys_job_run.requested_by` | 修好原因后在该页选时刻补跑；409 `JOB_BUSY` 表示正在运行，等它结束；已经成功的项不会重做 |
| 限流（10 §15.10，17zt） | `jabiz.process.rate-limits.*`；登录类 `jabiz.security.sign-in.rate-limit.*` | `jabiz.process.rate_limited{process,scope}`、`jabiz.auth.rate_limited{kind}` | 一个请求的全部桶全取或全不取（17zt）；正当用户被拒时看 `scope` 是本人、地址还是实例，再调对应的那一项 |
| 受控变更的值（17zt） | 各目标的上限（缺省 30 000 字符；邮件文字按标题与正文上限计算） | 422 `TOO_LONG` | 文字超过邮件文字规则的上限时由 `MAIL_TEXT_SAVE` 的规则先拒绝；受控变更本身不再因 JSON 转义而被拒 |
| 邮件文字与信纸（D72，17zh） | `jabiz.mail.texts.*`（标题与正文上限、允许的链接主机）、缺省信纸 `jabiz.mail.layout.<语言>.*` | 管理系统"邮件文字""信纸"页；`sys_mail_message` 的 `FAILED` | 改文字经 `MAIL_TEXT_SAVE`；带令牌、登录链接或受保护的文字、以及信纸的修改要另一位管理员发布；改坏了用"恢复缺省"（`MAIL_TEXT_RESET` / `MAIL_LAYOUT_RESET`） |
| 恢复核对（17zv） | `jabiz.ops.verify-restore.*`（只在演练时） | 演练记录 | 见 §3 |
| 密钥轮换（D78，17zw） | `JABIZ_*_PREVIOUS`；`jabiz.key-rotation.cron`（每 10 分钟）、`.batch-size`（500） | 启动检查 `KEYS`；任务 `jabiz.key-rotation` 的输出 | 见 §4；`unreadable` 大于 0 先查应用的 `SealedFields` 声明 |

## 6. 并发建索引与无效索引（INVALID，17zz18）

平台迁移里的 `CREATE INDEX CONCURRENTLY`（目前是 `V66`，账本的科目 + 第一维度索引）不阻塞过账，但有几点要求（决策 D81）：

- **会话级咨询锁**：运行平台脚本的 Flyway 用会话级锁（`flyway.postgresql.transactional.lock=false`）。默认的事务级锁由 Flyway 在**另一条连接**上以 `pg_advisory_xact_lock` 取得，
  该连接的事务开到整次迁移结束（`idle in transaction`）；并发建索引要等比它的快照旧的全部事务，也就等这一个（`pg_locks` 中对其 virtualxid 的 `ShareLock` 未授予），
  Flyway 又等建索引结束，两边永远等下去，PostgreSQL 也不当作死锁（一方在客户端等）。这在 PostgreSQL 16 + Flyway 11 上实测复现过。
  平台的 `PlatformSchemaMigration` 只对平台脚本设了会话级锁（它复制配置，应用自己的 Flyway 不受影响）；**应用不需要为平台做任何设置**。
  只有应用自己的迁移（`db/migration`）也要并发建索引时，才在应用中设 `spring.flyway.postgresql.transactional-lock=false`，并照样用 `.sql.conf` 的 `executeInTransaction=false`。
  不经 `PlatformSchemaMigration` 直接运行平台脚本的工具（Flyway CLI、测试夹具 `SealedColumnsHistory`）必须同样设置。
- **占位符 `${concurrently}`**：`PlatformSchemaMigration` 缺省为 `CONCURRENTLY`；不经它运行平台脚本而没有定义该占位符时，Flyway 立即报未解析的占位符（而不是卡住）。
  同一数据库里并行迁移多个 schema（测试）时设为空（`spring.flyway.placeholders.concurrently=`），因为并发建索引要等整个数据库的旧事务。
- **直连数据库**：Flyway（应用启动时的迁移）必须直连 PostgreSQL，不能经事务模式的 PgBouncer 等连接池：会话级咨询锁与并发建索引都需要一个始终是同一会话的连接。
- **长事务与快照**：并发建索引要等开始之前就有的全部事务（整个数据库，不只是本 schema）：运行中的报表与导出、`pg_dump`、`idle in transaction` 的会话都会让它等，
  等多久迁移就卡多久（不阻塞写入）。部署前看一眼：`SELECT pid, state, xact_start, left(query, 60) FROM pg_stat_activity WHERE xact_start < now() - interval '5 minutes';`
- **锁等待时限** `jabiz.schema.migration.lock-timeout`（环境变量 `JABIZ_SCHEMA_MIGRATION_LOCK_TIMEOUT`，缺省 `0s` 即不限）：设了之后，平台迁移的每条连接执行 `SET lock_timeout`，
  任何语句等锁超过它即失败。对普通迁移，这避免一条 `ALTER` 排在长事务之后、让后面所有写入跟着排队；对并发建索引，等旧事务也是等锁，超时即失败：Flyway 把 `V66` 记为失败，要按下面的步骤 repair。
  因此缺省不开；部署时有已知的长事务又不想等，就设一个值（如 `2m`），失败后再处理。
- **大表上耗时长**：建索引期间 Flyway 的启动在此等待，实例还没有就绪。大表上要么在部署时放宽启动探针与超时，要么先单独跑一次迁移（例如用同一镜像与同一设置启动一个实例、等它就绪后再启动其余实例，
  或一次性的 Flyway `migrate`，设置同上），再启动各实例。其他实例启动时等 Flyway 的锁。

**失败与恢复**

- 建索引语句失败（锁等待超时、死锁、被取消）：Flyway 把 `V66` 记为失败，下次启动在校验处停下。先排除原因，再对平台的历史表（`jabiz_schema_history`，与应用的 `flyway_schema_history` 分开）执行 repair，然后重启。
  `tools/ops` 里没有 repair 工具，用 Flyway CLI：
  `flyway -url=jdbc:postgresql://主机/库 -user=… -password=… -schemas=<schema> -table=jabiz_schema_history -locations=… repair`；
  或直接在库里：`DELETE FROM jabiz_schema_history WHERE version = '66' AND NOT success;`（repair 对失败的记录做的就是删去它）。
- 进程在建索引时被杀：没有历史记录，重启时 `V66` 重跑，不需要 repair。但数据库里执行 `CREATE INDEX CONCURRENTLY` 的后端进程可能还活着，要扫完整张表才结束。
  **重启之前**先确认没有遗留的建索引：`SELECT pid, state, now() - query_start AS running, left(query, 80) FROM pg_stat_activity WHERE query ILIKE 'CREATE INDEX%ledger_entry_version_account_dimension_1_idx%';`
  （别的角色的会话只有超级用户或 `pg_read_all_stats` 的成员能看到语句；否则看 `pg_locks` 中对 `ledger_entry_version` 持有 `ShareUpdateExclusiveLock` 的 `pid`）。
  有就等它结束，或以 `SELECT pg_cancel_backend(<pid>);` 取消，再重启。
- 重跑的 `V66` 先看同名索引：存在且 INVALID 时删去，有效的索引从不删；然后 `CREATE INDEX CONCURRENTLY IF NOT EXISTS`。删去用普通的 `DROP INDEX`（`DROP INDEX CONCURRENTLY` 不能放在 `DO` 块里），
  对 `ledger_entry_version` 取 ACCESS EXCLUSIVE 锁：**只有没有会话还在建该索引时才是一瞬间**。所以脚本删除之前先看是否有别的会话（autovacuum 除外）对该表持有 `ShareUpdateExclusiveLock`，
  有就报错 `… is still being built by another session (pid …)` 而不排队；删除等锁也至多等占位符 `drop_lock_timeout`（`PlatformSchemaMigration` 缺省 `3s`，可经 `spring.flyway.placeholders.drop_lock_timeout` 改）。
  这两种失败都让 Flyway 把 `V66` 记为失败：按上一条确认没有遗留的建索引（或手工 VACUUM 已结束、长读取已结束）后，repair 再重启。
- 启动检查 `SCHEMA`（`SchemaIndexChecks`，默认连接池当前 schema 的全部索引）把每个 INVALID 索引报为**警告**（从不阻止启动，`platformCheck` 也只列出），并设仪表 `jabiz.schema.invalid-indexes`，
  大于 0 时告警 `jabiz-schema-invalid-indexes`（设计 13 §7）。仪表在每次启动时更新：手工处理后到下次启动才归零。
  警告分三种说法：正在建（有会话在 `pg_stat_progress_create_index` 中建它，或另一会话持有该表的 SHARE UPDATE EXCLUSIVE 锁，并发建索引从头到尾持有它，VACUUM / ANALYZE 也持有，所以只说"可能"）；
  `REINDEX CONCURRENTLY` 的临时索引（`*_ccnew`、`*_ccold`）；其余是失败留下的。`pg_stat_progress_create_index` 只对超级用户与 `pg_read_all_stats` 的成员显示别的角色的会话所建的索引，
  迁移与应用用不同的数据库角色时看的是锁（`pg_locks` 对所有人可读）。
- 查看：`SELECT indexrelid::regclass, indrelid::regclass, indisready FROM pg_index WHERE NOT indisvalid;`；单个索引手工处理：`DROP INDEX CONCURRENTLY <索引名>;` 再重建。
