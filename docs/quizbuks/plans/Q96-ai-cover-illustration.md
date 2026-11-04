# Q96 AI 封面：插画、一张、按要求再生成（2026-11-05 需求方确认"开始"）

需求方（2026-11-05）：AI 生成的封面太简单；不要一次给三个，而是可以给出具体要求、生成一个，不满意再生成一个。
需求方选择：插画由 **Claude 画矢量图（SVG）**，不另接图片模型（不加提供方、密钥与数据处理方）；现有的图形封面**保留**为一种风格。

取代 `Q8-ai.md` A-4 的"3 个候选、只从图库设计"（见该文件"Q96 之后"一节）；A-4 的其余部分（Claude 不生成位图、服务器渲染、平台登记文件、没有写实图片的版权与肖像问题）不变。

## 1. 决定

| # | 决定 |
|---|---|
| D-Q96-1 | 两种风格：`ILLUSTRATION`（插画，缺省）与 `GRAPHIC`（Q8b 的图库封面）。每次请求**一张**（`CoverDesigns.COVERS = 1`）；`QbAiJob` 的 `cover2` / `cover3` 列不删、不再写（不加迁移） |
| D-Q96-2 | 请求加三项：`requirements`（商家的要求，≤ 500 字，可空）、`titleOverlay`（是否在图上显示标题，缺省是）、`titleText`（显示的文字，≤ 40 字，空即由 Claude 按标题写）。要求与标题文字是数据：放进 `<quiz_data>`，系统提示说明它们只描述画面，不能改变格式规则 |
| D-Q96-3 | **再生成**：同一模版、同一商家已有的 `READY` 封面作业，在新请求的同一事务中关闭为 `DISCARDED`（清空候选与文件列，图片由平台清扫）；请求被拒时旧的照旧可选。每次都计入现有限额（每日次数、每月 Token） |
| D-Q96-4 | 插画：Claude 只写 SVG（`viewBox="0 0 1200 630"`），**不写文字**；标题由服务器以现有字体叠加在 Claude 选的位置（上、下、中）的半透明底带上。中日文不缺字、不写错 |
| D-Q96-5 | **SVG 不交给通用渲染库**：服务器以禁止 DTD 的解析器读入，只认一个白名单子集（`svg`、`g`、`defs`、`rect`、`circle`、`ellipse`、`line`、`polyline`、`polygon`、`path`、`linearGradient`、`radialGradient`、`stop`，与填充、描边、透明度、变换、渐变等表现属性及 `style` 中的同名属性），自己用 Java2D 画（`CoverSvg`）。其他元素连同其子树、其他属性一律忽略：没有脚本、外部资源、嵌入图片、文字、滤镜、`use` 引用；颜色只认 `#rgb`、`#rrggbb`、`rgb()`、`rgba()`、常用色名与 `url(#渐变)`。上限：≤ 60 000 字符、≤ 4 000 个元素、嵌套 ≤ 32 层、数字 ≤ 100 000 个、坐标绝对值 ≤ 100 000。不合格的回答算"不是所要的文档"，按现有规则重试（计费） |
| D-Q96-6 | 平台对外部服务的单次请求时限是 2 分钟、不流式：插画请求以 effort `low`、`max_tokens` 12 000 发送（思考少、输出留给图），提示要求 SVG ≤ 24 000 字符；图库封面照旧用参数 `qb.ai.effort`、`max_tokens` 8 000 |
| D-Q96-7 | 候选文档：插画为 `{schema: 2, style: "ILLUSTRATION", covers: [{no: 1, style, text, placement, fileId}]}`，图库为 `{schema: 2, style: "GRAPHIC", covers: [{no: 1, style, palette, layout, motif, text, fileId}]}`；作业的 `params` 记 `style`、`titleOverlay`、`requirements` 与 `titleText`（页面据此预填"按要求再生成"） |

## 2. 改动

- 后端（`content.ai`）：`CoverSvg`（解析与绘制）、`CoverRenderer.renderIllustration`（插画 + 标题底带）、`AiCoverJob.Input` / `Result`（风格、要求、标题）、`AiCoverPrompt`（两种系统提示与模式）、
  `CoverDesigner.illustrate`、`AiCoverHandler`、`AiProcesses`（请求输入、关闭上一张、完成、只能选第 1 张）、本地桩 `tools/quizbuks/claude-stub`（插画请求回答一张固定的 SVG）。
- 商家后台 `sponsor/src/ai/AiCoverPanel.tsx`：风格、要求、是否显示标题与标题文字；一张预览，"使用这张""按要求再生成""放弃"；三语文案。

## 3. 测试

- 单元：`CoverSvgTest`（白名单：脚本、外部链接、`image`、`foreignObject`、`text`、`use`、事件属性、DTD 与实体、超限都不被画出或被拒；路径命令含弧线；渐变；变换）、`AiCoversTest`（提示、模式、处理器的两种风格、叠加文字）。
- IT：`AiCoverIT`（插画：请求 → 一张 → 选用；再生成关闭上一张；图库风格；不合格的 SVG 重试后失败；限额与权限照旧）。
- 前端：`ai.test.tsx`（面板的风格、要求、再生成、axe）。

## 4. 实施记录（2026-11-05）

- `CoverSvg`：解析（`DocumentBuilderFactory`：安全处理、禁止 DOCTYPE、禁止外部实体与 XInclude）、白名单的读取（属性与 `style` 中的同名属性，`fill` / `stroke` 等继承，组的不透明度相乘）、渐变（`objectBoundingBox` 与 `userSpaceOnUse`、`gradientTransform`、经 `href` 借用另一个渐变的色标）、路径（全部命令含弧线，紧凑写法如 `M10-5.5.5`、`a5 5 0 011 1`）、变换，以及界限。
  计划写的是"用 JSVG 渲染"，改为自己画白名单子集：不加依赖，安全边界只有自己解析过的东西（D-Q96-5 即按此写）。没有 `clipPath`、`mask`、`filter`、`pattern`、`use` 与文字：提示中说明不要用。
- `CoverRenderer.renderIllustration`：白底、视图框按"铺满并居中"缩放、标题底带（上、下为整宽并向画面渐隐，中为圆角面板），文字换行与字号沿用图形封面的规则。
- `AiCoverJob.Input` 加 `style`、`requirements`、`titleOverlay`、`titleText`（Q96 之前排队的作业没有 `style`，按图形处理）；`Result` 加 `style` 与 `overlay`；新错误码 `QB_AI_COVER_TEXT`（三语）。
- 请求流程：缺省 `ILLUSTRATION`；要求只许普通文字（可换行），封面文字 1–40 字；插画以 effort `low` 发送，预估按 `AiCoverJob.maxTokens(风格)`；作业参数记风格、要求、标题设置；
  取得 AI 锁、查过限额之后读本模版本人 `READY` 的封面作业，记下新作业之后把它们关为 `DISCARDED`（请求被拒即什么也不改）。
- 完成流程：插画只认文件 `cover-1`，标题与位置再查一次；候选文档 `schema: 2`。没有回答的尝试按风格的输出上限计费。
- 本地桩：插画请求回答一张固定的小场景（天空渐变、太阳、山丘）。
- 商家后台：对话框（风格、要求与字数、是否显示标题、封面文字、语言；从上一张"按要求再生成"时预填其要求并说明会替换它）；预览一张（大图、图上的文字、所提的要求）与"使用这张""按要求再生成""放弃"。

### 测试

- `CoverSvgTest`（7）：形状、样式、变换、渐变与不透明度、路径与弧线、不在名单上的一律不画（脚本、文字、图片、`foreignObject`、`use`、`style`、外部的 `url()`）、DTD 与实体、元素数与深度、坐标、视图框；标题底带。
- `AiCoversTest`（11）：一张、商家的文字取代设计的文字、插画的提示（要求作为数据、转义）与模式、处理器（标题的取舍、位置、没有标题时不需字体）、不合格的 SVG 可重试、按风格的输出上限。
- `AiCoverIT`（8）：插画从请求到使用、再请求关闭上一张（被拒的请求不关）与清扫、图形风格与商家的文字、不合格的 SVG 重试三次后失败且不存文件、原有的权限、过期、失败与限额；`AiCancelIT`、`AiPdfIT`、`AiCostControlIT` 照旧通过。
- 商家后台 Vitest：对话框的两种风格与无标题、预填与替换说明、使用、失败与过期、旧格式的候选（共 685 个全过），lint 与类型检查。
- e2e `ai-cover.spec.ts` 改为一张、按要求再生成、使用；**本机未运行**（本机 e2e 需要 Docker 中的 Mailpit，这次的环境没有），由 CI 的 `e2e` 作业运行。
