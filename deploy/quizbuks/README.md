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

以后阶段使用、本阶段**不读取**的外部密钥（先列出，便于准备）：

| 变量 | 阶段 | 说明 |
|---|---|---|
| `STRIPE_API_KEY` | Q6 | Stripe 密钥（测试模式用 `sk_test_…`）；每个部署（美国、日本）用本国的 Stripe 账户 |
| `STRIPE_WEBHOOK_SECRET` | Q6 | Stripe 通知的签名密钥（平台入站 Webhook 校验） |
| `OPENAI_API_KEY` | Q8 | AI 生成题目与封面 |

可观测性（OTLP）与单点登录的变量同平台：见 `docs/guide/quickstart.md`。
