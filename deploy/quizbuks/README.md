# QuizBuks — 部署

一个镜像（`Dockerfile`，两段式：构建 jar 并打包平台通用后台，再放进 JRE 镜像）加 PostgreSQL 16（`docker-compose.yml`）。
命令都在仓库根目录执行。

```sh
docker compose -f deploy/quizbuks/docker-compose.yml up -d --build      # 数据库 127.0.0.1:5440，应用 127.0.0.1:8080
docker compose -f deploy/quizbuks/docker-compose.yml logs app           # 首次启动打印管理员密码
```

- 管理员密码只在首次启动时打印一次；之后在仓库根目录用
  `docker compose -f deploy/quizbuks/docker-compose.yml exec app cat /var/lib/jabiz/secrets/admin-password` 读取
  （入口脚本提示中的 `docker compose exec app …` 省略了 `-f`；应用服务因此命名为 `app`）。
- 管理员后台：<http://localhost:8080/admin/>。答题 App（`/`）与商家后台（`/sponsor/`）在后续阶段加入。
- 首次登录后执行一次流程 `QB_SETUP`（建角色、账本科目、国家、业务参数；可重复执行，只补从未存在过的，不加回已删除的）。它要求二次验证：
  先在后台的"安全设置"中开启两步验证（TOTP）；只在本机演示时可设 `JABIZ_SECURITY_MFA_ADMINISTRATION=false` 跳过。
- `QB_SETUP` 把受控参数（金额、免审核开关）与审批规则（发布审核 `qb.publication.review`，Q4 起；商家入驻审核 `qb.sponsor.review`，Q2 起）
  作为受控变更**提出**，须由另一位持有 `control.publish` 的管理员（财务管理员）在后台发布（`CONTROL_CHANGE_PUBLISH`）。**发布之前，商家的入驻申请与提交发布一律被拒**
  （`PARAM_NOT_FOUND` 或 `QB_REVIEW_NOT_CONFIGURED`），不会未经审核通过。新版本部署后再执行一次 `QB_SETUP`，并发布新的提案（Q2 部署后：新权限、第二条规则）。

## 账户（Q2）

- **三个登录入口**：后台 `admin`（只接受管理员角色，`QB_ADMIN_ROLES`）、答题 App `app`（`QB_TAKER`，允许自助注册，未验证邮箱也能登录浏览）、商家后台 `sponsor`
  （`QB_SPONSOR`，允许自助注册，**要求已验证邮箱**）。注册的角色由 `QB_SETUP` 建立：执行之前注册一律 422 `SIGN_UP_DISABLED`。
  升级说明：此前在后台登录的商家、答题人的测试账号改用各自的入口（页面随 16c 加入之前用 API，`POST /api/auth/login` 带 `entry`）。
- **邮件必须开启**才能激活：`JABIZ_MAIL_ENABLED=true` 并配置发件人与邮件服务器（`JABIZ_MAIL_FROM`、`SPRING_MAIL_*`）；
  否则注册的用户收不到验证邮件，永远"待激活"（启动告警）。`JABIZ_MAIL_BASE_URL` 是**后台的地址**（如 `https://quizbuks.example.com/admin`）：
  平台的验证、找回密码链接由后台的落地页处理后跳回入口的页面。商家邮件中的商家后台地址取 `QB_SPONSOR_LINK`，缺省为该主机上的 `/sponsor/`。
- **在反向代理之后**运行时设置 `JABIZ_TRUSTED_PROXIES`（代理的地址或网段），否则登录记录中的 IP 都是代理的地址。不要同时设置 `server.forward-headers-strategy`（启动错误）。
- **商家入驻**：商家注册、验证邮箱后在商家后台申请入驻，由内容管理员在 `/tasks` 的审批面板中审核；通过后才能提交发布。
  Q2 之前由管理员手工建的商家账号没有入驻记录，须申请一次（`QB_SPONSOR_APPLY`）。
- **封禁**：超级管理员在后台对用户执行 `QB_USER_BAN`；被封禁的人三个入口都不能登录，已登录的会话在下一次刷新（至多 15 分钟）后失效，期间提交发布等动作被拒。
- 只起数据库做本地开发：`docker compose -f deploy/quizbuks/docker-compose.yml up -d db`，再在 `backend/` 下
  `./gradlew :quizbuks:bootRun --args='--spring.profiles.active=dev'`。

## 环境变量

放在 `deploy/quizbuks/.env`（不提交，已在 `.gitignore` 中）或部署平台的密钥库中。密钥都是 Base64、至少 32 字节（`openssl rand -base64 48`）。

| 变量 | 必需 | 说明 |
|---|---|---|
| `DB_PASSWORD` | 服务器上必需 | 数据库用户 `quizbuks` 的密码（缺省 `quizbuks` 只用于本机） |
| `JABIZ_JWT_SECRET` | 是 | 访问令牌签名密钥。本机首次启动时入口脚本自动生成到 secrets 卷 |
| `JABIZ_INTEGRITY_KEY` | 是 | 只追加表的封存密钥（平台 21 §2）。**保留副本**：没有它无法核验已有封存 |
| `JABIZ_MFA_KEY` | 是 | TOTP 密钥的加密密钥（平台 10 §9）。**保留副本**：没有它已绑定的用户无法二次验证 |
| `JABIZ_BOOTSTRAP_ADMIN_USER` / `JABIZ_BOOTSTRAP_ADMIN_PASSWORD` | 首次启动 | 第一个管理员（拥有全部权限）；本机未给密码时自动生成并打印 |
| `JABIZ_FILES_LOCAL_ROOT` | 是 | 上传文件目录；镜像中已设为卷 `/var/lib/jabiz/files` |
| `SPRING_R2DBC_URL` / `SPRING_R2DBC_USERNAME` / `SPRING_R2DBC_PASSWORD`、`SPRING_FLYWAY_URL` / `SPRING_FLYWAY_USER` / `SPRING_FLYWAY_PASSWORD` | compose 已给 | 数据库连接（请求路径用 R2DBC，迁移用 JDBC） |
| `JABIZ_SECURITY_MFA_ADMINISTRATION` | 否 | `false` 时平台管理操作（含 `QB_SETUP`）不要求二次验证；只用于本机演示与 e2e |
| `QUIZBUKS_PORT` / `QUIZBUKS_DB_PORT` | 否 | 本机端口，缺省 8080 / 5440 |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | 否 | `ecs` 输出 JSON 日志（compose 已设） |
| `QB_ADMIN_ROLES` | 否 | 后台入口接受的角色，逗号分隔；缺省 `ADMIN,QB_ADMIN_SUPER,QB_ADMIN_CONTENT,QB_ADMIN_FINANCE`。**不得**含 `*`、`QB_TAKER`、`QB_SPONSOR`（启动错误） |
| `QB_APP_SELF_REGISTRATION` / `QB_SPONSOR_SELF_REGISTRATION` | 否 | 答题 App、商家后台是否允许自助注册，缺省 `true` |
| `JABIZ_MAIL_ENABLED` | 注册时必需 | `true` 开启邮件（缺省关闭：消息只记录为 `SKIPPED`） |
| `JABIZ_MAIL_FROM` | 开启邮件时 | 发件人地址 |
| `JABIZ_MAIL_BASE_URL` | 开启邮件时 | 后台的地址，如 `https://quizbuks.example.com/admin`（链接与退订页） |
| `SPRING_MAIL_HOST` / `SPRING_MAIL_PORT` / `SPRING_MAIL_USERNAME` / `SPRING_MAIL_PASSWORD` | 开启邮件时 | 邮件服务器（SMTP） |
| `QB_SPONSOR_LINK` | 否 | 商家邮件中的商家后台地址；缺省为 `JABIZ_MAIL_BASE_URL` 主机上的 `/sponsor/` |
| `JABIZ_TRUSTED_PROXIES` | 在代理之后 | 可信代理的地址或网段（逗号分隔），登录记录据此取客户端地址 |

以后阶段使用、本阶段**不读取**的外部密钥（先列出，便于准备）：

| 变量 | 阶段 | 说明 |
|---|---|---|
| `STRIPE_API_KEY` | Q6 | Stripe 密钥（测试模式用 `sk_test_…`）；每个部署（美国、日本）用本国的 Stripe 账户 |
| `STRIPE_WEBHOOK_SECRET` | Q6 | Stripe 通知的签名密钥（平台入站 Webhook 校验） |
| `OPENAI_API_KEY` | Q8 | AI 生成题目与封面 |

可观测性（OTLP）与单点登录的变量同平台：见 `docs/guide/quickstart.md`。
