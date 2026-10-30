# QuizBuks — 部署到共用的 Docker 服务器（production 与 staging）

> **部署在线 1.3 上（2026-10-28）**：`1.3/quizbuks` → staging，`1.3/quizbuks-release` → production。线 1.2 只作备份，推送不再部署，手动运行须确认（`deploy-1.2`）。
> 1.3 迁移过的数据库不能再运行 1.2（Flyway 只向前），所以回滚只能回到 1.3 的提交；修复只做在 1.3。
> 从 1.2 切到 1.3 的一次性步骤见下文"从 1.2 切到 1.3"。

QuizBuks 是**一个镜像、一个服务**（`<APP_NAME>-app`，容器内端口 8080）：后端与三个前端（App `/`、商家后台 `/sponsor/`、管理后台 `/admin/`）都在一个 jar 中。
同一仓库、同一个镜像部署为两个互不相干的环境，各有自己的服务器目录、`.env`、数据库、数据卷与域名：

| | production | staging |
|---|---|---|
| 分支 | `1.3/quizbuks-release` | `1.3/quizbuks`（工作分支合入的线分支） |
| APP_NAME | `quizbuks` | `quizbuks-staging` |
| 域名 | `quizbuks.com` | `staging.quizbuks.com` |
| 服务器目录 | `/opt/apps/quizbuks/` | `/opt/apps/quizbuks-staging/` |
| 数据库（库与角色） | `quizbuks` | `quizbuks_staging` |
| 容器 | `quizbuks-app` | `quizbuks-staging-app` |
| 数据卷 | `quizbuks_files`、`quizbuks_secrets` | `quizbuks-staging_files`、`quizbuks-staging_secrets` |
| GitHub Environment | `production` | `staging` |

- 镜像：`ghcr.io/staunch0515/jabiz-quizbuks:<提交 SHA>`（`linux/amd64`），两个环境用同一个镜像，只是部署的提交不同。
- 仓库中的来源：`deploy/quizbuks/server/compose.yaml`（项目名与容器名取自 `.env` 的 `APP_NAME`）、`deploy/quizbuks/server/site.caddy.tpl`
  （占位 `__DOMAIN__`、`__APP_NAME__`、`__APP_DIR__`，由 workflow 按环境渲染）、`deploy/quizbuks/server/maintenance.html`（维护页）、
  `.github/workflows/quizbuks-deploy.yml`。
- 服务器上每个环境的目录中：`compose.yaml`、`site.caddy`、`maintenance/maintenance.html`（由 workflow 上传）、`.env`（只在服务器上手工维护，workflow 从不改它）；
  维护期间另有手工建立的 `maintenance/maintenance.lock`（见"维护模式"）。
- 网络：`proxy`（Caddy）、`db`（PostgreSQL），都是外部网络；不发布任何端口。
- 本机开发用的 `deploy/quizbuks/compose.dev.yaml`（自带数据库）与服务器无关，部署不使用它。

**发布流程**：工作分支照常经 PR 合入 `1.3/quizbuks`，合入即部署到 staging；在 staging 上确认后，以 PR 把 `1.3/quizbuks` 合并进 `1.3/quizbuks-release`
（只合并，不变基、不拣选），合入即部署到 production。

## 一次性设置（GitHub）

1. **基础域名**：缺省为 `quizbuks.com`（staging 即 `staging.quizbuks.com`）；要用其他域名时在 Settings → Secrets and variables → Actions → Variables
   新建 `QUIZBUKS_BASE_DOMAIN`（小写、不带协议）。**服务器的 secrets**：同一页的 Secrets 中设 `SERVER_HOST`、`SERVER_USER`、`SERVER_SSH_KEY`、`SERVER_KNOWN_HOSTS`
   （仓库级，或在 Environments 的 `staging` / `production` 中各设一份），缺少任何一个时部署在第一步停止并列出缺少的名字。
2. **Environments**：Settings → Environments 中的 `production` 与 `staging` 在第一次部署时自动建立；建议给 `production` 设 Required reviewers
   与 Deployment branches（只允许 `1.3/quizbuks-release`）。服务器的 secrets（`SERVER_*`）可以留在仓库级，也可以按环境分别设置。
3. **production 分支**：staging 部署正常之后，从 `1.3/quizbuks` 建 `1.3/quizbuks-release` 并推送（`git push origin origin/1.3/quizbuks:refs/heads/1.3/quizbuks-release`）。
   推送即第一次部署 production，所以先完成下面 production 的服务器设置。

## 首次部署前（在服务器上，每个环境各做一次）

下文以 production 为例；staging 的不同之处见"staging 环境"一节。

### 1. 数据库

```sh
/opt/infra/scripts/create-db.sh quizbuks
```

把输出的连接串拆成下面 `.env` 中的六个数据库变量（应用用 R2DBC 读写，Flyway 用 JDBC 在启动时自动迁移，两者分别配置）。
例如输出 `postgresql://quizbuks:<密码>@postgres:5432/quizbuks` 时：主机 `postgres`、端口 `5432`、库与角色都是 `quizbuks`。

### 1a. 账号删除的维护连接（开启账号删除时）

删除账号后，平台以一个单独的数据库登录清除只追加表中的个人信息（平台 10 §17.2，`ERASURE_PURGE`）。这个登录属于服务器上的角色 `jabiz_maintenance`，
只能读、插入与更新本库的表，不能删除；应用自己的登录（`create-db.sh` 建的）**绝不能**属于这个角色。脚本在仓库中，先复制到服务器：

```sh
# 在本机：scp deploy/quizbuks/server/create-maintenance-login.sh <服务器>:/opt/infra/scripts/
chmod +x /opt/infra/scripts/create-maintenance-login.sh
/opt/infra/scripts/create-maintenance-login.sh quizbuks
```

它输出三行，原样加进下面的 `.env`（再运行一次即给同一登录换新密码）：

```sh
JABIZ_ERASURE_DATASOURCE_URL=r2dbc:postgresql://postgres:5432/quizbuks
JABIZ_ERASURE_DATASOURCE_USERNAME=quizbuks_maint
JABIZ_ERASURE_DATASOURCE_PASSWORD=<输出的密码>
```

URL 是 **R2DBC** 格式（与 `SPRING_R2DBC_URL` 相同），不是 `SPRING_FLYWAY_URL` 的 `jdbc:`：写成 JDBC 时应用拒绝启动（检查报 `ERASURE | jabiz.erasure.datasource.url`），部署回滚。
还没有开启账号删除时可以不设这三行（`docs/quizbuks/operations.md` §1.1）。

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

# QuizBuks for Shopify（S1）：Shopify 应用的 API 密钥与密钥；还没有应用时填占位，密钥至少 32 个字符（openssl rand -hex 32）
SHOPIFY_API_KEY=<API key 或占位>
SHOPIFY_API_SECRET=<API secret 或占位>
```

**部署前的检查**：workflow 在服务器上改动任何东西之前，按所部署提交的 `deploy/quizbuks/server/required-env` 检查 `.env`
（`check-env.sh`，只列出缺少、过短或格式不对的变量名，不显示值）：有问题即停止，镜像不拉取、数据库不迁移。新增必需变量的提交同时把它加进这个清单；
可选的变量以 `?` 标出，给了才查（如 `JABIZ_ERASURE_DATASOURCE_URL` 必须以 `r2dbc:` 开头）。

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

production：推送 `1.3/quizbuks-release`（见上文"一次性设置"第 3 步）；staging：推送 `1.3/quizbuks`。或 GitHub → Actions → **QuizBuks deploy** →
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
   维护连接（"1a"）同样另建：`/opt/infra/scripts/create-maintenance-login.sh quizbuks_staging`（登录 `quizbuks_staging_maint`，URL `r2dbc:postgresql://postgres:5432/quizbuks_staging`）。
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

## 重置 staging（演练前）

上线演练要从"第一次部署"与"首次启动之后"走起时，先把 staging 清回空库。**只对 staging**：下面每一步都只用 `quizbuks-staging`、`quizbuks_staging`、
`quizbuks-staging_files` 这几个名字，生产的 `quizbuks`、`quizbuks_files` 一律不碰。第 0 步的检查不通过就停下。

0. **确认对象是 staging**（在服务器上；后面的命令都用这几个变量）：

   ```sh
   APP=quizbuks-staging; DB=quizbuks_staging; FILES=quizbuks-staging_files
   grep -qx "APP_NAME=$APP" /opt/apps/$APP/.env && ! grep -q '^JABIZ_WEB_ENVIRONMENT=production' /opt/apps/$APP/.env \
     && docker volume inspect "$FILES" >/dev/null && echo "OK: $APP / $DB / $FILES" || echo "停止：不是 staging 或名字不对"
   ```

1. **（可选）备份旧数据**：`docker exec postgres pg_dump -U postgres -Fc "$DB" > /root/$DB-$(date +%F).dump`
   （`postgres` 是共用 PostgreSQL 的容器名与超级用户；不同时换成实际的名字。）
2. **记下当前的镜像并停掉应用**：

   ```sh
   SHA=$(docker inspect "$APP-app" -f '{{.Config.Image}}' | cut -d: -f2); echo "$SHA"
   docker stop "$APP-app"
   ```

3. **删库重建**（同一个角色、密码不变，`.env` 的数据库变量不用改；迁移不需要扩展与超级用户，下次启动在空库上建全部的表）：

   ```sh
   docker exec postgres psql -U postgres -v ON_ERROR_STOP=1 \
     -c "DROP DATABASE \"$DB\" WITH (FORCE);" -c "CREATE DATABASE \"$DB\" OWNER \"$DB\";"
   ```

4. **清空上传的文件**（它们的记录已随库删除）：`docker run --rm -v "$FILES":/f alpine sh -c 'rm -rf /f/* /f/.[!.]*'`。
   `quizbuks-staging_secrets` 不用动（服务器上不在其中生成密钥）。
5. **让首个管理员重新创建**：在 `/opt/apps/quizbuks-staging/.env` 中写回 `JABIZ_BOOTSTRAP_ADMIN_USER` 与**新的** `JABIZ_BOOTSTRAP_ADMIN_PASSWORD`，
   删去 `QB_BOOTSTRAP_ADMIN_DONE=true`。平台密钥可以不变（空库用原来的密钥没有问题），也可以按"`.env`"一节重新生成，顺便演练换密钥。
6. **重新部署同一个版本**：`/opt/infra/scripts/deploy-app.sh "$APP" "$SHA"`（或 Run workflow，分支 `1.3/quizbuks`，`image-tag` 填 `$SHA`），
   再 `docker logs -f "$APP-app"` 直到健康、没有 `Platform check failed`。
7. **外部的旧测试数据**：Stripe（测试模式）中旧的顾客与 Connect 账户还在，可在 Developers 中"删除所有测试数据"（端点与签名密钥不受影响）；
   登录过 staging 的浏览器与已安装的 App 的会话都已失效，清掉 `staging.<基础域名>` 的站点数据再开始。

然后从"5. 首次启动之后"（`operations.md` §3）开始演练；首个管理员开启两步验证、`QB_SETUP` 的提案发布之后，照第 3 步改回 `.env`
（删 `JABIZ_BOOTSTRAP_ADMIN_PASSWORD`、加 `QB_BOOTSTRAP_ADMIN_DONE=true`）并重新部署，这一步本身也是演练的一部分。

## 从 1.2 切到 1.3（一次性，2026-10-28）

1.3 第一次部署到某个环境时，Flyway 在那个库上执行 1.3 的迁移；之后那个库只能运行 1.3。按下面的顺序做，staging 先、production 后：

1. **1.2 停止自动部署**：合并 1.2 上的部署 PR（推送 `1.2/quizbuks` 不再部署，手动运行须填 `deploy-1.2`）。先做这一步，以免之后 1.2 的一次推送把 1.2 部署到已迁移的库上。
2. **补齐 `.env`**：1.3 比 1.2 多出的必需变量只有 `SHOPIFY_API_KEY` 与 `SHOPIFY_API_SECRET`（缺少即启动失败；还没有 Shopify 应用时填占位，密钥至少 32 个字符）。
   部署前的检查会拦下缺少它们的部署；在这个检查之前（2026-10-28 staging 的第一次 1.3 部署），缺少它们时新镜像已迁移了库才启动失败，回滚到 1.2 也起不来：补上变量后重新运行部署即可。
   其余 Shopify 变量（`QB_SHOPIFY_*`）都有缺省值，见 `README.md` 的环境变量表与 `operations.md` §1。
3. **GitHub 的 Environments**：`staging`、`production` 若设了 Deployment branches，改为允许 `1.3/quizbuks`、`1.3/quizbuks-release`（原来的 1.2 分支可以删掉）。
4. **重置 staging**：照上一节第 0–5 步（确认对象、可选备份、停应用、删库重建、清文件、首个管理员）。不做第 6 步：下一步的部署就是第一次部署 1.3。
5. **部署 1.3 到 staging**：合并把部署改为 1.3 的 PR（推送 `1.3/quizbuks` 即部署）。`docker logs -f quizbuks-staging-app` 直到健康、没有 `Platform check failed`，
   再照"首次启动之后"（`operations.md` §3）走一遍。
6. **production**：staging 确认之后，在 production 的 `.env` 补上第 2 步的变量，从 `1.3/quizbuks` 建 `1.3/quizbuks-release` 并推送（"一次性设置"第 3 步），推送即第一次部署 production。

## 之后的部署

- **自动**：每次推送 `1.3/quizbuks`（staging）或 `1.3/quizbuks-release`（production）：构建并推送镜像（标签为提交 SHA）→ 按分支渲染站点、上传 compose 与站点 → `deploy-app.sh <APP_NAME> <SHA>`。部署失败时 `deploy-app.sh` 自动回到上一个标签。
  两个分支各自排队，互不等待。推送不再测试：合并进这两个分支的改动已在 PR 中检查（平台的 `ci.yml` 与 `quizbuks.yml`，两者都只对 PR 运行），
  启动检查与 `deploy-app.sh` 的健康检查拦住起不来的版本。合并前 PR 落后于目标分支时，先把目标分支合并进 PR、等检查通过再合并。
- **手动**：GitHub → Actions → **QuizBuks deploy** → Run workflow，分支选环境的分支，`image-tag` 留空即构建并部署该分支的最新提交。从其他分支运行时不构建、不部署。
- **回滚**：
  - Run workflow（分支选环境的分支）时在 `image-tag` 填旧的**完整** 40 位提交 SHA：跳过构建，以该提交自己的 compose 与站点模板部署这个已有的镜像。
    早于两个环境的提交（compose 中写死了 `quizbuks`、没有站点模板）一律拒绝，以免把 production 的容器当作 staging 替换；或者
  - 在服务器上 `/opt/infra/scripts/deploy-app.sh <APP_NAME> <旧 SHA>`（用的是服务器上现有的 compose 与站点）。镜像包是私有的时先登录 GHCR（有 `read:packages` 的个人令牌）：
    workflow 的登录只在它自己的临时 Docker 配置中，部署后即删除，不影响服务器上其他应用的登录。
  - **数据库迁移不随回滚撤销**：Flyway 只向前。回滚到更早的版本前确认其间没有新迁移（`backend/quizbuks/src/main/resources/db/migration` 与平台的 `db/jabiz`）：
    旧版本不认识新迁移改过的表结构，有新迁移时应修复后向前部署，而不是回滚。

## 维护模式

手工开关：本环境目录中存在 `maintenance/maintenance.lock` 时，Caddy 对**所有**请求直接返回维护页（`maintenance.html`），
状态码 503、`Retry-After: 300`、`Cache-Control: no-store`，请求不到达应用；删掉这个文件即恢复正常代理。Caddy 每个请求都检查这个文件，开关不需要重新加载 Caddy，
也不经过 workflow。

```bash
APP=quizbuks-staging          # production 为 quizbuks
# 开启
touch /opt/apps/$APP/maintenance/maintenance.lock
# 关闭
rm /opt/apps/$APP/maintenance/maintenance.lock
```

- **应用不在时自动显示**：没有锁文件、但 Caddy 连不上应用（`docker stop "$APP-app"`、重启、部署中的替换、尚未开始监听、崩溃）时，
  同样返回维护页（503、`Retry-After: 60`、`Cache-Control: no-store`）。只有 Caddy 连不上应用才算（它自己的 502）；应用自己回答的 503
  （`PROCESS_BUSY`、`QUERY_TIMEOUT` 等）照原样转发。计划内的维护仍先建锁文件：锁文件期间应用健康也不接请求，且 `Retry-After` 更长。
- **维护页**（Q55，`docs/quizbuks/plans/Q55-maintenance-page.md`）：由 `node quizbuks-web/maintenance/build.mjs` 按品牌档案生成（名称、标志、配色、插画，全部内联），
  只显示一种语言（链接的 `?lang=`，否则此浏览器在 App 中选过的语言，否则按时区：日本→日语，中国大陆、港澳台→中文，否则浏览器语言，否则英语），
  并每 10 秒请求一次访问者原来打开的地址，不再是 503 即自动回到那个页面。改了品牌档案或维护页后重新生成并提交（CI 检查）。
- 两个环境各有自己的开关，互不影响；同一服务器上的其他应用不受影响。
- 维护页由每次部署上传（`maintenance/maintenance.html`），锁文件 workflow 从不创建或删除：维护期间照常部署（例如修复后重新部署），维护页一直显示到手工关闭，
  可以先在服务器上确认应用健康（`docker logs "$APP-app"`）再关闭。
- 维护期间 Stripe 等的 Webhook（`/api/inbound/**`）也得到 503；Stripe 会自动重试（正式模式下至多 3 天，测试模式下只有几次），维护不长时关闭后照常送达。
  App 与商家后台的接口请求同样得到 503，页面显示请求失败，关闭后刷新即可。
- **第一次使用前给 Caddy 挂载这两个目录**。Caddy 读的是它自己容器内的路径，站点按 `/opt/apps/<APP_NAME>/maintenance` 与
  `/opt/apps/<APP_NAME>/well-known` 渲染；Caddy 容器看不到它们时，锁文件不起作用（网站照常代理），应用停掉时也只有空白的 502
  （经 Cloudflare 时是它的 "Bad gateway" 页）。只挂这两个子目录、只读，**不要挂整个 `/opt/apps`**：那里有各应用的 `.env`（密钥），
  整个挂进去，Caddy 一旦被攻破就能读到服务器上所有应用的密钥。

  ```bash
  # 1. Caddy 的容器名与 compose 文件
  docker ps --format '{{.Names}} {{.Image}}' | grep -i caddy
  docker inspect <Caddy 容器名> -f '{{index .Config.Labels "com.docker.compose.project.config_files"}}'
  # 2. 现有的挂载（只有 Caddyfile、站点目录与数据卷时，就要加下面的挂载）
  docker inspect <Caddy 容器名> -f '{{range .Mounts}}{{.Source}} -> {{.Destination}}{{"\n"}}{{end}}'
  ```

  在该 compose 文件中 Caddy 服务的 `volumes:` 下加上（容器内与服务器上同一路径，站点不必改；production 还没有时也可以先加，
  目录不存在时 Docker 建空目录）：

  ```yaml
      - /opt/apps/quizbuks-staging/maintenance:/opt/apps/quizbuks-staging/maintenance:ro
      - /opt/apps/quizbuks-staging/well-known:/opt/apps/quizbuks-staging/well-known:ro
      - /opt/apps/quizbuks/maintenance:/opt/apps/quizbuks/maintenance:ro
      - /opt/apps/quizbuks/well-known:/opt/apps/quizbuks/well-known:ro
  ```

  然后重建 Caddy（服务器上所有网站断开几秒）并确认：

  ```bash
  mkdir -p /opt/apps/quizbuks-staging/well-known /opt/apps/quizbuks/maintenance /opt/apps/quizbuks/well-known
  cd <compose 文件所在目录> && docker compose up -d <Caddy 服务名>
  docker exec <Caddy 容器名> ls /opt/apps/$APP/maintenance/maintenance.html
  ```

  挂的是目录，锁文件的建立与删除在容器中立即可见。Caddy 的 compose 已把服务器的 `/opt/apps` 挂在容器中另一个路径时，
  也可以不加挂载，把容器内对应 `/opt/apps` 的路径写入仓库变量 `QUIZBUKS_CADDY_APPS_DIR`（Settings → Secrets and variables → Actions →
  Variables）并重新部署。
- 开启后在浏览器（或 `curl -sI https://<本环境的域名>/`）确认返回 503 与维护页，再开始维护。

## Android App 的 assetlinks（Q49b）

Android App（`quizbuks-mobile/android`，`docs/quizbuks/plans/Q49b-android.md`）以 `https://<域名>/.well-known/assetlinks.json` 证明它属于这个域名：
没有它（或指纹不对）时 App 照常可用，只是顶部显示浏览器的地址栏，邮件链接不直接打开 App。平台 17l 合入前由 Caddy 站点提供：

1. Settings → Environments → `production` → Variables 中设两个变量（是公开的值，不是 secret）：
   - `QUIZBUKS_ANDROID_PACKAGE`：`com.quizbuks.app`（`twa-manifest.json` 的 `packageId`）；
   - `QUIZBUKS_ANDROID_CERT_SHA256`：Play Console →（本 App）→ 测试和发布 → 应用完整性 → 应用签名 中**应用签名密钥证书**与**上传密钥证书**的 SHA-256 指纹，以逗号分隔。
2. 部署一次（推送或手动运行部署 workflow）：workflow 以 `deploy/quizbuks/assetlinks.sh` 生成并检查 JSON，上传到服务器的 `/opt/apps/<APP_NAME>/well-known/assetlinks.json`；
   两个变量都没有时删除这个文件，只设了一个时部署失败。staging 缺省不设（App 只打开 production）。
3. 确认：`curl -s https://<域名>/.well-known/assetlinks.json` 返回这份 JSON（维护模式中也返回）；再以 Google 的
   [Statement List Tester](https://developers.google.com/digital-asset-links/tools/generator) 对包名与指纹测试一次。

Caddy 容器须能看到 `/opt/apps/<APP_NAME>/well-known/`，与维护页同一前提（见上节"第一次使用前给 Caddy 挂载这两个目录"）。17l 之后改由平台提供，本节与站点中的这一段随之删除。

## Android App 的构建与上传（Q49b）

workflow `QuizBuks mobile`（`.github/workflows/quizbuks-mobile.yml`）：PR 与推送时检查并构建未签名的包；在 `1.3/quizbuks` 上手动运行（Actions → QuizBuks mobile → Run workflow）时
以上传密钥签名，`track` 为 `none` 时只把签名的 `.aab` 存为运行的产物（第一次上传要在 Play Console 手动做），为 `internal` / `alpha`（封闭测试）时上传到 Google Play。
版本号（versionCode）是运行编号加 100，每次运行都更大。

Settings → Environments → 新建 `play`（建议设 Required reviewers 与 Deployment branches 只允许 `1.3/quizbuks`），其 Secrets：

| Secret | 内容 |
|---|---|
| `ANDROID_UPLOAD_KEYSTORE_B64` | 上传密钥库的 Base64（`base64 -w0 upload.keystore`） |
| `ANDROID_UPLOAD_KEYSTORE_PASSWORD` / `ANDROID_UPLOAD_KEY_ALIAS` / `ANDROID_UPLOAD_KEY_PASSWORD` | 密钥库的密码、密钥别名与密钥密码 |
| `PLAY_SERVICE_ACCOUNT_JSON` | Google Cloud 服务账号的 JSON 密钥（在 Play Console 的"用户和权限"中只授予本 App 的发布权限） |

上传密钥丢失时不影响已上架的 App（用户安装的是 Google 以应用签名密钥签的包）：在 Play Console 申请重置上传密钥，并更新上面的 secrets 与 `QUIZBUKS_ANDROID_CERT_SHA256`。

## iOS App 的 apple-app-site-association（Q49c）

iOS App（`quizbuks-mobile/ios`，`docs/quizbuks/plans/Q49c-ios.md`）的 Universal Links 要域名上的 `https://<域名>/.well-known/apple-app-site-association`：没有它时邮件链接在 Safari 打开。
平台 17l 合入前由 Caddy 站点提供，同 `assetlinks.json`：

1. Settings → Environments → `production` → Variables：`QUIZBUKS_IOS_APP_ID` = `<Team ID>.com.quizbuks.app`（Team ID 在 Apple Developer 的 Membership 页）。
2. 部署一次：workflow 以 `deploy/quizbuks/apple-app-site-association.sh` 生成（只认 `/taker/*`）并上传到 `/opt/apps/<APP_NAME>/well-known/apple-app-site-association`；变量没有时删除这个文件。
3. 确认：`curl -sI https://<域名>/.well-known/apple-app-site-association` 为 200、`Content-Type: application/json`、没有转向；Apple 的 CDN 缓存它，改了之后新装的 App 才看到（`https://app-site-association.cdn-apple.com/a/v1/<域名>` 可查 CDN 上的版本）。

## iOS App 的构建与上传（Q49c）

workflow `QuizBuks iOS`（`.github/workflows/quizbuks-ios.yml`，macOS 运行器）：PR 与推送时检查并构建不签名的包；在 `1.3/quizbuks` 上手动运行（Actions → QuizBuks iOS → Run workflow）时
以 App Store Connect API 密钥自动签名并上传，App Store Connect 处理完后出现在 TestFlight。构建号是运行编号加 100。

Settings → Environments → 新建 `appstore`（建议 Required reviewers 与 Deployment branches 只允许 `1.3/quizbuks`）：

| 名称 | 种类 | 内容 |
|---|---|---|
| `APP_STORE_CONNECT_KEY_ID` | secret | App Store Connect API 密钥的 Key ID |
| `APP_STORE_CONNECT_ISSUER_ID` | secret | 同一页的 Issuer ID |
| `APP_STORE_CONNECT_KEY_P8` | secret | 下载的 `AuthKey_<Key ID>.p8` 的全文（只能下载一次，自己另存一份） |
| `APPLE_TEAM_ID` | 变量 | Membership 页的 Team ID（10 位） |

密钥泄露或丢失时在 App Store Connect 吊销并新建，更新上面的 secrets；已上架的 App 不受影响。

## 备份

production 的数据库 `quizbuks` 按服务器的方案备份；另外备份数据卷 `quizbuks_files`（上传的文件不在数据库中），并离线保存 `JABIZ_INTEGRITY_KEY`、`JABIZ_MFA_KEY` 与 `JABIZ_SEAL_KEY`。
staging 是否备份按需要决定（`quizbuks_staging`、`quizbuks-staging_files`）。恢复演练见 `docs/quizbuks/operations.md` §2。
