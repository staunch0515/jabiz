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

**F1b 要求**
1. 日记账实体（迁移 `V2__finance_journals.sql`，全部只追加）：`FinJournal`、`FinJournalLine`、`FinJournalAttachment`、`FinPosting`（每笔总账交易的过账日、期间、来源、总账号、来源单据）、
   `FinRecurringTemplate` / `FinRecurringLine`；除周期模板（`fin.recurring.maintain`）外都只经流程写入（`processOnlyWrites`）。
2. 流程：`FIN_JOURNAL_SAVE`（只由准备人；替换行；任何修改退回草稿并使批准与控制科目例外失效，撤回待审请求）、`FIN_JOURNAL_DELETE`（未编号的草稿）、
   `FIN_JOURNAL_SUBMIT`（一次报告全部问题：行、借贷平衡及差额、科目可过账、控制科目、维度、期间；编号；审批规则）、内部的 `FIN_JOURNAL_POST`（内容哈希一致、期间未关）、
   `FIN_JOURNAL_APPROVAL_RESULT`（订阅批准/驳回事件）、`FIN_JOURNAL_GRANT_CONTROL_EXCEPTION`（不是准备人）、`FIN_JOURNAL_REVERSE`、`FIN_JOURNAL_ATTACH` / `_DETACH`。
3. 编号：单据号 `JE-nnnn` 按财年（`fin.journal`，唯一键（财年，单据号））在提交时取得；总账号 `GJ-MAN-<财年>-nnnnnn`（`fin.gl`）在过账时取得；被拒的提交不占号。
4. 审批（14b）：`ApprovalSubject` `fin.journal`（事实 amount、source、manual、afterPeriodEnd）；`FIN_SETUP` 提出规则 `FIN-MANUAL-10K`（手工且 > 10,000.00 → `fin.journal.approve`），另一人发布；
   不需审批时直接过账并记录已评估的规则版本。
5. 附件：两个文件策略（`fin.journal.support`：PDF 与图片；`fin.journal.sheets`：CSV 与 XLSX），记下内容哈希；过账后不能增删。
6. 周期分录与自动冲回：`FIN_RECURRING_RUN`（模板 × 期间各一次，日期为期末，照常审批）、`FIN_AUTO_REVERSE_RUN`（`<原号>-R`，期间关闭时跳过并说明），各有定时任务（公司时区）。
7. 报表模板（按过账日）：`finance.gl.trial_balance`（汇总科目合计下级、第 13 期可排除、`knownAt`）、`finance.gl.account_inquiry`（期初、逐行与滚动余额、期末）、`finance.gl.journal_register`。

**F1b 验收标准**
- [x] 草稿、提交、编号、过账；小额分录直接过账并记录规则版本（GL-010、GL-013、GL-015 第 2 条，`JournalLifecycleIT`）。
- [x] 超过 10,000.00 的手工分录等待他人批准；准备人即使有审批权限也被拒；驳回须写理由（GL-015 第 1、3 条，CT-001，`JournalLifecycleIT`、`FinScn02IT`）。
- [x] 批准后的修改使批准失效、须重新批准；待审请求随修改撤回（GL-014、CT-003，`JournalLifecycleIT`）。
- [x] 提交一次报告全部问题并显示差额；关闭与软关闭期间、第 13 期（GL-011、PC-003，`JournalLifecycleIT`）。
- [x] 控制科目只在 Controller 授权例外后接受手工行，修改使例外失效（GL-005、Q1，`JournalLifecycleIT`）。
- [x] 过账后的分录不能修改或删除，通用接口也不能；冲回金额相反并引用原分录，只能冲回一次（GL-012，`JournalLifecycleIT`）。
- [x] 20 笔并发提交的单据号与总账号连续无重复（GL-013，`JournalLifecycleIT`）。
- [x] 周期分录每期一次、再次运行不生成；自动冲回在冲回日生成 `<原号>-R`，期间关闭时等待（GL-017、GL-018，`JournalAutomationIT`）。
- [x] 附件带内容哈希，过账后不能增删、文件不能删除（GL-016，`AttachmentIT`）。
- [x] 试算表按过账日汇总、第 13 期可排除、按 `knownAt` 重现；账户查询的期初、滚动与期末余额（GL-003、GL-022，`GlReportsIT`）。
- [x] FIN-SCN-02 第 1–5 步；JE-0001…JE-0004 的总账行等于 FIN-EXP-02；单据号连续（`FinScn02IT`）。
- [x] 自查后的加固：迟到的旧审批决定不生效；批准后科目被停用则不过账、可修改重提；冲回日与第 13 期标记校验；冲回分录的行固定；
  附件只由准备人在草稿时增删且只能附自己的上传；不能过账的周期模板被跳过；自动冲回不提前、只看未冲回的分录（`JournalLifecycleIT`、`JournalAutomationIT`、`AttachmentIT`）。
- [x] 纯计算的单元与属性测试（`JournalValidatorTest`）；所有 `fi_` 表只插入；`./gradlew :finance:check` 与 `tools/check-app-paths.sh` 通过。

**F1c 要求与验收标准**：在开始时写入本节（计划见上文与 `docs/finance-work/00-development-plan.md` §5.2）。

## F2 — F11

范围、需求编号与验收口径见 `docs/finance-work/00-development-plan.md` §5.2；每个阶段开始时把详细要求与验收标准写入本节。
