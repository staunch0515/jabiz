# 首发配置：运维指南

全新的系统第一次启动时，用一个配置文件完成全部初始化（设计：`docs/design/23-first-launch.md`）。

## 准备

1. 在测试系统上做好内容（QuizBuks：发起方在发起方页面导出发起方包），放进配置的目录。
2. 写主文件 `first-launch.yml`（示例与各段的说明见应用的文档；QuizBuks：应用分支的 `docs/quizbuks/plans/impl/F-LAUNCH.md` 第 1.3 节）。不写任何密码或兑换码。
3. **试运行**：
   - 命令：`JABIZ_FIRST_LAUNCH_CONFIG=/路径/first-launch.yml JABIZ_FIRST_LAUNCH_DRY_RUN=true` 启动；
   - 有问题时启动失败，日志逐条写出"位置 | 问题 | 怎么改"；
   - 没有问题时打印将执行的步骤后退出，不写任何东西。
   - 核对日志中的每个邮箱。

## 正式启动

- 使用全新的空数据库（从未有过任何用户）与空的文件目录（`JABIZ_FILES_LOCAL_ROOT`）。
- 设置 `JABIZ_FIRST_LAUNCH_CONFIG`。不要设置 `JABIZ_BOOTSTRAP_ADMIN_USER` / `_PASSWORD`，两者同时设置即启动失败。
- 内容就位之前，系统对所有请求回答 503（"系统正在进行首发初始化"）。日志中每完成一步写一行；完成时写"首发：内容已就位，开始对外服务"。

## 出错时

内容阶段任一步失败时：
- 日志写出失败的步骤与原因；
- 应用退出；
- 之后每次启动都会同样失败。

处理：
1. 按日志修改配置或程序；
2. **清空数据库与文件目录**；
3. 重新启动。

首发不续做。

## 完成之后

- 撤下 `JABIZ_FIRST_LAUNCH_CONFIG` 与配置文件（留着也不会再执行，但每次启动都会提醒）。
- 有用户的数据库永远不会执行首发：误把配置带到正在运行的系统上，只会得到一行警告。

## 设置

| 设置 | 缺省 | 说明 |
|---|---|---|
| `jabiz.first-launch.config` | — | 主文件（`JABIZ_FIRST_LAUNCH_CONFIG`） |
| `jabiz.first-launch.dry-run` | false | 只校验并打印计划 |
| `jabiz.first-launch.content-timeout` | 10m | 内容阶段的时限 |
| `jabiz.first-launch.await-interval` | 1s | 等待中的步骤两次尝试之间的间隔 |
| `jabiz.first-launch.max-file-bytes` / `max-total-bytes` / `max-files` | 20 MiB / 200 MiB / 2000 | 配置目录的上限 |
