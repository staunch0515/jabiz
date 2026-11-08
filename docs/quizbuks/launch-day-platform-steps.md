# 上线当天的平台步骤（值班的人照做）

上线清单（`launch-checklist.md`）中属于平台的各项，按要做的顺序排成一张单子：每步写明在哪里做、命令、应看到什么。
详细说明仍以 `deploy/quizbuks/DEPLOY.md`、`operations.md` 与平台的 `docs/guide/platform-operations.md` 为准；本页只排顺序、给出可照抄的命令。
做完一步在上线清单对应的项写上确认人、日期与依据（命令的输出或演练记录）。

"本机彩排"一栏：2026-11-08 S1 在本机按本页走过的结果（本机没有 Docker 与 Grafana，标"服务器上做"的只能在服务器或测试环境上做）。

## 0. 前一天：测试环境（staging）

| # | 做什么 | 命令 / 位置 | 应看到 | 本机彩排 |
|---|---|---|---|---|
| 0.1 | 告警的规则全部能求值 | 在服务器上，staging 的 Grafana：`GRAFANA_URL=http://localhost:3001 GRAFANA_USER=… GRAFANA_PASSWORD=… tools/ops/alert-fire-test.sh rules` | 每条规则 `ok`；QuizBuks 的文件夹 `quizbuks` 也在 | 服务器上做（规则的渲染与格式由 CI 的 `alerts.test.sh` 检查，已通过） |
| 0.2 | 值班的人收到测试告警 | 同上，`… alert-fire-test.sh notify` | 值班邮箱收到 Grafana 的测试邮件 | 服务器上做 |
| 0.3 | 每条规则能进入 pending / firing | 同上，`… alert-fire-test.sh fire` | 列出的每条规则 `pending` 或 `firing`（一小时内自行消失） | 服务器上做 |
| 0.4 | 磁盘告警（F-F13 的验收） | 同上，`… alert-fire-test.sh disk /var/lib/docker` | 磁盘告警进入 pending 后脚本删去临时文件 | 服务器上做 |
| 0.5 | 应用停 5 分钟的告警 | `docker stop quizbuks-staging-app`，等 5 分钟，`docker start quizbuks-staging-app` | 收到 `jabiz-metrics-absent` | 服务器上做 |
| 0.6 | 测试环境的横条 | 打开 staging 的 App、发起方页面与平台管理系统的登录页 | 顶部**有**"测试环境"的横条 | 服务器上做 |

## 1. 正式环境的设置文件

| # | 做什么 | 命令 / 位置 | 应看到 | 本机彩排 |
|---|---|---|---|---|
| 1.1 | 按 `DEPLOY.md`"2. `.env`"写好 `/opt/apps/quizbuks/.env`；四把平台密钥各自 `openssl rand -base64 48`，`JABIZ_INTEGRITY_KEY`、`JABIZ_MFA_KEY`、`JABIZ_SEAL_KEY` 另存离线副本 | 服务器 | — | 按模板写出的文件通过 1.2 |
| 1.2 | 部署工作流的检查（缺少、过短、格式） | `sh deploy/quizbuks/server/check-env.sh /opt/apps/quizbuks/.env deploy/quizbuks/server/required-env` | 无输出、退出码 0 | 通过 |
| 1.3 | 平台的设置检查（此时还有首个管理员的两个变量，**预期**正是这两条错误） | `tools/ops/env-check.sh /opt/apps/quizbuks/.env deploy/quizbuks/server/env-check.rules` | 只有 `JABIZ_BOOTSTRAP_ADMIN_USER` / `_PASSWORD` 两条 `ERROR`；`JABIZ_FILES_LOCAL_ROOT` 的警告（镜像中已设）看过即可 | 与预期相同 |

## 2. 第一次部署与首次启动

| # | 做什么 | 命令 / 位置 | 应看到 | 本机彩排 |
|---|---|---|---|---|
| 2.1 | 先把 quizbuks.com 从 staging 移交出来（否则 Caddy 拒绝整个配置） | `DEPLOY.md`"4. 第一次部署" | — | 服务器上做 |
| 2.2 | 部署 | 推送 `1.3/quizbuks-release`，或 Actions → **QuizBuks deploy** | 工作流绿；`docker logs quizbuks-app` 中没有 `Platform check failed` | 同一个 jar 在本机以非 dev 启动：约 15 秒健康，无启动检查错误 |
| 2.3 | 首个管理员开启两步验证、保存恢复码 | `/admin/` →"安全设置" | — | 本机经接口做过（恢复码 10 个） |
| 2.4 | `QB_SETUP`、财务管理员、两位超级管理员 | `operations.md` §3 第 3–5 步 | — | 不在本页（业务设置） |
| 2.5 | 从 `.env` 删去 `JABIZ_BOOTSTRAP_ADMIN_USER` **与** `JABIZ_BOOTSTRAP_ADMIN_PASSWORD`，加 `QB_BOOTSTRAP_ADMIN_DONE=true`，重新部署当前 SHA | `DEPLOY.md`"5. 首次启动之后"第 3 步 | 健康 | 删去两者后 1.2、2.6 都通过；只删密码时 2.6 仍报 `JABIZ_BOOTSTRAP_ADMIN_USER`（文档已改为两者都删） |
| 2.6 | 平台的设置检查，这次必须 0 错误 | 同 1.3 | `0 error(s)` | 通过（`0 error(s), 1 warning(s)`） |
| 2.7 | 正式环境没有横条 | 打开 App、发起方页面与平台管理系统的登录页 | 顶部**没有**"测试环境"的横条、标题没有 `[TEST]` | 服务器上做 |

## 3. 备份与恢复演练（加密钥轮换）

前提：`DEPLOY.md`"备份的做法"A、B 已由需求方与值班的人做完（WAL 归档与每日全量在跑，`pgbackrest info` 有全量备份）。

| # | 做什么 | 命令 / 位置 | 应看到 | 本机彩排 |
|---|---|---|---|---|
| 3.1 | 选 T（如一小时前），恢复到新的空卷（只恢复） | `DEPLOY.md`"C. 恢复演练"第 1 步（`DRILL_RESTORE_ONLY=true`） | `restored in …s`；写出第一份记录（恢复用时） | 本机 pgBackRest（加密仓库）：1 秒 |
| 3.2 | 启动恢复出的库，确认不归档 | 同上第 2 步；`show archive_mode` | `off` | `off`；恢复点之后的那次登录不在副本中（4 条操作而不是 5 条） |
| 3.3 | 文件恢复到 T 之前最近的快照 | 同上第 3 步 | — | 服务器上做（本机没有文件） |
| 3.4 | 核对与密钥轮换 | 同上第 4 步（三个模板与 `DRILL_ROTATE=true`） | 记录的 `Result: PASSED`；三段（生产密钥、新密钥加旧密钥重新加密到底、只用新密钥）每项 `passed` | **PASSED**，三段各 16–17 秒：1 个二次验证重新加密、三个模板都没有行 |
| 3.5 | 收拾 | 同上第 5 步 | — | — |
| 3.6 | 两份记录都附在上线清单"运维"的"备份已测试"一项；RTO = 3.1 的恢复用时 + 3.4 的核对用时 | — | — | — |

## 4. 安全的最后几项

| # | 做什么 | 命令 / 位置 | 应看到 |
|---|---|---|---|
| 4.1 | 全部管理员有二次验证、管理员角色"要求二次验证"、建立期已结束 | 平台管理系统的用户与角色页；`operations.md` §3 | — |
| 4.2 | 依赖与镜像的已知漏洞 | `security/dependency-review.md`；部署的镜像来自已合入平台 17zz6 的提交 | QuizBuks CI 的"Image vulnerability report"中 `app.jar` 没有高、严重条目 |
| 4.3 | 数据库与管理端口不对公网开放、只经 HTTPS | `ss -ltnp` 只在内部地址；`curl -I http://quizbuks.com` 转到 https | — |

## 5. 上线之后的第一周

- 每天看一次 Grafana 的告警列表与 `jabiz.keys.*`、`jabiz.auth.refresh.reuse` 等仪表（`platform-operations.md` §5）。
- 第一次真实的每日核对（`qb.system.recon-totals`）结果为没有行。
- 恢复演练改为每季度一次（`DEPLOY.md`"C."），换密钥之前再做一次带 `DRILL_ROTATE=true` 的。
