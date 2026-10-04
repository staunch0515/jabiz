# 财务接口（FIN-DI-005、DI-006、DI-008、DI-009）

财务的接口就是平台的接口：流程、数据视图、SQL 模板、导出、待办都经同一套 HTTP API，按元数据声明的权限检查，与页面走的是同一条路。
因此接口建的单据与页面建的一样校验、审批、过账（FIN-DI-005），不另有一套"接口规则"。接口的完整描述是 OpenAPI 快照
`frontend/openapi/openapi.json`（运行中的应用在 `/api/meta/openapi`，需要认证）；本文只写财务集成常用的部分。验收见 `FinScn13IT`（FIN-SCN-13）。

## 1. 认证

集成方以一个**专用用户**登录（管理员用 `SEC_USER_CREATE` 建，分配只含所需权限的角色；不要用个人账号，也不要给 `*`）：

```http
POST /api/auth/login
{"userName": "erp-client", "password": "…"}
→ {"status": "SIGNED_IN", "tokenType": "Bearer", "accessToken": "…", "accessTokenExpiresAt": "…", "refreshToken": "…", …}
```

之后每个请求带 `Authorization: Bearer <accessToken>`。访问令牌默认 15 分钟有效；到期前用 `POST /api/auth/refresh {"refreshToken": "…"}` 换新
（刷新令牌一次有效，用过即轮换，重放会吊销整个会话，见 docs/design/10-security.md §2）。刷新只在空闲窗口内有效（访问令牌有效期 + 空闲超时，缺省共 30 分钟，10 §11）：
空闲更久的客户端重新登录。角色要求二次验证时，专用用户不要分配这类角色。

权限不足为 403（`PERMISSION_DENIED`）；未登录或令牌过期为 401。

## 2. 建单据：流程

```http
POST /api/processes/{流程}/latest
Idempotency-Key: erp-bill-T-9001
{…输入…}
→ 200 {"output": {…}, "processSeqId": …}
```

| 做什么 | 流程 | 权限 | 输出要点 |
|---|---|---|---|
| 存账单草稿 | `FIN_BILL_SAVE` | `fin.bill.prepare` | `billId` |
| 过账账单（超过审批限额时过账但待审批前不能付款，FIN-AP-006） | `FIN_BILL_POST` | `fin.bill.prepare` | `billNo`、`approval`（`NOT_REQUIRED` / `PENDING`）、`approvalRequestId` |
| 存发票草稿 / 过账 | `FIN_INVOICE_SAVE` / `FIN_INVOICE_POST` | `fin.invoice.prepare` | `invoiceId` / `invoiceNo` |
| 记收款并核销 | `FIN_RECEIPT_RECORD` | `fin.receipt.record` | `receiptNo` |
| 存日记账 / 提交 | `FIN_JOURNAL_SAVE` / `FIN_JOURNAL_SUBMIT` | `fin.journal.prepare` | `journalId` / `journalNo`、`approval` |

各流程的输入以 `GET /api/meta/processes`（调用者能运行的流程）与 OpenAPI 为准；页面上的必填、范围、格式检查在服务端同样执行，违反时 400（附 `violations`），
业务规则拒绝为 422（附 `violations[].ruleCode`，如 `FIN_BILL_DUPLICATE`、`FIN_PERIOD_CLOSED`）。审批由人在页面或 `APPROVAL_DECIDE` 中作出，准备人不能审批自己的单据。

### 幂等（FIN-DI-006）

带 `Idempotency-Key`（1–128 个字母、数字或 `.` `_` `:` `-`）的请求，同一调用者以同一个键再次请求时返回第一次的结果，流程不再运行；并发的重复请求等第一个完成后返回同一结果。
建议以外部单号作键（如 `erp-bill-<供应商>-<发票号>`）。同一个键用于另一个流程为 409（`IDEMPOTENCY_KEY_REUSED`）。键按调用者区分，不同的专用用户不会互相命中。
注意：

- 重放时**不比较请求体**：同一个键、不同的内容返回第一次的结果，不建第二张单据。一个键只对应一份内容。
- 被拒绝（4xx）的请求不占用键，改正后可以用同一个键重试。
- 重放的响应带 `Idempotency-Replayed: true`；输出中的敏感字段在重放时为空。

此外，账单以"供应商 + 供应商发票号"拒绝重复（`FIN_BILL_DUPLICATE`），导入以文件哈希与外部引用只导入一次（docs/design/20-imports.md）。

## 3. 读主数据与单据：数据视图

```http
POST /api/datasets/urn:jabiz:dataset:default:FinBill/query
{"filters": [{"field": "vendorInvoiceNo", "op": "eq", "value": "T-9001"}], "limit": 100}
→ {"items": [{"id": "…", "attributes": {…}}], "total": 1, "offset": 0, "limit": 100}
```

调用者能读的数据视图见 `GET /api/meta/datasets`；字段见 `GET /api/meta/entities/{实体}`。财务实体只经流程写入，数据视图的写入接口对它们为 422 或 403。
遮蔽字段（税号、银行账号）在读接口中一律遮蔽，持有权限者经 `POST /api/datasets/{id}/reveal` 逐值显示并留记录。单个实例的历史：`GET /api/datasets/{数据视图}/entities/{id}/history`。

## 4. 读余额与报表：SQL 模板

```http
POST /api/queries/finance.report.trial_balance
{"params": {"through": "2026-01-31"}, "limit": 500}
→ {"items": [{"accountCode": "1010", "closingDebit": 211555.00, "closingCredit": 0.00, …}], …}
```

常用模板：`finance.report.trial_balance`（试算表，`ledger.read`）、`finance.gl.trial_balance`、`finance.gl.detail`、`finance.ar.aging`、`finance.ap.aging`；
全部模板及其参数见 `GET /api/meta/queries`。按时点读取用请求的 `asOf` / `knownAt`。导出为文件：`POST /api/queries/{id}/export?format=csv|xlsx|pdf`
（超过 `jabiz.reports.export.max-rows` 即拒绝，不截断）。需要原样重现的报表用 `REPORT_ISSUE` 签发，取存档：`GET /api/reports/runs/{runId}/export?format=…`。

## 5. 全量导出（FIN-DI-008）

`POST /api/exports/data {"datasets": [...], "asOf": …, "knownAt": …, "reports": true, "reportsFrom": …, "reportsTo": …}`（`data.export`，另需每个数据视图的读取权限）
返回 ZIP：每个数据视图一个 CSV、`schema.json`、`manifest.json`（每个文件的 SHA-256 与行数）；`reports: true` 时另加 `[reportsFrom, reportsTo)` 内签发的报表 PDF（需要 `report.archive.read`）。一次最多 100 个数据视图，全量导出按 `GET /api/meta/datasets` 的列表分批请求（`FinScn12IT` 即如此，导出的已过账分录行数等于系统内行数）。
校验与离线重算：`tools/finance/verify-package.py`、`tools/finance/trial-balance-from-archive.py`。
附件（文件内容）不在导出中，按 `sys_file` 的清单经 `GET /api/files/{id}/content` 取；审计记录经 `GET /api/audit/records`（`audit.read`）分页读取。

## 6. 待办与通知（FIN-DI-009）

需要人做的事（审批、关账任务、例外）是待办：`GET /api/tasks/mine` 返回调用者的未完成待办（标题、对象、链接）。开启邮件（`jabiz.mail.enabled=true`、`spring.mail.*`、
`jabiz.mail.from`、`jabiz.mail.base-url`）后，每个新待办给有邮箱的负责人发一封带链接（`<base-url>/tasks`，待办列表，审批在其中处理）的邮件，失败按退避重试并留记录（docs/design/18 §5）。

## 7. 事件（FIN-DI-007）

发票（不含贷项通知单）过账时，财务发布事件 `finance.invoice-posted`，载荷：

```json
{"invoiceId": "…", "invoiceNo": "INV-1004", "customerCode": "C100", "invoiceDate": "2026-01-06",
 "dueDate": "2026-02-05", "currency": "USD", "subtotal": 50000.00, "taxTotal": 3300.00, "total": 53300.00,
 "totalUsd": 53300.00}
```

由平台的 webhook（docs/design/11-ledger-events-jobs.md §2.4，决策 D33）签名后 POST 给订阅的系统，失败按退避重试（至多 `jabiz.events.delivery.max-attempts` 次，默认 10），至少送达一次
（接收方按请求头 `X-Jabiz-Event-Id` 去重，并按 `X-Jabiz-Signature` 验证）。订阅写在配置中（应用的 `application.yml`；
用 Compose 部署时也可全部以环境变量给出，如 `JABIZ_WEBHOOKS_ALLOWED_HOSTS`、`JABIZ_WEBHOOKS_SUBSCRIPTIONS_0_NAME`、`…_0_SECRET`，同一订阅的各项须来自同一处）：

```yaml
jabiz:
  webhooks:
    allowed-hosts: [erp.example.com]
    subscriptions:
      - name: erp-invoices
        event-type: finance.invoice-posted
        url: https://erp.example.com/hooks/invoices
        secret: ${ERP_WEBHOOK_SECRET}   # 至少 32 字符，openssl rand -base64 32
```

新加的订阅会收到此前全部已过账发票的事件；只想要今后的，接收方按 `createdTime` 丢弃更早的。载荷不含客户的税号、地址与银行信息。期初导入的未结发票（`FIN_AR_OPENING`）不发布此事件。
