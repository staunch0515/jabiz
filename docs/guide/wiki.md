# 把文档发布到 GitHub Wiki

仓库的 Wiki（`https://github.com/<owner>/<repo>/wiki`）由仓库中的文档自动生成，是**只读的镜像**：文档只在仓库中修改，经 PR 合并后自动同步；
在 Wiki 上直接编辑的内容会在下一次同步时被覆盖。

## 1. 结构

- Wiki 只有一份，每种分支只发布**最新的线**：推送到旧线（如 `1.2/platform`）不改变 Wiki，开了新线（如 `1.4/platform`）后由新线接管，workflow 不用改。
- 每个来源是 Wiki 的一节，有自己的目录、目录页与 `Home`、`_Sidebar` 中自己的一块（以 `<!-- wiki-section:<名> -->` 标记）；一次同步只替换自己的这些，
  不碰其他节。平台一节（`Platform`）发布 `docs/design/`、`docs/guide/`、`docs/perf/` 与 `docs/ROADMAP.md`。
- Wiki 的页面只按名字寻址，所以页面名由节名与路径组成：`docs/design/09-decisions.md` → `Platform-design-09-decisions`。两个文件得出同一个名字时同步失败。
- 文档之间的相对链接改为 Wiki 页面（保留 `#锚点`，跨节也可以）；指向代码等其他文件的链接改为 GitHub 上该分支的文件；代码块、行内代码、绝对地址与
  不是文件的目标（如邮件模板里的 `{entryUrl}`）原样保留。每页顶部注明来源文件与分支。
- 超过约 400 KB 的文档在二级或三级标题前分成几页（`Platform-ROADMAP`、`Platform-ROADMAP-2` …），页首互相链接：GitHub 不渲染约 512 KB 以上的 Markdown。

## 2. 组成

| 文件 | 作用 |
|---|---|
| `tools/wiki/sync.mjs` | 把一节写进 Wiki 的工作副本（Node，无依赖） |
| `tools/wiki/publish.sh` | 判断是否最新的线，克隆 Wiki、同步、提交并推送；推送被另一节抢先时在新的 Wiki 上重做（三次） |
| `.github/workflows/wiki.yml` | 平台一节：推送到 `<线>/platform` 且改了上述文档时，或手动运行 |
| `tools/wiki/sync.test.mjs`、`tools/test/wiki-publish.test.sh` | 测试，在 CI 的 `app-paths` 作业中运行（每个分支） |

推送 Wiki 用 workflow 的 `GITHUB_TOKEN`（`contents: write`）；它被拒绝时，在仓库的 Actions secrets 中设 `WIKI_TOKEN`（只对本仓库、`Contents: Read and write` 的细粒度令牌），workflow 优先用它。

## 3. 第一次

1. Settings → General → Features 勾选 **Wikis**，在 Wiki 页签中建第一页（Wiki 的仓库在第一页之前不存在，克隆会失败）。第一页随后被生成的 `Home` 替换。
2. Actions → **Wiki** → Run workflow，分支选最新的 `<线>/platform`；之后每次合并自动运行。

## 4. 应用的一节

应用在自己的分支上加自己的 workflow（文件列入 `.jabiz-app-paths`），触发条件为推送到 `[0-9]*.[0-9]*/<应用>` 且改了应用的文档，调用同一个脚本：

```yaml
env:
  WIKI_BRANCH: ${{ github.ref_name }}
  WIKI_LINE_KIND: <应用>
  WIKI_TOKEN: ${{ secrets.WIKI_TOKEN || secrets.GITHUB_TOKEN }}
run: >-
  tools/wiki/publish.sh --repo "$GITHUB_REPOSITORY" --title <显示名>
  --section <节名>:docs/<应用>:.
  --link-section Platform:docs:design,guide,perf,ROADMAP.md
```

`--link-section` 声明平台一节的页面，应用文档中指向平台文档的相对链接因此也成为 Wiki 链接。节名以字母开头、只含字母与数字，不能与其他节相同。

## 5. 本机试运行

```bash
mkdir -p /tmp/wiki && node tools/wiki/sync.mjs --wiki /tmp/wiki --repo <owner>/<repo> --branch 1.3/platform \
  --title 平台 --section Platform:docs:design,guide,perf,ROADMAP.md
```
