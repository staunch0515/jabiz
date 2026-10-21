# QuizBuks — 部署到共用的 Docker 服务器

QuizBuks 是**一个镜像、一个服务**（`quizbuks-app`，容器内端口 8080）：后端与三个前端（App `/`、商家后台 `/sponsor/`、管理后台 `/admin/`）都在一个 jar 中。

| 项目 | 值 |
|---|---|
| APP_NAME | `quizbuks` |
| 域名 | `quizbuks.example.com` |
| 镜像 | `ghcr.io/staunch0515/jabiz-quizbuks:<提交 SHA>`（`linux/amd64`） |
| 服务器上的文件 | `/opt/apps/quizbuks/compose.yaml`、`site.caddy`（由 workflow 上传）、`.env`（只在服务器上手工维护） |
| 仓库中的来源 | `deploy/quizbuks/server/compose.yaml`、`deploy/quizbuks/server/site.caddy`、`.github/workflows/quizbuks-deploy.yml` |
| 网络 | `proxy`（Caddy）、`db`（PostgreSQL），都是外部网络；不发布任何端口 |
| 数据卷 | `quizbuks_files`（上传的图片、PDF、音频：与数据库一起备份）、`quizbuks_secrets` |

本机开发用的 `deploy/quizbuks/compose.dev.yaml`（自带数据库）与服务器无关，部署不使用它。

## 首次部署前（在服务器上做一次）

### 1. 数据库

```sh
/opt/infra/scripts/create-db.sh quizbuks
```

把输出的连接串拆成下面 `.env` 中的六个数据库变量（应用用 R2DBC 读写，Flyway 用 JDBC 在启动时自动迁移，两者分别配置）。
例如输出 `postgresql://quizbuks:<密码>@postgres:5432/quizbuks` 时：主机 `postgres`、端口 `5432`、库与角色都是 `quizbuks`。

### 2. `/opt/apps/quizbuks/.env`

```sh
mkdir -p /opt/apps/quizbuks && touch /opt/apps/quizbuks/.env && chmod 600 /opt/apps/quizbuks/.env
```

下面是需要的变量与格式（`<…>` 换成实际值；不要提交到仓库）。完整的说明、来源与轮换方法见 `docs/quizbuks/operations.md` §1。
这个文件同时是 compose 的变量文件，值中的 `$` 会被当作变量展开：含 `$` 的密码等值用单引号括起（`SPRING_MAIL_PASSWORD='pa$$word'`）。

```sh
# 数据库（create-db.sh 的输出）
SPRING_R2DBC_URL=r2dbc:postgresql://postgres:5432/quizbuks
SPRING_R2DBC_USERNAME=quizbuks
SPRING_R2DBC_PASSWORD=<数据库密码>
SPRING_FLYWAY_URL=jdbc:postgresql://postgres:5432/quizbuks
SPRING_FLYWAY_USER=quizbuks
SPRING_FLYWAY_PASSWORD=<数据库密码>

# 平台密钥：各自 openssl rand -base64 48 生成；JABIZ_INTEGRITY_KEY 与 JABIZ_MFA_KEY 另存离线副本，丢失不能恢复
JABIZ_JWT_SECRET=<base64>
JABIZ_INTEGRITY_KEY=<base64>
JABIZ_MFA_KEY=<base64>

# 首个管理员：只在第一次启动（空库）时需要，见下文"首次启动之后"
JABIZ_BOOTSTRAP_ADMIN_USER=admin
JABIZ_BOOTSTRAP_ADMIN_PASSWORD=<强密码>

# 反向代理：只信任 Caddy 在 proxy 网络中的地址，使登录限流与登录记录看到真实的客户端地址
#   docker inspect <Caddy 容器名> -f '{{(index .NetworkSettings.Networks "proxy").IPAddress}}'
JABIZ_TRUSTED_PROXIES=<如 172.18.0.2/32>

# 地址
JABIZ_MAIL_BASE_URL=https://quizbuks.example.com
QB_APP_LINK=https://quizbuks.example.com/
QB_SPONSOR_LINK=https://quizbuks.example.com/sponsor/

# 邮件（注册验证、找回密码）
JABIZ_MAIL_ENABLED=true
JABIZ_MAIL_FROM=noreply@<发件域>
SPRING_MAIL_HOST=<SMTP 主机>
SPRING_MAIL_PORT=587
SPRING_MAIL_USERNAME=<SMTP 用户>
SPRING_MAIL_PASSWORD=<SMTP 密码>
# 587 端口要先 STARTTLS 才能认证（服务商只给 465 时改为 SPRING_MAIL_PORT=465 与 …_SMTP_SSL_ENABLE=true）
SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH=true
SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE=true
SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_REQUIRED=true

# Stripe（测试模式用 sk_test_ / rk_test_；端点 https://quizbuks.example.com/api/inbound/stripe 与 /api/inbound/stripe-connect）
STRIPE_API_KEY=<rk_test_…>
STRIPE_WEBHOOK_SECRET=<whsec_…>
STRIPE_CONNECT_WEBHOOK_SECRET=<whsec_…>

# Claude（AI 出题；开关 qb.ai.enabled 缺省关闭）
ANTHROPIC_API_KEY=<sk-ant-…>
```

`QB_LOCAL_SECRETS=false`、`QB_STRIPE_PLACEHOLDERS=false` 已写在 compose 中：缺少任何密钥时应用拒绝启动，不会自己生成。
**关于可信代理**：`proxy` 网络上还有其他应用的容器，它们能直接访问 `quizbuks-app:8080`。只信任 Caddy 的地址，它们就不能伪造客户端地址；
若信任整个网段（`docker network inspect proxy -f '{{(index .IPAM.Config 0).Subnet}}'`），同一网络上的任何容器都能冒充任意地址，绕过登录限流。
Caddy 重建后地址可能改变：改后应用只会把所有请求看成来自 Caddy（限流变严），不会放松安全，按新地址更新 `.env` 并重新部署即可。
邮件服务器不在健康检查中（服务器外的故障不使部署回滚），发信失败只出现在日志里：首次部署后注册一个测试账号，确认收到验证邮件。

**不要**设 `SPRING_PROFILES_ACTIVE=dev`、`JABIZ_SECURITY_MFA_ADMINISTRATION=false`、`QB_DIRECT_EXPOSURE=true` 或 `server.forward-headers-strategy`
（客户端地址只认 `JABIZ_TRUSTED_PROXIES` 中的代理）。

### 3. DNS

仓库中的 `deploy/quizbuks/server/site.caddy` 与本文写的是 `quizbuks.example.com`：用真实域名时先改 `site.caddy`（以及上面 `.env` 中的地址）再推送，
否则 Caddy 无法为它申请证书。给域名添加 A 记录，指向服务器的公网 IPv4（有 IPv6 时另加 AAAA）。记录生效后 Caddy 才能申请证书。

### 4. 第一次部署

推送 `1.2/quizbuks`，或在 GitHub → Actions → **QuizBuks deploy** → Run workflow（`image-tag` 留空）。第一次启动在空库上迁移并运行全部启动检查，
需要一两分钟（健康检查的 `start_period` 为 180 秒）。日志：`docker logs -f quizbuks-app`。

### 5. 首次启动之后

1. 以 `admin` 登录 `https://quizbuks.example.com/admin/`，在右上角用户名 →"安全设置"中开启两步验证。
2. 在"流程"中执行 `QB_SETUP`，再按 `docs/quizbuks/operations.md` §3 建财务管理员并由其发布 `QB_SETUP` 的提案（发布之前充值、入驻与提交发布一律被拒）。
3. 从 `.env` 删去 `JABIZ_BOOTSTRAP_ADMIN_PASSWORD`，加上 `QB_BOOTSTRAP_ADMIN_DONE=true`（入口脚本在两者都没有时拒绝启动），
   再以下文"回滚"的方式重新部署当前的 SHA（Run workflow，`image-tag` 填当前的 SHA，即 `docker inspect quizbuks-app -f '{{.Config.Image}}'` 中冒号后的部分），
   经 `deploy-app.sh` 等待健康并在失败时回滚。

## 之后的部署

- **自动**：每次推送 `1.2/quizbuks`：测试（`:quizbuks:check`、入口脚本的测试、两个前端与后台扩展的检查）→ 构建并推送镜像（标签为提交 SHA 与 `latest`）→ 上传 compose 与站点 → `deploy-app.sh`。
  部署失败时 `deploy-app.sh` 自动回到上一个标签。
- **手动**：GitHub → Actions → **QuizBuks deploy** → Run workflow（分支 `1.2/quizbuks`），`image-tag` 留空即测试、构建并部署分支的最新提交。从其他分支运行时不构建、不部署。
- **回滚**：
  - Run workflow（分支选 `1.2/quizbuks`）时在 `image-tag` 填旧的**完整** 40 位提交 SHA：跳过测试与构建，以该提交自己的 compose 与站点部署这个已有的镜像；或者
  - 在服务器上 `/opt/infra/scripts/deploy-app.sh quizbuks <旧 SHA>`（用的是服务器上现有的 compose 与站点）。镜像包是私有的时先登录 GHCR（有 `read:packages` 的个人令牌）：
    workflow 的登录只在它自己的临时 Docker 配置中，部署后即删除，不影响服务器上其他应用的登录。
  - **数据库迁移不随回滚撤销**：Flyway 只向前。回滚到更早的版本前确认其间没有新迁移（`backend/quizbuks/src/main/resources/db/migration` 与平台的 `db/jabiz`）：
    旧版本不认识新迁移改过的表结构，有新迁移时应修复后向前部署，而不是回滚。

## 备份

数据库按服务器的方案备份 `quizbuks` 库；另外备份数据卷 `quizbuks_files`（上传的文件不在数据库中），并离线保存 `JABIZ_INTEGRITY_KEY` 与 `JABIZ_MFA_KEY`。
恢复演练见 `docs/quizbuks/operations.md` §2。
