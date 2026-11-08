# F-F13 第 2 条：`entry-fee.spec.ts` 直接触发入账任务（实施计划，S1）

来源：规格 F-F13 第 2 条（2026-11-08 总控改派给 S1）。

## 做什么

入场费的 e2e 原来让每日任务 `qb.entry-fee-settle` 在 e2e 中每分钟运行（`app.env` 的 `QB_ENTRY_FEE_SETTLE_CRON='45 * * * * *'`），测试轮询发起方钱包最多 120 秒。
改为测试自己触发：平台的手动补跑（17zs，D77：`POST /api/jobs/{job}/runs`，管理员的 `job.run`）补跑该任务最近的计划时刻。
这是逐项任务（每个 2170 有余额的发起方一项），补跑按当时的数据选取，所以测试中的发起方当即入账（该时刻已成功过的项照常重放）。不需要新的接口或测试开关。

## 改动

| 文件 | 内容 |
|---|---|
| `quizbuks-web/e2e/tests/support.ts` | `runJobNow(request, job)`：读 `GET /api/jobs` 的 `rerunTimes[0]`，请求补跑（被计划运行占着锁时 409 `JOB_BUSY` 则重试），按 `requestSeqId` 等到结束，断言 `SUCCEEDED` |
| `quizbuks-web/e2e/tests/entry-fee.spec.ts` | 先断言费用在"待入账"中（以前每分钟的任务可能已入账，断言不了），再 `runJobNow`，刷新一次即为 30 Kudos |
| `quizbuks-web/e2e/app.env` | 删去 `QB_ENTRY_FEE_SETTLE_CRON`（e2e 中按缺省的每日时刻；配置项本身保留） |
| `docs/quizbuks/operations.md`（入场费的任务） | 该 cron 的说明 |

没有表与迁移，不改后端。

## 测试

本机 `tools/quizbuks/e2e.sh run --build -- tests/entry-fee.spec.ts`：通过（51.6 秒，含第一次的准备）；再 `--repeat-each=3` 连跑三次全部通过（每次约 34 秒）。
本机没有 Docker：Mailpit 以一个只实现测试所用的两个接口的替身代替（不提交）。

## 风险

- 其他测试不依赖每分钟的入账（`finance.spec.ts`、`a11y.spec.ts` 只数科目卡）。
- 补跑需要 `job.run`（受保护的权限）：e2e 的管理员持有 `*`，二次验证在 e2e 中关闭（`JABIZ_SECURITY_MFA_ADMINISTRATION=false`）。

## 删除的功能

无（e2e 的设置 `QB_ENTRY_FEE_SETTLE_CRON` 不是功能；配置项 `quizbuks.entry-fee.settle-cron` 保留）。
