# Q26 — 启动检查：缺少业务参数时报警告（2026-10-25 确认）

## 1. 缘由

Q22 新增的参数 `qb.reward.cash.pay-days`、`qb.reward.redeem-days` 在部署后没有再执行 `QB_SETUP`（operations.md 第 8 步），
答题人提交答案时才看到 `PARAM_NOT_FOUND`。问题应在部署时的启动日志（或 `platformCheck`）中出现，而不是在用户的屏幕上。

## 2. 决定

| # | 决定 |
|---|---|
| D-Q26-1 | 启动检查 `setup.SetupParamsCheck`（`PlatformCheck`，类别 `QUIZBUKS`）：`QbParams.ALL` 中每个此刻没有生效值的参数各报一条**警告**（`parameter <键>`），普通参数提示"部署后执行 `QB_SETUP`"，受控参数另提示"由另一位管理员发布其提案"。只是警告：应用必须启动，管理员才能执行设置 |
| D-Q26-2 | 一个参数都不存在的数据库（从未执行过 `QB_SETUP`）只报一条（`QB_SETUP has not run yet`），不逐个报 |
| D-Q26-3 | 经 `ParamService.loadAvailable` 在启动线程读取（同平台的外部作业参数检查）；没有参数服务的上下文（无数据库的 `platformCheck`）跳过；读不出来报一条警告而不是失败 |

## 3. 测试

`SetupParamsCheckIT`：新 schema 一条警告；执行 `QB_SETUP` 后只有受控参数被点名；发布提案后无警告。
