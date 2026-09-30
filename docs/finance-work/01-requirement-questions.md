# 需求中的疑问与处理

需求副本（`docs/finance-requirements/`）只读；发现的歧义与冲突记在这里，说明实现怎样处理，由需求方在源仓库中改版后整体重新复制。

| # | 发现于 | 需求 | 疑问 | 处理 |
|---|---|---|---|---|
| Q1 | F1（2026-09-30） | FIN-GL-005、FIN-EXP-02、`sample-company/transactions.csv` | FIN-GL-005 把银行科目列为控制科目，手工分录须由 Controller 逐笔授权例外，否则拒绝；但期望结果中的 JE-0001（2100 / 1010）与 PAYROLL-2601 都以手工分录贷记现金 1010。 | 1010 仍为控制科目（类别 `BANK`）。Controller 在批准这类分录时以 `FIN_JOURNAL_GRANT_CONTROL_EXCEPTION` 授权该笔分录的例外并留记录（F1b）；两笔都超过 10,000.00，本就需要 Controller 审批，不增加额外步骤。 |
| Q2 | F1（2026-09-30） | FIN-GL-001、`chart-of-accounts.csv` | 样例科目表没有控制科目与上级科目列。 | 样例公司的控制科目（1010、1050 BANK；1200 AR；2000 AP；1500、1510、1520 FA_COST；1590 FA_ACCUM）在录入时指定；科目表文件导入（F2）提供这些列。 |
