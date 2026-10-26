# 内容包工具（Q36）

把一个商户的测验与发布保存成**内容包**（一个目录），再写进任何一套 QuizBuks（测试或正式）。
只经与商户后台相同的 API，审核、奖励上限、封存、审计等规则照常检查；不连数据库。设计见 `docs/quizbuks/plans/Q36-content-pack.md`。

需要 Node 22。密码只从环境变量 `QB_PASSWORD` 读取（不要写在命令行参数里）。每个命令都可以加 `--dry-run`，只显示会做什么。

```sh
cd tools/quizbuks/content
read -s QB_PASSWORD; export QB_PASSWORD     # 商户账号的密码，不留在命令历史中

# 测试系统：把商户的测验与发布导出成内容包
node qbcontent.mjs export --base https://test.example.com --user me@example.com --pack ~/qb-content

# 正式系统：写进同一商户（自助注册、已通过入驻审核）的账号；已有的跳过，可以反复运行
node qbcontent.mjs apply  --base https://quizbuks.example.com --user me@example.com --pack ~/qb-content --dry-run
node qbcontent.mjs apply  --base https://quizbuks.example.com --user me@example.com --pack ~/qb-content
#   --with-profile  同时更新商户的显示名、公司名与简介（取自内容包）

# 长期的发布：结束前 30 天内的，接上新的一期（每年或每月运行一次）
node qbcontent.mjs renew  --base https://quizbuks.example.com --user me@example.com --pack ~/qb-content --renew-days 30

# 只限测试系统：5 个虚构的答题者注册（经 Mailpit 验证邮箱）并作答内容包中的发布
node qbcontent.mjs takers --base https://test.example.com --count 5 --mailpit https://mail.test.example.com --pack ~/qb-content
```

## 内容包

```
qb-content/
  content.json                         发布列表与商户的显示资料
  quizzes/<标题>.quizbuks.json          每个测验一个，与后台"导出测验"的文件相同（含图片、PDF、音频）
```

`content.json` 中每个发布：

| 字段 | 说明 |
|---|---|
| `name` | 发布的名称；`apply` 按它判断是否已有 |
| `quiz` | 测验文件（`quizzes/…`）；按测验标题判断是否已导入 |
| `durationDays` | 1–366；从应用的时刻起算 |
| `regions`、`audience`、`timeLimitSec`、`passScore`、`budgetCap`、`maxWinners`、`tiers` | 与后台的发布设置相同 |
| `rewardMethod` | `PLATFORM`（Kudos）、`SPONSOR_CASH`、`OFFLINE_REDEEM`、`DIGITAL_PRIZE`；商户须已被允许该方式 |
| `digitalContentEnv` | 共用码（`DIGITAL_PRIZE` + `SHARED`）的内容所在的环境变量名：码本身从不写进内容包 |

注意：

- 测验按**标题**、发布按**名称**认定。改了内容想重新写入时，改标题或名称，或在后台处理旧的。
- 保存时有问题的发布会留作**草稿**并列出字段与原因；在后台改好提交，或删掉草稿、改内容包后再运行。
- 服务器要求同意新版条款时，工具会停下：请本人在后台同意后再运行（工具从不替人同意）。
- 内容包放在自己的**私有仓库**，不要放进 jabiz 仓库。

## 示范包：长期的欢迎问卷

`example/` 是一年期（366 天）的三题问卷，每份完整的回答奖励 10 Kudos，最多 300 人（预算 3 000 Kudos，商户钱包要有余额）。
问卷只奖励完整的回答：未答完的档奖励必须是 0，且至少一档有奖励。不想花 Kudos 时可把 `rewardMethod` 改为
`OFFLINE_REDEEM`（到店兑换，写 `redeemDescription`）或电子奖品。每年用 `renew` 接上下一期，新注册的答题者就始终至少能看到一个问卷。

## 测试

```sh
node --test tools/quizbuks/content/lib.test.mjs       # 仓库根目录
```

对运行中的应用：`quizbuks-web/e2e/tests/content-tool.spec.ts`（`pnpm e2e`）。
