# CLAUDE.md — QuizBuks 应用规则

应用 QuizBuks（商家出资发布 Quiz、用户答题得 Kudos 奖励）的后端（模块 `backend/quizbuks`）。根目录 `CLAUDE.md` 的全部规则照常适用；本文件只写本应用额外的约定。
需求分析：`docs/quizbuks/01-requirements-analysis.md`；设计：`docs/quizbuks/02-design.md`；计划：`docs/quizbuks/03-plan.md`；各阶段的实施计划：`docs/quizbuks/plans/`。

## 1. 需求副本只读

- `docs/quizbuks-requirements/` 是需求方文档的原样副本，**永远不修改**。需求有问题时记在 `docs/quizbuks/`（分析或设计的待确认问题），由需求方改版后整体重新复制。

## 2. 边界

- 分支按平台版本线（平台决策 D21、`docs/guide/version-lines.md`）：`<线>/quizbuks`，工作分支 `<线>/quizbuks-<N>-<名>`。现在是线 1.2（`1.2/quizbuks`）。
- 本分支只改 `.jabiz-app-paths` 中的路径（`tools/check-app-paths.sh`，基准为本线的平台分支），不改 `.jabiz-platform-line`。平台缺能力时，
  先在 `1.2/platform` 的工作分支上补（带平台自己的测试与 `app` 中的示范，不提 QuizBuks），合入后再合并到 `1.2/quizbuks`。计划中的平台阶段见 `docs/quizbuks/03-plan.md` 第 1 节。

## 3. 命名

| 对象 | 约定 | 例 |
|---|---|---|
| 包 | `com.jabiz.quizbuks.<模块>`（`identity`、`content`、`publishing`、`play`、`wallet`、`messaging`、`stats`；公用的在 `setup`、`country`） | `com.jabiz.quizbuks.wallet` |
| 启动类 | `QuizbuksApp` | |
| 表 | 前缀 `qb_`，时态表 `qb_<名>_version` | `qb_country_version` |
| 实体 | 前缀 `Qb` | `QbCountry` |
| 权限 | `qb.<模块>.<动作>`，常量在 `QbPermissions`；只经 `setup.QbRoles` 授予（`QB_SETUP`） | `qb.content.write` |
| 流程 | `QB_*` | `QB_SETUP` |
| 错误 / 规则代码 | `QB_*`，三语文案 | `QB_COUNTRY_CODE_FORMAT` |
| 业务参数 | `qb.<模块>.<名>`，缺省值在 `setup.QbParams`，由 `QB_SETUP` 建立 | `qb.transfer.threshold` |
| 迁移 | `db/migration/V<n>__quizbuks_<名>.sql`，自 V1 起（应用迁移有自己的历史表，与平台的 `db/jabiz` 互不冲突） | `V1__quizbuks_countries.sql` |
| SQL 模板 | `queries/qb/**`，id 前缀 `qb.` | `qb.app.feed` |
| 字典 | `urn:jabiz:dict:quizbuks:<名>` | `urn:jabiz:dict:quizbuks:region` |

## 4. 应用约定

- **金额**：账本与接口一律 JPY、小数位 0（`jabiz.ledger.currency: JPY`、`scale: 0`；字段 `asMonetary("JPY", 0)`）。三个应用前端把金额显示为 "Kudos"（`1,234 Kudos`，一处格式化函数）；
  平台通用后台对管理员仍显示 JPY。不写"Kudos"以外的对外币种名。
- **会计年度**为日历年（`jabiz.fiscal-year-end: 12`）。
- **语言**：消息资源 `messages_{en,zh,ja}.properties` 三语齐全（`jabizApp { languages("en", "zh", "ja") }`，启动检查）；实体与字段 `entity.<实体>[.<字段>]`、流程 `process.<名>`、参数 `param.<键>`。
  产品显示名只写在 `app.name`。
- **初始数据只经 `QB_SETUP`**：角色、账本科目、国家、业务参数由它建立；它可重复执行，只补**从未存在过**的（`QbSetupRecord` 记下它建过或见过的每一项），不改已有的，也不加回管理员删除的角色、收回的权限。新阶段的新权限、科目、参数加到
  `QbRoles` / `QbLedger` / `QbParams`，部署后再执行一次 `QB_SETUP`。
- **账本**只经流程的子流程 `LEDGER_POST` / `LEDGER_REVERSE` 写入；`ledger.post`、`ledger.reverse`、`ledger.account.write` 不授予任何角色。
- **国家数据**在 `quizbuks/countries.txt`（`code|en|zh|ja|regions`，无引号）；文件问题由启动检查（类别 `QUIZBUKS`）一次报告。
- **地区**：国家的 `regions` 是按 `country.Regions.ALL` 顺序、以逗号连接的地区代码，总含 `GLOBAL`（平台没有多值字段）；规则 `QB_COUNTRY_REGIONS_FORMAT` 只接受这一种写法。
- **外部密钥**（`STRIPE_*`、`OPENAI_API_KEY`）只来自环境变量（`deploy/quizbuks/README.md`）。

## 5. 测试与命令（在 `backend/` 下）

- `./gradlew :quizbuks:check`（测试 + `platformCheck`）；单个类：`./gradlew :quizbuks:test --tests '*SetupIT'`。
- 集成测试连接真实 PostgreSQL（同平台：Testcontainers 或 `JABIZ_TEST_DB_*`），测试类放在 `com.jabiz.quizbuks.*` 下。
- 场景回放：`src/test/resources/scenarios/qb/**.yml`，`./gradlew :quizbuks:test --tests '*ScenarioTest'`；确认变化正确后加 `-Dscenario.update-snapshots=true`。

## 6. 运行

- 本地：`docker compose -f deploy/quizbuks/docker-compose.yml up -d --build`（数据库端口 5440、应用 8080，后台在 `/admin/`）；
  或只起数据库（`… up -d db`）后在 `backend/` 下 `./gradlew :quizbuks:bootRun --args='--spring.profiles.active=dev'`。
- 首次启动后，管理员登录 `/admin/` 执行一次 `QB_SETUP`（流程页面）。
