# F-F13 第 3 条：旧镜像的清理与磁盘告警（实施计划，S1）

来源：规格 F-F13 第 3 条、troubleshooting T8。2026-11-08 总控把 F-F13 第 2、3 条从 S3 改派给 S1（S3 的运维材料 #515 不变）。

## 做什么

1. **部署后只保留最近 N 个镜像**：新脚本 `deploy/quizbuks/server/prune-images.sh`，由部署工作流在 `deploy-app.sh` 成功之后、在同一个 SSH 会话中运行。
   - 每个环境在 `/opt/apps/<APP_NAME>/deployed-images` 记部署历史；保留每个环境最近 N 个不同的标签，以及任何容器在用的镜像。
     staging 与 production 同一台服务器、同一个仓库，按各自的历史保留，互不删除。
   - N 为环境 `.env` 的 `QB_DEPLOY_KEEP_IMAGES`（缺省 3，至少 2）。部署前的 `.env` 检查（`required-env` 的新形式 `keep-images`）拒绝不合规的值。
   - 只删本仓库的镜像，不强制；失败只记一行日志，不让已成功的部署变红。
2. **磁盘告警**：不需要新代码。平台 `jabiz-disk-usage` 已测量工作目录 `/app` 所在的文件系统，在容器中就是存放镜像与日志的主机盘（T8 的日志正是 `/app/.`）；
   应用停止时由 `jabiz-metrics-absent` 告警。改准 `operations.md` §5 与 `DEPLOY.md` 的说明，写清前提（本环境的 LGTM 在运行）。

## 改动

| 文件 | 内容 |
|---|---|
| `deploy/quizbuks/server/prune-images.sh` | 新：清理脚本（POSIX sh，服务器的 `sh`） |
| `deploy/quizbuks/server/check-env.sh`、`required-env` | 新形式 `keep-images`；`?QB_DEPLOY_KEEP_IMAGES` |
| `.github/workflows/quizbuks-deploy.yml` | 上传脚本；部署成功后运行（早于本功能的提交不运行） |
| `.github/workflows/quizbuks.yml` | 运行新测试 |
| `deploy/quizbuks/DEPLOY.md` | 新节"旧镜像的清理" |
| `deploy/quizbuks/README.md` | 环境变量表加 `QB_DEPLOY_KEEP_IMAGES` |
| `docs/quizbuks/operations.md` §5 | "应用的磁盘"一行：测量的是哪块盘、处理步骤、试发 |
| `docs/quizbuks/troubleshooting.md` T8 | 预防已做；归档条件 |

没有表与迁移。

## 测试

- `tools/quizbuks/test/prune-images.test.sh`（新，假的 `docker`）：缺省保留 3 个；staging 的部署不删 production 的；`QB_DEPLOY_KEEP_IMAGES` 为 2、带引号的 5、不合规时按 3；
  回滚到旧标签；容器在用的、其他仓库的镜像保留；无标签的本仓库镜像按 ID 删除；同一镜像的第二个标签；历史只留 200 行；历史中其他仓库的同名前缀不算；
  Docker 的 `ps` / `image ls` / `image rm` 失败与历史不可写时都退出 0、不删东西。
- `tools/quizbuks/test/check-env.test.sh`：`keep-images` 形式的六个用例；`required-env` 的语法检查认识新形式。

## 风险

- 功能上线后的第一次部署时，另一个环境还没有历史，它不在运行的旧镜像会被删除；回滚时从仓库重新拉取（多花拉取时间，不丢东西）。
- 服务器上的 `deploy-app.sh` 不在仓库中：清理放在它之后，不改它。
- 验收中"服务器上只剩 N 个镜像"与"把磁盘填到阈值收到告警"只能在服务器上做，写进告警试发的步骤（总控清单 B4），由需求方照做。

## 删除的功能

无。
