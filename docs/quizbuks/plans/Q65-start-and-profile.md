## 1. 商家资料与店铺主页合并

需求方（2026-10-31 确认）：商家后台的"商家资料"（入驻：`QB_SPONSOR_APPLY` / `QB_SPONSOR_PROFILE_SAVE`，`QbSponsor`）与"我的主页"（Q60a，`QbSponsorPage`）重复，合并为一页（Q65b，分支 `1.3/quizbuks-65b-sponsor-profile`）。

- **菜单**：商家后台只有一个入口"商家资料"（en "Sponsor profile"、ja "スポンサー情報"，`/onboarding`）；"我的主页"的菜单项删除，旧地址 `/page` 转到 `/onboarding`。
- **两部分**：① 基本资料 = 原入驻的字段（名字、公司、标志 / 头像、简介），照旧在入驻时审核，批准后修改不再审核；② 店铺主页 = 只有主页自己的：链接名（handle）、语言、标题、封面图、网站，以及发布 / 撤下、状态与审核（照旧，首次发布与下架后都须审核）。店铺主页部分在申请前也显示（与原"我的主页"相同；只有已批准的商户能发布）。
- **主页不再有自己的 `logo` 与 `story`**：`QbSponsorPage` 删去这两个字段，`SponsorPageProcesses.SaveInput` 也不再接受（仍发送的调用方被忽略，平台对未知输入属性不报错）；表 `qb_sponsor_page` 是普通表，两列保留、不再读写（V50 加了列注释），不做数据迁移（只有 staging 数据）。
- **公开主页显示入驻的头像与简介**：模板 `qb.public.sponsor-page` 与 `qb.admin.sponsor-pages` 的结果名 `logo` / `story` 不变（App 的主页无需改动），取自 `QbSponsor.avatar` / `QbSponsor.intro`；公开读取经 `QbSponsor` 的公开视图（固定范围：已批准），白名单由 `userId, company` 扩为 `userId, company, intro, avatar`；主页本身的固定范围（`PUBLISHED`）照旧，只有已发布的主页返回它们。
- **取舍**：已批准商户的头像因此是公开文件（平台按公开行的白名单文件字段推导，D17）。头像的文件编号是随机的 UUIDv4（`privateMetadata()`），只有 `qb.public.sponsor-page` 对已发布的主页给出；主页撤下或下架后封面立即停止公开（`FileAccess.invalidate`），头像则仍作为已批准商户的头像可读（答题者登录后本来就能读取头像）。
- **审核**：主页审核的内容哈希只含 `handle, language, headline, cover, website` 与下架时间（`SponsorPageApprovals.content`），不再含 logo / story；头像与简介随入驻审核。"下架后须经人工审核"（`reviewRequired`）照旧。迁移前提交、尚在审核中的主页，其请求按旧内容绑定，批准时不再匹配，需重新提交（只有 staging 数据）。
- **简介上限 5,000 字**（原 2,000，与原主页的 story 相同）：`AccountLimits.INTRO_LENGTH`、商家后台的输入上限与三语提示；迁移 V50 把只追加表 `qb_sponsor_version.intro` 由 `varchar(2000)` 放宽为 `varchar(5000)`——平台决策 D58 允许文本列加长（摘要不变，`SealedColumnsMigrationIT` 检查），不改名、不删列。
- **管理后台**（扩展）：主页审核页的"标志"与"介绍"显示入驻的头像（按头像策略的尺寸）与简介，并注明它们随入驻审核、不在主页的审核范围内。
