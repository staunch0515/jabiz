# 快速开始

三种用法，按需选择：

| 目的 | 需要 | 命令 |
|---|---|---|
| 看一看、试一试（整套系统） | Docker | `docker compose up -d --build` |
| 开发业务对象（后端热启动 + 前端开发服务器） | JDK 21、Docker（或本机 PostgreSQL 16）、Node 22 + pnpm | 见第 2 节 |
| 只跑测试 | JDK 21、Docker（或本机 PostgreSQL 16） | `cd backend && ./gradlew check` |

## 1. 一条命令启动整套系统

```bash
docker compose up -d --build        # 首次构建镜像约数分钟（下载 Gradle 依赖、Node、pnpm）
docker compose logs app | grep "Sign in"   # 首次启动生成的管理员密码
```

- 打开 <http://localhost:8080>，用 `admin` 和上面的密码登录（页面与接口同一端口）。
- 忘了密码：`docker compose exec app cat /var/lib/jabiz/secrets/admin-password`。
- 想自己指定密码或签名密钥：复制 `.env.example` 为 `.env` 并填写（`.env` 不提交）。生成的密钥只用于本地演示（决策 D16）。
- 演示数据（商品、仓库、库存、订单，全部经 API 写入）：
  ```bash
  JABIZ_PASSWORD=<管理员密码> tools/demo/seed.sh      # 需要 curl 与 jq
  ```
- 观测：<http://localhost:3000>（Grafana，默认账号 admin/admin）→ Dashboards → **jabiz platform**：请求、流程吞吐与耗时、
  被拒绝的流程、模板与数据视图耗时、连接池、日志；Explore → Tempo 可看到一次请求的完整链路（请求 → 流程 → 数据视图 → SQL）。
- 停止：`docker compose down`（保留数据）；`docker compose down -v`（连同数据库、生成的密钥一起删除）。
- pgAdmin：`docker compose --profile tools up -d`，<http://localhost:5050>。

服务一览：`db`（PostgreSQL 16，主机端口 5436）、`app`（8080）、`lgtm`（Grafana 3000）。详见 `docs/design/13-observability-ops.md`。

### 登录后能做什么

- **数据**（数据视图目录 `/data`）：每个实体一个列表页（筛选、排序、分页）、表单、历史时间线（任意时间点回看、操作详情、撤销）。
  这些页面全部由元数据生成——`Product`、`Warehouse`、`Supplier` 等没有一行前端代码。
- **流程**：`ORDER_PLACE`（下单）、`ORDER_SHIP`（发货并过账）、`PRODUCT_REPRICE`（预定调价）等，表单由流程输入类型生成。
- 库存、订单只能经流程变化（它们的视图是 `processOnlyWrites`），因此列表页上没有它们的编辑按钮。

## 2. 本地开发

```bash
docker compose up -d db                          # 只起数据库（端口 5436）
cd backend && ./gradlew :app:bootRun --args='--spring.profiles.active=dev'   # 8080；dev 下可用 X-Jabiz-* 请求头代替登录
cd frontend && pnpm install && pnpm dev          # 5173，/api 代理到 8080
```

- 非 `dev` 启动需要 `JABIZ_JWT_SECRET`（`openssl rand -base64 48`）、封存密钥 `JABIZ_INTEGRITY_KEY`（同样生成，务必备份，见 design 21 §2.4）、二次验证密钥 `JABIZ_MFA_KEY`（同样生成，务必备份，见 design 10 §9）、首个管理员 `JABIZ_BOOTSTRAP_ADMIN_USER` / `JABIZ_BOOTSTRAP_ADMIN_PASSWORD`
  与上传文件的存储目录 `JABIZ_FILES_LOCAL_ROOT`（`dev` 下默认 `backend/app/build/jabiz-files`；compose 用专用卷）。
- 公开只读访问（15）默认关闭；`JABIZ_PUBLIC_ENABLED=true` 打开后，匿名即可读取示范的公开商品目录：
  `curl 'http://localhost:8080/api/public/queries/commerce.public.catalog?sort=unitPrice:asc'`（商品照片经 `/api/public/files/{id}`）。
- 启动时 Flyway 先迁移平台脚本（`db/jabiz`），再迁移业务脚本（`db/migration`）；元数据、视图、模板、流程的不一致会在启动时一次性报告。
- 接入本地观测：`docker compose up -d lgtm`，再以环境变量启动后端：
  `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT=http://localhost:4318/v1/traces`、
  `MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED=true`、`MANAGEMENT_OTLP_METRICS_EXPORT_URL=http://localhost:4318/v1/metrics`
  （compose 的 `lgtm` 服务默认只把 3000 映射到主机；需要时加上 `4318:4318`）。

## 3. 测试与静态校验

```bash
cd backend
./gradlew check                     # 编译、单元与集成测试（Testcontainers postgres:16）、覆盖率门禁、platformCheck
./gradlew :app:test --tests '*ScenarioTest'     # 场景回放（快照对比）
cd ../frontend && pnpm lint && pnpm typecheck && pnpm test && pnpm build
```

没有 Docker 时设置 `JABIZ_TEST_DB_URL`、`JABIZ_TEST_DB_USER`、`JABIZ_TEST_DB_PASSWORD` 连接本机 PostgreSQL 16（每个测试类独立 schema）。

## 4. 下一步

- 新增自己的业务对象：`docs/guide/new-business-object.md`。
- 平台的设计与约定：`docs/design/00-overview.md` 起，决策见 `09-decisions.md`。
- 压测：`docs/perf/phase-11-load-test.md`。
