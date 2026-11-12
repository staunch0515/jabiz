# 版本线的日常操作

平台按不兼容版本分为版本线（决策 D21、`docs/design/17-apps-and-branches.md` §1）。本文件给出各项操作的命令。
例子中的线是 `1.0`、`1.1`，应用是 `finance`；命令在仓库根目录执行，远端为 `origin`。

## 1. 分支与文件一览

| 名称 | 是什么 |
|---|---|
| `<线>/platform` | 该线的平台分支（长期） |
| `<线>/<应用>` | 该线上的应用分支（长期） |
| `<线>/phase-<N><x>-<名>` | 平台工作分支，PR 合回 `<线>/platform` |
| `<线>/<应用>-<N>-<名>` | 应用工作分支，PR 合回 `<线>/<应用>` |
| `.jabiz-platform-line` | 平台文件，写线号（如 `1.0`）；应用只经合并获得，不能修改 |
| `platform-v<主>.<次>.<修订>` | 发布标签，打在 `<线>/platform` 上 |

不要建名为 `1.0` 这样只有线号的分支：git 中它与 `1.0/…` 冲突。

## 2. 平台上做一个阶段

```sh
git fetch origin
git switch -c 1.0/phase-13g-example origin/1.0/platform
# … 实现、测试 …
git push -u origin 1.0/phase-13g-example      # PR：base = 1.0/platform
```

合入后，把平台合并进该线的各应用（每个应用各一次）：

```sh
git switch 1.0/finance && git pull
git merge --no-ff origin/1.0/platform
tools/check-app-paths.sh                      # 以 origin/1.0/platform 为基准
git push
```

## 3. 应用上做一个阶段

```sh
git switch -c 1.0/finance-1-gl origin/1.0/finance
# … 只改 .jabiz-app-paths 中的路径 …
tools/check-app-paths.sh
git push -u origin 1.0/finance-1-gl           # PR：base = 1.0/finance
```

## 4. 开一条新线（只为不兼容的改动）

```sh
git switch -c 1.1/platform origin/1.0/platform
echo 1.1 > .jabiz-platform-line
git commit -am "Open platform line 1.1"
git push -u origin 1.1/platform
```

之后不兼容的平台工作都在 `1.1/phase-…` 上进行；在平台的 `docs/ROADMAP.md` 的版本线表中登记新线。

## 5. 应用升级到新线

```sh
git switch -c 1.1/finance origin/1.0/finance   # 从旧线的应用分支拉出，保留应用的历史
git merge --no-ff origin/1.1/platform          # 带来 .jabiz-platform-line = 1.1 与新线的平台
# … 适配不兼容的改动，跑全部测试 …
tools/check-app-paths.sh                       # 以 origin/1.1/platform 为基准
git push -u origin 1.1/finance
```

此后应用只在 `1.1/finance` 上开发；`1.0/finance` 冻结，只接受线 1.0 平台修复的合并（如果仍有部署在用）。

### 合并平台时需要应用适配的线内改动

线内的平台改动原则上兼容；少数加严的启动检查会让应用原有的声明在合并后启动失败，合并时按下表适配（应用分支上改，平台不改应用目录）。

| 平台阶段 | 应用要做的 |
|---|---|
| 1.2 / 16l（D41，谁能启动流程） | 重新归类应用中的 `internal()` 流程：事件消费、定时任务、回调保留 `internal()`；只被用户运行的业务流程以 `CallProcess` 调用的改为 `subProcessOnly()`（不是 `internal()` 的流程调用 `internal()` 流程即启动错误 `PROCESS`）；事件消费与任务的流程若是普通流程，改为 `internal()`，或在也要手工运行时声明 `manualToo()`（否则 `EVENT` / `JOB` 警告）；导入的目标流程不能是 `internal()` / `entryOnly()`（`IMPORT` 错误）；业务代码不再引用 `ProcessExecutor`、`ExecutionOptions`、`ActingUser`、`RequestContext.system`（`PlatformArchRules.noProcessMachinery`）。已知：`1.2/quizbuks` 的 `QB_QUIZ_RELEASE_FILES`（`internal()`，由用户运行的 `QB_QUIZ_DELETE` 调用）改为 `subProcessOnly()`。 |

## 6. 修复旧线并向前合并

修复做在最旧的受影响线上，再逐条向前合并，最后合并到各线的应用：

```sh
git switch -c 1.0/phase-13g-fix-x origin/1.0/platform   # 修复、测试、PR 合入 1.0/platform
git switch 1.1/platform && git pull
git merge --no-ff origin/1.0/platform                   # 向前合并（1.1 → 1.2 … 依次进行）
git push
# 再把各线的平台合并进该线的应用（第 2 节）
```

- 只合并，不变基、不拣选。
- 旧线上的修复**不加迁移**（平台 `db/jabiz` 与应用 `db/migration` 都不加）：需要改表结构的修复只做在最新线上。
- 向前合并时 `.jabiz-platform-line` 不会冲突：旧线从不改它。

### 迁移的顺序（17zz8）

Flyway 不执行比已应用的最新版本更旧的迁移（"Detected resolved migration not applied to database"）。两个 PR 并行时，号小的那个后合入，部署就会失败
（2026-11-08 在 `1.3/quizbuks`：V80 在 V81 之后合入）。CI 的 `migration-order`（`ci.yml`，`tools/check-migration-order.sh`）对每个 PR 检查：
它新增或改名的 `V<n>__*.sql` 都大于目标分支**当时最新提交**上同一序列的最大号，且同一序列中没有重号；不满足即失败并写明"基线已到 V<m>，请改为 V<m+1>"。

- 平台的序列：`backend/runtime/src/main/resources/db/jabiz`（平台）、`backend/app/src/main/resources/db/migration`（示范应用）、
  `backend/app/src/test/resources/db/testmigration`（示范应用的测试迁移，V1000 起）。各算各的。
- 应用分支在 `.jabiz-migration-sequences`（应用自己的文件，列在 `.jabiz-app-paths` 中）里每行写一个目录的正则（`#` 起注释），
  如 `backend/quizbuks[^/]*/src/main/resources/db/migration`：匹配的各目录合为一条序列。
- 检查只看 CI 那一刻的目标分支：之后别的 PR 带着更大的号先合入，这个 PR 仍是绿的。**合并带迁移的 PR 之前**再跑一次
  `tools/check-migration-order.sh origin/<目标分支> origin/<PR 的分支> [<应用的序列>…]`（或开启分支保护的"要求分支是最新的"）。
- **已合并的迁移不改**（17zz18）：目标分支已有的迁移不能修改、删除或改名，其 `.sql.conf` 也不能增改删（Flyway 按校验和核对已执行的脚本，执行过旧内容的库会在校验处停下）。
  说明或更正写进**新的、可重跑的迁移**（`IF NOT EXISTS`、先查目录再改），这是首选。
- **例外（只限原样改名）**：一个已合并、但**还没有在任何正式环境执行过**的迁移（例如号排在了已部署的迁移之前，部署会失败）可以改名补救：
  在仓库根目录的例外文件中按改名前的路径登记一行 `<路径> <YYYY-MM-DD> <原因>`（授予例外的日期；原因必填，可含空格；`#` 起注释），
  如 `backend/runtime/src/main/resources/db/jabiz/V65__x.sql 2026-11-12 号在已部署的 V66 之前，尚未上线`。登记的迁移只能原样改名（内容不变，连同其 `.sql.conf`），
  修改、删除仍然失败；格式不对的行也失败。平台分支写 `.jabiz-migration-exceptions`，应用分支写自己的 `.jabiz-app-migration-exceptions`（列在 `.jabiz-app-paths` 中），
  这样应用分支不改平台的文件，平台合并也不碰应用的文件；两个文件都读。已经在正式环境执行过的迁移不能用例外：改名后那里的库会报"已应用的迁移在本地找不到"。

## 7. 发布

```sh
git switch 1.0/platform && git pull
git tag -a platform-v1.0.1 -m "platform 1.0.1"
git push origin platform-v1.0.1
```

应用可以在自己的文档中写明所用的平台发布（例如 `docs/<应用>/ROADMAP.md`）。

## 8. 检查失败时

| 输出 | 原因与处理 |
|---|---|
| `branch '1.1/…' belongs to line 1.1 but .jabiz-platform-line says '1.0'` | 应用分支还没合并 `1.1/platform`（第 5 节）；或新线的平台分支没改线号（第 4 节） |
| `changed path(s) outside .jabiz-app-paths` 且含 `.jabiz-platform-line` | 应用自己改了线号；改回来，用合并新线的平台代替 |
| `base 'origin/1.1/platform' not found` | 先 `git fetch origin 1.1/platform`（CI 会自动取） |
| `.jabiz-platform-line must hold a line such as 1.0` | 文件内容不是 `<主>.<次>` |
