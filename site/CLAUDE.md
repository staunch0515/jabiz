# CLAUDE.md — culture 公开网站规则

Culture, Unfiltered 的公开网站（`site/`，挂在 `/`）。根目录 `CLAUDE.md` 与 `backend/culture/CLAUDE.md` 照常适用；本文件只写网站额外的约定。
设计：`docs/culture/00-design.md` §9、§16；需求：`docs/culture/brief.md`；视觉原型经 C3 确认。

## 1. 边界

- 只读、匿名：数据**只**来自 `/api/public/queries/{id}` 与 `/api/public/files/{id}[/{变体}]`（`src/api/client.ts`），不登录、不带
  `Authorization`、不设 Cookie、不用 `localStorage` / `sessionStorage`（与同源的后台共享，17 §3.2），不接第三方统计。
- 模板的参数与行的类型由 `pnpm gen:api` 从 `src/api/public-queries.json` 生成（`src/api/public-queries.ts`，不手改）；要新的数据先在
  `backend/culture` 加公开模板（见其 `CLAUDE.md` §4），再生成类型。
- **不写死任何地点、参与者、主题或故事**：数量、名称、顺序都来自数据；固定页面的文字来自文案块（`useSiteBlocks`）。
  界面文字（导航、标题、按钮）在 `src/i18n/{en,zh,ja}.json`，三种语言齐全（`i18n.test.ts` 检查）。
- 字典值（媒体类型、活动类型、年龄段）的名称写在 `dict.*`；未知的值显示为由代码生成的文字（`humanize`），筛选只提供已命名的值（设计 §16）。
- 不用 UI 组件库；样式为 CSS Modules + `src/styles/tokens.css` 中的设计令牌。新颜色组合先加进 `tokens.test.ts` 的对比度清单。
- 内容安全策略（`backend/culture/src/main/resources/application.yml`）：`style-src 'self'`、`script-src 'self'`、`img-src 'self' data:`、
  `frame-src` 只有 youtube-nocookie 与 player.vimeo。不写内联 `<style>` / `<script>`、不引用外部字体或图片；端到端测试遇到 CSP 违规即失败。

## 2. 页面与组件

- 地址以语言开头（`/en/…`、`/zh/…`、`/ja/…`，`src/lib/paths.ts`），`/` 按浏览器语言跳转。页面在 `src/pages/`，路由在 `src/routes.tsx`。
- 多语言内容一律经 `LocalizedText` / `Markdown` / `pick`：回退顺序为界面语言 → 英语 → 任一语言，回退的元素带 `lang`。
- `Markdown`：不渲染原始 HTML 与图片；内容中的标题降为 `h3` / `h4`，不与页面的 `h1` / `h2` 竞争；外链带 `rel="noopener noreferrer"` 与"外部链接"说明。
- `VideoEmbed`：点击前只显示我们自己的封面，不向视频站发任何请求；地址只由 `src/lib/video.ts` 按白名单拼出。
- `ResponsiveImage`：固定比例的框 + 变体 `w320/w640/w1280`，加载失败回退原件；`alt` 必填，装饰图写 `alt=""`。
- 筛选状态写在地址的查询串里（`src/lib/filters.ts`），结果数以 `role="status"` 播报。
- 地图（`src/pages/Map.tsx`、`src/lib/map.ts`）：只画打包的陆地轮廓，不请求任何地图服务；每个地点是一个按钮，标记在屏幕上相距 ≥ 46 px；
  地点列表提供同样的内容（侧栏），手机宽度下是唯一的入口。选中的地点与搜索的词都在地址中（`?place=`、`?q=`）。

## 2a. 首屏（设计 §9.4，`docs/culture/operations.md` §7）

- 首屏 JS ≤ 200 KB（gzip）：每次 `vite build` 由 `scripts/check-bundle.mjs` 检查，超出即构建失败。首页以外的页面在 `src/routes.tsx` 中按需加载；
  Markdown 渲染器（`MarkdownContent`）也是，加载完成后预取。新的大依赖只在需要它的页面里引用。
- 不让内容一块块出现、把下面的内容往下推（布局偏移）：第一屏的各部分在数据到齐后一起出现（首页、故事库的筛选、地图页），
  首页标题立即绘制（它是 LCP 元素，不要让它等数据，也不要在数据到达时换掉这个元素）。文案块未到时显示迁移预置的英文标题；
  编辑改了标题文案块（或写了中日文）时，首屏会先显示预置标题、再换成文案块：这是为 LCP 接受的取舍（设计 §17 第 3 条）。
- 按需加载的页面失败（断网、新版本替换了旧文件）时显示 `RouteError`（在页头页脚之内，提供重新加载）；有 Markdown 的页面与渲染器一起加载，
  正文不会晚于页面其余部分出现。
- `pnpm lighthouse`（对运行中的应用，`E2E_BASE_URL`）：首页 LCP ≥ 2.5 s 或任一页无障碍 < 100 即失败；CI 的 `e2e` 作业在端到端测试后运行。

## 3. 无障碍（设计 §9.6，验收项）

- 每页恰好一个 `h1`，层级不跳级；列表页的卡片标题是 `h2`，页面分节下的卡片是 `h3`（`StoryCard` / `PersonCard` 的 `headingLevel`）。
- 所有交互元素可键盘操作、焦点可见、点击目标 ≥ 44 px；换页后焦点移到 `main`；菜单打开时焦点进入菜单，`Esc` 关闭并还给按钮。
- 旗帜与主题图标只作装饰（`aria-hidden`），名称以文字给出。动效只在 `prefers-reduced-motion: no-preference` 下出现，≤ 200 ms。
- 375 px 宽度下不得横向滚动：单栏网格用 `grid-template-columns: minmax(0, 1fr)`，长词换行。

## 4. 文案语气（简报 §19、设计 §9.2）

- 用第一人称复数（"我们"、"we"），好奇而不权威："我们在问的问题"，不写"了解日本文化"。
- 永远写"某人在某地的经验"，**不写"某国人如何如何"**；地点是标注，不是主语。卡片与视角先写人，后写地点。
- 个人化而不是机构腔；分析而不学究；尊重而不猎奇：不用"exotic""traditional people""native"之类的词，不用国旗色、地标照片做装饰。
- 简短、主动语态；按钮说清做什么（"Explore the stories"、"下载 PDF"）；错误说明发生了什么、可以怎么做，不道歉、不含糊。
- 中文、日文界面文字自然表达，不逐字翻译英文；专有名词（Culture, Unfiltered）不翻译。

## 5. 命令（在 `site/` 下）

- `pnpm dev`（5173，`/api` 代理到 8080）；`pnpm lint`、`pnpm typecheck`、`pnpm test`（Vitest + 脚本的测试）、`pnpm build`（含首屏 JS 检查）；
  `pnpm check:api`；`pnpm lighthouse`（见 §2a）。
- 端到端：先运行打包的应用（`./gradlew :culture:bootJar`，以 `JABIZ_PUBLIC_ENABLED=true`、`JABIZ_JWT_SECRET`、`JABIZ_BOOTSTRAP_ADMIN_USER/PASSWORD`、
  `JABIZ_FILES_LOCAL_ROOT` 与数据库环境变量启动），再 `E2E_ADMIN_USER=… E2E_ADMIN_PASSWORD=… pnpm e2e`（`E2E_BASE_URL` 默认
  `http://localhost:8080`）。测试经后台接口新增各自独有的内容，只增不删，可对同一数据库重复运行；CI 的 `e2e` 作业（`.github/workflows/culture.yml`）即如此。
