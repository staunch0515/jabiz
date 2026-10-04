# 财务应用：易用性研究与无障碍审计（FIN-UI-001、FIN-UI-009）

两项验收都需要真人：FIN-UI-001 要求至少 8 名会计与职员参加易用性研究，核心任务无需帮助完成率 ≥ 90%、SUS ≥ 75；
FIN-UI-009 要求外部无障碍审计在核心任务上没有未关闭的 A / AA 问题。研究与审计由使用方组织，本文给出材料。
自动检查（axe，`finance-web/e2e/a11y.spec.ts` 与平台的 `frontend/e2e/a11y.spec.ts`）在本地通过、CI 中照常运行，但只能发现审计问题的一部分。

## 1. 易用性研究的做法

- **参与者**：≥ 8 人，按岗位分布：会计 3、应收职员 2、应付职员 2、控制人 1（可多于此）；此前没有用过本系统，有一年以上同类工作经验。
- **环境**：示范公司 Northwind Components 的副本（FIN-SCN-06 之后的账，任务前为 C100 做两次资料修改，`docs/finance-requirements/21-expected-results.md`），每人一个账号
  （角色按岗位），桌面浏览器，屏幕 ≥ 1366 × 768；会话 60 分钟，录屏与出声思考。
- **主持**：先读开场说明（研究的是系统而不是人；可以随时放弃一项任务），然后逐张给出任务卡（§2，英文，原样朗读）。
  不演示、不提示；参与者求助或 5 分钟内没有进展即记为"需要帮助"并给出下一张卡。
- **记录**：每项任务 完成 / 需要帮助 / 放弃、用时、错误与犹豫之处；全部任务后填 SUS（§3）与三个开放问题。
- **通过标准**：所有参与者所有核心任务中"无需帮助完成"的比例 ≥ 90%；SUS 平均分 ≥ 75。未达到时按问题严重程度整理改进清单，修改后再测。

## 2. 任务卡（核心任务）

角色栏为执行该卡的岗位；没有该岗位的参与者跳过。

| # | 角色 | 任务卡（原样朗读） | 完成标准 |
|---|---|---|---|
| T1 | 会计 | "Record the January rent of 4,500.00 for the warehouse, paid from the operating account, dated January 31, and submit it." | 分录已提交（过账或待审批），科目与金额正确 |
| T2 | 会计 | "Paste these five lines from the spreadsheet into a new journal entry and save it." （给出 5 行的表格） | 5 行全部进入且借贷平衡、已保存 |
| T3 | 应收职员 | "Customer C100 bought components for 3,000.00, taxed as in Austin. Enter and post the invoice dated February 2." | 发票过账，税额按 TX-AUSTIN 计算 |
| T4 | 应收职员 | "C100 paid 1,500.00 by check on February 3. Record the receipt and apply it to their oldest open invoice." | 收款入账并核销到最早的发票 |
| T5 | 应付职员 | "Enter the bill from vendor V100, their invoice P-8100, for 1,200.00 of components, dated February 2." | 账单已保存并提交，供应商与到期日（按条件）正确 |
| T6 | 应付职员 | "Find all bills due by February 20 and prepare a payment run for them." | 付款批包含且只包含这些账单 |
| T7 | 会计 | "Find what was posted to account 6400 Professional Fees in January and open the largest entry." | 打开了正确的单据 |
| T8 | 会计 | "Match the operating account's January bank statement and tell me what is still unmatched." | 匹配完成，说出未匹配的项目 |
| T9 | 控制人 | "Approve the journal entry waiting for you, then show me the income statement for January." | 审批完成；打开 1 月利润表 |
| T10 | 控制人 | "Start the January close and tell me which checks are failing." | 关账清单已开始，正确说出未通过的检查 |
| T11 | 任一 | "Customer C100's details were changed twice. Show me what they were before each change." | 打开历史并说出两次修改前后的值 |

## 3. SUS 问卷

标准的 System Usability Scale（Brooke, 1996），英文原文，每题 1（Strongly disagree）到 5（Strongly agree）：

1. I think that I would like to use this system frequently.
2. I found the system unnecessarily complex.
3. I thought the system was easy to use.
4. I think that I would need the support of a technical person to be able to use this system.
5. I found the various functions in this system were well integrated.
6. I thought there was too much inconsistency in this system.
7. I would imagine that most people would learn to use this system very quickly.
8. I found the system very cumbersome to use.
9. I felt very confident using the system.
10. I needed to learn a lot of things before I could get going with this system.

计分：奇数题得分 −1，偶数题 5 − 得分，十题相加 × 2.5，得 0–100。研究的 SUS 为参与者得分的平均。

开放问题：What was the most frustrating part? What did you like most? Is there anything you expected to find but did not?

## 4. 外部无障碍审计（FIN-UI-009）

- **标准**：WCAG 2.2 A 与 AA。
- **范围**：§2 的全部核心任务所经过的页面（登录与二次验证、日记账登记簿与录入、发票、收款、账单、付款批、银行匹配、报表与钻取、关账工作台、历史），
  以及平台的通用页面（数据列表、表单、待办）。
- **方法**：键盘单独操作、焦点可见与顺序、屏幕阅读器（NVDA + Firefox、VoiceOver + Safari）、200% 缩放与 320 CSS px 重排、对比度、表单错误的提示与关联。
- **交付**：问题清单（准则、页面、复现步骤、严重程度）；全部 A / AA 问题关闭后复测确认。平台层面的问题在平台分支修复（docs/design/12-frontend.md §11），
  财务页面的问题在 finance 分支修复。
