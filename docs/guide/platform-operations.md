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

## 6. 无效索引（INVALID，17zz18）

平台迁移里的 `CREATE INDEX CONCURRENTLY`（目前是 `V66`）不阻塞写入，但中途失败（死锁、被取消、重复数据）会留下 INVALID 索引：它仍随每次写入维护，却从不被查询使用，
而迁移的 `IF NOT EXISTS` 下次会跳过它。启动检查 `SCHEMA`（`SchemaIndexChecks`，检查当前 schema 的全部索引）把每个 INVALID 索引报为错误，`platformCheck` 也会列出。

- 查看：`SELECT indexrelid::regclass, indrelid::regclass FROM pg_index WHERE NOT indisvalid;`
- 处理：`DROP INDEX CONCURRENTLY <索引名>;`，再手工执行迁移里的那条 `CREATE INDEX CONCURRENTLY IF NOT EXISTS …`（Flyway 的历史表已记为成功，不会再跑）；失败的原因（锁冲突、重复行）先排除。
- 部署新版本前对大表的并发建索引可能耗时较长：它在脚本自己的非事务连接中运行，Flyway 的启动在此期间等待。
