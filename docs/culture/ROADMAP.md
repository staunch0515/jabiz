# Culture, Unfiltered — 路线图

应用 culture 的阶段计划。设计见 `00-design.md`（待确认），需求原文见 `brief.md`。
执行方式与平台相同（根目录 `CLAUDE.md` 第 7 节）：每个阶段先出实施计划、经确认后实现；工作分支 `culture-<N>-<名>` 从 `culture` 拉出并合回；
PR 逐条对照本文件的验收标准。平台能力来自 `platform` 分支的阶段 13（`docs/ROADMAP.md`），合入 `platform` 后再合并到 `culture`。

| 阶段 | 名称 | 依赖（平台） | 预估 | 状态 |
|---|---|---|---|---|
| C0 | 设计 | — | — | ☑ 已完成（C1 计划时确认） |
| C1 | 内容模型、工作流与后台 | 13a、13b、13d、13e | 4–5 天 | ☑ 已完成 |
| C2 | 公开接口 | 13c | 3–4 天 | ☑ 已完成 |
| C3 | 公开网站 | — | 8–10 天 | ☑ 已完成（PR 待合并，分支 `culture-3-site`；与原型一致待人工确认） |
| C4 | 地图、搜索与上线准备 | — | 3–4 天 | ☑ 已完成（PR 待合并，分支 `culture-4-launch`） |

平台阶段 13 与 C1–C4 合计约 35–45 天。13c 与 C1 可以并行；C3 的视觉原型可以在 C1 期间先做。

---

## C1 内容模型、工作流与后台

**要求**
1. 模块 `backend/culture`（`jabiz.boot-app`，`/` 与 `/admin/` 两个 SPA，公开网站先放占位页）、`backend/culture/CLAUDE.md`。
2. 实体与迁移（设计 §3）、字典、文件策略、数据视图（§5）、文案（zh / ja / en）、文案块的英语预置内容。
3. 流程（§6.3）、两个开关（§6.5）、`CULTURE_SETUP`（§6.6）。
4. `deploy/culture/docker-compose.yml`；`docs/culture/editor-guide.md`。

**验收标准**
- [x] 编辑只用后台（不写前端代码）即可完成：建地点、主题、参与者与同意记录，上传照片，起草多人故事，发布。
- [x] 通讯员只能看到、改动自己的内容；提交后不能再改，退回后可以（集成测试）。
- [x] 发布检查的每条违规都有测试；两个开关各取两种值的行为都有测试。
- [x] 撤回同意后，包含该参与者的故事全部下线；抹除后相关行与文件都不存在（集成测试）。
- [x] 场景回放"六个地点的 HOME 故事"全过程快照；`platformCheck` 通过；`check-app-paths` 通过（平台 13e 合入 `platform` 后）。

说明：C1 发现的三个平台问题先在平台 13e（`phase-13e-initial-state`）上解决：显式初始状态、`CallProcess.forEach`、违规只报告一次。
设计中的调整记在 `00-design.md` §14。通讯员在后台里的引用下拉与子实体列表依赖能读目标实体的默认视图，
通讯员没有这个权限，因此 C1 中他们在"我的视角 / 我的照片"里要填写故事的主键；由平台补"经非默认视图查找"后改善（见 PR 的已知问题）。

## C2 公开接口

**要求**
1. 公开数据视图（§7.1）与公开模板（§7.2），搜索所需的迁移（`pg_trgm`、`cu_i18n_text`、索引）。
2. 公开模板目录快照与 `site/` 中的类型生成。

**验收标准**
- [x] 属性测试：任意状态组合下，公开模板只返回已发布的数据（`PublicVisibilityPropertyIT`）。
- [x] 同意书永远不能匿名获取；草稿与下线内容的文件 404；撤回同意后文件在失效后 404（`PublicFilesIT`、`PublicVisibilityPropertyIT`）。
- [x] 按地点、主题、媒体类型筛选与搜索（含中日文）返回正确结果；通配符被转义（`PublicTemplatesIT`）。

说明：计划确认的调整见设计 §15：可见性的连带（隐藏的参与者、主题、地点离开页面）、`q` 的长度在 SQL 中判断、C1 的下线类流程补上文件判定的失效。
18 个公开模板在 `backend/culture/src/main/resources/queries/culture/public/`；迁移 `V4__culture_search.sql`；`site/` 的类型生成
（`pnpm gen:api` / `check:api` / `typecheck` / `test`）与 CI 作业 `.github/workflows/culture.yml`。已知限制：隐藏参与者的视角文件知道 id 时仍可取得；
平台约定插件过早读取 `jabizApp.publicQueriesSnapshot`（culture 的构建脚本另行设置，待平台修正）。

## C3 公开网站

**要求**
1. 视觉原型（首页、主题页、故事页；手机与桌面）→ **经确认后**实现。
2. 全部页面（设计 §9.1）、三种界面语言、组件（§9.3）、`site/CLAUDE.md`（含文案语气规则）。
3. CI：`site` 的 lint、typecheck、test、build 与端到端（`.github/workflows/culture.yml`）。

**验收标准**
- [x] 没有任何写死的国家、参与者或主题：加一个地点、参与者、主题、故事后，不改代码即出现在所有相关页面（端到端测试 `site/e2e/content.spec.ts`）。
- [x] 全部页面在三种语言、桌面与 375 px 宽度下 axe 检查的严重与重大问题为 0（`accessibility.spec.ts`，另查标题层级与横向滚动）；
  只用键盘完成"首页 → 主题 → 故事 → 播放视频"（`interaction.spec.ts`）。
- [x] 视频在点击前页面没有任何第三方请求（Playwright 断言，`interaction.spec.ts`）。
- [ ] 与视觉原型一致（人工确认）。

说明：计划确认的调整见设计 §16：字体与强调色、字典显示名由网站命名（未知值显示为可读的代码）、固定比例的图片框与变体回退、视角视频的封面、
地图与搜索页留在 C4。网站的规则在 `site/CLAUDE.md`（含文案语气）；CI 作业 `site`（lint、typecheck、Vitest、build）与 `e2e`
（打包的应用 + Playwright + axe）。已知：首屏 JS 约 176 KB（gzip），C4 以 Lighthouse 测量时再拆分（如 Markdown 渲染按需加载）。

## C4 地图、搜索与上线准备

**要求**
1. 地图（§9.5）与地点列表；搜索页。
2. Lighthouse 测量与优化；备份与恢复演练；部署文档（`docs/culture/operations.md`）。

**验收标准**
- [x] 地图可键盘操作，地点列表提供相同内容（`map-search.test.tsx`；端到端 `interaction.spec.ts`、`content.spec.ts`；axe 覆盖地图与搜索页）。
- [x] 移动端首页 LCP < 2.5 s、首屏 JS < 200 KB（gzip），Lighthouse 无障碍 100（记录在 `docs/culture/operations.md` §7；
  构建检查首屏 JS，CI 的 `e2e` 作业运行 Lighthouse）。
- [x] 从备份恢复出一套可用的系统（数据库 + 文件），步骤写入运维文档（`operations.md` §4–§6；演练脚本 `tools/culture/test/backup-restore.test.sh`，
  CI 作业 `restore-drill`）。

说明：计划确认的调整见设计 §17：地图侧栏的两个公开模板（`location_themes`、`location_media`）、只画陆地、首屏的按需加载与
无布局偏移（桌面版首页标题改为顶端对齐）、静态资源压缩、备份保留 30 天与撤回 / 抹除登记。恢复演练发现并修正了 compose 部署
无法启动的问题（空的 OTLP 端点）。首页 LCP 1.82–2.28 s（多次运行）、首屏 JS 137.3 KB、各页无障碍 100。
