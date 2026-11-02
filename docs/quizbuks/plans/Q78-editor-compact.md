# Q78：商户的题目编辑页收短 + 店员菜单归入"设置"

来源：需求方 2026-11-02 的八项要求中的第一、第四项（`docs/quizbuks/05-console-requests.md`）。只改商户后台的前端（`quizbuks-web/sponsor`），不改流程、数据与接口。

## 0. 为什么

- 编辑器的"题目"页把每一题完整铺开（题干、题图、全部选项与正确标记、四个按钮），上面还常驻 AI 出题面板；二三十题就要滚很久，找一题、挪一题都费力。列表本身只读（编辑在对话框里），它只需让人认得出是哪题。
- 店员是商户自己的人，放在"奖励"组里不好找；"设置"组是商户自己的事。

## 1. 现状

| 处 | 现在 |
|---|---|
| `content/QuizEditorPage.tsx` | `PageHeader`（标题、种类 / 状态 / 版本徽章、四个按钮：保存版本、预览、复制、删除）；其下依情形出现 `stale`、版本被拒的 `version-problems`、`version-saved`、`version-incomplete`；六个标签页 |
| `content/QuestionsPanel.tsx` | 提示行 + "添加题目"；`<ol>` 每题一张 `Card`：题号、分数徽章（`useScoredBadge`）、`question-choice`（单选 / 多选）、题干全文 `question-stem-text`、题图（160px）、全部选项（`option`，正确的打勾 + `sr-only` "(correct)"、选项图 64px）、按钮 `question-edit` / `question-up` / `question-down` / `question-delete`。排序经 `QB_QUESTION_REORDER`（`reorderQuestions`），移动后 `useMoveFocus` 把焦点与播报放到新位置 |
| `ai/AiQuestionsPanel.tsx` | 常驻 `Card`（标题、说明、"用 AI 出题"按钮、剩余额度、进行中 / 失败 / 过期 / 待校对的状态区）；待校对时内嵌 `Review` |
| `content/MaterialsPanel.tsx` | 同样每条资料一张 `Card`（上限小得多，暂不动） |
| `App.tsx` 菜单 | `rewards` 组：现金、电子奖品、兑换、**店员**；`settings` 组：入驻、商品、统计、邮件服务器、消息、账户、帮助 |
| 测试 | 单元 `content/content.test.tsx`、`ai/*.test.tsx`；e2e `content.spec.ts`（`question`、`question-up`、`question-stem-text`、`question-edit`）、`a11y.spec.ts`（`question`、`question-edit`）、`ai.spec.ts`（`ai-panel`、`ai-generate` …） |
| 上限 | `LIMITS.questions = 100`（`ContentLimits.MAX_QUESTIONS`） |

`@jabiz/ui` 没有 Accordion / Collapsible 组件（`components/ui` 里没有）；规则是组件只在平台一处（D34），应用不自己复制 shadcn 组件。

## 2. 决定（草案，待确认）

| # | 决定 |
|---|---|
| D-Q78-1 | **题目列表默认折叠成一行**：题号、题干首行（CSS 截断，`line-clamp-1`）、分数、单选 / 多选、选项数（"4 个选项"）、有图的小图标；一行点开展开为现在的完整卡片（题干、题图、选项、按钮）。展开是**披露**（`Button` 带 `aria-expanded` / `aria-controls` + 条件渲染），不引入新组件、不改平台；以后平台有了 Accordion 再换。一次只展开一题（点另一题即收起前一题）；刚添加、刚编辑、刚移动的题自动展开并聚焦（接在 `useMoveFocus` 之后）。 |
| D-Q78-2 | **排序**：上 / 下两个按钮改为一个"移到…"的下拉（`Select`，1..n）+ 保留键盘可达；仍经 `QB_QUESTION_REORDER` 整表重排，一次移到任意位置（现在一次一格）。按钮区在折叠行上就有（编辑、移到、删除），不必展开才能操作。不做拖拽（不引入新依赖）。 |
| D-Q78-3 | **AI 出题面板收起**：缺省只有一行（"用 AI 出题"按钮 + 剩余额度一句），点按钮直接开请求对话框（现在也是）；只有**进行中、失败、过期、待校对**时面板才展开成卡片显示状态与候选。`ai-panel` 的 `data-status` 照旧，`ai-*` 的 testid 不变。 |
| D-Q78-4 | **摘要行**在列表上方：题数 / 上限、总分、单选 × 多选 ×、"n 题还没标正确答案"（有则红字，点它展开第一题这样的题）。只是前端算的，不请求服务器。 |
| D-Q78-5 | **页头只留一条告警**：优先级 `stale` > 版本被拒 > 译文不完整 > 已保存；窄屏（< `sm`）复制与删除收进一个"更多"下拉（`DropdownMenu`），保存版本与预览留在外面。testid 不变。 |
| D-Q78-6 | 工具条（添加题目 + 摘要）在滚动时 `sticky top-0`（页面主区的滚动容器内，带背景色与下边线）。 |
| D-Q78-7 | **店员菜单**移到 `settings` 组，放在"入驻"之后、"商品"之前；`rewards` 组剩现金、电子奖品、兑换。路径 `/staff` 不变。 |
| D-Q78-8 | 资料页（`MaterialsPanel`）这次**不改**：上限小、每条本来短；同一个折叠行组件写成可复用（`content/Disclosure.tsx`），Q79 或以后给资料用。 |

## 3. 改动

### 3.1 `sponsor/src/content/`

- 新 `QuestionRow.tsx`（或并入 `QuestionsPanel.tsx`）：折叠行 + 展开卡片；`data-testid="question"`、`data-question`、`data-expanded`；行上的按钮 `question-edit`、`question-move`（下拉）、`question-delete`；展开区 `question-detail` 里是现有的题干 `question-stem-text`、选项 `option`、`question-choice`。`question-up` / `question-down` 去掉。
- `QuestionsPanel.tsx`：摘要行 `questions-summary`（`questions-count`、`questions-score`、`questions-unmarked`）；一次一题的展开状态；添加 / 编辑 / 移动后展开该题（`useMoveFocus` 的回调里 `setExpanded(id)`）。
- `Disclosure.tsx`：按钮 + 面板的披露，属性 `expanded`、`onToggle`、`summary`、`children`、`testId`；键盘 Enter / Space；`aria-controls` 指向面板的 `id`。
- 文案（`content/messages.ts` 三语）：`questions.summary.*`、`questions.options`（"{{n}} 个选项"）、`questions.moveTo`、`questions.expand` / `collapse`、`questions.unmarked`（"{{n}} 题还没标正确答案"）。

### 3.2 `sponsor/src/ai/AiQuestionsPanel.tsx`

- 空闲（`latest` 为空或 `ACCEPTED` 已关闭）时渲染一行：按钮 `ai-generate` + `ai-remaining`；`ai-off` 照旧一行。其他状态渲染现在的卡片。请求对话框不变。

### 3.3 `sponsor/src/content/QuizEditorPage.tsx`

- 告警区：按 D-Q78-5 只渲染第一条成立的；`version-saved` 仍在保存后短暂显示（它在优先级最后，其他告警存在时不显示——测试里"已保存"的断言要确认没有同时存在的告警）。
- 窄屏的"更多"菜单：`editor-more`（`DropdownMenu`）里 `copy-quiz`、`delete-quiz`；宽屏照旧并排。e2e 以桌面视口运行，选择器不变。

### 3.4 `sponsor/src/App.tsx`

- `staff` 从 `rewards` 移到 `settings`（D-Q78-7）。文案不变。

## 4. 测试

- `content/content.test.tsx`：
  - 折叠行显示题号、首行、分数、选项数；点行展开，展开另一题时前一题收起；`aria-expanded` 正确；键盘展开。
  - 添加后新题展开并聚焦；编辑后保持展开；"移到"下拉发出整表的新顺序（`QB_QUESTION_REORDER` 的输入），移动后焦点与播报在新位置。
  - 摘要：题数、总分、单选 / 多选、没标正确答案的题数与点击展开。
  - 页头告警只出现一条（`stale` 与"已保存"同时时只见 `stale`）。
  - axe 两种外观（已有的 `expectAccessible`，加展开态）。
- `ai/aiQuestions.test.tsx`（或现有测试文件）：空闲时只有一行、无 `CardHeader`；进行中 / 失败 / 待校对展开；现有断言不变。
- `App` 菜单测试（若有按组断言）：店员在 `menu-group-settings` 中。
- e2e：`content.spec.ts` 第 78–84、123 行改为"展开第 2 题 → 移到 1"（或直接用行上的 `question-move`）；`a11y.spec.ts` 第 183 行 `question-edit` 在行上，不必展开；`ai.spec.ts` 的 `ai-generate` 在一行里照旧可点。
- `pnpm lint`、`pnpm typecheck`、`pnpm test`、`pnpm app:check ../quizbuks-web/sponsor`。

## 5. 文档

- `02-design.md` §7 商户后台一节：题目列表折叠与摘要、AI 面板按状态展开、菜单分组。
- `backend/quizbuks/CLAUDE.md` 第 5 节"内容工坊"一行：列表只读且折叠，操作在行上；新增列表项时照 `Disclosure`。
- `05-console-requests.md` 第一、四项标"已实现"；`03-plan.md` 加 Q78 一行。

## 6. 估计

| 部分 | 天 |
|---|---|
| 折叠行、摘要、移到、展开状态与焦点 | 0.5 |
| AI 面板按状态展开、页头告警与"更多" | 0.25 |
| 菜单、文案三语、单元测试、e2e 选择器 | 0.5 |
| **合计** | **1–1.5** |

## 7. 风险

| 等级 | 风险 | 对策 |
|---|---|---|
| 低 | 折叠后看不到选项，核对题目要多点一下 | 行上有选项数与"没标正确答案"的提醒；摘要给总览；一次只展开一题使对比仍方便 |
| 低 | "已保存"提示被其他告警挤掉 | 只在没有更要紧的告警时显示；保存成功另有 `notify` 吐司 |
| 低 | e2e 与无障碍测试的选择器变化 | 本 PR 一并改；`question`、`question-edit`、`question-stem-text`、`ai-*` 不变 |
| 低 | 窄屏"更多"菜单里的删除需二次确认 | 仍经 `ConfirmDialog`，菜单项只是触发 |

## 8. 需要需求方确认的

1. D-Q78-1：默认全部折叠、一次只展开一题（而不是记住每题的开合）。
2. D-Q78-2：上 / 下按钮换成"移到…"下拉，不做拖拽。
3. D-Q78-3：AI 面板空闲时只留一行按钮。
4. D-Q78-7：店员放在"设置"组的"入驻"之后。
5. D-Q78-8：资料页这次不动。
6. 编号 Q78。
