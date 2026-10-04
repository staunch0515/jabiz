# 财务应用：安装与升级（FIN-NF-006）

面向管理员，按本文一天内完成安装。部署文件在 `deploy/finance/`；备份、恢复与监控见 `operations.md`。

## 1. 需要什么

| 项 | 要求 |
|---|---|
| 主机 | Linux，4 vCPU、16 GB 内存起（性能结果的环境，`perf.md` §8），数据盘按每年约 8 GB 数据（NF-001 的一年全量）加备份留足 |
| 软件 | Docker Engine 24+ 与 Compose v2；`openssl`；做升级检查与演练时另需 JDK 21、Node 22 + pnpm（仓库的 `frontend/` 依赖） |
| 网络 | 应用只监听本机 `127.0.0.1:8080`，对外经反向代理（TLS）；数据库只监听本机 `127.0.0.1:5438` |
| 备份去处 | 另一块盘或另一台机器（`operations.md` §2） |

## 2. 安装

在仓库根目录：

1. 写 `deploy/finance/.env`（不提交，权限 600）：
   ```bash
   umask 077
   cat > deploy/finance/.env <<EOF
   DB_PASSWORD=$(openssl rand -base64 24)
   JABIZ_JWT_SECRET=$(openssl rand -base64 48)
   JABIZ_INTEGRITY_KEY=$(openssl rand -base64 48)
   JABIZ_MFA_KEY=$(openssl rand -base64 48)
   JABIZ_BOOTSTRAP_ADMIN_USER=admin
   JABIZ_BOOTSTRAP_ADMIN_PASSWORD=<至少 12 位的初始密码>
   EOF
   ```
   把完整性密钥与二次验证密钥另存一份到保险处：没有它们，封存无法核验，已登记二次验证的用户无法登录。
   邮件（待办通知、单据发送）另设 `JABIZ_MAIL_ENABLED=true`、`JABIZ_MAIL_FROM`、`JABIZ_MAIL_BASE_URL`（用户打开的地址）与 `SPRING_MAIL_*`（`api.md` §6）；单点登录见 `docs/guide/quickstart.md`。
2. 构建并启动：`docker compose -f deploy/finance/docker-compose.yml up -d --build`（首次构建下载 Gradle 依赖与 Node，约十分钟）。
3. 等待健康：`docker compose -f deploy/finance/docker-compose.yml ps` 中 `finance` 为 `healthy`。启动时平台先迁移平台脚本、再迁移财务脚本，
   并做全部启动检查；任何不一致都会在日志中一次列出并拒绝启动（`docker compose … logs finance`）。
4. 用初始管理员登录 `http://localhost:8080`（或反向代理的地址），立即登记二次验证、修改密码。
5. 建账：管理员运行"财务设置"（`FIN_SETUP`，建角色、审批规则、关账模板），由控制人发布审批规则（四眼）；建会计年度、科目表，
   导入期初余额与主数据（`docs/finance/api.md`、页面"Imports"）；为各人建用户、分配角色（职责分离规则在设置中给出）。
6. 安排备份（`operations.md` §2）并做一次恢复演练（§4）。

## 3. 安装后的检查

- `curl -sf localhost:8080/actuator/health` 为 `{"status":"UP"}`。
- 数据库归档在工作：`docker compose … exec db psql -U finance -d finance -c "SELECT archived_count, failed_count FROM pg_stat_archiver"`，
  `failed_count` 为 0、`archived_count` 随时间增加（至多五分钟一个）。
- 第一次 `deploy/finance/backup.sh base` 成功，备份目录中有 `base-…/complete`。

## 4. 升级

升级只换应用镜像；数据库迁移在新版本启动时自动执行（Flyway，先平台后财务），执行前后由启动检查核对表结构与元数据。

1. 通知用户停止录入；记下升级前的报表：
   `E2E_BASE_URL=http://localhost:8080 REPORTS_USER=<有报表权限的用户> REPORTS_PASSWORD=… tools/finance/ops/upgrade-check.sh before`
   （默认读 2026 年 1 月，即示范公司的账；自己的账用 `REPORTS_FROM` / `REPORTS_THROUGH` 指定一个已关账的月份，银行账户用 `REPORTS_BANK`）。
2. 做一次基础备份：`deploy/finance/backup.sh base`，并记下完成的时刻 `T`（UTC，如 `2026-10-04 09:30:00+00`）。
3. 取得新版本（`git pull` 到新的发布标签），`docker compose -f deploy/finance/docker-compose.yml up -d --build`，等待 `healthy`。
4. 核对：`… tools/finance/ops/upgrade-check.sh after`。FIN-EXP-01 … 14 的报表（试算表、过账登记簿、三张报表、权益变动、应收应付账龄、
   银行调节、固定资产登记簿、汇兑损益、销售税、1099）逐行与升级前相同即通过；新版本才有的报表标为"new in this release"。
   不同即停止使用并回退：检出原来的发布标签、`docker compose -f deploy/finance/docker-compose.yml build finance`（旧镜像），
   再 `deploy/finance/restore.sh --yes deploy/finance/backups/base-<第 2 步> '<T>'`——必须给出时刻 `T`：不给时会重放到 WAL 的末尾，
   连新版本的迁移一起恢复。然后报告问题。
5. 通知用户恢复录入。

升级不能回退到旧版本（旧版本不认识新迁移）：回退一律用升级前的备份恢复。跨平台版本线的升级以发布说明为准。

## 5. 升级演练

`tools/finance/ops/drill.sh upgrade`（需要 Docker、JDK 21 与 `frontend/node_modules`）：在一次性的安装上以旧版本的 jar（`OLD_JAR`）
建账并过账，读升级前的报表，换成新版本的 jar 启动（迁移自动执行），再读一次并比较。2026-10-04 在开发环境以 F9b 的发布
（合入 #77 时的 `060bb7c`）升级到 F11d：执行了 2 个迁移，FIN-EXP-01 … 14 的报表全部相同（现金流量表为新报表）。
示范公司的完整账（FIN-EXP 的全部单据）只在集成测试中构建，演练的账只有日记账；对示范公司的账做升级检查由使用方在其副本上按 §4 运行。

## 6. 演示环境

用示范公司 Northwind Components（`docs/finance-requirements/sample-company/`）的账演示：1 月 2026 的全部单据按 FIN-EXP-02 录入并关账，
2 月有一笔日记账与一张账单等待控制人审批。数据经接口由各角色的人录入，与集成测试的 `JanuaryBooks` 相同。只用于新装的演示环境，不要对正式账运行。

1. `.env` 中除 §2 的各项外再写（编号接着样例的旧单据，管理员不必登记二次验证即可运行财务设置）：
   ```bash
   JABIZ_SECURITY_MFA_ADMINISTRATION=false
   FINANCE_AR_INVOICE_NUMBERS_START=1004
   FINANCE_AR_CREDIT_MEMO_NUMBERS_START=2001
   FINANCE_FA_ASSET_NUMBERS_START=3
   ```
   本机演示时 §2 的密钥可以不写（首次启动自动生成），只写 `JABIZ_BOOTSTRAP_ADMIN_USER` / `JABIZ_BOOTSTRAP_ADMIN_PASSWORD` 与上面四行。
2. 按 §2 启动，等 `healthy`。
3. 在仓库根目录（需要 Node 22 与 pnpm）：
   ```bash
   (cd frontend && pnpm install)
   read -rsp 'admin password: ' E2E_ADMIN_PASSWORD; echo; read -rsp 'demo password (12+): ' DEMO_PASSWORD; echo
   export E2E_ADMIN_PASSWORD DEMO_PASSWORD   # 不写在命令行上，免得留在 shell 历史中
   tools/finance/demo/seed.sh
   ```
   约一分钟；途中核对发票、付款批、资产与日记账的编号，最后核对资产负债表合计 617,415.00、净利润 5,127.10（FIN-EXP-05、04），并列出演示用户。
   账里已有完整的演示数据时什么也不做；上一次中途失败（或账里有别的数据）时拒绝运行，按下面"重来一次"从空库开始。
   应用不在本机 8080 时设 `E2E_BASE_URL`。
   运行完后如要给别人演示，从 `.env` 删去 `JABIZ_SECURITY_MFA_ADMINISTRATION=false` 并重启应用（管理员登录即要求登记二次验证）。
4. 用下列用户登录（密码都是 `DEMO_PASSWORD`）：

   | 用户 | 角色 | 可以演示的 |
   |---|---|---|
   | `controller` | 控制人 | 待办中审批 2 月的日记账与账单；三张报表、仪表盘、钻取；关账工作台（1 月已关）；审计、访问审查 |
   | `accountant` | 会计 | 日记账登记簿与录入、试算表、银行匹配与调节、折旧、重估 |
   | `ar-clerk` | 应收职员 | 客户、发票（INV-1004 的税额说明）、收款 |
   | `ap-clerk` | 应付职员 | 供应商、账单、付款批；登录要二次验证 |
   | `treasurer` | 出纳 | 银行账户、放行付款批；登录要二次验证 |

   `ap-clerk` 与 `treasurer` 的认证器密钥在脚本输出的最后（`otpauth://` 地址，可在认证器应用中手工添加密钥）。

演示数据的业务日期在 2026 年 1、2 月：仪表盘与报表选到这两个月（默认的"本月"按当天，可能没有数据）。重来一次：删除数据库卷
（`docker compose -f deploy/finance/docker-compose.yml down -v`，会删除全部数据）后重新启动并运行脚本。
