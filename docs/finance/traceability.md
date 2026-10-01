# 需求追踪：需求 → 实现 → 测试

逐条记录每个需求由哪些实体、流程、模板、页面实现，由哪些测试验证（设计 §19、计划 §5.2）。各阶段完成时补上本阶段的需求；
"部分"表示本阶段只做了其中一部分，余下的阶段写在备注中。需求副本（`docs/finance-requirements/`）只读，编号与标题取自其中。

路径简写：`gl/` = `backend/finance/src/main/java/com/jabiz/finance/gl/`，`it/` = `backend/finance/src/test/java/com/jabiz/finance/it/`，
`queries/` = `backend/finance/src/main/resources/queries/finance/gl/`，`web/` = `finance-web/src/journal/`，`e2e/` = `finance-web/e2e/`。

## F1 总账、期间、日记账与审批

| 需求 | 标题 | 状态 | 实现 | 测试 |
|---|---|---|---|---|
| FIN-GL-001 | Account definition | 完成 | 平台 `LedgerAccount` + `FinAccount`；`FIN_ACCOUNT_CREATE`（`gl/AccountProcesses`） | `it/ChartOfAccountsIT`（样例公司 36 个科目、重复代码） |
| FIN-GL-002 | Standard chart template | 完成 | `FIN_COA_TEMPLATE_PREVIEW` / `_APPLY`（`gl/ChartTemplateProcesses`） | `it/ChartTemplateIT` |
| FIN-GL-003 | Account hierarchy and roll-up | 完成 | 上级与汇总科目；试算表上卷（`queries/trial_balance.sql`） | `it/ChartOfAccountsIT`、`it/GlReportsIT` |
| FIN-GL-004 | Changes to accounts | 完成 | `FIN_ACCOUNT_UPDATE` / `_DEACTIVATE` / `_REACTIVATE` / `_DELETE`；平台历史 | `it/ChartOfAccountsIT` |
| FIN-GL-005 | Control accounts | 完成 | `FinAccount.controlClass`；提交检查（`gl/JournalValidator`）；`FIN_JOURNAL_GRANT_CONTROL_EXCEPTION`（Q1） | `JournalValidatorTest`、`it/JournalLifecycleIT`、`it/FinScn02IT` |
| FIN-GL-006 | Dimensions | 完成 | `FinDepartment`、`FinLocation` 与两个 `LedgerDimension`；行的维度检查 | `it/SetupAndMasterDataIT`、`JournalValidatorTest` |
| FIN-GL-010 | Entry structure | 完成 | `FinJournal`、`FinJournalLine`（迁移 V2）；`FIN_JOURNAL_SAVE` | `it/JournalLifecycleIT`、`it/FinScn02IT` |
| FIN-GL-011 | Balanced entries | 完成 | `JournalValidator.checkForPosting`（差额）；网格的实时合计（`web/grid.ts`） | `JournalValidatorTest`、`it/JournalLifecycleIT`、`web/grid.test.ts`、`e2e/journal.spec.ts` |
| FIN-GL-012 | Immutability of posted entries | 完成 | 只追加的表、`processOnlyWrites`；`FIN_JOURNAL_REVERSE` | `it/JournalLifecycleIT`、`it/FinScn02IT` |
| FIN-GL-013 | Gap-free numbering | 完成 | `NumberSequence` `fin.journal`（按财年）、`fin.gl`；提交检查通过后才取号 | `it/JournalLifecycleIT`（20 路并发）、`it/FinScn02IT` |
| FIN-GL-014 | Entry lifecycle | 完成 | 状态 DRAFT → SUBMITTED → APPROVED → POSTED / REJECTED；修改退回草稿并撤回审批 | `it/JournalLifecycleIT` |
| FIN-GL-015 | Approval of manual entries | 完成 | `ApprovalSubject` `fin.journal`、规则 `FIN-MANUAL-10K`（`FIN_SETUP` 提出、另一人发布）；`FIN_JOURNAL_APPROVAL_RESULT` | `it/JournalLifecycleIT`、`it/FinScn02IT`、`e2e/journal.spec.ts` |
| FIN-GL-016 | Supporting documents | 完成 | `FinJournalAttachment`（两个文件策略、内容哈希）；`FIN_JOURNAL_ATTACH` / `_DETACH`；页面 `web/AttachmentsCard.tsx` | `it/AttachmentIT`、`it/FinScn02IT` |
| FIN-GL-017 | Recurring entries | 完成 | `FinRecurringTemplate` / `Line`；`FIN_RECURRING_RUN` 与定时任务 | `it/JournalAutomationIT`、`it/FinScn02IT` |
| FIN-GL-018 | Automatic reversing entries | 完成 | `autoReverseDate`、`reversedById`；`FIN_AUTO_REVERSE_RUN` 与定时任务 | `it/JournalAutomationIT` |
| FIN-GL-020 | Bulk journal entry grid | 完成 | `web/JournalGrid.tsx`、`web/grid.ts`（粘贴、合计、当场校验）、`web/JournalEntryPage.tsx` | `web/grid.test.ts`、`web/JournalGrid.test.tsx`、`e2e/journal.spec.ts`（粘贴 50 行） |
| FIN-GL-022 | Account inquiry | 部分 | `queries/account_inquiry.sql`（期初、逐行、滚动余额、期末，来源单据） | `it/GlReportsIT`；验收中的 1010 一月余额待期初导入（F2）与子账过账（F3–F5）后用样例公司验证 |
| FIN-PC-001 | Fiscal calendar | 完成 | `FinFiscalYear`、`FinPeriod`（第 13 期）；`calc/FiscalCalendar` | `FiscalCalendarTest`、`it/PeriodStateIT` |
| FIN-PC-003 | Period states | 部分 | 开放 / 软关账 / 关账与子账状态；`calc/PeriodPolicy` | `PeriodPolicyTest`、`it/PeriodStateIT`、`it/JournalLifecycleIT`；结账清单与受控重开在 F8 |
| FIN-FX-001 | Currencies | 完成 | `FinCurrency` | `it/SetupAndMasterDataIT` |
| FIN-FX-002 | Exchange rates | 完成 | `FinExchangeRate`（更正保留历史） | `it/SetupAndMasterDataIT` |
| FIN-CT-001 | Segregation of duties | 部分 | 日记账：准备人不能批准（平台 14b）、不能授权自己的例外；只有准备人改附件；Controller 不维护周期模板 | `it/JournalLifecycleIT`、`it/FinScn02IT`、`it/AttachmentIT`；其余职责分离规则在 F10 |
| FIN-CT-002 | Approval rules are versioned | 部分 | 规则经四眼的控制变更发布；每次评估记下规则版本（`sys_approval_evaluation`） | `it/FinScn02IT`、`it/JournalLifecycleIT`；按生效日改阈值的验收在 F10 |
| FIN-CT-003 | Approval integrity | 完成 | 批准绑定内容哈希与当前请求；修改使批准失效 | `it/JournalLifecycleIT` |
| FIN-CT-005 | Suspense and clearing accounts | 部分 | `FinAccount.clearing` 标记 | `it/ChartOfAccountsIT`；期末清单检查在 F8 |
| FIN-UI-002 | Keyboard-first entry | 部分 | 网格的键盘操作与快捷键（Ctrl+S / Ctrl+Enter / Ctrl+D / Ctrl+Z / Alt+N）、科目联想、金额不用分隔符 | `web/JournalGrid.test.tsx`、`web/JournalEntryPage.test.tsx`、`e2e/journal.spec.ts`（只用键盘）；发票、账单、收款在 F3–F4 |
| FIN-UI-003 | Grid entry | 完成 | 粘贴与复制到表格、向下填充、撤销与重做 | `web/grid.test.ts`、`web/JournalGrid.test.tsx`、`e2e/journal.spec.ts`；验收中的可用性研究（50 行 ≤ 5 分钟）需由人进行 |
| FIN-UI-004 | Registers and drill-down | 部分 | 日记账登记簿（`queries/journal_register.sql`、`web/JournalListPage.tsx`：排序、筛选、合计、打开单据） | `web/JournalListPage.test.tsx`、`it/GlReportsIT`、`e2e/journal.spec.ts`；其他登记簿与报表钻取在 F3–F9 |
| FIN-NF-001 / 002 | Volumes / Response times | 摸底 | 生成器 `tools/finance/perf/`（直接写库） | 结果见 `docs/finance/perf.md`；带 50 用户的压测在 F11 |

## F2 导入、期初、迁移

路径简写另有：`io/` = `backend/finance/src/main/java/com/jabiz/finance/io/`，`migration/` = `…/finance/migration/`。

| 需求 | 标题 | 状态 | 实现 | 测试 |
|---|---|---|---|---|
| FIN-DI-001 | Master-data import | 部分 | `io/FinanceImports`（文件策略 `fin.import`；`finance.chart` → `FIN_ACCOUNT_CREATE`，`finance.fx_rates` → `gl/ExchangeRateProcesses`）；整文件校验、整体拒收、预览、导入记录（平台 14e） | `it/FinanceImportIT`（样例科目表与汇率 0 拒收；一行错整体拒收、无变化；重复文件 409）；客户、供应商、税码、资产随 F3/F4/F6 |
| FIN-DI-002 | Migration of open items and history | 部分 | `gl/OpeningProcesses`、`io/FinanceImports`（`finance.opening_balances`）；对账报告 `queries/../migration/reconciliation.sql` | `it/OpeningIT`（FIN-EXP-01 重现，每个科目差额 0.00）；场景 `f2_setup_books`；未结应收、应付、资产、银行项目随 F3–F6 |
| FIN-DI-003 | Data-quality decisions | 部分 | `migration/MigrationEntities`、`migration/MigrationProcesses`（科目决定，决定人与时间进入对账报告） | `it/OpeningIT`；重复客户合并（验收 1）随 F3 |
| FIN-PC-002 | Opening balances | 部分 | 期初期间 0（`FinPeriod.opening`）、`FIN_OPENING_POST` / `FIN_OPENING_CLOSE`，控制科目由期初分录记入 | `it/OpeningIT`（验收 1 的总账部分、验收 2）、`FinanceImportsTest`、`OpeningLinesTest`；子账合计 = 控制科目随 F3–F6 |
| FIN-GL-019 | Journal import | 完成 | `io/FinanceImports`（`finance.journals`）、`gl/JournalImportProcesses`（`FIN_JOURNAL_IMPORT`，`FinJournal.externalRef`）；菜单 `finance-web/src/index.tsx` | `it/JournalImportIT`（验收 1；照常审批、控制科目、只导入一次）、`it/PayrollImportIT`（验收 2）、`e2e/imports.spec.ts` |
| FIN-DI-004 | Payroll journal import | 完成 | `payroll/PayrollEntities`（映射）、`payroll/PayrollLines`、`payroll/PayrollProcesses`（`FIN_PAYROLL_IMPORT`）、`io/FinanceImports`（`finance.payroll`） | `it/PayrollImportIT`（PAYROLL-2601 = FIN-EXP-02）、`PayrollLinesTest`（含属性测试） |

