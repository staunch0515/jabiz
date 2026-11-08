# 告警试发：在测试环境（staging）照做的步骤

对应上线清单"新告警已配置并试发"、规格 F-F13 的验收（"人为把磁盘填到阈值时收到告警"）、troubleshooting T8 的归档，以及 `launch-day-platform-steps.md` §0。
做一次大约 40 分钟（多半是等待）。每步写了**要输入什么**、**应看到什么**、**不对时怎么办**。命令可以整段复制。

工具是平台的 `tools/ops/alert-fire-test.sh`（设计 13 §7），QuizBuks 的规则另有假数据 `deploy/quizbuks/alerting/quizbuks-alerts.fakes`；部署时两者都被放到服务器的
`/opt/apps/<环境>/ops/`，不需要在服务器上取仓库。

> **只对 staging 做。** `fire` 会以应用的名字推送假数据。staging 与 production 在同一台服务器上：第 6 步（磁盘）真的会把**共用的磁盘**填到阈值以上，
> production 的磁盘告警也会响。上线之前做；上线之后要做时，先告诉值班的人。

## 0. 前提（一次性）

| 要有 | 怎么确认 |
|---|---|
| 能用 SSH 登录服务器、能用 `sudo` | `ssh <用户>@<服务器>` |
| staging 的监控在运行：staging 的 `.env`（`/opt/apps/quizbuks-staging/.env`）有 `COMPOSE_PROFILES=observability`、`JABIZ_ALERT_EMAIL=<值班邮箱>`、Grafana 的发信设置 `GF_SMTP_*`（`DEPLOY.md`"2a"） | `docker ps --format '{{.Names}}' \| grep quizbuks-staging-lgtm` 有一行 |
| 部署过一次含本页的版本（服务器上有 `ops/`） | `ls /opt/apps/quizbuks-staging/ops/` 列出 `alert-fire-test.sh` 与 `quizbuks-alerts.fakes` |
| Grafana 的管理员密码（第一次登录时改过的；没改过是 `admin`） | 本机 `ssh -L 3000:127.0.0.1:<端口> <服务器>` 后打开 `http://localhost:3000` 能登录（端口是 `.env` 的 `QB_GRAFANA_PORT`，未设为 3000） |
| 服务器上有 `curl`、`python3`、`fallocate` | `command -v curl python3 fallocate` 列出三行 |

## 1. 准备（每次）

登录服务器后：

```bash
APP=quizbuks-staging
cd /opt/apps/$APP/ops
port=$(sed -n 's/^QB_GRAFANA_PORT=//p' ../.env | tail -n 1)
export GRAFANA_URL=http://127.0.0.1:${port:-3000}
export GRAFANA_USER=admin
read -rsp 'Grafana 的密码: ' GRAFANA_PASSWORD; echo; export GRAFANA_PASSWORD
# 收集器的端口不对外：从服务器本身经容器的内部地址连接
export OTLP_URL=http://$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' $APP-lgtm):4318
service=$(sed -n 's/^JABIZ_ALERT_SERVICE=//p' ../.env | tail -n 1); export JABIZ_ALERT_SERVICE=${service:-quizbuks}
percent=$(sed -n 's/^JABIZ_ALERT_DISK_PERCENT=//p' ../.env | tail -n 1); export JABIZ_ALERT_DISK_PERCENT=${percent:-80}
export ALERT_FOLDERS="jabiz quizbuks" ALERT_FAKES=$PWD/quizbuks-alerts.fakes
echo "$GRAFANA_URL $OTLP_URL $JABIZ_ALERT_SERVICE $JABIZ_ALERT_DISK_PERCENT"
```

**应看到**：一行，如 `http://127.0.0.1:3001 http://172.20.0.3:4318 quizbuks 80`。`OTLP_URL` 中间是空的（`http://:4318`）就是监控没在运行，回到第 0 步。

## 2. 规则都载入了

```bash
bash alert-fire-test.sh rules
```

**应看到**：每行 `<规则> inactive ok`（或 `pending` / `firing`），最后一行 `all 25 rules evaluate`（平台 17 条加 QuizBuks 8 条；以后加了规则数字会变）。
**不对时**：`no rules in the folder(s)` → 规则没有挂进容器，重新部署一次；某行不是 `ok` → 把这一行发给开发者。`401` → 密码不对。

## 3. 值班的人收到邮件

```bash
bash alert-fire-test.sh notify
```

**应看到**：`Grafana delivered a test notification to jabiz-oncall`，几分钟内 `JABIZ_ALERT_EMAIL` 的邮箱收到标题含 `jabiz alert test` 的邮件（看看垃圾邮件夹）。
**不对时**：`was not delivered` 后面是发信服务器的错误，核对 `.env` 的 `GF_SMTP_*`，改后 `docker compose up -d lgtm`（在 `/opt/apps/$APP/` 下）再试。

## 4. 每条规则都能响

```bash
bash alert-fire-test.sh fire
```

**应看到**（约 5 分钟）：
- 每条规则一行 `faked <规则>`；
- 两行"不能伪造"，正常：`jabiz-metrics-absent`（第 5 步另试）、`qb-reconcile-missing`（要 26 小时没有核对）；
- 最后 `all 23 rules are pending or firing`；
- 值班邮箱陆续收到告警邮件。规则里写了"持续多久"的（如 15 分钟）先是 pending，到时才发邮件。

假数据都标着 `alert-test`。一小时内规则自己恢复，恢复时还会收到"已解决"的邮件。`qb-reconcile-diff`（核对差异）在应用下一次报告真实值时恢复。
**不对时**：`not pending or firing after 300s: <规则>` → 把这一行与 `docker logs --tail 50 $APP-lgtm` 发给开发者；`Connection refused` → `OTLP_URL` 不对（第 1 步）。

## 5. 应用停了会告警

```bash
docker stop $APP-app
sleep 420     # 7 分钟
docker start $APP-app
```

**应看到**：停后 5～7 分钟收到 `No metrics from the application`（`jabiz-metrics-absent`）的邮件；启动后几分钟收到"已解决"。staging 在这段时间打不开，是正常的。

## 6. 磁盘告警（F-F13，T8）

先看占用：`df -h /var/lib/docker`。已经高于阈值（80 %）时先清理（`DEPLOY.md`"旧镜像的清理"），不要做这一步。

```bash
sudo -E bash alert-fire-test.sh disk /var/lib/docker
```

**应看到**：`filling … MiB in /var/lib/docker/.jabiz-alert-fire-test.…`，几分钟后 `all 1 rules are pending or firing`，然后 `removed …`（临时文件已删）。
十分钟后（规则要持续 10 分钟）值班邮箱收到 `Disk usage`；文件删掉后几分钟恢复。应用测量的是容器的工作目录所在的盘，也就是存放镜像的这块盘（`operations.md` §5）。
**不对时**：`already pending or firing` → 先等它恢复；中途按了 Ctrl-C → 脚本仍会删临时文件，确认 `ls -a /var/lib/docker | grep jabiz-alert` 没有输出。

## 7. 旧镜像的清理（F-F13）

在部署过两三次之后：

```bash
docker image ls | grep jabiz-quizbuks
cat /opt/apps/quizbuks-staging/deployed-images | tail -n 5
```

**应看到**：staging 的镜像不超过 3 个（`.env` 的 `QB_DEPLOY_KEEP_IMAGES`，未设为 3），另加 production 在用或保留的；部署日志（GitHub → Actions → QuizBuks deploy → Deploy）里有 `prune-images: deleted …`。

## 8. 记录

把下表填好，贴到上线清单"安全与运维"的"新告警已配置并试发"一项（确认人、日期），并告诉开发者归档 T8。

| 步骤 | 结果（通过 / 不通过 + 说明） | 时间 |
|---|---|---|
| 2 规则 | | |
| 3 测试邮件 | | |
| 4 每条规则 | | |
| 5 应用停止 | | |
| 6 磁盘 | | |
| 7 旧镜像 | | |

做完后关掉 SSH 会话（第 1 步输入的密码只在这个会话里）。
