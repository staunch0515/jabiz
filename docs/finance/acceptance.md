# 财务应用：验收状态（F11e，D8、D9）

逐条列出 `docs/finance-requirements/` 的 153 个需求（Must 130、Should 20、Could 3）的验收状态与依据。依据中的路径同 `traceability.md`
（`it/` 为集成测试，`e2e/` 为端到端测试）；实现细节与各阶段的记录见 `traceability.md` 与 `ROADMAP.md`。

状态：**完成**＝需求的验收标准由自动测试或演练满足；**部分**＝验收标准满足或部分满足，但需求中有未做的部分（写在依据中）；
**待使用方**＝验收需要真人或正式环境（易用性研究、外部审计、一个月的可用性、全量压测）；**不做**＝Could，未实现。

## 1. 汇总

| 优先级 | 完成 | 部分 | 待使用方 | 不做 | 合计 |
|---|---:|---:|---:|---:|---:|
| Must | 123 | 5 | 2 | 0 | 130 |
| Should | 17 | 2 | 1 | 0 | 20 |
| Could | 0 | 0 | 0 | 3 | 3 |

场景：14 个场景有自动测试（`it/FinScn01IT` … `it/FinScn13IT`、`it/FinScn15IT`）；FIN-SCN-14 部分自动（NF-003、005、006、007 与 UI-008、010 的测试与演练），其余（易用性研究、外部审计、全量压测）由使用方进行，
`./gradlew :finance:check` 一次运行全部。FIN-EXP-01 … 17 由这些场景逐项比对。

## 2. 未完成的 Must 与使用方要做的事

- **FIN-UI-001 易用性研究**、**FIN-UI-009 外部无障碍审计**：材料见 `usability.md`；自动无障碍检查已通过。
- **FIN-NF-001 / 002 全量压测**：工具与数据生成器就绪（`tools/finance/perf`、`tools/finance/load`），结果模板见 `perf.md` §8，在正式硬件上以 3 年数据复跑。
- **FIN-NF-005 全量恢复演练**（开发环境的演练已完成）：`tools/finance/ops/drill.sh restore` 的步骤在正式环境以全量数据进行，记下恢复时间（RTO ≤ 4 小时）。
- 部分满足的 Must（验收标准满足，需求中有未做的部分）：FIN-SC-004（到期匿名化）、FIN-UI-007（统一的计算说明）、FIN-DI-008（导出包不含附件与审计记录）。

## 3. 已知上限（D9）

| 上限 | 值 | 评估 |
|---|---|---|
| 一张对账单的行数 | 5,000（`StatementProcesses.MAX_LINES`） | 超过即整体拒收，可分文件导入；常见的月对账单远小于此 |
| 一次接受的匹配建议 | 500（`MatchProcesses.MAX_ACCEPTED`） | 页面分批提交；5,000 行匹配按 500 一批完成（perf.md §8） |
| 匹配项目 | 10,000 行以上在提交时失败 | 一个银行账户的未结项目在正常运行中远小于此；记为已知限制 |
| 报表格式的行数 | 200 | 样例与常见格式在 100 行以内（F11a 已从 100 提到 200） |
| 流程中的读取 | `jabiz.process.max-read-rows`（平台 D32） | 超过即 422，不截断；关账、年结读取的行数随科目数而不随单据数增长 |
| 数据视图查询的时限 | 5 s（平台 14q，503 `QUERY_TIMEOUT`） | 全量下关账与年结的查询接近时限（perf.md §8），正式环境按硬件调整 |
| 编号序列 | 无缺号、串行取号 | 高并发过账在取号处排队（perf.md §7），满足 NF-003 |
| 一次全量导出的数据视图 | 100 | 全量导出分批（api.md §5） |

## 4. 核心任务与端到端测试（FIN-UI-001 的任务卡，FIN-UI-010 在各浏览器中）

| 任务卡（usability.md §2） | 端到端测试（`finance-web/e2e/`） |
|---|---|
| T1、T2 日记账录入与粘贴 | `journal.spec.ts` |
| T3、T4 发票与收款核销 | `receivables.spec.ts` |
| T5、T6 账单与付款批 | `payables.spec.ts` |
| T7 科目明细与钻取 | `reports.spec.ts` |
| T8 银行匹配 | `bank.spec.ts` |
| T9 审批与报表 | `controls.spec.ts`、`reports.spec.ts` |
| T10 关账清单 | `close.spec.ts` |
| T11 历史 | `history.spec.ts` |

## 5. 逐条状态

| 需求 | 标题 | 优先级 | 场景 | 状态 | 依据 |
|---|---|---|---|---|---|
| FIN-GL-012 | Immutability of posted entries | Must | FIN-SCN-02 | 完成 | `it/JournalLifecycleIT`、`it/FinScn02IT` |
| FIN-GL-001 | Account definition | Must | FIN-SCN-01 | 完成 | `it/ChartOfAccountsIT`（样例公司 36 个科目、重复代码） |
| FIN-GL-002 | Standard chart template | Should | FIN-SCN-01 | 完成 | `it/ChartTemplateIT` |
| FIN-GL-003 | Account hierarchy and roll-up | Must | FIN-SCN-01 | 完成 | `it/ChartOfAccountsIT`、`it/GlReportsIT` |
| FIN-GL-004 | Changes to accounts | Must | FIN-SCN-15 | 完成 | `it/ChartOfAccountsIT` |
| FIN-GL-005 | Control accounts | Must | FIN-SCN-01 | 完成 | `JournalValidatorTest`、`it/JournalLifecycleIT`、`it/FinScn02IT` |
| FIN-GL-006 | Dimensions | Should | — | 完成 | `it/SetupAndMasterDataIT`、`JournalValidatorTest` |
| FIN-GL-010 | Entry structure | Must | FIN-SCN-02 | 完成 | `it/JournalLifecycleIT`、`it/FinScn02IT` |
| FIN-GL-011 | Balanced entries | Must | FIN-SCN-02 | 完成 | `JournalValidatorTest`、`it/JournalLifecycleIT`、`web/grid.test.ts`、`e2e/journal.spec.ts` |
| FIN-GL-013 | Gap-free numbering | Must | FIN-SCN-02 | 完成 | `it/JournalLifecycleIT`（20 路并发）、`it/FinScn02IT` |
| FIN-GL-014 | Entry lifecycle | Must | FIN-SCN-02 | 完成 | `it/JournalLifecycleIT` |
| FIN-GL-015 | Approval of manual entries | Must | FIN-SCN-02 | 完成 | `it/JournalLifecycleIT`、`it/FinScn02IT`、`e2e/journal.spec.ts` |
| FIN-GL-016 | Supporting documents | Must | FIN-SCN-02 | 完成 | `it/AttachmentIT`、`it/FinScn02IT` |
| FIN-GL-017 | Recurring entries | Must | FIN-SCN-02 | 完成 | `it/JournalAutomationIT`、`it/FinScn02IT` |
| FIN-GL-018 | Automatic reversing entries | Must | FIN-SCN-06 | 完成 | `it/JournalAutomationIT` |
| FIN-GL-019 | Journal import | Must | FIN-SCN-06 | 完成 | `it/JournalImportIT`（验收 1；照常审批、控制科目、只导入一次）、`it/PayrollImportIT`（验收 2）、`e2e/imports.spec.ts` |
| FIN-GL-020 | Bulk journal entry grid | Must | FIN-SCN-02 | 完成 | `web/grid.test.ts`、`web/JournalGrid.test.tsx`、`e2e/journal.spec.ts`（粘贴 50 行） |
| FIN-GL-021 | Posting from subledgers | Must | FIN-SCN-03 | 完成 | 子账过账均自动生成总账行并链接来源（F3–F8）；`it/InvoiceIT`、`it/FinScn06IT` |
| FIN-GL-022 | Account inquiry | Must | FIN-SCN-06 | 完成 | `it/FinScn05IT`、`it/FinScn06IT`（1010 一月期初 250,000.00、期末 211,555.00） |
| FIN-PC-001 | Fiscal calendar | Must | FIN-SCN-01, FIN-SCN-11 | 完成 | `FiscalCalendarTest`、`it/PeriodStateIT` |
| FIN-PC-002 | Opening balances | Must | FIN-SCN-01 | 完成 | 期初分录与应收、应付、资产、银行期初项目（F2–F6）；`it/FinScn01IT`、`it/OpeningIT`、`it/FinScn06IT`（子账等于控制科目） |
| FIN-PC-003 | Period states | Must | FIN-SCN-06 | 完成 | 开放、软关、关闭与子账按期关闭（F1、F8）；`it/PeriodStateIT`、`it/FinScn07IT` |
| FIN-PC-004 | Close checklist | Must | FIN-SCN-06 | 完成 | `CloseChecksTest`、`it/CloseIT`（每项检查失败与通过、结果、时间、证据；手工任务与待办）、`it/FinScn06IT`（营运账户未调节时失败并指明，调节签核后通过） |
| FIN-PC-005 | Closing a period | Must | FIN-SCN-06 | 完成 | `it/FinScn06IT`（产物即 FIN-EXP-03、子账 = 控制科目、控制人与时刻；按关账时刻重跑哈希相同）、`it/CloseIT` |
| FIN-PC-006 | Governed reopen | Must | FIN-SCN-07 | 完成 | `it/FinScn07IT`（拒绝后仍关闭；批准后再关账，第二份产物引用第一份；按第一次关账时刻重跑相同）、`it/ReopenIT`（无规则、更晚的已关期间） |
| FIN-PC-007 | Prior-period items in the current period | Must | FIN-SCN-07 | 完成 | `it/FinScn07IT`（BILL-OS-0120）、`it/ReopenIT`（发票） |
| FIN-PC-008 | Year-end close | Must | FIN-SCN-11 | 完成 | `it/FinScn11IT`（CLS-2026、再年结 CLS-2026-R 与 CLS-2026-2、产物被取代、2027 损益为零）、`it/YearCloseIT` |
| FIN-PC-009 | Close status overview | Should | — | 完成 | `it/FinScn06IT`（2 / 10 未完成时先列出、负责权限与到期日）、`close/ClosePage.test.tsx`、`e2e/close.spec.ts` |
| FIN-AR-001 | Customer master | Must | FIN-SCN-01 | 完成 | `it/ReceivablesMasterIT`、`io/ReceivableRowsTest`、`it/InvoiceDocumentIT`（验收 2） |
| FIN-AR-002 | Payment terms | Must | FIN-SCN-03 | 完成 | `calc/PaymentTermsTest`（含属性测试）、`it/ReceivablesMasterIT`、`it/ReceivablesIT`（验收 2） |
| FIN-AR-003 | Customer invoice | Must | FIN-SCN-03 | 完成 | `it/InvoiceIT`（INV-1004…1007 = FIN-EXP-02）、`ar/InvoicePostingTest`（含属性测试） |
| FIN-AR-004 | Invoice numbering and immutability | Must | FIN-SCN-03 | 完成 | `it/InvoiceIT` |
| FIN-AR-005 | Invoice document | Must | FIN-SCN-03 | 完成 | `it/InvoiceDocumentIT`、`it/FinScn03IT`（验收 1） |
| FIN-AR-006 | Credit memos | Must | FIN-SCN-03 | 完成 | `it/InvoiceIT`（CM-2001）、`it/ReceivablesIT`（退款） |
| FIN-AR-007 | Cash receipts | Must | FIN-SCN-03 | 完成 | `it/ReceivablesIT`（RCPT-0001…0003 = FIN-EXP-02） |
| FIN-AR-008 | Application of receipts and credits | Must | FIN-SCN-03 | 完成 | `it/ReceivablesIT`（验收 1） |
| FIN-AR-009 | Customer statements | Must | FIN-SCN-03 | 完成 | 对账单数据与文件（F3d）；`it/ReceivablesIT` |
| FIN-AR-010 | Receivables aging | Must | FIN-SCN-03 | 完成 | `it/ReceivablesIT`（验收 1 未重估部分、验收 2、任意日期 = 1200） |
| FIN-AR-011 | Allowance for credit losses | Should | — | 完成 | `it/ReceivablesIT`（1,983.85，原值随 F7） |
| FIN-AR-012 | Write-off and recovery | Must | FIN-SCN-15 | 完成 | `it/ReceivablesIT` |
| FIN-AR-013 | Credit limit check | Should | — | 完成 | `it/InvoiceIT`（警告）、`it/ReceivablesIT`（审批） |
| FIN-AR-014 | Recurring invoices | Should | — | 完成 | `it/ReceivablesIT`（验收 1） |
| FIN-AR-015 | Deferred service revenue (simple) | Could | — | 不做（Could） | — |
| FIN-AP-001 | Vendor master | Must | FIN-SCN-01 | 完成 | `it/PayablesMasterIT`、`VendorRowsTest` |
| FIN-AP-002 | Tax identification (Form W-9) | Must | FIN-SCN-01 | 完成 | `it/PayablesMasterIT`、`TaxIdsTest` |
| FIN-AP-003 | Bank-detail change control | Must | FIN-SCN-04 | 完成 | `it/PayablesMasterIT`、`it/PaymentIT`、`BankNumbersTest` |
| FIN-AP-004 | Vendor bill | Must | FIN-SCN-04 | 完成 | `it/BillIT`、`BillPostingTest` |
| FIN-AP-005 | Duplicate bill control | Must | FIN-SCN-04 | 完成 | `it/BillIT`、`BillDuplicatesTest` |
| FIN-AP-006 | Bill approval | Must | FIN-SCN-04 | 完成 | `it/BillIT`、`it/PaymentIT` |
| FIN-AP-007 | Capitalization from bills | Must | FIN-SCN-04 | 完成 | `it/BillIT` |
| FIN-AP-008 | Vendor credits and prepayments | Must | FIN-SCN-15 | 完成 | `it/BillIT`、`it/PaymentIT` |
| FIN-AP-009 | Payables aging | Must | FIN-SCN-04 | 完成 | `it/BillIT`（合计 = 2000）、`it/PaymentIT`（= FIN-EXP-09，46,300.00） |
| FIN-AP-010 | Payment run proposal | Must | FIN-SCN-04 | 完成 | `it/PaymentIT` |
| FIN-AP-011 | Payment approval and release | Must | FIN-SCN-04 | 完成 | `it/PaymentIT` |
| FIN-AP-012 | Payment posting | Must | FIN-SCN-04 | 完成 | `it/PaymentIT`（PAY-RUN-01、PAY-RUN-02 = FIN-EXP-02） |
| FIN-AP-013 | Payment methods and files | Must | FIN-SCN-04 | 完成 | `it/PaymentIT`、`NachaWriterTest`（含性质测试）、`CheckFilesTest` |
| FIN-AP-014 | Voids and stopped payments | Must | FIN-SCN-15 | 完成 | `it/PaymentIT` |
| FIN-AP-015 | Manual payments and non-vendor payments | Must | FIN-SCN-04 | 完成 | `it/PaymentIT`（STX-PAY-2512） |
| FIN-AP-020 | 1099 classification | Must | FIN-SCN-04 | 完成 | `it/FinScn04IT`、`Form1099AllocationTest` |
| FIN-AP-021 | Reportable amounts and thresholds | Must | FIN-SCN-04 | 完成 | `it/PayablesMasterIT`、`it/FinScn04IT`（= FIN-EXP-14；2027 年阈值 600.00） |
| FIN-AP-022 | 1099 outputs | Must | FIN-SCN-04 | 完成 | `it/FinScn04IT`、`Form1099AllocationTest` |
| FIN-AP-023 | 1099 corrections | Should | — | 完成 | `it/FinScn04IT` |
| FIN-BK-001 | Bank accounts | Must | FIN-SCN-01 | 完成 | `it/BankStatementIT`（1010、1050；账号遮蔽，Treasurer 逐值显示） |
| FIN-BK-002 | Bank transfers | Must | FIN-SCN-15 | 完成 | `it/BankStatementIT`（50,000.00：1050 减、1010 增；在途与作废）；两边的匹配随 F5b |
| FIN-BK-003 | Statement import | Must | FIN-SCN-05 | 完成 | `it/BankStatementIT`（10 行、256,555.00；同一文件 409；BAI2 与 camt.053 说明已记录、不增加；合计不符、不衔接、重叠、他人账户拒收）、`Bai2ParserTest`、`Camt053ParserTest`、`StatementCheckTest` |
| FIN-BK-004 | Automatic matching | Must | FIN-SCN-05 | 完成 | `BankMatcherTest`（1 月的 8 个匹配、原因与置信度、性质测试）、`it/BankMatchIT` |
| FIN-BK-005 | Manual matching and bank-originated entries | Must | FIN-SCN-05 | 完成 | `it/BankMatchIT`（BANK-FEE-2601、BANK-INT-2601 已过账并匹配）、页面 `bank/MatchingPage.test.tsx`、`e2e/bank.spec.ts` |
| FIN-BK-006 | Match history | Must | FIN-SCN-05 | 完成 | `it/BankMatchIT` |
| FIN-BK-007 | Reconciliation | Must | FIN-SCN-05 | 完成 | `it/BankReconciliationIT`（差额 10.00 不能完成；之前未签核的月份先签核）、`it/FinScn05IT`（= FIN-EXP-10） |
| FIN-BK-008 | Reconciliation report and sign-off | Must | FIN-SCN-05 | 完成 | `it/BankReconciliationIT`（准备人不能签核；无规则不能完成；审批中账面变化回到准备；2 月过账后重印相同）、`it/FinScn05IT`、页面 `bank/ReconciliationPage.test.tsx`、`e2e/bank.spec.ts` |
| FIN-BK-009 | Outstanding items carried forward | Should | — | 完成 | `it/BankReconciliationIT`（95 天的支票被列出） |
| FIN-BK-010 | Cash position | Should | — | 完成 | `it/FinScn05IT`（1010 账面 211,555.00、对账单 256,555.00；P-7902 22,000.00 于 2026-02-08 到期）、`it/BankReconciliationIT` |
| FIN-BK-011 | Payment files and positive pay | Must | FIN-SCN-04 | 完成 | `it/PaymentIT` |
| FIN-FA-001 | Asset classes | Must | FIN-SCN-01 | 完成 | `it/AssetIT`（FA-003 取 Computer equipment 36 月直线；门槛以下的账单行被拒） |
| FIN-FA-002 | Asset register | Must | FIN-SCN-01 | 完成 | `it/AssetIT`、`it/AssetOpeningAfterBillsIT`、`it/FinScn01IT`（资产子账 = 1500 / 1510 / 1520 / 1590） |
| FIN-FA-003 | Depreciation methods | Must | FIN-SCN-06 | 完成 | `DepreciationTest`（FA-001 2,000.00、FA-002 1,666.67、转直线）、`it/DepreciationIT`（工作量 150 / 1,000 单位 = 1,500.00；未录用量整体拒收） |
| FIN-FA-004 | Conventions and rounding | Must | FIN-SCN-06 | 完成 | `DepreciationTest`（FA-003 1 月 333.33、全寿命 12,000.00；属性测试：全寿命 = 成本 − 残值） |
| FIN-FA-005 | Depreciation run | Must | FIN-SCN-06 | 完成 | `it/DepreciationIT`（DEP-2601 = FIN-EXP-11 的 6700 / 1590 4,000.00，明细 2,000.00 / 1,666.67 / 333.33；再运行不过账；已结期间、跳月、未分类拒绝；冲回后 DEP-2601-2） |
| FIN-FA-006 | Changes in estimates | Must | FIN-SCN-15 | 完成 | `DepreciationTest`、`it/DepreciationIT`（FA-001 自 2026-02 延长 12 个月：1,489.36，1 月不变；DEP-2602 取新条件） |
| FIN-FA-007 | Disposal | Must | FIN-SCN-15 | 完成 | `it/DepreciationIT`（FA-002 1 月运行后以 60,000.00 出售：收益 1,666.67；报废损失 11,333.34；之前月份未运行拒绝） |
| FIN-FA-008 | Impairment | Could | — | 不做（Could） | — |
| FIN-FA-009 | Asset reports | Must | FIN-SCN-06 | 完成 | `it/FinScn01IT`（FIN-EXP-11；滚动表 190,000.00 → 202,000.00、58,000.00 → 62,000.00；FA-003 预测合计 12,000.00）、`it/DepreciationIT`（1–3 月与总账一致）、`assets.spec.ts` |
| FIN-FA-010 | Tax depreciation book | Could | — | 不做（Could） | — |
| FIN-FX-001 | Currencies | Must | FIN-SCN-01 | 完成 | `it/SetupAndMasterDataIT` |
| FIN-FX-002 | Exchange rates | Must | FIN-SCN-01, FIN-SCN-09 | 完成 | `it/SetupAndMasterDataIT` |
| FIN-FX-003 | Foreign-currency documents | Must | FIN-SCN-09 | 完成 | `it/FxReceivablesIT`、`gl/JournalValidatorTest`、`it/FxPayablesIT` |
| FIN-FX-004 | Realized gains and losses | Must | FIN-SCN-09 | 完成 | `it/FxReceivablesIT`、`it/FxPayablesIT` |
| FIN-FX-005 | Period-end remeasurement | Must | FIN-SCN-06, FIN-SCN-09 | 完成 | `it/FinScn09IT` |
| FIN-FX-006 | Foreign-currency bank accounts | Should | — | 部分（Should） | 外币银行账户不在范围内：只有本位币银行账户；外币重估覆盖应收应付（`it/FinScn09IT`） |
| FIN-FX-007 | Currency reports | Must | FIN-SCN-09 | 完成 | `it/FinScn09IT`、`it/FxReceivablesIT`、`it/FxPayablesIT` |
| FIN-TX-001 | Jurisdictions and rates | Must | FIN-SCN-01 | 完成 | `tax/TaxRatesTest`、`it/ReceivablesMasterIT`、`io/ReceivableRowsTest` |
| FIN-TX-002 | Customer and line taxability | Must | FIN-SCN-03 | 完成 | `calc/SalesTaxTest`、`it/InvoiceIT`、`it/FinScn03IT` |
| FIN-TX-003 | Tax calculation and rounding | Must | FIN-SCN-03 | 完成 | `calc/SalesTaxTest`、`it/InvoiceIT` |
| FIN-TX-004 | Exemption certificates | Must | FIN-SCN-03 | 完成 | `calc/SalesTaxTest`、`it/ReceivablesMasterIT`、`it/InvoiceIT` |
| FIN-TX-005 | Credits and returns | Must | FIN-SCN-03 | 完成 | `it/InvoiceIT`（165.00） |
| FIN-TX-006 | Tax payable accounts | Must | FIN-SCN-03 | 完成 | `it/ReceivablesIT`（3,135.00） |
| FIN-TX-007 | Use tax on purchases | Should | — | 完成 | `it/BillIT`（82.50）、`BillPostingTest` |
| FIN-TX-008 | Return data | Must | FIN-SCN-03 | 完成 | `it/ReceivablesIT`（= FIN-EXP-13） |
| FIN-RP-001 | Trial balance | Must | FIN-SCN-06 | 完成 | `it/PeriodBalanceIT`（FIN-EXP-03 合计 826,012.90；与逐行汇总相同；重开后按两个时刻）、`it/FinScn11IT`（年结后全年） |
| FIN-RP-002 | Balance sheet | Must | FIN-SCN-06 | 完成 | `it/FinStatementsIT`（FIN-EXP-05；2150 未映射时拒绝签发） |
| FIN-RP-003 | Income statement | Must | FIN-SCN-06 | 完成 | `it/FinStatementsIT`（FIN-EXP-04，净利润 5,127.10） |
| FIN-RP-004 | Statement of cash flows | Must | FIN-SCN-06 | 完成 | `it/FinCashFlowIT`（FIN-EXP-06，赊购服务器 12,000.00 为非现金活动，净减少 38,320.00） |
| FIN-RP-005 | Statement of stockholders' equity | Must | FIN-SCN-06 | 完成 | `it/FinStatementsIT`（FIN-EXP-07） |
| FIN-RP-006 | Drill-down | Must | FIN-SCN-06 | 完成 | `it/FinScn06IT`（专业费 34,000.00 → BILL-DC-2601、BILL-JR-014、JE-0002）、`reports/StatementPage.test.tsx`、`e2e/reports.spec.ts` |
| FIN-RP-007 | Subledger reconciliation reports | Must | FIN-SCN-06 | 完成 | `it/FinScn06IT`（2026-01-31 差额 0.00） |
| FIN-RP-008 | General ledger and journal reports | Must | FIN-SCN-06 | 完成 | `it/FinScn06IT` |
| FIN-RP-009 | Report runs are reproducible | Must | FIN-SCN-07, FIN-SCN-08 | 完成 | `it/FinScn08IT`、`it/FinStatementsIT`、`it/FinCashFlowIT`（签发后核对相同） |
| FIN-RP-010 | Export formats | Must | FIN-SCN-06 | 完成 | `it/FinScn06IT`（利润表导出 PDF 与 Excel）、`e2e/reports.spec.ts`（PDF 下载） |
| FIN-RP-011 | Report layouts | Should | — | 完成 | `it/FinStatementsIT`（旧版本签发的报表核对相同）、`report/StatementLayoutTest` |
| FIN-RP-012 | Notes support | Should | — | 完成 | `it/FinCashFlowIT`（各科目期初 + 变动 = 期末，合计等于资产负债表各行） |
| FIN-RP-020 | "As of" and "as known on" reporting | Must | FIN-SCN-07, FIN-SCN-08 | 完成 | `it/FinScn08IT`（FIN-EXP-16；签发的报表核对相同） |
| FIN-RP-021 | Dashboard | Should | — | 完成 | `reports/StatementPage.test.tsx`（311,680.00、158,735.00、46,300.00 与链接）、`e2e/reports.spec.ts` |
| FIN-CT-001 | Segregation of duties | Must | FIN-SCN-02 | 完成 | 准备人不能审批、冲突规则与冲突报告（平台 D23）；`it/JournalLifecycleIT`、`it/SodAdminIT`、`it/PayablesMasterIT` |
| FIN-CT-002 | Approval rules are versioned | Must | FIN-SCN-02, FIN-SCN-10 | 完成 | `it/FinScn02IT`、`it/JournalLifecycleIT`、`it/FinScn10IT`（阈值 5,000.00 生效 2026-03-01，1 月仍为第 1 版） |
| FIN-CT-003 | Approval integrity | Must | FIN-SCN-02 | 完成 | `it/JournalLifecycleIT` |
| FIN-CT-004 | Impact preview of rule changes | Should | FIN-SCN-10 | 完成 | `it/FinScn10IT`（评估 3 笔、0 笔不同） |
| FIN-CT-005 | Suspense and clearing accounts | Must | FIN-SCN-06 | 完成 | `it/ChartOfAccountsIT`、`it/CloseIT`（暂记科目）、`it/FinScn06IT`（未核销收款 1250 使清单失败） |
| FIN-CT-010 | Complete audit trail | Must | FIN-SCN-02, FIN-SCN-12 | 完成 | `it/PayablesMasterIT`（验收 1）、`it/FinScn12IT`（验收 2） |
| FIN-CT-011 | Tamper evidence | Must | FIN-SCN-12 | 完成 | `it/FinScn12IT` |
| FIN-CT-012 | Audit evidence package | Should | FIN-SCN-12 | 完成 | `it/FinScn12IT`、`audit/AuditPackagePage.test.tsx`、`e2e/controls.spec.ts` |
| FIN-SC-001 | Authentication | Must | FIN-SCN-04 | 完成 | `it/PayablesMasterIT`、`it/PaymentIT`、`it/FinScn04IT` |
| FIN-SC-002 | Role-based access | Must | FIN-SCN-01, FIN-SCN-12 | 完成 | 角色授予、金额审批限额、按期限的审计师；`it/ControlsIT` |
| FIN-SC-003 | Access review | Must | FIN-SCN-12 | 完成 | `it/FinScn12IT` |
| FIN-SC-004 | Sensitive data protection | Must | FIN-SCN-01 | 部分（验收满足） | 遮蔽与显示记录满足验收；到期后的个人数据匿名化不做（F10 D9）；静态加密由数据库与磁盘负责（install.md） |
| FIN-SC-005 | Configuration change control | Should | FIN-SCN-10 | 完成 | `config/ConfigPackageTest`、`it/ConfigPromotionIT` |
| FIN-CT-020 | Retention | Must | FIN-SCN-12 | 完成 | 保留策略与法律保全（平台 D27）；`it/ControlsIT` |
| FIN-CT-021 | Readable archives | Must | FIN-SCN-12 | 完成 | `it/FinScn12IT` |
| FIN-DI-001 | Master-data import | Must | FIN-SCN-01 | 完成 | 科目、客户、供应商、税码、汇率、资产导入（F2–F6）；`it/FinScn01IT`、`it/FinanceImportIT` |
| FIN-DI-002 | Migration of open items and history | Must | FIN-SCN-01 | 完成 | 期初与未结项目迁移、控制总数调节表（`finance.migration.reconciliation`）；`it/FinScn01IT`、`it/OpeningIT` |
| FIN-DI-003 | Data-quality decisions | Should | — | 完成 | 客户合并的决定记入迁移报告（F3a）；`it/ReceivablesMasterIT` |
| FIN-DI-004 | Payroll journal import | Must | FIN-SCN-06 | 完成 | `it/PayrollImportIT`（PAYROLL-2601 = FIN-EXP-02）、`PayrollLinesTest`（含属性测试） |
| FIN-DI-005 | Application programming interface | Must | FIN-SCN-13 | 完成 | `it/FinScn13IT`（接口建的账单与页面一样过账待审批；无权限的过账 403） |
| FIN-DI-006 | Idempotent interfaces | Must | FIN-SCN-13 | 完成 | `it/FinScn13IT`（同键两次建账单、发票各只一张） |
| FIN-DI-007 | Events for integration | Should | — | 部分（验收满足） | 发票过账事件经平台 webhook（D33）签名投递：`it/FinDi007IT`（INV-1004 一次、号码与合计）；审批可订阅平台的 `jabiz.approval.*` 事件；关账事件未发布 |
| FIN-DI-008 | Full data export | Must | FIN-SCN-12 | 部分（验收满足） | 已过账行数等于系统内（`it/FinScn12IT`）；附件内容与审计记录不在导出包中，经各自接口取（api.md §5） |
| FIN-DI-009 | Notifications | Must | FIN-SCN-02 | 完成 | `it/FinScn02IT`（JE-0002 的待办与带链接的邮件） |
| FIN-NF-001 | Volumes | Must | FIN-SCN-14 | 部分 | 1/10 数据量 50 人达标；全量（NF-001）数据已造成，压测与批处理计时由使用方复跑（perf.md §8） |
| FIN-NF-002 | Response times | Must | FIN-SCN-14 | 部分 | 同上 |
| FIN-NF-003 | Correctness under concurrency | Must | FIN-SCN-14 | 完成 | `it/PeriodLockIT`；`tools/finance/concurrency`（20 人 30 分钟、两次 `kill -9`，结果见 perf.md §7） |
| FIN-NF-004 | Availability | Should | — | 待使用方（Should） | 可用性需一个月的运行测量；部署、备份与监控见 install.md、operations.md |
| FIN-NF-005 | Backup and recovery | Must | FIN-SCN-14 | 完成（开发环境） | `tools/finance/ops/drill.sh restore`（operations.md §4）；全量恢复时间由使用方演练 |
| FIN-UI-001 | Professional workbench | Must | FIN-SCN-14 | 待使用方 | 易用性研究（≥ 8 人、SUS ≥ 75）由使用方组织，任务脚本与问卷见 usability.md |
| FIN-UI-002 | Keyboard-first entry | Must | FIN-SCN-02 | 完成 | 五行账单只用键盘（`e2e/payables.spec.ts`）；日记账、发票、收款同样 |
| FIN-UI-003 | Grid entry | Must | FIN-SCN-02 | 完成 | `web/grid.test.ts`、`web/JournalGrid.test.tsx`、`e2e/journal.spec.ts`；验收中的可用性研究（50 行 ≤ 5 分钟）需由人进行 |
| FIN-UI-004 | Registers and drill-down | Must | FIN-SCN-06 | 完成 | `web/JournalListPage.test.tsx`、`it/GlReportsIT`、`e2e/journal.spec.ts`、`it/FinScn03IT`（1 月发票登记簿合计）、`receivables/InvoiceListPage.test.tsx`、`e2e/receivables.spec.ts`；账单、付款批、付款登记簿（`queries/finance/ap/bill_register.sql`、`p… |
| FIN-UI-005 | Financial number presentation | Must | FIN-SCN-06 | 完成 | `reports/StatementPage.test.tsx`（(2,000.00)）、`e2e/reports.spec.ts` |
| FIN-UI-006 | Close workspace | Must | FIN-SCN-06 | 完成 | `close/ClosePage.test.tsx`（一步关账；被拒时逐项列出）、`e2e/close.spec.ts` |
| FIN-UI-007 | Explanations | Must | FIN-SCN-03 | 部分（验收满足） | 税额说明满足验收（`it/InvoiceIT`）；折旧、汇兑、审批要求的说明分散在各页面，没有统一的"为什么" |
| FIN-UI-008 | History view | Must | FIN-SCN-08 | 完成 | 平台历史时间线；`e2e/history.spec.ts`（C100 式的客户两次修改） |
| FIN-UI-009 | Accessibility | Must | FIN-SCN-14 | 待使用方（自动检查通过） | 验收为外部审计（usability.md §4）；自动检查（axe，WCAG 2.2 A/AA）通过：`e2e/a11y.spec.ts`（19 个财务页面）与平台 `frontend/e2e/a11y.spec.ts` |
| FIN-UI-010 | Browsers and locale | Must | FIN-SCN-14 | 完成 | CI 以 Chromium、Firefox、WebKit、Edge 运行全部 e2e（后三者 1366 × 768），2026-10-04 全部通过；e2e 覆盖 usability.md 的任务卡 T1–T11（§5） |
| FIN-NF-006 | Installation and upgrade | Must | FIN-SCN-14 | 完成 | install.md；`tools/finance/ops/drill.sh upgrade` |
| FIN-NF-007 | Monitoring | Must | FIN-SCN-14 | 完成 | 平台监控；CI 日志扫描（`scan-logs.py`） |
