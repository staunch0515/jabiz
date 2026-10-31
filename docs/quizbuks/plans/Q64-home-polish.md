# Q64 首页打磨

## 4. 发布自己的封面

需求方决定（2026-10-31）：要允许发布有自己的封面图片，虽然可能试卷一样，但是发布要有自己不一样的封面图片。

- `QbPublication` 新增可选字段 `cover`（文件，策略 `qb.content.image`，迁移 `V49__quizbuks_publication_cover.sql` 只给 `qb_publication_version` 加列）；商家在草稿中经 `QB_PUBLICATION_SAVE` 与其他字段一起保存（每次保存给出整个草稿，不给即清空），必须是商家自己上传的文件（`QuizEditing.requireOwnFile`，`QB_CONTENT_FILE_NOT_OWN`）；复制发布时一起复制。
- **提交即冻结照旧**：只有 `DRAFT` 能改封面（`QB_PUBLICATION_NOT_DRAFT`），已上架的发布不能改，要换封面就复制，复制出的草稿照常再审核；没有绕过审核的途径。
- 审批绑定封面：有自己的封面时，审批内容（`PublicationApprovals.content`）含 `cover`，批准的就是上架的；没有时内容与以前相同（Q64 之前的审批哈希不变）。后台审核页在设置中显示它（没有时注明"使用模版的封面"）。
- 卡片的 `cover` = 发布自己的封面，否则版本的封面（`PublicationEditing.cardCover`）：上架时（`goingOn`）与版本切换（Q51，`PublicationVersions.make`）都按此写，切换不换掉发布自己的封面。答题人的首页、详情与历史只读卡片，因此随之；商家后台的列表（`qb.sponsor.publications`）同样先取发布自己的。
- 商家后台：向导第一步在名称之下有"封面图片"（与模版编辑器同一个 `FileField`：上传、预览、去掉），说明显示在首页卡片与开始页、不设置时用 Quiz 的封面；预览（`publicationPreview`）同样先取发布自己的封面。
- 测试：`PublicationCoverIT`（卡片、首页、详情、历史、商家列表；审批哈希；冻结与复制；版本切换；他人的文件）、`PublicationApprovalsTest`、商家后台与后台扩展的 Vitest。
