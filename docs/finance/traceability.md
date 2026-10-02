# 需求追踪：需求 → 实现 → 测试

逐条记录每个需求由哪些实体、流程、模板、页面实现，由哪些测试验证（设计 §19、计划 §5.2）。各阶段完成时补上本阶段的需求；
"部分"表示本阶段只做了其中一部分，余下的阶段写在备注中。需求副本（`docs/finance-requirements/`）只读，编号与标题取自其中。

路径简写：`gl/` = `backend/finance/src/main/java/com/jabiz/finance/gl/`，`it/` = `backend/finance/src/test/java/com/jabiz/finance/it/`，
`queries/` = `backend/finance/src/main/resources/queries/finance/gl/`，`web/` = `finance-web/src/journal/`，`receivables/` = `finance-web/src/receivables/`，`e2e/` = `finance-web/e2e/`。

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
| FIN-UI-002 | Keyboard-first entry | 部分 | 网格的键盘操作与快捷键（Ctrl+S / Ctrl+Enter / Ctrl+D / Ctrl+Z / Alt+N）、科目联想、金额不用分隔符 | `web/JournalGrid.test.tsx`、`web/JournalEntryPage.test.tsx`、`e2e/journal.spec.ts`（只用键盘）；发票与收款：`receivables/InvoicePage.test.tsx`、`ReceiptPage.test.tsx`、`e2e/receivables.spec.ts`；账单（5 行，只用键盘，验收 1）：`payables/BillPage.test.tsx`、`e2e/payables.spec.ts` |
| FIN-UI-003 | Grid entry | 完成 | 粘贴与复制到表格、向下填充、撤销与重做 | `web/grid.test.ts`、`web/JournalGrid.test.tsx`、`e2e/journal.spec.ts`；验收中的可用性研究（50 行 ≤ 5 分钟）需由人进行 |
| FIN-UI-004 | Registers and drill-down | 部分 | 日记账登记簿（`queries/journal_register.sql`、`web/JournalListPage.tsx`：排序、筛选、合计、打开单据）；发票、收款登记簿（`queries/finance/ar/invoice_register.sql`、`receipt_register.sql`；`receivables/InvoiceListPage.tsx`、`ReceiptListPage.tsx`） | `web/JournalListPage.test.tsx`、`it/GlReportsIT`、`e2e/journal.spec.ts`、`it/FinScn03IT`（1 月发票登记簿合计）、`receivables/InvoiceListPage.test.tsx`、`e2e/receivables.spec.ts`；账单、付款批、付款登记簿（`queries/finance/ap/bill_register.sql`、`payment_run_register.sql`、`payment_register.sql`；`payables/BillListPage.tsx`、`RunListPage.tsx`、`PaymentListPage.tsx`）：`payables/registers.test.tsx`、`e2e/payables.spec.ts`、`it/PaymentIT`；银行登记簿与报表钻取在 F5–F9 |
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
| FIN-AR-001 | Customer master | 完成 | `ar/ArEntities`（`FinCustomer`，预定生效的地址）、`ar/CustomerProcesses`（`FIN_CUSTOMER_SAVE`）、`io/ReceivablesImports`（`finance.customers`）；重印按发票日的地址：`ar/InvoiceDocuments` | `it/ReceivablesMasterIT`、`io/ReceivableRowsTest`、`it/InvoiceDocumentIT`（验收 2） |
| FIN-AR-002 | Payment terms | 完成 | `calc/PaymentTerms`、`FinPaymentTerms`、`FIN_PAYMENT_TERMS_SAVE`；折扣：`ar/ReceiptProcesses`、`queries/finance/ar/receipt_suggestions.sql` | `calc/PaymentTermsTest`（含属性测试）、`it/ReceivablesMasterIT`、`it/ReceivablesIT`（验收 2） |
| FIN-TX-001 | Jurisdictions and rates | 完成 | `tax/TaxEntities`、`tax/TaxRates`、`tax/TaxProcesses`（`FIN_TAX_RATE_SET`、`FIN_TAX_CODE_SAVE`）、`io/TaxCodeRows` | `tax/TaxRatesTest`、`it/ReceivablesMasterIT`、`io/ReceivableRowsTest` |
| FIN-TX-002 | Customer and line taxability | F3a 计算（发票在 F3b） | `calc/SalesTax` | `calc/SalesTaxTest` |
| FIN-TX-003 | Tax calculation and rounding | F3a 计算（发票在 F3b） | `calc/SalesTax`（每张单据 × 辖区舍入、最大余数分摊） | `calc/SalesTaxTest`（含属性测试） |
| FIN-TX-004 | Exemption certificates | F3a 证书与报告（发票引用在 F3b） | `FinExemptionCertificate`、`FIN_EXEMPTION_CERTIFICATE_SAVE`、`FinArSettings.missingCertificate`、`queries/finance/ar/certificates.sql` | `calc/SalesTaxTest`、`it/ReceivablesMasterIT` |
| FIN-DI-003 | Data-quality decisions | 科目（F2a）、客户合并（F3a） | `migration/MigrationProcesses`（`CUSTOMER`）、`FIN_CUSTOMER_SAVE` 跳过并入的代码 | `it/ReceivablesMasterIT` |
| FIN-AR-003 | Customer invoice | 完成 | `ar/InvoiceEntities`、`ar/InvoiceProcesses`（`FIN_INVOICE_SAVE`、`FIN_INVOICE_POST`）、`ar/InvoicePosting` | `it/InvoiceIT`（INV-1004…1007 = FIN-EXP-02）、`ar/InvoicePostingTest`（含属性测试） |
| FIN-AR-005 | Invoice document | 完成 | `ar/InvoiceDocuments`（版式 `finance.ar.invoice` / `finance.ar.credit_memo`、`FIN_INVOICE_ISSUE`、`FIN_INVOICE_SEND`）、`company/`（`FinCompanyProfile`、`FIN_COMPANY_PROFILE_SET`）、`queries/finance/ar/invoice_document_*.sql`、`queries/finance/company/profile.sql`；平台 `DOCUMENT_ISSUE` / `DOCUMENT_SEND`（D30） | `it/InvoiceDocumentIT`、`it/FinScn03IT`（验收 1） |
| FIN-AR-004 | Invoice numbering and immutability | 完成 | `NumberSequence` `fin.ar.invoice` / `fin.ar.credit-memo`、`FIN_INVOICE_VOID`、`gl/SubledgerPosting`（`FIN_SUBLEDGER_REVERSE`） | `it/InvoiceIT` |
| FIN-AR-006 | Credit memos | 完成 | `FinInvoice`（`CREDIT_MEMO`）、`FIN_CREDIT_APPLY`、`FIN_CREDIT_REFUND`、`FinApplication` | `it/InvoiceIT`（CM-2001）、`it/ReceivablesIT`（退款） |
| FIN-AR-013 | Credit limit check | 完成 | `FIN_INVOICE_POST`（审批对象 `fin.ar.invoice`）、`FinArSettings.creditLimitCheck`、`FIN_INVOICE_APPROVAL_RESULT` | `it/InvoiceIT`（警告）、`it/ReceivablesIT`（审批） |
| FIN-TX-005 | Credits and returns | 完成 | `FIN_INVOICE_POST`（原发票日期的税率） | `it/InvoiceIT`（165.00） |
| FIN-GL-021 | Posting from subledgers | 应收完成 | `gl/SubledgerPosting`（`FIN_SUBLEDGER_POST`，账本交易引用单据、`FinPosting`） | `it/InvoiceIT` |
| FIN-UI-007 | Explanations | 部分（销售税与使用税的说明完成；其他计算的说明随各模块） | `FinInvoiceTax`、`FinBillTax`；发票页与账单页的计算说明（`receivables/InvoiceView.tsx`、`payables/BillView.tsx`） | `it/InvoiceIT`、`receivables/InvoicePage.test.tsx`、`e2e/receivables.spec.ts`、`payables/BillPage.test.tsx` |
| FIN-DI-002 | Migration of open items | 应收完成 | `FIN_AR_OPENING`、`finance.open_receivables`、`reconciliation.sql`（`OPEN_ITEMS`） | `it/InvoiceIT` |
| FIN-AR-007 | Cash receipts | 完成（外币在 F7） | `ar/ReceiptEntities`（`FinReceipt`）、`ar/ReceiptProcesses`（`FIN_RECEIPT_RECORD`、`FIN_RECEIPT_VOID`） | `it/ReceivablesIT`（RCPT-0001…0003 = FIN-EXP-02） |
| FIN-AR-008 | Application of receipts and credits | 完成 | `FIN_RECEIPT_APPLY`、`FIN_APPLICATION_REVERSE`、`FIN_RECEIPT_REASSIGN`、`queries/finance/ar/receipt_suggestions.sql` | `it/ReceivablesIT`（验收 1） |
| FIN-AR-009 | Customer statements | 数据完成（文件在 F3d） | `queries/finance/ar/statement.sql`、`aging.sql`（单个客户） | `it/ReceivablesIT`（C300 未结项目、C100 期间） |
| FIN-AR-010 | Receivables aging | 完成（INV-1005 重估在 F7） | `queries/finance/ar/aging.sql` | `it/ReceivablesIT`（验收 1 未重估部分、验收 2、任意日期 = 1200） |
| FIN-AR-011 | Allowance for credit losses | 完成 | `queries/finance/ar/allowance_suggestion.sql`、`FinArSettings` 损失率 | `it/ReceivablesIT`（1,983.85，原值随 F7） |
| FIN-AR-012 | Write-off and recovery | 完成 | `ar/WriteOffProcesses`（审批对象 `fin.ar.write-off`、规则 `FIN-WRITE-OFF`） | `it/ReceivablesIT` |
| FIN-AR-014 | Recurring invoices | 完成 | `FinRecurringInvoice`、`ar/RecurringInvoiceProcesses`、定时任务 `fin.recurring-invoices` | `it/ReceivablesIT`（验收 1） |
| FIN-TX-006 | Tax payable accounts | 完成（缴款在 F4 为付款） | 一个 2200，辖区明细在 `FinInvoiceTax` | `it/ReceivablesIT`（3,135.00） |
| FIN-TX-008 | Return data | 完成 | `queries/finance/tax/sales_tax.sql`、`sales_tax_return.sql` | `it/ReceivablesIT`（= FIN-EXP-13） |

## F4 应付、付款与 1099

路径简写：`ap/` = `backend/finance/src/main/java/com/jabiz/finance/ap/`，`bank/` = `…/finance/bank/`，`calc/` = `…/finance/calc/`，`io/` = `…/finance/io/`。

| 需求 | 标题 | 状态 | 实现 | 测试 |
|---|---|---|---|---|
| FIN-AP-001 | Vendor master | 完成 | `FinVendor`（迁移 V10）、`FIN_VENDOR_SAVE`（`ap/VendorProcesses`）；导入 `finance.vendors`（`io/PayablesImports`、`io/VendorRows`） | `it/PayablesMasterIT`、`VendorRowsTest` |
| FIN-AP-002 | Tax identification (Form W-9) | 完成（验收 2 的警告在 F4b 的过账中） | `FinVendorTaxInfo`（TIN `TAX_ID` 遮蔽，平台 14k）、`FIN_VENDOR_TAX_SAVE`、`calc/TaxIds` | `it/PayablesMasterIT`、`TaxIdsTest` |
| FIN-AP-003 | Bank-detail change control | 完成 | `FinVendorBankAccount`、`FIN_VENDOR_BANK_CHANGE` / `_APPROVAL_RESULT`（审批对象 `fin.ap.vendor-bank`、规则 `FIN-VENDOR-BANK`）、`calc/BankNumbers`；付款建议与释放中的暂停（`ap/PaymentProcesses`） | `it/PayablesMasterIT`、`it/PaymentIT`、`BankNumbersTest` |
| FIN-AP-021 | Reportable amounts and thresholds | 完成 | `Fin1099Threshold`、`FIN_1099_THRESHOLD_SET`、导入 `finance.ap_thresholds`；`Fin1099Amount`（迁移 V13）、报表 `finance.ap.form_1099` | `it/PayablesMasterIT`、`it/FinScn04IT`（= FIN-EXP-14；2027 年阈值 600.00） |
| FIN-CT-001 | Segregation of duties | 部分（应付：F4a 的两条规则与冲突报告） | `FIN_SETUP` 提出 `FIN-SOD-VENDOR-BANK-RELEASE`、`FIN-SOD-PAYABLES-RELEASE`（平台 14b） | `it/PayablesMasterIT`（验收 2） |
| FIN-CT-010 | Complete audit trail | 部分（银行信息变更） | 平台审计（遮蔽字段存遮蔽形式）；`FinVendorBankAccount.requestedBy` / `decidedBy` | `it/PayablesMasterIT`（验收 1） |
| FIN-SC-001 | Authentication | 部分（应付：银行信息变更与付款释放；会话与单点登录由平台 14g） | `FIN_VENDOR_BANK_CHANGE`、`FIN_BANK_ACCOUNT_SAVE`、`FIN_PAYMENT_RUN_RELEASE` 的 `requiresMfa(ALWAYS)` | `it/PayablesMasterIT`、`it/PaymentIT` |
| FIN-SC-004 | Sensitive data protection | 部分（TIN、银行账号） | `masked(…)`、`sys_reveal_record`；W-9 文件策略的读取权限 | `it/PayablesMasterIT` |
| FIN-AP-004 | Vendor bill | 完成 | `FinBill`、`FinBillLine`（迁移 V11）、`FIN_BILL_SAVE` / `_POST`（`ap/BillProcesses`、`ap/BillPosting`） | `it/BillIT`、`BillPostingTest` |
| FIN-AP-005 | Duplicate bill control | 完成 | `calc/BillDuplicates`，保存与过账时检查 | `it/BillIT`、`BillDuplicatesTest` |
| FIN-AP-006 | Bill approval | 完成 | 审批对象 `fin.ap.bill`、规则 `FIN-AP-BILL-10K`、`FIN_BILL_APPROVAL_RESULT`；未批准的账单在付款中暂停 | `it/BillIT`、`it/PaymentIT` |
| FIN-AP-007 | Capitalization from bills | 完成 | `FinAsset`、`FIN_ASSET_CREATE`（`fa/`） | `it/BillIT` |
| FIN-AP-008 | Vendor credits and prepayments | 完成 | 种类 CREDIT、`FIN_AP_APPLY`、`FinApApplication`；预付款为付款批的行（`PREPAYMENT`），`FIN_AP_PREPAYMENT_APPLY` | `it/BillIT`、`it/PaymentIT` |
| FIN-AP-009 | Payables aging | 完成 | `queries/finance/ap/aging.sql` | `it/BillIT`（合计 = 2000）、`it/PaymentIT`（= FIN-EXP-09，46,300.00） |
| FIN-TX-007 | Use tax | 完成 | 行的使用税码、`FinBillTax`、`FinApSettings.useTaxAccount` | `it/BillIT`（82.50）、`BillPostingTest` |
| FIN-AP-010 | Payment run proposal | 完成 | `FinPaymentRun`、`FinPaymentLine`（迁移 V12）、`FIN_PAYMENT_RUN_PROPOSE` / `_ADD` / `_REMOVE`（暂停与原因、提前付款折扣） | `it/PaymentIT` |
| FIN-AP-011 | Payment approval and release | 完成 | 审批对象 `fin.ap.payment-run`、规则 `FIN-AP-PAYMENT`、`FIN_PAYMENT_RUN_SUBMIT` / `_APPROVAL_RESULT` / `_RELEASE` / `_CANCEL`（内容哈希锁定） | `it/PaymentIT` |
| FIN-AP-012 | Payment posting | 完成 | `FinPayment`、`FIN_PAYMENT_RECORD`（内部）、`FinApApplication`（`PAYMENT`） | `it/PaymentIT`（PAY-RUN-01、PAY-RUN-02 = FIN-EXP-02） |
| FIN-AP-013 | Payment methods and files | 完成（pain.001 不做，F4 计划 D6） | `calc/NachaWriter`、`calc/NachaValidator`、`calc/CheckFiles`、`calc/WireFile`、`FIN_PAYMENT_FILE_GENERATE`（`ap/PaymentFiles`） | `it/PaymentIT`、`NachaWriterTest`（含性质测试）、`CheckFilesTest` |
| FIN-AP-014 | Voids and stopped payments | 完成 | `FIN_PAYMENT_VOID`（冲正、相反的核销） | `it/PaymentIT` |
| FIN-AP-015 | Manual payments and non-vendor payments | 完成 | 付款批的其他付款行（非控制科目，支票或 MANUAL 批），同一审批规则 | `it/PaymentIT`（STX-PAY-2512） |
| FIN-BK-011 | Payment files and positive pay | 完成（应付） | `FinPaymentFile`、`FIN_PAYMENT_FILE_GENERATE` / `_CANCEL`、生成文件存档（平台 14k） | `it/PaymentIT` |
| FIN-AP-020 | 1099 classification | 完成 | 供应商与账单行的表与栏（F4a、F4b）；`FIN_PAYMENT_RECORD` 按行分摊（`calc/Form1099Allocation`）；方式 CARD 不计入 | `it/FinScn04IT`、`Form1099AllocationTest` |
| FIN-AP-022 | 1099 outputs | 完成（州金额随联合申报列给出） | 审核报表 `finance.ap.form_1099_review`、`FIN_1099_ISSUE`（单据 `finance.ap.form_1099`）、`FIN_1099_EXPORT`（`calc/Form1099File`、`Fin1099Filing`） | `it/FinScn04IT`、`Form1099AllocationTest` |
| FIN-AP-023 | 1099 corrections | 完成 | `FIN_1099_CORRECT`（与最后一次申报比较，CORRECTED） | `it/FinScn04IT` |
| FIN-SCN-04 | Bills to payment with Form 1099 | 完成 | F4a–F4d | `it/FinScn04IT`（账龄 = FIN-EXP-09，1099 = FIN-EXP-14，PAY-RUN-02 的文件不能生成两次） |
| FIN-SCN-05 | Bank reconciliation | 完成 | F5a–F5c | `it/FinScn05IT`（8 个匹配、手续费与利息分录、撤销再匹配、调节表 = FIN-EXP-10、2 月过账后重印相同） |
| FIN-DI-002 | Opening open items | 完成（应付部分） | `FIN_AP_OPENING`、导入 `finance.open_payables` | `it/BillIT` |


## F5 银行与对账

路径简写：`bank/` = `backend/finance/src/main/java/com/jabiz/finance/bank/`，`io/` = `backend/finance/src/main/java/com/jabiz/finance/io/`。

| 需求 | 标题 | 状态 | 实现 | 测试 |
|---|---|---|---|---|
| FIN-BK-001 | Bank accounts | 完成 | `FinBankAccount`（F4a；F5a 加对账单格式，一个现金科目只对应一个账户）、`FIN_BANK_ACCOUNT_SAVE`、`FinBankSettings` / `FIN_BANK_SETTINGS_SET` | `it/BankStatementIT`（1010、1050；账号遮蔽，Treasurer 逐值显示） |
| FIN-BK-002 | Bank transfers | 完成 | `FinBankTransfer`（迁移 V14）、`FIN_BANK_TRANSFER_POST` / `_RECEIVE` / `_VOID`（`bank/TransferProcesses`，同日一笔，跨日经在途科目） | `it/BankStatementIT`（50,000.00：1050 减、1010 增；在途与作废）；两边的匹配随 F5b |
| FIN-BK-003 | Statement import | 完成（OFX 不做，F5 计划 D3） | `FinBankStatement`、`FinStatementLine`、`FIN_BANK_STATEMENT_RECORD`（`bank/StatementProcesses`）、`calc/StatementCheck`、`io/Bai2Parser`、`io/Camt053Parser`、导入 `finance.bank_statement` / `_bai2` / `_camt053`（`io/StatementImports`） | `it/BankStatementIT`（10 行、256,555.00；同一文件 409；BAI2 与 camt.053 说明已记录、不增加；合计不符、不衔接、重叠、他人账户拒收）、`Bai2ParserTest`、`Camt053ParserTest`、`StatementCheckTest` |
| FIN-DI-001 | Master-data import（未达银行项目） | 部分（银行） | `FinBankOpening`、`FinBankOpeningItem`、`FIN_BANK_OPENING_ITEMS`、导入 `finance.bank_opening_items` | `it/BankStatementIT`（253,200.00 − CHK-1045 3,200.00 = 1010 期初 250,000.00；不符拒收并给出差额；每个账户一次） |
| FIN-BK-004 | Automatic matching | 完成 | `calc/BankMatcher`、`FIN_BANK_MATCH_PROPOSE` / `_ACCEPT`（`bank/MatchProcesses`）、模板 `finance.bank.book_items`、`finance.bank.statement_items` | `BankMatcherTest`（1 月的 8 个匹配、原因与置信度、性质测试）、`it/BankMatchIT` |
| FIN-BK-005 | Manual matching and bank-originated entries | 完成 | `FIN_BANK_MATCH`（一对一、一对多、多对一）、`FinBankEntryRule`、`FinBankEntry`、`FIN_BANK_ENTRY_RULE_SAVE`、`FIN_BANK_ENTRY_FROM_LINE`（`bank/BankEntryProcesses`） | `it/BankMatchIT`（BANK-FEE-2601、BANK-INT-2601 已过账并匹配）、页面 `bank/MatchingPage.test.tsx`、`e2e/bank.spec.ts` |
| FIN-BK-006 | Match history | 完成 | `FinBankMatch` / `FinBankMatchItem`（只写一次，撤销是新记录）、`FIN_BANK_UNMATCH`、模板 `finance.bank.match_history` | `it/BankMatchIT` |
| FIN-BK-007 | Reconciliation | 完成 | `FinBankReconciliation`（迁移 V16）、`FIN_BANK_REC_PREPARE` / `_COMPLETE`（`bank/ReconciliationProcesses`）、模板 `finance.bank.reconciliation` | `it/BankReconciliationIT`（差额 10.00 不能完成；之前未签核的月份先签核）、`it/FinScn05IT`（= FIN-EXP-10） |
| FIN-BK-008 | Reconciliation report and sign-off | 完成 | 审批对象 `fin.bank.reconciliation`、规则 `FIN-BANK-REC`（`FIN_SETUP`）、`FIN_BANK_REC_APPROVAL_RESULT`、`FIN_BANK_REC_ISSUE`（`REPORT_ISSUE`）、签核后匹配不能撤销 | `it/BankReconciliationIT`（准备人不能签核；无规则不能完成；审批中账面变化回到准备；2 月过账后重印相同）、`it/FinScn05IT`、页面 `bank/ReconciliationPage.test.tsx`、`e2e/bank.spec.ts` |
| FIN-BK-009 | Outstanding items carried forward | 完成 | 调节表按日计未达项（未匹配的项逐期带入）、模板 `finance.bank.stale_checks` | `it/BankReconciliationIT`（95 天的支票被列出） |
| FIN-BK-010 | Cash position | 完成 | 模板 `finance.bank.cash_position` | `it/FinScn05IT`（1010 账面 211,555.00、对账单 256,555.00；P-7902 22,000.00 于 2026-02-08 到期）、`it/BankReconciliationIT` |

## F6 固定资产

路径简写：`fa/` = `backend/finance/src/main/java/com/jabiz/finance/fa/`，`calc/` = `backend/finance/src/main/java/com/jabiz/finance/calc/`

| 需求 | 标题 | 状态 | 实现 | 测试 |
|---|---|---|---|---|
| FIN-FA-001 | Asset classes | 完成（F6a） | `FinAssetClass`（迁移 V17）、`FIN_FA_CLASS_SAVE`（`fa/AssetClassProcesses`）；资本化门槛在登记时检查 | `it/AssetIT`（FA-003 取 Computer equipment 36 月直线；门槛以下的账单行被拒） |
| FIN-FA-002 | Asset register | 部分（F6a：登记、来源、历史；期末合计随 F6b 的运行） | `FinAsset`（类别、方法、年限、残值、惯例、地点、保管人、状态、来源 BILL / ACQUISITION / OPENING）、`FIN_FA_ASSET_SAVE`、`FIN_FA_ACQUIRE`、账单资本化 `FIN_ASSET_CREATE` | `it/AssetIT`、`it/AssetOpeningAfterBillsIT` |
| FIN-FA-003 | Depreciation methods | 部分（F6a：计算） | `calc/Depreciation`（直线、200% / 150% 余额递减并转直线；工作量法 `byUse`） | `DepreciationTest`（FA-001 2,000.00、FA-002 1,666.67、转直线） |
| FIN-FA-004 | Conventions and rounding | 部分（F6a：计算） | `calc/Depreciation`（全月、月中、次月；按资产 × 月舍入，尾差在资产年度与寿命末月） | `DepreciationTest`（FA-003 1 月 333.33、全寿命 12,000.00；属性测试：全寿命 = 成本 − 残值） |
| FIN-FA-006 | Changes in estimates | 部分（F6a：计算） | `calc/Depreciation.restart` | `DepreciationTest`（FA-001 延长 12 个月：1,489.36） |
| FIN-DI-001/002 | 资产期初导入 | 完成（资产） | `FIN_FA_OPENING`（`fa/AssetOpeningProcesses`）、导入 `finance.fixed_assets`（`io/AssetImports`） | `it/AssetIT`（合计 ≠ 1500 / 1510 / 1590 整体拒收；FA-001、FA-002 带累计登记；只一次）、`it/AssetOpeningAfterBillsIT`（FA-003 由账单在先时补齐类别与条件） |
