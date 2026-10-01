# 需求中的疑问与处理

需求副本（`docs/finance-requirements/`）只读；发现的歧义与冲突记在这里，说明实现怎样处理，由需求方在源仓库中改版后整体重新复制。

| # | 发现于 | 需求 | 疑问 | 处理 |
|---|---|---|---|---|
| Q1 | F1（2026-09-30） | FIN-GL-005、FIN-EXP-02、`sample-company/transactions.csv` | FIN-GL-005 把银行科目列为控制科目，手工分录须由 Controller 逐笔授权例外，否则拒绝；但期望结果中的 JE-0001（2100 / 1010）与 PAYROLL-2601 都以手工分录贷记现金 1010。 | 1010 仍为控制科目（类别 `BANK`）。Controller 在批准这类分录时以 `FIN_JOURNAL_GRANT_CONTROL_EXCEPTION` 授权该笔分录的例外并留记录（F1b）；两笔都超过 10,000.00，本就需要 Controller 审批，不增加额外步骤。 |
| Q2 | F1（2026-09-30） | FIN-GL-001、`chart-of-accounts.csv` | 样例科目表没有控制科目与上级科目列。 | 样例公司的控制科目（1010、1050 BANK；1200 AR；2000 AP；1500、1510、1520 FA_COST；1590 FA_ACCUM）在录入时指定；科目表文件导入（F2）提供这些列。 |
| Q3 | F1b（2026-10-01） | FIN-SCN-02 第 2 步、FIN-GL-014 | 场景要求"Controller 批准后会计修改备注 → 批准失效"；但批准即触发过账，过账后的分录不可修改（FIN-GL-012）。 | 批准与过账之间隔着事件投递（批准事件经 Outbox 交给 `FIN_JOURNAL_APPROVAL_RESULT`）：在此之前的修改使分录回到草稿、批准因内容哈希不符而不生效；之后的修改被拒（`FIN_JOURNAL_POSTED`），只能冲回。`FinScn02IT` 在批准与投递之间修改。 |
| Q4 | F1b（2026-10-01） | FIN-SCN-02 第 2 步、FIN-CT-001 | "会计试图批准 → 被拒"：Accountant 角色本无审批权限，被拒的原因是权限而不是职责分离。 | 两种情形都测：只有 Accountant 角色时 403（`PERMISSION_DENIED`）；同时拥有 Approver 角色时仍被拒（422 `APPROVAL_OWN_REQUEST`，平台 14b 的准备人不能审批）。 |
