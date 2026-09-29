# CLAUDE.md — finance 应用规则

应用 finance（美国单一公司的基础财务系统）的后端（模块 `backend/finance`）。根目录 `CLAUDE.md` 的全部规则照常适用；本文件只写本应用额外的约定。
设计：`docs/finance/00-design.md`；阶段：`docs/finance/ROADMAP.md`；总体计划与已确认的决定：`docs/finance-work/00-development-plan.md`。

## 1. 需求副本只读

- `docs/finance-requirements/` 是 staunch0515/ubos-unit 中 fin-req-v1.0 的原样副本，**永远不修改**（包括格式、生成文件）。
  需求有问题时记在 `docs/finance-work/`，由需求方在源仓库中改版后整体重新复制。
- 期望结果（`21-expected-results.md`、`sample-company/*.csv`）只在测试中作为判定依据，**永不写入系统**：税、折旧、汇兑损益、
  所得税等都由系统计算（需求 20 §3、40 §1.5）。

## 2. 边界

- 分支按平台版本线（平台决策 D21、`docs/guide/version-lines.md`）：`<线>/finance`，工作分支 `<线>/finance-<N>-<名>`。现在是线 1.0（`1.0/finance`）；
  平台阶段 14 在线 1.1 上，14a–14c 合入 `1.1/platform` 后建 `1.1/finance`（从 `1.0/finance` 拉出，再合并 `1.1/platform`），F1 起在线 1.1 上开发，`1.0/finance` 冻结。
- 本分支只改 `.jabiz-app-paths` 中的路径（`tools/check-app-paths.sh`，基准为本线的平台分支），不改 `.jabiz-platform-line`。平台缺能力时，
  先在该线平台的工作分支上补（带平台自己的测试与 `app` 中的示范，不提财务），合入 `<线>/platform` 后再合并到 `<线>/finance`。计划中的平台阶段为 14a–14g（设计 §2）。
- 包 `com.jabiz.finance`；启动类 `FinanceApp`；表前缀 `fi_`；迁移 `db/migration/V<n>__finance_<名>.sql`；SQL 模板 `queries/finance/**`，id 前缀 `finance.`；
  权限码 `fin.<模块>.<动作>`。

## 3. 提交与记录（比较协议，需求 40 §2）

- 每个提交信息以工作项 ID 开头：需求（`FIN-GL-013: …`）、场景（`FIN-SCN-02: …`）、变更请求（`CR-A: …`）或任务（`TASK-<短名>: …`）。
  一个提交服务多个工作项时，以主要的那个开头，正文列出其余。
- 发现缺陷即在 `docs/finance-work/defects.csv` 追加一行；行从不删除，更正以新行表示。工时行（`work-items.csv`）由人记录。

## 4. 财务约定（设计 §4、§5）

- 金额 `BigDecimal`；舍入只经 `calc.Money`（远离零，`HALF_UP`），只在需求规定的层级舍入。
- 过账日期、单据日期是 `LocalDate`；记录时间来自注入的 `Clock`。报表都带 `asOf` 与 `knownAt`。
- 影响总账的单据只经其过账流程写入总账（子流程 `LEDGER_POST` / `LEDGER_REVERSE`）；`ledger.post` 不授予任何角色。
  过账后不可修改，更正只能是贷项、作废（冲正）或调整分录。
- 纯计算（税、折旧、到期日、账龄、匹配评分、1099 汇总、外币损益）写成没有 I/O 的纯 Java 类，并有单元测试与属性测试。

## 5. 测试与命令（在 `backend/` 下）

- `./gradlew :finance:check`（测试 + `platformCheck`）；单个类：`./gradlew :finance:test --tests '*FinanceAppIT'`。
- 集成测试连接真实 PostgreSQL（同平台：Testcontainers 或 `JABIZ_TEST_DB_*`）。
- 样例公司验收：按 `transactions.csv` 的顺序经 API 录入，与期望结果逐项比较（设计 §18）。

## 6. 运行

- 本地：`docker compose -f deploy/finance/docker-compose.yml up -d --build`（数据库端口 5438、应用 8080）；
  或只起数据库（`… up -d db`）后在 `backend/` 下 `./gradlew :finance:bootRun --args='--spring.profiles.active=dev'`。
