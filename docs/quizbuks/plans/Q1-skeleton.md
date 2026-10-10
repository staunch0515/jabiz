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
   `jabiz.fiscal-year-end: 12`（会计年度为日历年，已确认）；密钥全部取自环境变量；`jabiz.i18n.default-locale: en`）；`application-dev.yml`（开发用请求头、文件目录）；
   测试配置 `src/test/resources/config/application.properties`（固定测试密钥、BCrypt 4、关闭投递与调度）。
3. **权限与角色**（`QbPermissions`、`QbRoles`）：设计 §2 的权限码与五个角色；流程 `QB_SETUP`（幂等、只增加，同 finance 的 `FIN_SETUP`）建角色与授权。
   管理员角色 `requireMfa`。
4. **账本**：`QB_SETUP` 开立设计 §4.1 的 7 个科目（`LEDGER_ACCOUNT_OPEN`）；`LedgerDimension.define(1, "party", d -> d.entity("SecUser", "userId"))`（当事人 = 用户编号）。
5. **国家字典** `QbCountry`（时态实体，设计 §3.1）：ISO 3166-1 全部国家（三语名称、所属地区），数据在 `src/main/resources/quizbuks/countries.txt`（评审后由 CSV 改为无引号的 `|` 分隔格式），
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
`db/migration/V2__quizbuks_setup_records.sql`（评审后加）：`qb_setup_record_version`（只写一次，`QB_SETUP` 建过或见过的每一项）。

## 测试

- `QuizbuksAppIT`：启动、`/actuator/health` 为 UP、未认证访问 `/api/meta/datasets` 为 401。
- `SetupIT`：`QB_SETUP` 建出五个角色及权限、7 个科目、全部国家与参数；再执行一次不重复、不改变已有数据；`qb_country_version` 上没有 UPDATE / DELETE。
- `ArchitectureTest`：`com.jabiz.quizbuks` 不引用 `reactor.*`。
- `ScenarioTest` + 场景 `scenarios/qb/setup.yml`（固定时钟，快照随提交）。
- `platformCheck` 通过；消息三语齐全（启动检查）。

## 验收标准

- [x] `./gradlew :quizbuks:check` 通过；`tools/check-app-paths.sh` 通过。`ci.yml` 与 `quizbuks.yml` 在 PR 上全绿：**待 PR 验证**（本阶段未开 PR；`quizbuks.yml` 的步骤已在本机以同样的命令走过：`bootJar`、以临时密钥对空库启动、健康检查、`/admin/` 与其客户端路由返回页面、未认证 API 为 401）。
- [ ] `docker compose -f deploy/quizbuks/docker-compose.yml up` 能启动，管理员能登录 `/admin/` 并看到国家、参数、科目：**未能在开发环境验证**（无 Docker 守护进程）；`docker compose … config` 通过。
  同等验证：打包的 jar 对新库启动，引导管理员经 `/api/auth/login` 登录、执行 `QB_SETUP` 成功（5 角色、62 项授权、7 科目、249 国、8 参数），经数据视图 API 读到国家。
- [x] 角色、科目、国家、参数只经 `QB_SETUP` 建立，可重复执行（`SetupIT`、场景 `qb/setup.yml`；国家数据视图 `processOnlyWrites`）。

## 风险

- ~~会计年度末未定~~：已确认为日历年（`jabiz.fiscal-year-end: 12`）。
- 平台 `ci.yml` 跑全部模块，CI 时间随之增加（约 +3 分钟）。

## 实施记录

### 国家数据（`countries.txt`）的判断

- 249 个 ISO 3166-1 代码，与 JDK 的 `Locale.getISOCountries()` 逐一相同（`CountryDataTest`）。名称用常用短名（如 South Korea、Türkiye、Czechia），不用 ISO 的正式长名。
- 地区按 UN M49 的大洲划分，例外如下：
  - 跨洲国家属两个地区：俄罗斯、土耳其、塞浦路斯 → `EUROPE` + `ASIA`；高加索三国（GE、AM、AZ）与哈萨克斯坦只属 `ASIA`。
  - 中美洲、加勒比、格陵兰、百慕大、圣皮埃尔和密克隆 → `NORTH_AMERICA`；福克兰群岛、南乔治亚、法属圭亚那 → `SOUTH_AMERICA`；布韦岛视为南极，只属 `GLOBAL`。
  - `JP`、`CN`、`US` 只含该国本身：香港、澳门、台湾只属 `ASIA`；波多黎各、美属维尔京群岛 → `NORTH_AMERICA`；关岛、美属萨摩亚、北马里亚纳、美国本土外小岛屿属大洋洲，只属 `GLOBAL`。
  - 非洲、大洋洲、南极（及英属印度洋领地、法属南部领地等）没有自己的地区，只属 `GLOBAL`。
  - 中文名：香港、澳门、台湾写作"香港""澳门""台湾"；如需"中国香港"等写法，改数据文件后由管理员更正（国家字典只经流程写入，届时加一个修改流程）。
- `GLOBAL` 在数据文件中不写，导入时加上；`regions` 存为固定顺序、逗号连接的代码（平台没有多值字段，设计 3.1 已注明）。

### 业务参数的缺省值

`qb.creator-fee-rate` 0（numeric 5,4）、`qb.transfer.threshold` 1 000、`qb.transfer.review-above` 50 000（JPY，小数位 0）、`qb.transfer.enabled` false（Stripe 在 Q6 才接入）、
`qb.review.sponsor.auto` / `qb.review.publication.auto` false（默认人工审核）、`qb.ai.model` `gpt-4o-mini`（占位，Q8 前确认）、`qb.app.min-version` `1.0.0`。

### 与计划的差异

- 新增两个权限（设计 §2 已补）：`qb.country.read`（五个角色）、`qb.setup`（超级管理员）。财务管理员另有 `ledger.account.read`，以便在后台看科目。
- 国家数据视图 `maxWriteBatchSize(300)`：`QB_SETUP` 一次写入全部 249 国（缺省上限 100）。
- `03-plan.md` 的 Q1 行提到 `QbWallet`；已确认的本计划不含它，留到 Q6。
- 三个前端外壳不在本阶段（见开头）；CI 的 `web` / `e2e` 作业随前端阶段加入。

## 评审后的修改（2026-10-10）

1. `QB_SETUP` 只加从未存在过的：它建过或见过的每一项写入只写一次的 `QbSetupRecord`（`role:` / `grant:` / `account:` / `param:` / `country:`），
   有记录的永不再加，所以管理员删除的角色、收回的权限、改过的参数都保持原样（`SetupRemovalsIT`）。没有记录的项若现在存在、或已排定将来开始，只记下不新建
   （角色与授权另经模板 `qb.setup.roles` / `qb.setup.grants` 以远期时点读取，`SetupScheduledIT`）。
2. 见已知问题：平台的受控变更不覆盖业务参数，`platform.param.write` 保留。
3. 地区规则 `QB_COUNTRY_REGIONS_FORMAT` 只接受规范写法（以 GLOBAL 开头、按 `Regions.ALL` 顺序、不重复、只含已知代码），前后端同一个正则。
4. 流程中的读取都取全部结果（`limit(Integer.MAX_VALUE)`，超过平台上限即 422，D32），不再用自定的上限。
5. 同 1。
6. 国家文件由 `CountryCatalog`（`PlatformCheck`，类别 `QUIZBUKS`）在启动时读取，每一行的每个问题一次报告；没有静态初始化中的异常。
7. 文件格式改为无引号的 `|` 分隔（名称中可有逗号，不可有引号），各格与以逗号分隔的地区都去掉空白；空地区、重复、GLOBAL 写出、未知代码都报告。
8. compose 中应用的服务名改为 `app`，与平台入口脚本的提示一致；README 给出带 `-f` 的完整命令。
9. `quizbuks.yml` 的触发方式同平台 `ci.yml`（推送只在长期分支，工作分支经 PR，取消被取代的 PR 运行，`workflow_dispatch`）。
10. `SetupIT` 检查 `regions` 列的长度不小于 `Regions.MAX_LENGTH`。

## 已知问题

- ~~业务参数的修改不是四眼（需要平台改动）~~：平台阶段 16i（决策 D40）已合入，随 Q3 分支一起完成：
  `setup.SetupConfig` 以 `ControlledParams` 声明 `QbParams.CONTROLLED`（抽成比例、转账门槛、人工审核额、转账开关，以及两个"免审核"开关——打开即去掉人工审核，本身就是控制）；
  `qb.ai.model`、`qb.app.min-version` 不受控。受控键不能直接建立（`PARAM_CONTROLLED`），所以 `QB_SETUP` 以子流程 `CONTROL_CHANGE_PROPOSE` 提出它们的首个值
  （输出 `paramsProposed` / `proposals`），由另一位管理员 `CONTROL_CHANGE_PUBLISH` 发布；撤回的提案不再提出（`QbSetupRecord`）。
  `QB_ADMIN_SUPER` 不再有 `platform.param.write`（保留 `platform.param.read`、`control.propose`）。`QB_SETUP` 只增不减，所以**已有安装中的这项授权要由管理员收回一次**
  （角色权限页面删除 `QB_ADMIN_SUPER` 的 `platform.param.write`）；之后 `QB_SETUP` 不会加回。
  `control.publish` 只授予 `QB_ADMIN_FINANCE`（2026-10-10 确认，Q3 的 PR 中实施）：超级管理员提议、财务管理员发布；已有安装再执行一次 `QB_SETUP` 即补上该授权。
  注意：首次安装若只有一个平台管理员，他执行 `QB_SETUP` 后不能发布自己的提案，受控参数要等第二个管理员发布后才有值（Q5、Q6 之前须完成）。
- 首次 `QB_SETUP` 写约 600 行（249 国及每项的记录），本机约 10 秒；之后的运行几乎不写。

- ~~账本维度未声明（需要平台改动）~~：平台阶段 16h（PR #102）已合入，`wallet/WalletConfig` 声明维度 1 `party`（`SecUser.userId`），`QuizbuksAppIT` 检查。
- `docker compose up` 与 PR 上的 CI 未在本环境运行（见验收标准）。
