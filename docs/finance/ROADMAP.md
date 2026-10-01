# finance — 路线图

应用 finance 的阶段计划。设计见 `00-design.md`，需求原文见 `docs/finance-requirements/`（只读），总体计划与已确认的决定见 `docs/finance-work/00-development-plan.md`。
执行方式与平台相同（根目录 `CLAUDE.md` 第 7 节）：每个阶段先出实施计划、经确认后实现；PR 逐条对照本文件的验收标准。
分支按平台版本线（平台决策 D21）：F0 在线 1.0（`1.0/finance`）。平台阶段 14a–14g 在线 1.1（`1.1/platform`）上进行，已全部合入；
`1.1/finance` 已建立（从 `1.0/finance` 拉出，再合并 `1.1/platform`，见下方"升级到线 1.1"），`1.0/finance` 冻结。F1 起的工作分支为 `1.1/finance-<N>-<名>`。

| 阶段 | 名称 | 依赖（平台） | 预估 | 状态 |
|---|---|---|---|---|
| F0 | 设计与骨架 | — | 3–4 天 | ☑ 已完成（设计待确认） |
| F1 | 总账、期间、日记账与审批 | 14a 14b 14c 14h | 8–10 天 | ◐ F1a PR 待合并 |
| F2 | 主数据导入、期初与迁移、工资导入 | 14e | 4–5 天 | ☐ |
| F3 | 应收与销售税 | — | 8–10 天 | ☐ |
| F4 | 应付、付款与 1099 | — | 8–10 天 | ☐ |
| F5 | 银行与对账 | 14e | 6–8 天 | ☐ |
| F6 | 固定资产 | — | 4–5 天 | ☐ |
| F7 | 多币种 | — | 4–5 天 | ☐ |
| F8 | 结账、重开、年结 | — | 5–6 天 | ☐ |
| F9 | 财务报表与报告 | 14d | 8–10 天 | ☐ |
| F10 | 控制、审计支持、安全配置 | 14f 14g | 4–5 天 | ☐ |
| F11 | 接口、性能、运维、全场景验收 | — | 6–8 天 | ☐ |

财务阶段之间的依赖：F2、F3、F4 在 F1 之后；F5 在 F3、F4 之后；F6 在 F4 之后；F7 在 F3–F5 之后；F8 在 F1–F7 之后；F9 在 F8 之后；F11 最后。

---

## F0 设计与骨架

**要求**
1. `.jabiz-app-paths`；根目录 `CLAUDE.md` 恢复为平台版本，财务规则写在 `backend/finance/CLAUDE.md`。
2. `docs/finance/00-design.md`（含需求覆盖表）、本路线图。
3. 模块 `backend/finance`（`jabiz.boot-app`，暂用平台后台前端），`deploy/finance/`（compose、Dockerfile），`.github/workflows/finance.yml`。
4. `docs/finance-work/work-items.csv`、`defects.csv`（由需求的 `templates/` 起始）。

**验收标准**
- [x] `./gradlew :finance:check`（`ArchitectureTest`、`FinanceAppIT`、`platformCheck` 0 错误）通过。
- [x] `tools/check-app-paths.sh` 通过（线 1.0 起以 `origin/1.0/platform` 为基准）。
- [x] 打包的 jar 在空数据库上启动、迁移、健康检查 UP，`/` 提供后台页面，匿名调用 `/api` 为 401（本地冒烟；CI 作业 `Finance / package`）。
- [ ] 设计文档经确认。

说明：本地环境没有 Docker，集成测试以 `JABIZ_TEST_DB_*` 连接本地 PostgreSQL 16 运行；compose 与 Dockerfile 未在本地构建，由使用者或以后的 CI 作业验证。

## 升级到线 1.1（2026-09-30）

**要求**
1. `1.1/finance` 从 `1.0/finance` 拉出，合并 `1.1/platform`（`.jabiz-platform-line` = 1.1）。
2. 配置：`jabiz.integrity.key`（`JABIZ_INTEGRITY_KEY`）、`jabiz.security.mfa.key`（`JABIZ_MFA_KEY`）；测试配置的固定密钥；
   CI 作业 `Finance / package` 与 `deploy/finance` 的说明带上两个新密钥（容器首次本地启动时由入口脚本生成）；单点登录按平台 `docs/guide/quickstart.md` 以环境变量配置。
3. 设计文档：平台依赖改为"已有（线 1.1）"；§15 写明 14g 的具体用法（`requiresMfa`、`withinDataPeriod`、`f.masked`、访问审查）。
   这些用法在对应实体出现时实施（F1 起，F10 验收）。

**验收标准**
- [x] 合并无冲突；`tools/check-app-paths.sh` 以 `origin/1.1/platform` 为基准通过。
- [x] `./gradlew :finance:check`（`ArchitectureTest`、`FinanceAppIT`、`platformCheck`）通过。
- [x] CI（`build`、`Finance / package` 等）在 `1.1/finance` 上通过。

## F1 总账、期间、日记账与审批

需求：FIN-GL-001…006、010…018、020、022；FIN-PC-001、PC-003；FIN-CT-001（日记账部分）、CT-002、CT-003、CT-005（标记）；FIN-FX-001、FX-002（主数据）。
验收口径：FIN-SCN-02 步骤 1–5；JE-0001…0003 的总账行等于 FIN-EXP-02。计划已确认（2026-09-30，接受全部推荐）。分三个 PR：

- **F1a**（`1.1/finance-1a-accounts`）：纯计算、科目、维度、币种与汇率、财年与期间及其状态、`FIN_SETUP` 与角色、科目模板、迁移 V1。
- **F1b**（`1.1/finance-1b-journals`）：日记账全部流程与表、编号与审批规则、过账与 `FinPosting`、控制科目例外、附件、冲回、周期分录与自动冲回、试算表与账户查询模板、`FinScn02IT`。
- **F1c**（`1.1/finance-1c-grid`）：`finance-web` 的录入网格与日记账页面、Vitest 与 Playwright、性能摸底、需求追踪表。

开始时发现平台没有日期类型：先在平台补齐（14h，`f.asDate()`），再在其上实现（设计 §2、§4.2）。
应用改为只用英语、区域 en-US（FD4，`jabizApp { languages("en"); region = "en-US" }`）。需求的疑问记在 `docs/finance-work/01-requirement-questions.md`。

**F1a 要求**
1. 纯计算（`calc`）：`Money`（远离零舍入、最大余数分摊）、`FiscalCalendar`（财年与期间、第 13 期）、`BookingTime`（过账日 → 芝加哥零点）、`PeriodPolicy`（期间检查）。
2. 科目：平台 `LedgerAccount` + `FinAccount`（财务类型、正常余额、报表行、现金流分类、控制类别、清算标记、必填维度）；
   流程 `FIN_ACCOUNT_CREATE` / `_UPDATE` / `_DEACTIVATE` / `_REACTIVATE` / `_DELETE`（`fin.account.maintain`）；有发生额的科目不能删除。
3. 科目模板：`FIN_COA_TEMPLATE_PREVIEW` / `_APPLY`（GL-002）。
4. 维度：`FinDepartment`、`FinLocation` 与两个 `LedgerDimension`（GL-006）。
5. 币种与汇率：`FinCurrency`、`FinExchangeRate`（FX-001、FX-002），经数据视图维护，更正保留历史。
6. 财年与期间：`FinFiscalYear`、`FinPeriod`，`FIN_FISCAL_YEAR_CREATE`、`FIN_PERIOD_SET_STATE`、`FIN_PERIOD_SET_SUBLEDGER_STATE`（PC-001、PC-003）。
7. `FIN_SETUP`：9 个角色及其 F1 权限、本位币 USD；幂等；任何角色都没有 `ledger.post` / `ledger.reverse` / `ledger.account.write`。
8. 迁移 `V1__finance_gl.sql`（全部为只追加的时态表）；配置：账本 USD 两位、公司时区、财年末 12 月；英语消息。

**F1a 验收标准**
- [x] 样例公司 36 个科目经 API 录入后属性齐全；重复代码被拒（GL-001，`ChartOfAccountsIT`）。
- [x] 汇总科目不可过账；停用的科目拒绝过账但仍在科目表中；有发生额的科目不能删除、提示停用；改名的历史列出新旧名、操作人与时间（GL-003、GL-004，`ChartOfAccountsIT`）。
- [x] 科目模板先预览（不保存）再复制，可排除；已有科目时拒绝（GL-002，`ChartTemplateIT`）。
- [x] 日历年 12 期与第 13 期、非日历财年、财年不重叠；期间与子账状态只经流程、需要关账权限（PC-001、PC-003，`PeriodStateIT`、`FiscalCalendarTest`、`PeriodPolicyTest`）。
- [x] 维度值校验账本行；汇率按 `fx-rates.csv` 录入，更正保留新旧值与操作人；一对一日一类型一个汇率（GL-006、FX-001、FX-002，`SetupAndMasterDataIT`）。
- [x] `FIN_SETUP` 建立 9 个角色与 USD，再次运行不变；需要二次验证；没有角色能直接写总账（`SetupAndMasterDataIT`、`FinanceRolesTest`）。
- [x] 纯计算的单元测试与属性测试（`MoneyTest`、`FiscalCalendarTest`、`BookingTimeTest`、`PeriodPolicyTest`、`AccountTypesTest`）。
- [x] 所有 `fi_` 表只插入（各 IT 的 `assertOnlyInserted`）；`./gradlew :finance:check`（含 `platformCheck`）与 `tools/check-app-paths.sh` 通过。

**F1b / F1c 要求与验收标准**：在各自开始时写入本节（计划见上文与 `docs/finance-work/00-development-plan.md` §5.2）。

## F2 — F11

范围、需求编号与验收口径见 `docs/finance-work/00-development-plan.md` §5.2；每个阶段开始时把详细要求与验收标准写入本节。
