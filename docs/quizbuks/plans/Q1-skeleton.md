# Q1 骨架（3–4 天）— 实施计划

分支：`1.2/quizbuks-1-skeleton`（从 `1.2/quizbuks` 拉出，PR 合回 `1.2/quizbuks`）。只改 `.jabiz-app-paths` 中的路径。
不依赖阶段 15、16 的任何新能力；三个前端的外壳放到 16c 合入之后（Q2 / Q3），本阶段只有后台（`/admin/`，平台通用后台，暂无扩展）。

## 要求

1. **模块** `backend/quizbuks`（自动包含，`settings.gradle.kts` 不改）：
   ```kotlin
   plugins { id("jabiz.boot-app") }
   jabizApp {
       mainClass = "com.jabiz.quizbuks.QuizbuksApp"
       spa("/admin", "../../frontend")          // 管理员后台；App（/）与商家后台（/sponsor）在 16c 后加入
       languages("en", "zh", "ja")
       openApiSnapshot = "quizbuks/src/test/resources/openapi.json"            // 不写到平台目录
       publicQueriesSnapshot = "quizbuks/src/test/resources/public-queries.json"
   }
   dependencies { implementation(project(":runtime")); testImplementation(testFixtures(project(":runtime"))); … archunit、jqwik }
   ```
2. **启动类与配置**：`QuizbuksApp`（`JabizApplication.run`）；`application.yml`（数据库端口 5440、库与用户 `quizbuks`；`jabiz.ledger.currency: JPY`、`scale: 0`；
   `jabiz.fiscal-year-end`【缺省 12，待确认】；密钥全部取自环境变量；`jabiz.i18n.default-locale: en`）；`application-dev.yml`（开发用请求头、文件目录）；
   测试配置 `src/test/resources/config/application.properties`（固定测试密钥、BCrypt 4、关闭投递与调度）。
3. **权限与角色**（`QbPermissions`、`QbRoles`）：设计 §2 的权限码与五个角色；流程 `QB_SETUP`（幂等、只增加，同 finance 的 `FIN_SETUP`）建角色与授权。
   管理员角色 `requireMfa`。
4. **账本**：`QB_SETUP` 开立设计 §4.1 的 7 个科目（`LEDGER_ACCOUNT_OPEN`）；`LedgerDimension.define(1, "party", d -> d.entity("SecUser", "userId"))`（当事人 = 用户编号）。
5. **国家字典** `QbCountry`（时态实体，设计 §3.1）：ISO 3166-1 全部国家（三语名称、所属地区），数据在 `src/main/resources/quizbuks/countries.csv`，
   由 `QB_SETUP` 导入（已存在则跳过）；地区为静态字典 `urn:jabiz:dict:quizbuks:region`。
6. **业务参数**（`QB_SETUP` 建立，有缺省值）：`qb.creator-fee-rate`（0）、`qb.transfer.threshold`、`qb.transfer.review-above`、`qb.transfer.enabled`、
   `qb.review.sponsor.auto`、`qb.review.publication.auto`、`qb.ai.model`、`qb.app.min-version`。
7. **消息**：实体、字段、字典、参数、流程的三语显示名（`messages_{en,zh,ja}.properties`）。
8. **约定文件** `backend/quizbuks/CLAUDE.md`：包 `com.jabiz.quizbuks`、表前缀 `qb_`、实体前缀 `Qb`、权限 `qb.<模块>.<动作>`、流程 `QB_*`、
   迁移 `V<n>__quizbuks_<名>.sql`、模板 `queries/qb/**`、金额显示 "Kudos"、需求副本只读。
9. **CI** `.github/workflows/quizbuks.yml`：`package` 作业（PostgreSQL 服务、`:quizbuks:bootJar`、以临时密钥启动、等健康检查、`/admin/` 返回页面）。
   `./gradlew check`（含 `:quizbuks:check` 与 `platformCheck`）由平台 `ci.yml` 执行。
10. **部署** `deploy/quizbuks/`：`docker-compose.yml`（`db` 5440、`quizbuks`）、`Dockerfile`（同 finance 的两段式）、`README.md`（环境变量清单：
    `JABIZ_*` 密钥、`STRIPE_*` 与 `OPENAI_API_KEY` 先列出、本阶段不使用）。

## 迁移

`db/migration/V1__quizbuks_countries.sql`：`qb_country_version`（时态，`jabiz_create_temporal_table` 的写法同平台、建索引与只追加触发器）。

## 测试

- `QuizbuksAppIT`：启动、`/actuator/health` 为 UP、未认证访问 `/api/meta/datasets` 为 401。
- `SetupIT`：`QB_SETUP` 建出五个角色及权限、7 个科目、全部国家与参数；再执行一次不重复、不改变已有数据；`qb_country_version` 上没有 UPDATE / DELETE。
- `ArchitectureTest`：`com.jabiz.quizbuks` 不引用 `reactor.*`。
- `ScenarioTest` + 场景 `scenarios/qb/setup.yml`（固定时钟，快照随提交）。
- `platformCheck` 通过；消息三语齐全（启动检查）。

## 验收标准

- [ ] `./gradlew :quizbuks:check` 通过；`ci.yml` 与 `quizbuks.yml` 在 PR 上全绿；`tools/check-app-paths.sh` 通过。
- [ ] `docker compose -f deploy/quizbuks/docker-compose.yml up` 能启动，管理员能登录 `/admin/` 并看到国家、参数、科目。
- [ ] 角色、科目、国家、参数只经 `QB_SETUP` 建立，可重复执行。

## 风险

- 会计年度末未定（影响保留期与报表的年度），先用 12，确认后改配置即可。
- 平台 `ci.yml` 跑全部模块，CI 时间随之增加（约 +3 分钟）。
