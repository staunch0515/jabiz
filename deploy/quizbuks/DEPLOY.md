# QuizBuks — 部署到共用的 Docker 服务器（production 与 staging）

QuizBuks 是**一个镜像、一个服务**（`<APP_NAME>-app`，容器内端口 8080）：后端与三个前端（App `/`、商家后台 `/sponsor/`、管理后台 `/admin/`）都在一个 jar 中。
同一仓库、同一个镜像部署为两个互不相干的环境，各有自己的服务器目录、`.env`、数据库、数据卷与域名：

| | production | staging |
|---|---|---|
| 分支 | `1.2/quizbuks-release` | `1.2/quizbuks`（工作分支合入的线分支） |
| APP_NAME | `quizbuks` | `quizbuks-staging` |
| 域名 | `quizbuks.com` | `staging.quizbuks.com` |
| 服务器目录 | `/opt/apps/quizbuks/` | `/opt/apps/quizbuks-staging/` |
| 数据库（库与角色） | `quizbuks` | `quizbuks_staging` |
| 容器 | `quizbuks-app` | `quizbuks-staging-app` |
| 数据卷 | `quizbuks_files`、`quizbuks_secrets` | `quizbuks-staging_files`、`quizbuks-staging_secrets` |
| GitHub Environment | `production` | `staging` |

- 镜像：`ghcr.io/staunch0515/jabiz-quizbuks:<提交 SHA>`（`linux/amd64`），两个环境用同一个镜像，只是部署的提交不同。
- 仓库中的来源：`deploy/quizbuks/server/compose.yaml`（项目名与容器名取自 `.env` 的 `APP_NAME`）、`deploy/quizbuks/server/site.caddy.tpl`
  （占位 `__DOMAIN__`、`__APP_NAME__`，由 workflow 按环境渲染）、`.github/workflows/quizbuks-deploy.yml`。
- 服务器上每个环境的目录中：`compose.yaml`、`site.caddy`（由 workflow 上传）、`.env`（只在服务器上手工维护，workflow 从不改它）。
- 网络：`proxy`（Caddy）、`db`（PostgreSQL），都是外部网络；不发布任何端口。
- 本机开发用的 `deploy/quizbuks/compose.dev.yaml`（自带数据库）与服务器无关，部署不使用它。

**发布流程**：工作分支照常经 PR 合入 `1.2/quizbuks`，合入即部署到 staging；在 staging 上确认后，以 PR 把 `1.2/quizbuks` 合并进 `1.2/quizbuks-release`
（只合并，不变基、不拣选），合入即部署到 production。

## 一次性设置（GitHub）

1. **基础域名**：缺省为 `quizbuks.com`（staging 即 `staging.quizbuks.com`）；要用其他域名时在 Settings → Secrets and variables → Actions → Variables
   新建 `QUIZBUKS_BASE_DOMAIN`（小写、不带协议）。**服务器的 secrets**：同一页的 Secrets 中设 `SERVER_HOST`、`SERVER_USER`、`SERVER_SSH_KEY`、`SERVER_KNOWN_HOSTS`
   （仓库级，或在 Environments 的 `staging` / `production` 中各设一份），缺少任何一个时部署在第一步停止并列出缺少的名字。
2. **Environments**：Settings → Environments 中的 `production` 与 `staging` 在第一次部署时自动建立；建议给 `production` 设 Required reviewers
   与 Deployment branches（只允许 `1.2/quizbuks-release`）。服务器的 secrets（`SERVER_*`）可以留在仓库级，也可以按环境分别设置。
3. **production 分支**：staging 部署正常之后，从 `1.2/quizbuks` 建 `1.2/quizbuks-release` 并推送（`git push origin origin/1.2/quizbuks:refs/heads/1.2/quizbuks-release`）。
   推送即第一次部署 production，所以先完成下面 production 的服务器设置。

## 首次部署前（在服务器上，每个环境各做一次）

下文以 production 为例；staging 的不同之处见"staging 环境"一节。

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
# 环境：compose 的项目名与容器名；workflow 部署前检查这一行与所部署的环境一致（不加引号）
APP_NAME=quizbuks
# 页面上的系统标志（平台 16zd）：只有正式环境写 production；其他值（或不写）都让每一页顶部显示"测试环境"的横条。
# workflow 部署前检查：production 的 .env 必须有这一行，staging 的 .env 不得写 production
JABIZ_WEB_ENVIRONMENT=production

# 数据库（create-db.sh 的输出）
SPRING_R2DBC_URL=r2dbc:postgresql://postgres:5432/quizbuks
SPRING_R2DBC_USERNAME=quizbuks
SPRING_R2DBC_PASSWORD=<数据库密码>
SPRING_FLYWAY_URL=jdbc:postgresql://postgres:5432/quizbuks
SPRING_FLYWAY_USER=quizbuks
SPRING_FLYWAY_PASSWORD=<数据库密码>

# 平台密钥：各自 openssl rand -base64 48 生成；JABIZ_INTEGRITY_KEY、JABIZ_MFA_KEY 与 JABIZ_SEAL_KEY 另存离线副本，丢失不能恢复
JABIZ_JWT_SECRET=<base64>
JABIZ_INTEGRITY_KEY=<base64>
JABIZ_MFA_KEY=<base64>
# 商家邮件服务器密码的密封密钥（Q29）：丢失或更换后商家要重新输入密码
JABIZ_SEAL_KEY=<base64>

# 首个管理员：只在第一次启动（空库）时需要，见下文"首次启动之后"
JABIZ_BOOTSTRAP_ADMIN_USER=admin
JABIZ_BOOTSTRAP_ADMIN_PASSWORD=<强密码>

# 反向代理：只信任 Caddy 在 proxy 网络中的地址，使登录限流与登录记录看到真实的客户端地址
#   docker inspect <Caddy 容器名> -f '{{(index .NetworkSettings.Networks "proxy").IPAddress}}'
JABIZ_TRUSTED_PROXIES=<如 172.18.0.2/32>

# 地址（本环境的域名）
JABIZ_MAIL_BASE_URL=https://quizbuks.com
QB_APP_LINK=https://quizbuks.com/taker/
QB_SPONSOR_LINK=https://quizbuks.com/sponsor/

# 邮件（注册验证、找回密码）
JABIZ_MAIL_ENABLED=true
JABIZ_MAIL_FROM=noreply@<发件域>
SPRING_MAIL_HOST=<SMTP 主机>
SPRING_MAIL_PORT=587
SPRING_MAIL_USERNAME=<SMTP 用户>
SPRING_MAIL_PASSWORD=<SMTP 密码>
# 用 Gmail（quizbuks@gmail.com，Q41）发信时：JABIZ_MAIL_FROM=quizbuks@gmail.com、SPRING_MAIL_HOST=smtp.gmail.com、
# SPRING_MAIL_PORT=587、SPRING_MAIL_USERNAME=quizbuks@gmail.com、SPRING_MAIL_PASSWORD=<该账号的应用专用密码>（须先开启两步验证）。
# 发件人必须就是登录的 Gmail 账号，否则被当作冒充；个人 Gmail 每天约 500 封的上限，注册一多就要换成自有域名的发信服务。
# 587 端口要先 STARTTLS 才能认证（服务商只给 465 时改为 SPRING_MAIL_PORT=465 与 …_SMTP_SSL_ENABLE=true）
SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH=true
SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE=true
SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_REQUIRED=true

# Stripe（测试模式用 sk_test_ / rk_test_；端点 https://<本环境的域名>/api/inbound/stripe 与 /api/inbound/stripe-connect）
STRIPE_API_KEY=<rk_test_…>
STRIPE_WEBHOOK_SECRET=<whsec_…>
STRIPE_CONNECT_WEBHOOK_SECRET=<whsec_…>

# Claude（AI 出题；开关 qb.ai.enabled 缺省关闭）
ANTHROPIC_API_KEY=<sk-ant-…>
```

`QB_LOCAL_SECRETS=false`、`QB_STRIPE_PLACEHOLDERS=false` 已写在 compose 中：缺少任何密钥时应用拒绝启动，不会自己生成。
**关于可信代理**：`proxy` 网络上还有其他应用（包括另一个环境）的容器，它们能直接访问本环境的容器。只信任 Caddy 的地址，它们就不能伪造客户端地址；
若信任整个网段（`docker network inspect proxy -f '{{(index .IPAM.Config 0).Subnet}}'`），同一网络上的任何容器都能冒充任意地址，绕过登录限流。
Caddy 重建后地址可能改变：改后应用只会把所有请求看成来自 Caddy（限流变严），不会放松安全，按新地址更新 `.env` 并重新部署即可。
邮件服务器不在健康检查中（服务器外的故障不使部署回滚），发信失败只出现在日志里：首次部署后注册一个测试账号，确认收到验证邮件。

**不要**设 `SPRING_PROFILES_ACTIVE=dev`、`JABIZ_SECURITY_MFA_ADMINISTRATION=false`、`QB_DIRECT_EXPOSURE=true` 或 `server.forward-headers-strategy`
（客户端地址只认 `JABIZ_TRUSTED_PROXIES` 中的代理）。

### 3. DNS

给域名添加 A 记录，指向服务器的公网 IPv4（有 IPv6 时另加 AAAA）。记录生效后 Caddy 才能申请证书。

### 3a. Cloudflare（记录经 Cloudflare 代理时，即橙色云朵）

Cloudflare 的浏览器检查（Browser Integrity Check）与 Bot Fight Mode 把不是浏览器的请求以 **403 `error code: 1010`** 拒绝，请求到不了 Caddy 与应用。
这挡住的是**外部服务的回调**：Stripe 的 Webhook（`/api/inbound/stripe`、`/api/inbound/stripe-connect`）一个也到不了，充值付款成功却一直是 `OPEN`。

- Security → **WAF → Custom rules** → 新建：条件 **URI Path starts with `/api/inbound/`**，动作 **Skip**，勾选可跳过的全部检查（含 Browser Integrity Check）。
  应用对每个回调自己验签（`STRIPE_WEBHOOK_SECRET` 等），跳过 Cloudflare 的检查不降低安全。
- Security → **Bots**：免费套餐的 **Bot Fight Mode** 不能按路径跳过，开着时关掉。
- 检查（在服务器上，请求经 Cloudflare 回到服务器）：

  ```sh
  curl -s -o /dev/null -w '%{http_code}\n' -X POST https://staging.quizbuks.com/api/inbound/stripe \
    -A 'Stripe/1.0 (+https://stripe.com/docs/webhooks)' -H 'Content-Type: application/json' -H 'Stripe-Signature: t=1,v1=x' -d '{}'
  docker logs --since 2m quizbuks-staging-app 2>&1 | grep /api/inbound
  ```

  400 且应用日志中有 `POST /api/inbound/stripe -> 400`（签名不符）：请求到了应用，放行生效。403 且日志中没有：仍被 Cloudflare 拦下。
- 服务器上的脚本（如发布提案）不必经 Cloudflare：直接用容器的地址
  `http://$(docker inspect <APP_NAME>-app -f '{{(index .NetworkSettings.Networks "proxy").IPAddress}}'):8080`。

### 4. 第一次部署

production：推送 `1.2/quizbuks-release`（见上文"一次性设置"第 3 步）；staging：推送 `1.2/quizbuks`。或 GitHub → Actions → **QuizBuks deploy** →
Run workflow，分支选该环境的分支，`image-tag` 留空。第一次启动在空库上迁移并运行全部启动检查，需要一两分钟（健康检查的 `start_period` 为 180 秒）。
日志：`docker logs -f quizbuks-app`（staging：`quizbuks-staging-app`）。

### 5. 首次启动之后

1. 以 `admin` 登录 `https://<本环境的域名>/admin/`，在右上角用户名 →"安全设置"中开启两步验证。
2. 在"流程"中执行 `QB_SETUP`，再按 `docs/quizbuks/operations.md` §3 建财务管理员并由其发布 `QB_SETUP` 的提案（发布之前充值、入驻与提交发布一律被拒）。
3. 从 `.env` 删去 `JABIZ_BOOTSTRAP_ADMIN_PASSWORD`，加上 `QB_BOOTSTRAP_ADMIN_DONE=true`（入口脚本在两者都没有时拒绝启动），
   再以下文"回滚"的方式重新部署当前的 SHA（Run workflow，`image-tag` 填当前的 SHA，即 `docker inspect <APP_NAME>-app -f '{{.Config.Image}}'` 中冒号后的部分），
   经 `deploy-app.sh` 等待健康并在失败时回滚。

## staging 环境

与 production 相同，只有以下不同（两个环境不共用任何数据库、密钥或数据卷）：

1. **数据库**：

   ```sh
   /opt/infra/scripts/create-db.sh quizbuks_staging
   ```

   `.env` 中的库与角色都是 `quizbuks_staging`：`SPRING_R2DBC_URL=r2dbc:postgresql://postgres:5432/quizbuks_staging`、
   `SPRING_FLYWAY_URL=jdbc:postgresql://postgres:5432/quizbuks_staging`、`SPRING_R2DBC_USERNAME` 与 `SPRING_FLYWAY_USER` 为 `quizbuks_staging`，密码为输出中的密码。
2. **`/opt/apps/quizbuks-staging/.env`**：

   ```sh
   mkdir -p /opt/apps/quizbuks-staging && touch /opt/apps/quizbuks-staging/.env && chmod 600 /opt/apps/quizbuks-staging/.env
   ```

   变量同上，另外：

   ```sh
   APP_NAME=quizbuks-staging
   JABIZ_WEB_ENVIRONMENT=staging
   SPRING_PROFILES_ACTIVE=staging
   JABIZ_MAIL_BASE_URL=https://staging.quizbuks.com
   QB_APP_LINK=https://staging.quizbuks.com/taker/
   QB_SPONSOR_LINK=https://staging.quizbuks.com/sponsor/
   ```

   - **`APP_NAME` 必须是 `quizbuks-staging`**：它决定 compose 的项目名与容器名。从 production 复制 `.env` 而忘了改它时，compose 会替换 production 的容器；
     workflow 在部署前检查这一行，不一致即停止。
   - 平台密钥（`JABIZ_JWT_SECRET`、`JABIZ_INTEGRITY_KEY`、`JABIZ_MFA_KEY`、`JABIZ_SEAL_KEY`）与首个管理员的密码都**另外生成**，不与 production 共用。
   - Stripe 只用测试模式的密钥（`sk_test_` / `rk_test_`）。在 Stripe 后台（测试模式）建两个端点 `https://staging.quizbuks.com/api/inbound/stripe` 与
     `/api/inbound/stripe-connect`（订阅的事件见 `docs/quizbuks/stripe-test-mode.md` §4.2），`STRIPE_WEBHOOK_SECRET` / `STRIPE_CONNECT_WEBHOOK_SECRET` 是
     **这两个端点各自的签名密钥**（`whsec_…`，由 Stripe 给出，不能自己生成：签名对不上时每个事件都被拒绝，充值停在 `OPEN`）。经 Cloudflare 时先做"3a. Cloudflare"。
   - `SPRING_PROFILES_ACTIVE=staging` 只标出环境，应用中没有按环境写死的配置：环境的差异全部在这个 `.env` 中。同时开 Google 登录时写 `staging,google`（再加 Facebook、LINE 时写 `staging,social`，Q40），并设 Google 客户端与两个回调地址（`QB_OIDC_REDIRECT_APP`、`QB_OIDC_REDIRECT_SPONSOR`，见 README"前端"）。
   - `JABIZ_WEB_ENVIRONMENT=staging`（平台 16zd）让三个前端的每一页顶部都显示 "STAGING · 测试环境，不是正式版" 的横条，标题前加 `[STAGING]`；
     不写也一样（缺省 `test`）。**绝不能写 `production`**：workflow 部署前检查，staging 的 `.env` 写了 production 即停止。
3. **DNS**：给 `staging.<基础域名>` 添加 A 记录（及 AAAA），指向同一台服务器。
4. 第一次部署与首次启动之后的步骤同上（`https://staging.<基础域名>/admin/`）。

## 之后的部署

- **自动**：每次推送 `1.2/quizbuks`（staging）或 `1.2/quizbuks-release`（production）：测试（`:quizbuks:check`、入口脚本的测试、两个前端与后台扩展的检查）
  → 构建并推送镜像（标签为提交 SHA）→ 按分支渲染站点、上传 compose 与站点 → `deploy-app.sh <APP_NAME> <SHA>`。部署失败时 `deploy-app.sh` 自动回到上一个标签。
  两个分支各自排队，互不等待。
- **手动**：GitHub → Actions → **QuizBuks deploy** → Run workflow，分支选环境的分支，`image-tag` 留空即测试、构建并部署该分支的最新提交。从其他分支运行时不构建、不部署。
- **回滚**：
  - Run workflow（分支选环境的分支）时在 `image-tag` 填旧的**完整** 40 位提交 SHA：跳过测试与构建，以该提交自己的 compose 与站点模板部署这个已有的镜像。
    早于两个环境的提交（compose 中写死了 `quizbuks`、没有站点模板）一律拒绝，以免把 production 的容器当作 staging 替换；或者
  - 在服务器上 `/opt/infra/scripts/deploy-app.sh <APP_NAME> <旧 SHA>`（用的是服务器上现有的 compose 与站点）。镜像包是私有的时先登录 GHCR（有 `read:packages` 的个人令牌）：
    workflow 的登录只在它自己的临时 Docker 配置中，部署后即删除，不影响服务器上其他应用的登录。
  - **数据库迁移不随回滚撤销**：Flyway 只向前。回滚到更早的版本前确认其间没有新迁移（`backend/quizbuks/src/main/resources/db/migration` 与平台的 `db/jabiz`）：
    旧版本不认识新迁移改过的表结构，有新迁移时应修复后向前部署，而不是回滚。

## 备份

production 的数据库 `quizbuks` 按服务器的方案备份；另外备份数据卷 `quizbuks_files`（上传的文件不在数据库中），并离线保存 `JABIZ_INTEGRITY_KEY`、`JABIZ_MFA_KEY` 与 `JABIZ_SEAL_KEY`。
staging 是否备份按需要决定（`quizbuks_staging`、`quizbuks-staging_files`）。恢复演练见 `docs/quizbuks/operations.md` §2。
