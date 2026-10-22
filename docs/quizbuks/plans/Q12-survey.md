# Q12 调查问卷（计划，2026-10-22 确认）

需求方（2026-10-22）：商家可以把 Quiz 做成**调查问卷**。问卷的题目没有对错；答题人**必须回答全部题目**才能提交；
只要完整提交，不论答了什么，都得到同一笔奖励。商家看到每题各选项的人数（汇总，不看个人的回答）。

阶段号：下一个是 Q11，但"Q11"在设计文档中已是规模缺省问题的标签，为免混淆称为 **Q12**。

## 1. 已确认的决定

| # | 决定 |
|---|---|
| D-Q12-1 | **问卷是整个 Quiz 的属性**：`QbQuiz.kind` = `QUIZ`（缺省）\| `SURVEY`，创建时选定、以后不能改（`immutable`）；要换只能复制成新的。不支持一个 Quiz 中混合计分题与问卷题 |
| D-Q12-2 | **每道问卷题单选或多选**：`QbQuestion.selection` = `SINGLE` \| `MULTIPLE`（只有问卷的题有值；计分题为空，仍按"所选恰为全部正确项"判定）。单选恰选 1 项、多选至少 1 项即为"已回答"。计分题不提供单选标记（它会透露"只有一个正确项"） |
| D-Q12-3 | 问卷的选项**没有正误**（`correct` 一律 false），题目分值一律 1（流程写入，商家不填）。"至少一个正确项"（`QB_QUESTION_NO_CORRECT`）不适用于问卷；问卷的题须有 `selection`（`QB_QUESTION_SELECTION_REQUIRED`），计分题不得有 |
| D-Q12-4 | **奖励只有一档**：问卷的满分 = 题数 n，合格分 = n，档位 [0, n−1] 奖励 0、[n, n] 奖励 R（商家只填 R）。向导替商家生成这两档，服务端以 `PublicationReadiness` 检查形状（`QB_PUBLICATION_SURVEY_PASS_SCORE`、`QB_PUBLICATION_SURVEY_TIERS`），档位、预算、名额、最大支出、卡片与审批照旧，不另写一套 |
| D-Q12-5 | **提交须完整**：问卷的提交有未回答的题即 422 `QB_ATTEMPT_INCOMPLETE`（参数列出题号），单选题选了多项即 422 `QB_ANSWER_SINGLE_CHOICE`；评分为已回答的题数（每题 1 分），完整即满分即得奖励 |
| D-Q12-6 | **超时不付**：超时任务 `QB_ATTEMPT_EXPIRE` 照常按已保存的答案评分，不完整即低于合格分、奖励 0；单选题存了多项按未回答计（保存进度不读快照，`AttemptStatementBudgetIT` 不变，单选只在提交与评分时检查） |
| D-Q12-7 | **结果没有正误**：问卷的 `result` 中每题的 `correct` 为空列表、`earned` 为 0 或 1；App 的结果页显示"已完成"与奖励，不显示对错；历史显示"已完成 / 未完成"而不是"及格 / 不及格" |
| D-Q12-8 | **汇总结果**（Q12b）：每题各选项的人数，由分析任务聚合（经 `optionOrder` 换回版本的编号，只有系统任务读得到），沿用最小分组 k（至少 k 个完成才显示、新增至少 k 个完成才变化）；不给出个人的回答，也不导出个人的回答 |
| D-Q12-9 | **AI 出题本阶段不支持问卷**：提示与输出格式要求正确项；问卷的模版请求即 422 `QB_AI_SURVEY_UNSUPPORTED`，页面不显示 AI 面板（封面照常可用） |

## 2. 快照与哈希

- 问卷的版本快照多一个顶层键 `"kind": "SURVEY"`，题目多一个键 `"selection"`；**计分题的快照不写这两个键**，
  已有版本的内容与哈希、场景快照中的哈希、已绑定内容哈希的审批因此都不变。`QuizSnapshot.SCHEMA` 仍为 1：读取时没有 `kind` 即 `QUIZ`。
- 卡片 `QbPublicationCard.kind` 在上架时从快照冻结（迁移中旧行缺省 `QUIZ`），App 的首页、详情、历史与答题只读卡片。

## 3. 两个 PR

### Q12a 问卷（`1.2/quizbuks-12a-survey`）

| 部分 | 改动 |
|---|---|
| 迁移 V19 | `qb_quiz_version.kind`（缺省 `QUIZ`）、`qb_question_version.selection`、`qb_publication_card.kind`（缺省 `QUIZ`） |
| 内容 | `QbContent`（字段与字典 `quiz-kind`、`question-selection`）、`QuizSnapshot` / `QuizSnapshots`、`QuizCompleteness`、`QuizProcesses`（创建时的 `kind`、克隆沿用）、`QuestionProcesses`（问卷：分值 1、正误 false、`selection`）、AI 请求拒绝问卷 |
| 发布 | `PublicationReadiness` 的问卷检查、卡片的 `kind`、审批内容不变（档位与合格分已在其中） |
| 答题 | `Scoring`（问卷按已回答计分）、`AttemptFinishing`（提交须完整、单选）、开始与继续的输出带 `kind` 与每题的 `selection`、`PlayCodes` |
| 模板 | 首页、详情、历史、商家与后台读版本与卡片的模板带 `kind` |
| 前端 | App：问卷的单选（单选按钮）与多选、全部回答前不能提交、结果页与历史；商家后台：新建时选 Quiz / 问卷、编辑器（单选 / 多选，隐藏正误与分值）、发布向导只填一笔奖励、版本列表与预览；后台扩展：版本预览与发布审核显示"问卷"与每份奖励 |
| 测试 | 单元（完整性、快照的哈希不变与问卷的键、评分、发布检查）、`SurveyIT`（完整提交得奖、不完整与单选多项被拒、超时不付、类型不可改、AI 拒绝）、场景 `survey.yml`、前端 Vitest、e2e `survey.spec.ts`；已有场景快照只多出 `kind`（`QUIZ`）与 `selection`（空）两个字段，哈希不变 |

### Q12b 问卷结果（`1.2/quizbuks-12b-survey-results`）

分析任务按版本的选项编号聚合每题各选项的人数（新表，迁移 V20），模板 `qb.sponsor.analytics.survey-answers`（k 规则同 Q9e），商家的分析页显示每题的分布与合计，导出经平台的 `POST /api/queries/{id}/export`。

## 4. 实施约定（Q12a）

- 是否完整只在提交与评分时检查（`Answers.incomplete`、`Scoring`），保存进度不读快照（`AttemptStatementBudgetIT` 不变）；超时以已保存的答案评分，单选存了多项按未回答计。
- 问卷的规则在三处：流程写入（分值 1、没有正确项）、`QuizCompleteness`（保存版本与提交发布时再查：`QB_QUESTION_SELECTION_REQUIRED`、`QB_QUESTION_SURVEY_SCORED`）与 `PublicationReadiness`（合格分与档位）；AI 的请求与接受都拒绝问卷。
- 只有问卷的快照写 `kind` 与 `selection`；卡片的 `kind` 在上架时冻结，App 只读卡片与开始的输出。
- 不完整的提交在发布锁内才被拒绝（App 在全部回答前不让提交）；锁外的预先检查不做，以免答题路径多一条读取。
- 在 Q12b 之前，商家分析与后台的"正确率""及格"对问卷读作"作答率""完成"。

## 5. 风险

- **随便点也有钱**：问卷鼓励乱答。现有的保护照旧：一人一次、已验证邮箱、收款时的身份核实；注意力检查题等质量控制不在本阶段。
- **计分 Quiz 的路径必须不变**：快照、评分、档位与页面的分支都以 `kind` 区分，已有测试与场景快照守住计分 Quiz。
