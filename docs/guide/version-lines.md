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
