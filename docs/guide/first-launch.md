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

## 等人阶段（账号）

- 内容就位后，任务 `jabiz.first-launch` 一分钟内给每个账号寄邀请。本人从邮件设定密码，在 `/admin/` 登录并绑定二次验证；之后一分钟内得到配置中的角色。
- 使人成为管理员的角色（持有 `*` 或管理员权限）另须**运维确认**：日志 WARN 给出账号、姓名、邮箱、接受邀请与绑定的时刻与 12 位**指纹**。与本人核对（例如电话确认时刻）后，
  把指纹加入 `JABIZ_FIRST_LAUNCH_CONFIRM_ADMINS`（逗号分隔）并重启。本人重新绑定二次验证后，旧指纹失效。
- **邮箱写错**：只要该账号尚未接受邀请，改配置中的 `email` 后重启，即以新地址重寄邀请（旧链接作废）。改错了还可以再改回（A→B→A 也是一次更正：B 的链接作废，任务寄到 A）。其他改动（含已接受账号的邮箱）不执行，日志说明，请在管理系统中照常办理。
- **发现异常即停用**：某账号可疑（例如不是本人接受的邀请），在管理系统中停用它，首发即不再为它邀请或授予，报告列出它。
- 全部授予完成即 `DONE`：日志"系统已正式运行"，报告寄给 `report: true` 的账号（含剩下的人工事项）。超过期限（缺省 14 天）即 `EXPIRED`，报告列出未完成的账号；待命角色停用。

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
| `jabiz.first-launch.confirm-admins` | — | 运维确认的管理员指纹，逗号分隔（`JABIZ_FIRST_LAUNCH_CONFIRM_ADMINS`） |
| `jabiz.first-launch.remind-after` | 3d | 未接受邀请的账号再邀请一次 |
| `jabiz.first-launch.deadline` | 14d | 自内容阶段完成起，到期即 `EXPIRED` |
| `jabiz.first-launch.cron` | 每分钟 | 任务 `jabiz.first-launch` |
| `jabiz.first-launch.ready-attempts` | 5 | 账号就绪后的应用步骤连续失败几次后，运行的结束不再等它们（23 §9） |
