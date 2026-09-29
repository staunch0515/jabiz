# Culture, Unfiltered — 应用设计

需求原文见 `brief.md`（下文"简报 §n"指其中的章节）。本文件是应用 culture 的设计，状态：**已确认**（C1 计划确认时一并确认，含 C1、C2 中的调整，见 §14、§15）。
平台部分（文件、公开访问、内容编辑、平台与应用分开、版本线）在平台分支（线 1.0：`1.0/platform`）的 `docs/design/14–17` 与决策 D17–D19、D21 中，本文件只引用，不重复。

## 0. 设计的出发点

简报的核心一句话是：**记录个人经验，而不是整个文化**。它直接约束信息结构：

- 一级导航是**人、主题、故事**，不是国家。国家（地点）只作为筛选条件、卡片上的小标注和次要的地图入口（简报 §3、§13）。
- 每条内容都挂在**具体的人**名下；页面上始终写"某某在某地的经验"，从不写"日本人如何如何"。
- 主题页是"比较不同的人对同一个问题的回答"（简报 §8），这是数据模型的中心：**故事 × 参与者 = 一个视角（Contribution）**。
- 国家、参与者、主题、故事都是数据，数量不写死（简报 §20）。首页的"Six teenagers. Six places."也来自可编辑的文案块。

另外两条贯穿全部设计的约束（不是简报原文，但由简报的 CONSENT 与 RESPONSIBLE REPRESENTATION 原则和"参与者是青少年"推出）：

- **未成年人的隐私与同意是硬性要求**：没有有效的同意记录就不能公开；照片去掉 GPS 等元数据；不公开姓氏、学校和精确位置；撤回同意后内容立即下线，并且可以真正删除。
- **无障碍是验收项**：照片必须有替代文本、视频必须确认有字幕，否则不能发布（简报 §18）。

## 1. 与平台的关系

| 需要的能力 | 来自 |
|---|---|
| 实体、校验、状态机、数据视图与范围、流程、业务参数（开关）、定时任务、审计 | 平台已有（02、03、06、04 §9、11） |
| 上传照片、PDF、音频；去元数据；图片尺寸变体 | 平台阶段 13b（14、D18） |
| 匿名访客读取已发布内容；公开文件 | 平台阶段 13c（15、D17） |
| 多语言文本、引用选择、流程行操作、子实体列表、只经流程写入的字段 | 平台阶段 13d（16） |
| 同一个 jar 提供公开网站（`/`）与后台（`/admin/`） | 平台阶段 13a（17、D19） |
| 数据模型、工作流、同意规则、公开模板、公开网站、视觉设计 | **本应用**（本文件） |

culture 不修改平台目录（`.jabiz-app-paths`）；做 culture 时发现的平台缺口，先在所在线的平台分支（`1.0/platform`）上补（例如 16 §5 的 `processOnly` 就是这样加入的）。

## 2. 目录

| 路径 | 内容 |
|---|---|
| `backend/culture/` | Gradle 模块（`jabiz.boot-app`），包 `com.jabiz.culture`，启动类 `CultureApp`；迁移 `db/migration/V*__culture_*.sql`（表前缀 `cu_`）；公开模板 `queries/culture/**/*.sql`；文案 `messages_{zh,ja,en}.properties`；`CLAUDE.md`（应用规则） |
| `site/` | 公开网站（React 19 + Vite，不用 antd，D19 第 4 条）；`site/CLAUDE.md` |
| `docs/culture/` | 简报、本设计、路线图、编辑指南 |
| `deploy/culture/` | compose（数据库、应用、文件卷、可选的 TLS 与观测） |
| `.github/workflows/culture.yml` | 公开网站的检查与端到端、应用的 compose 冒烟 |

## 3. 数据模型

全部是**普通实体**（非时态）：内容里有未成年人的个人信息，删除权要求能真正删除（04 §10、D18 的同一理由）。
修改的"谁、何时、做了什么"由流程操作记录（`op_process`）保留；为此 culture 的流程**只接收主键与枚举**，自由文本输入标 `@Sensitive`（见 §6.4）。
主键均为 UUIDv7（以文本 `varchar(36)` 存储：平台对普通实体主键的规范类型是文本）；每个实体有 `version`（乐观锁）。下表中"公开"列是公开数据视图的白名单（§7.1）。

`i18n(n)` 表示 `jabiz.i18n-text`（16 §1，每种语言至多 n 字符，英语必填除非注明）；`md` 表示 Markdown；`file(p)` 表示 `jabiz.file`（14 §4），`p` 为策略；
`PO` 表示 `processOnly()`（16 §5）。

### 3.1 地点 `Location`（`cu_location`）

| 字段 | 类型 | 公开 | 说明 |
|---|---|---|---|
| `slug` | Text(40)，`PATTERN [a-z0-9-]+`，唯一 | ✓ | URL 与筛选用，如 `japan`、`eswatini` |
| `name` | i18n(80) | ✓ | 如 "Japan"。地点也可以是"伦敦的波兰社区"这类非国家单位（简报 §20） |
| `countryCode` | Text(2)，`PATTERN [A-Z]{2}`，可空 | ✓ | ISO 3166-1；前端据此显示小旗帜（区域指示符），不存图片 |
| `placeLabel` | i18n(80)，可空 | ✓ | 最细到城市或地区（如 "Tokyo area"），**不写学校与街区** |
| `latitude` / `longitude` | Numeric(8,5) / Numeric(9,5)，`RANGE` | ✓ | 地图标记位置；取城市或国家中心，不取住址 |
| `sortOrder` | Numeric(5,0) | ✓ | |
| `visible` | Bool | 范围 | |

### 3.2 主题 `Theme`（`cu_theme`）

| 字段 | 类型 | 公开 | 说明 |
|---|---|---|---|
| `slug` | Text(40)，唯一 | ✓ | `home`、`school`、`language`、`food`、`stereotypes`、`family`、`digital-life`、`identity` |
| `icon` | Text(8) | ✓ | 一个 emoji（简报 §8 的 🏠 等），装饰性，读屏时隐藏 |
| `title` | i18n(60) | ✓ | |
| `question` | i18n(200) | ✓ | 该主题的人类学问题，如 "What makes somewhere feel like home?" |
| `intro` | i18n(4000) md，英语可空 | ✓ | 主题页导语 |
| `sortOrder`、`visible` | | ✓ / 范围 | |

### 3.3 参与者 `Participant`（`cu_participant`）

| 字段 | 类型 | 公开 | 说明 |
|---|---|---|---|
| `slug` | Text(40)，唯一 | ✓ | |
| `displayName` | Text(40)，必填 | ✓ | **名或化名，不写姓**（编辑指南中强调） |
| `locationId` | Reference(Location)，必填 | ✓ | |
| `portraitFileId` | file(`culture.image`)，可空 | ✓ | 可用插画或头像代替照片 |
| `portraitAlt` | i18n(200)，有头像时英语必填 | ✓ | 实体级校验 `PORTRAIT_ALT_REQUIRED` |
| `shortBio` | i18n(300) | ✓ | 卡片上的一句话（简报 §6 的 "Interested in …"） |
| `bio` | i18n(3000) md | ✓ | About |
| `perspective` | i18n(600) | ✓ | "My Perspective"（简报 §7） |
| `reflection` | i18n(5000) md，可空 | ✓ | Personal Reflection |
| `interests` | i18n(200)，可空 | ✓ | 逗号分隔，前端显示为标签 |
| `languages` | Text(200)，可空 | ✓ | **留空即不公开**（简报 §6 "if the participant wishes to disclose"）；因此不需要另设"是否公开"开关 |
| `sortOrder` | Numeric(5,0) | ✓ | |
| `accountActorId` | Text(64)，可空，唯一 | ✗ | 通讯员登录账号的操作人标识（§5 的范围字段） |
| `adult` | Bool | ✗ | 已成年：不要求监护人同意（§6.2） |
| `curatorNote` | Text(2000) 多行，可空 | ✗ | 内部备注 |
| `status` | Code `DRAFT` `ACTIVE` `HIDDEN` `WITHDRAWN`，PO | 范围 | §6.1 |

### 3.4 故事 `Story`（`cu_story`）

| 字段 | 类型 | 公开 | 说明 |
|---|---|---|---|
| `slug` | Text(80)，唯一 | ✓ | |
| `title` | i18n(120)，必填（任一语言；英语由发布检查要求） | ✓ | |
| `summary` | i18n(300)，可空（英语由发布检查要求） | ✓ | 卡片上的短描述 |
| `about` | i18n(3000) md，英语可空 | ✓ | "About this story"：研究问题 |
| `body` | i18n(40000) md，英语可空 | ✓ | 文章正文（`ARTICLE`、`INTERVIEW` 用） |
| `mediaType` | Code，字典 `culture.media-type`：`VIDEO` `ARTICLE` `PHOTO` `INTERVIEW` `AUDIO` | ✓ | 数据库字典，可增加（简报 §14） |
| `storyDate` | Temporal(EVENT_TIME) | ✓ | 记录发生的日期（简报 §9 的 date） |
| `thumbnailFileId` | file(`culture.image`)，可空（发布检查要求） | ✓ | |
| `thumbnailAlt` | i18n(200)，可空（英语由发布检查要求） | ✓ | |
| `videoProvider` | Code `YOUTUBE` `VIMEO`，可空 | ✓ | 故事自己的主视频（可无，视频也可在各视角中） |
| `videoId` | Text(32)，`PATTERN [A-Za-z0-9_-]{6,32}`，可空 | ✓ | **只存平台 + 视频 ID，不存任意 URL**（嵌入地址由前端按白名单拼出） |
| `captionsConfirmed` | Bool | ✗ | 编辑确认该视频有准确的字幕（发布检查，§6.3） |
| `transcript` | i18n(40000) md，可空 | ✓ | 文字稿（无障碍与搜索） |
| `reflectionSurprised` / `reflectionAssumed` / `reflectionLearned` | i18n(3000) md，英语可空 | ✓ | 简报 §10 的三个问题 |
| `featured` | Bool | ✓ | 首页精选 |
| `ownerActorId` | Text(64)，可空 | ✗ | 通讯员创建时由范围自动填入（§5）；编辑创建的为空 |
| `status` | Code `DRAFT` `IN_REVIEW` `PUBLISHED` `UNPUBLISHED`，PO | 范围 | §6.1 |
| `publishedTime` | Temporal(EVENT_TIME)，PO | ✓ | 首次发布时间 |
| `reviewNote` | Text(2000) 多行，PO | ✗ | 退回时写给通讯员的意见 |

### 3.5 故事的主题 `StoryTheme`（`cu_story_theme`）

`storyId`、`themeId`（唯一 `(storyId, themeId)`）、`visibility`（Code `PRIVATE` `PUBLIC`，PO，由发布 / 下线流程维护，公开范围）。一个故事可以属于多个主题。

`visibility`（本节与 §3.6、§3.7）声明为状态机 `PRIVATE ⇄ PUBLIC`，显式初始状态 `PRIVATE`（平台 13e 的 `st.initial`）：
因此它可以必填且 `processOnly`，经任何数据视图插入时由平台填为 `PRIVATE`。

### 3.6 视角 `Contribution`（`cu_contribution`）——模型的中心

一个参与者在一个故事中的那一部分（简报 §8 "HOME: Japan → video …"、§10 "Perspectives"）。

| 字段 | 类型 | 公开 | 说明 |
|---|---|---|---|
| `storyId` | Reference(Story) | ✓ | 唯一 `(storyId, participantId)` |
| `participantId` | Reference(Participant) | ✓ | |
| `heading` | i18n(120)，可空 | ✓ | |
| `text` | i18n(10000) md，可空 | ✓ | 文字、观察记录 |
| `videoProvider` / `videoId` / `captionsConfirmed` / `transcript` | 同 Story | ✓ / ✓ / ✗ / ✓ | |
| `audioFileId` | file(`culture.audio`)，可空 | ✓ | 音频片段；发布要求有 `transcript` |
| `sortOrder` | Numeric(5,0) | ✓ | |
| `ownerActorId` | Text(64) | ✗ | 通讯员范围字段 |
| `editable` | Bool，可空，PO | ✗ | 故事为 `DRAFT` 时为 true；通讯员可写视图的范围（§5）。经编辑的默认视图插入时为空（等同 false：通讯员本来也看不到别人的行） |
| `visibility` | Code `PRIVATE` `PUBLIC`，PO | 范围 | |

### 3.7 媒体 `MediaItem`（`cu_media_item`）

故事或视角中的照片与音频（一组照片，每张一行）。

| 字段 | 类型 | 公开 | 说明 |
|---|---|---|---|
| `storyId` | Reference(Story) | ✓ | |
| `contributionId` | Reference(Contribution)，可空 | ✓ | 属于某个视角时填写 |
| `kind` | Code `PHOTO` `AUDIO` | ✓ | |
| `imageFileId` / `audioFileId` | file(`culture.image`) / file(`culture.audio`)，可空 | ✓ | 一个文件字段只能有一个策略，因此分两列；实体级校验 `MEDIA_FILE_KIND`：`PHOTO` 恰有图片、`AUDIO` 恰有音频 |
| `alt` | i18n(300)，`PHOTO` 时英语必填 | ✓ | 替代文本（简报 §18） |
| `caption` | i18n(500)，可空 | ✓ | |
| `credit` | Text(80)，可空 | ✓ | |
| `showsIdentifiablePeople` | Bool | ✗ | 照片里有可辨认的人（包括家人、同学） |
| `peopleConsentConfirmed` | Bool | ✗ | 编辑确认已取得这些人（或其监护人）的同意；发布检查 |
| `sortOrder` | Numeric(5,0) | ✓ | |
| `ownerActorId`、`editable`、`visibility` | 同 Contribution | ✗ / ✗ / 范围 | |

### 3.8 教学资源 `Resource`（`cu_resource`）与 `ResourceStory`（`cu_resource_story`）

| 字段 | 类型 | 公开 | 说明 |
|---|---|---|---|
| `slug` | Text(80)，唯一 | ✓ | |
| `title` | i18n(120) | ✓ | |
| `description` | i18n(1000) | ✓ | |
| `activityType` | Code，字典 `culture.activity-type`：`CLASSROOM` `DISCUSSION` `REFLECTION` `LANGUAGE` | ✓ | 可增加 |
| `ageGroup` | Code，字典 `culture.age-group`：`11-13` `14-16` `16-18` `ADULT` | ✓ | |
| `durationMinutes` | Numeric(4,0)，`RANGE 5..600` | ✓ | |
| `pdfFileId` | file(`culture.pdf`)，可空 | ✓ | 可下载的 PDF |
| `body` | i18n(20000) md，英语可空 | ✓ | 活动步骤（页面上也能直接阅读，不只是 PDF：无障碍） |
| `sortOrder` | | ✓ | |
| `status` | Code `DRAFT` `PUBLISHED`，PO | 范围 | 发布检查：有描述、年龄、时长 |

`ResourceStory`：`resourceId`、`storyId`（唯一），公开视图 `allRows()`（只有两个主键）；公开模板一律与公开的 `Story` 联接，因此未发布的故事不会出现。

### 3.9 文案块 `SiteBlock`（`cu_site_block`）

首页、方法页、关于页等固定页面上的文字，也由编辑维护（简报 §15 "不改设计就能改内容"）。

| 字段 | 类型 | 公开 | 说明 |
|---|---|---|---|
| `blockKey` | Text(80)，`PATTERN [a-z0-9.-]+`，唯一 | ✓ | 如 `home.hero.subtitle`、`home.question`、`method.reflexivity`、`method.principle.consent` |
| `body` | i18n(10000) md | ✓ | |
| `note` | Text(500)，可空 | ✗ | 给编辑的说明：这个块显示在哪里 |

迁移以简报原文预置英语内容（普通表，可直接插入）；中文与日文由内容团队补充，缺失时按 16 §1 回退到英语。

### 3.10 同意记录 `Consent`（`cu_consent`）——从不公开

| 字段 | 类型 | 说明 |
|---|---|---|
| `participantId` | Reference(Participant) | |
| `party` | Code `PARTICIPANT` `GUARDIAN` | 本人或监护人 |
| `coversPhoto` / `coversVideo` / `coversVoice` | Bool | 同意公开的媒体种类（文字与名字由本人同意本身覆盖） |
| `signedOn` | Temporal(EVENT_TIME)，不晚于当前 | |
| `documentFileId` | file(`culture.consent-doc`)，可空 | 签字的同意书扫描件；策略只有 `culture.consent.read` 可读，且没有任何公开视图引用它，因此**永远不能匿名获取**（15 §4） |
| `withdrawnTime` | Temporal(EVENT_TIME)，PO | 撤回时间 |

除 `withdrawnTime` 外全部 `immutable`：同意记录不修改，更正 = 撤回 + 新记录。
谁在何时登记了同意记录，由数据视图 `commit` 的操作记录保留（操作人、时间、实体与主键，10 §6），因此不另设 `recordedBy` 字段
（平台只能以范围字段自动填入操作人，而范围会让编辑只看到自己登记的记录）。

### 3.11 文件策略（14 §3）

| 策略 | 类型 | 上限 | 处理 | 上传 / 读取权限 |
|---|---|---|---|---|
| `culture.image` | JPEG、PNG | 15 MB，4000 万像素 | 去元数据、转正，变体 320 / 640 / 1280 / 1920 | `culture.media.upload` / `culture.media.read` |
| `culture.audio` | MP3、M4A、OGG | 30 MB | 无 | 同上 |
| `culture.pdf` | PDF | 30 MB | 无（附件 + sandbox） | `culture.resource.write` / `culture.media.read` |
| `culture.consent-doc` | PDF、JPEG、PNG | 15 MB | 图片去元数据 | `culture.consent.write` / `culture.consent.read` |

视频不上传（简报 §16）：只存 YouTube / Vimeo 的视频 ID。

## 4. 角色与权限

`CULTURE_SETUP`（§6.6）创建以下角色（平台的 `SecRole` 与 `SecRolePermission`）；管理员也可以在后台调整。

| 角色 | 谁 | 权限 |
|---|---|---|
| `ADMIN` | 项目负责人 | `*`（平台首个管理员） |
| `CURATOR` | 编辑 / 指导老师 | `culture.content.read` `culture.content.write` `culture.story.review` `culture.story.publish` `culture.story.unpublish` `culture.participant.manage` `culture.consent.read` `culture.consent.write` `culture.consent.withdraw` `culture.resource.write` `culture.resource.publish` `culture.media.upload` `culture.media.read` `culture.public.read` `platform.param.read` |
| `CORRESPONDENT` | 参与者本人 | `culture.own.read` `culture.own.write` `culture.story.submit` `culture.media.upload` `culture.media.read` |

- 开关（§6.5）的修改需要 `platform.param.write`，默认只有 `ADMIN` 具备——即简报答复中的"由管理人员设置"。
- 抹除个人数据（§6.4）需要 `culture.erase`，只给 `ADMIN`。
- **账号名用化名**（如 `cu-jp-01`）：操作人标识会永久留在只追加的操作表与安全表中，不能写真实姓名。编辑指南中写明。

## 5. 数据视图

| 视图 | 实体 | 范围 | 权限（读 / 写） | 用途 |
|---|---|---|---|---|
| `urn:jabiz:dataset:culture:<实体>`（默认） | 全部 | 无 | `culture.content.read` / `culture.content.write`（Consent：`culture.consent.*`） | 编辑后台 |
| `urn:jabiz:dataset:own-view:Participant` | Participant | `accountActorId` ← 操作人 | `culture.own.read` / 只读 | 通讯员查看自己的资料；资料只由编辑修改（C1 评审：可写视图会让通讯员改 `adult`，绕过监护人同意） |
| `urn:jabiz:dataset:own:Story` | Story | `ownerActorId` ← 操作人；`status = DRAFT` | `culture.own.read` / `culture.own.write` | 通讯员起草自己的故事 |
| `urn:jabiz:dataset:own:Contribution` / `own:MediaItem` | 同名 | `ownerActorId` ← 操作人；`editable = true` | 同上 | 通讯员为故事（包括编辑发起的多人故事）添加自己的视角与照片 |
| `urn:jabiz:dataset:own-view:<Story/Contribution/MediaItem>` | 同名 | `ownerActorId` ← 操作人 | `culture.own.read` / 只读 | 查看已提交、已发布的自己的内容 |
| `urn:jabiz:dataset:public:<实体>` | 除 Consent 外 | 见 §7.1 | `culture.public.read` / 只读 | 公开模板的来源（15 §2）；编辑也可经数据视图 API 预览（C2） |

- 通讯员提交后，故事变为 `IN_REVIEW`、子项 `editable = false`，于是落在可写视图的范围之外：**提交后不能再改**，直到编辑退回。
  这完全由数据视图范围实现（03 §2.2 "更新时不允许把数据移出范围"），不需要额外代码。
- 通讯员新建的行由范围自动填入 `ownerActorId` 与 `editable = true`；`status` 由平台填为初始状态 `DRAFT`（16 §5）。
- 通讯员可以给任何故事添加自己的视角（多人故事由编辑创建）；视角是否属于该通讯员本人的参与者资料，由发布检查核对（§6.3 第 5 条）。

## 6. 工作流

### 6.1 状态机

```
Story:        DRAFT ──submit──► IN_REVIEW ──publish──► PUBLISHED ──unpublish──► UNPUBLISHED
                │  ◄──return─────┘                        ▲                        │
                └──────────publish（开关关闭或编辑直接发布）┘◄────────publish────────┘
              UNPUBLISHED ──reopen──► DRAFT

Participant:  DRAFT ──activate──► ACTIVE ◄──► HIDDEN          （activate 检查同意）
              DRAFT / ACTIVE / HIDDEN ──（撤回同意）──► WITHDRAWN ──reopen──► DRAFT

Resource:     DRAFT ⇄ PUBLISHED
```

状态字段都是 `processOnly`：只能经下列流程改变。这些状态机都会回到起点，初始状态（`DRAFT`、`PRIVATE`）以 `st.initial(...)` 显式声明（平台 13e）。

### 6.2 同意规则

参与者 P 的内容可以公开，当且仅当：
1. P 有一条未撤回的 `PARTICIPANT` 同意记录——**始终要求，不受开关影响**；
2. 开关 `culture.consent.guardian.required` 为真且 P 不是 `adult` 时，另有一条未撤回的 `GUARDIAN` 同意记录；
3. 要公开的媒体种类被同意覆盖：照片（包括头像）→ `coversPhoto`；视频 → `coversVideo`；音频 → `coversVoice`。
   两条记录都需要时，两条都必须覆盖。

### 6.3 流程

全部用平台步骤做 I/O、计算步骤做判断（CLAUDE.md §3），声明权限；带主键的流程声明 `actsOn`（16 §3），后台在行上显示按钮。

| 流程 | 权限 | 作用 |
|---|---|---|
| `CULTURE_STORY_SUBMIT` | `culture.story.submit` | `DRAFT` → `IN_REVIEW`，子项 `editable = false`；开关 `culture.review.required` 为假时，随即以子流程执行 `CULTURE_STORY_PUBLISH`（`CallProcess.when`） |
| `CULTURE_STORY_RETURN` | `culture.story.review` | `IN_REVIEW` → `DRAFT`，写 `reviewNote`，子项 `editable = true` |
| `CULTURE_STORY_PUBLISH` | `culture.story.publish` | 发布检查（下），通过后 → `PUBLISHED`，首次时写 `publishedTime`；故事的全部视角、媒体、主题关联 `visibility = PUBLIC`、`editable = false`。对已发布的故事再次执行 = 重新检查并公开新加入的子项 |
| `CULTURE_STORY_UNPUBLISH` | `culture.story.unpublish` | → `UNPUBLISHED`，子项 `visibility = PRIVATE`；使相关文件的公开判定失效（15 §4） |
| `CULTURE_STORY_REOPEN` | `culture.story.review` | `UNPUBLISHED` → `DRAFT`，子项 `editable = true` |
| `CULTURE_PARTICIPANT_ACTIVATE` / `_HIDE` / `_REOPEN` | `culture.participant.manage` | 参与者状态；`ACTIVATE` 检查 §6.2（有头像时含照片），不满足时 `CONSENT_MISSING`；`HIDE` 使头像的公开判定失效 |
| `CULTURE_CONSENT_WITHDRAW` | `culture.consent.withdraw` | 写 `withdrawnTime`；重新评估 §6.2：P 的内容不再满足时，P → `WITHDRAWN`，**下线所有包含 P 的视角或媒体的故事**，并使文件判定失效 |
| `CULTURE_PARTICIPANT_ERASE` | `culture.erase` | 见 §6.4 |
| `CULTURE_RESOURCE_PUBLISH` / `_UNPUBLISH` | `culture.resource.publish` | 资源状态；`_UNPUBLISH` 使 PDF 的公开判定失效 |
| `CULTURE_SETUP` | `security.role.write` + `security.menu.write` + `platform.param.write` | §6.6 |

**发布检查**（`CULTURE_STORY_PUBLISH` 的计算步骤；全部违规一次返回 422，前端逐条显示）：
1. 故事：`title`、`summary`、`thumbnailAlt` 有英语；有缩略图；至少一个主题；`ARTICLE` / `INTERVIEW` 有 `body` 或视角文字；`VIDEO` 有视频（故事或视角）。
2. 每个视频（故事的、视角的）`captionsConfirmed = true` → 否则 `CAPTIONS_NOT_CONFIRMED`。
3. 每个音频有 `transcript` → `TRANSCRIPT_REQUIRED`；每张照片有英语替代文本 → `ALT_TEXT_REQUIRED`。
4. 有可辨认人物的照片 `peopleConsentConfirmed = true` → `PEOPLE_CONSENT_NOT_CONFIRMED`。
5. 每个视角、每个属于视角的媒体：其参与者为 `ACTIVE`（否则 `PARTICIPANT_NOT_ACTIVE`）且满足 §6.2 → `CONSENT_MISSING`（参数：参与者主键、缺少的同意种类）；
   由通讯员添加的视角（及其媒体），其 `ownerActorId` 必须等于该参与者的 `accountActorId`；通讯员添加的故事级媒体只能在自己的故事上 → `CONTRIBUTION_OWNER_MISMATCH`。
6. 故事至少有一个视角或正文（不存在"空故事"）→ `STORY_EMPTY`。

第 1 条的错误码：英语缺失 `ENGLISH_REQUIRED`（参数 `field`）、`THUMBNAIL_REQUIRED`、`THEME_REQUIRED`、`BODY_REQUIRED`、`VIDEO_REQUIRED`。
违规的 `field` 为 `<实体>.<字段>`，参数带 `entity` 与 `id`；参数与文案中只有主键与枚举，不含名字或正文（失败的操作记录会保存错误信息）。

### 6.4 撤回、抹除与操作记录中的个人信息

- **撤回**立即下线（§6.3）。公开文件最迟在"判定缓存 60 s + 浏览器缓存 300 s"后不可得（15 §4），编辑指南中如实说明。
- **抹除** `CULTURE_PARTICIPANT_ERASE`（前提：P 为 `WITHDRAWN`）：在一个事务中删除 P 的视角、这些视角的媒体、P 的同意记录与参与者行，
  保存后以 `CallProcess.forEach` 对这些行引用的每个文件（头像、音频、照片、同意书）调用 `FILE_DELETE`（平台 13e）。
  同意记录一并删除（§12 问题 4 的决定：C1 不提供保留选项；将来需要保留时另行设计）。
  P 的登录账号所拥有的内容也一并删除：P 自己添加的媒体；P 起草、且没有其他人视角的故事（连同其主题、资源关联、媒体与缩略图）；
  有其他人视角的故事保留，只清空其 `ownerActorId`。P 的登录账号由管理员禁用（平台用户是时态实体，只禁用不删除；账号名本来就是化名）。
- 操作记录（只追加、不可清除）中不能出现个人信息：culture 的流程输入只含主键、枚举与布尔；唯一的自由文本输入（退回意见 `note`）标 `@Sensitive`，
  在 `input_summary` 中为 `***`（它本身保存在可删除的 `Story.reviewNote` 中）。数据视图 `commit` 本来就只记字段名（D12）。

### 6.5 两个开关（平台业务参数，04 §9）

| 参数 | 类型 | 默认 | 含义 |
|---|---|---|---|
| `culture.review.required` | bool | `true` | 通讯员提交后是否需要编辑审核；为假时提交即发布（发布检查照常执行） |
| `culture.consent.guardian.required` | bool | `true` | 未成年参与者是否需要监护人同意记录 |

业务参数是时态的：每次修改都有历史与操作记录，也可以预定生效时间。流程以 `LoadParams` 按操作时间读取。

### 6.6 初始化 `CULTURE_SETUP`

幂等：不存在时创建角色 `CURATOR`、`CORRESPONDENT` 及其权限、后台菜单（§8）、两个开关（以 `CallProcess.forEach` 调用 `PARAM_CREATE`）。
已存在的角色不再补权限（管理员之后的调整得以保留）。首次部署后由管理员在后台执行一次；再次执行不改变已有数据。
数据库字典（媒体类型、活动类型、年龄段）的初始值不在这里创建：启动检查要求字典在启动时已存在，因此它们是迁移
`V3__culture_dictionaries.sql` 中的基础数据（平台函数 `jabiz_dict_put`），编辑之后可在后台增加。

## 7. 公开接口

### 7.1 公开数据视图（15 §2）

| 视图 | 范围 | 白名单 |
|---|---|---|
| `public:Location` | `visible = true` | §3.1 中 ✓ 的字段 + 主键 |
| `public:Theme` | `visible = true` | §3.2 ✓ |
| `public:Participant` | `status = ACTIVE` | §3.3 ✓ |
| `public:Story` | `status = PUBLISHED` | §3.4 ✓ |
| `public:StoryTheme` / `public:Contribution` / `public:MediaItem` | `visibility = PUBLIC` | §3.5–3.7 ✓ |
| `public:Resource` | `status = PUBLISHED` | §3.8 ✓ |
| `public:ResourceStory` / `public:SiteBlock` | `allRows()` | 主键与 ✓ 字段 |

`Consent` 没有公开视图。读权限 `culture.public.read`（编辑经数据视图 API 预览），行数上限为平台默认（≤ `jabiz.public.max-limit`）。

**可见性的连带**（模板遵守，属性测试以此为准）：
- 视角与媒体在页面上出现，当且仅当自身 `PUBLIC`、所属故事 `PUBLISHED`、（视角或所属视角的）参与者 `ACTIVE`。
  因此隐藏的参与者（`HIDDEN`）没有个人页，其视角从所有页面消失，故事本身保持发布。
- 隐藏的主题不出现在故事的主题列表中，也不能用于筛选；隐藏的地点不出现在地点列表、地图与筛选中，其参与者照常显示、地点字段为空（`LEFT JOIN`）。
- 公开文件由公开视图推导（15 §4），不看这些连带：隐藏参与者的视角音频与照片在知道文件 id 时仍可取得（页面上不再出现，见 §15 已知限制）。

### 7.2 公开模板（`queries/culture/public/*.sql`，`access: public`）

| 模板 | 参数 | 用于 |
|---|---|---|
| `culture.public.site_blocks` | `keys`（列表） | 固定页面的文案 |
| `culture.public.locations` | — | 地点、每个地点的参与者数与故事数（地图、筛选） |
| `culture.public.people` | `location`（列表，可空） | PEOPLE 卡片 |
| `culture.public.person` | `slug` | 个人页头部与正文 |
| `culture.public.person_stories` / `person_themes` | `slug` | 个人页的故事与主题 |
| `culture.public.themes` | — | 主题及其故事数、参与地点数 |
| `culture.public.theme` / `theme_perspectives` | `slug` | 主题页：该主题下已发布故事中的**全部视角**，平铺的行，每行带地点字段、按地点排序；分组由网站完成（简报 §8 的横向比较） |
| `culture.public.stories` | `location`、`theme`、`mediaType`（列表）、`q`、`featured` | STORIES 列表与筛选；结果列 `locationSlugs` 为各故事涉及的地点（逗号分隔、按地点顺序；模板结果列没有数组类型，名称取自 `locations`） |
| `culture.public.story` / `story_themes` / `story_perspectives` / `story_media` | `slug` | 故事页 |
| `culture.public.resources` | `activityType`、`ageGroup`（列表） | RESOURCES |
| `culture.public.resource` / `resource_stories` | `slug` | 资源页 |
| `culture.public.location_themes` / `location_media` | `location` | 地图上一个地点的侧栏：该地点的人参与的已发布故事的主题（附故事数）；该地点的人的照片与视频（`kind` = `PHOTO` `VIDEO`，各带所属故事；视频含视角的视频与"有该地点的人参与"的故事自己的视频，封面为故事缩略图）（C4） |
| `culture.public.search` | `q` | 搜索：故事、参与者、主题、资源的统一结果（`kind` = `STORY` `PERSON` `THEME` `RESOURCE`、`slug`、`title` 或人名 `name`、`summary`、`imageFileId`） |

- 筛选"国家"= 故事中有来自该地点参与者的视角（`EXISTS`），不是故事的属性——这正是"故事属于人，不属于国家"。
- 搜索：`q` 为 2–100 字符，以 `ILIKE cu_like_pattern(:q) ESCAPE '\'` 匹配（函数转义 `\` `%` `_` 并加首尾 `%`，通配符按字面匹配）；
  范围为故事的标题、摘要、正文、文字稿，参与者名与简介，主题标题与问题，资源标题与描述的**全部语言**。平台不检查文本参数的长度，
  因此长度在 SQL 中判断：`q` 为空时不筛选（`stories`），不在 2–100 字符之间时不返回任何行；网站同时限制输入。
  迁移 `V4__culture_search.sql` 在 `public` 中建立 `pg_trgm` 扩展（扩展全库唯一，测试每个类一个 schema；索引写全名 `public.gin_trgm_ops`），
  建立不可变函数 `cu_i18n_text(jsonb)`（拼接各语言文本，空为 `''`）与 `cu_like_pattern(text)`，以及每个实体一条与模板表达式完全相同的
  trigram GIN 表达式索引。trigram 对英文与中日文都可用（少于 3 个字符或某些 locale 下的中日文用不上索引，结果仍由 `ILIKE` 保证）；
  归档规模（数百到数千条）下足够，以后需要词干与排序时再引入全文检索。生产的数据库账号需要对数据库有 `CREATE` 权限以创建扩展（受信任扩展）。
- 字典（`mediaType` 等数据库字典）中不存在的值不被拒绝，只是匹配不到任何行（编辑可随时增加字典值）。
- 全部模板 `cacheSeconds: 60`。公开模板目录快照在 `site/src/api/public-queries.json`（`jabizApp.publicQueriesSnapshot`），
  `site/` 下 `pnpm gen:api` 由此生成 `src/api/public-queries.ts`（每个模板的参数、行、外层筛选与排序的类型；无依赖的脚本
  `scripts/gen-public-api.mjs`），`pnpm check:api` 确认二者一致（CI：`.github/workflows/culture.yml` 的 `site` 作业）（15 §7）。
- 公开访问的总开关 `jabiz.public.enabled` 在应用中为 `${JABIZ_PUBLIC_ENABLED:false}`（与平台默认一致），`deploy/culture` 的 compose 打开它。
- 下线类流程（`UNPUBLISH`、`CONSENT_WITHDRAW`、`PARTICIPANT_HIDE`、`RESOURCE_UNPUBLISH`）以提交后步骤 `FileAccess.invalidate(...)`
  立即结束相关文件的公开判定（15 §4）；已缓存文件的浏览器最多再显示 5 分钟，多实例时其他实例在判定有效期（60 s）内停止。

## 8. 后台（编辑与通讯员）

- 平台通用后台，挂在 `/admin/`（17 §3），不写专门代码：多语言文本以标签页编辑、上传控件、引用下拉、行上的"提交 / 发布 / 下线 / 退回"按钮、
  故事详情中的视角与媒体子列表（16）。
- 菜单（`CULTURE_SETUP` 创建，三种语言）：内容（故事、视角、媒体、主题、地点、资源、文案块）、人员（参与者、同意记录）、设置（两个开关，经平台参数页面）；
  通讯员只看到"我的资料""我的故事""我的视角""我的照片"。
- 实体与字段的显示名写在 `messages_{zh,ja,en}.properties`（CLAUDE.md §4）。
- `docs/culture/editor-guide.md`（英文，C1 交付）：如何建参与者、登记同意、起草与提交、发布检查的每条提示是什么意思、如何撤回与抹除、化名规则。

## 9. 公开网站 `site/`

### 9.1 路由（语言在路径中：可分享、可被搜索引擎按语言收录）

| 路径 | 页面 | 简报 |
|---|---|---|
| `/` | 按浏览器语言跳转到 `/en/`、`/zh/` 或 `/ja/`（默认 `en`） | |
| `/:lang/` | 首页：主视觉（标题、副标题、地点列表、两个按钮）→ 核心问题 "Can one person represent an entire culture?" → "Probably not…" → 精选故事 → 主题入口 → 参与者 | §4 |
| `/:lang/people`、`/:lang/people/:slug` | 参与者卡片；个人页（头部、About、My Perspective、故事、主题、反思） | §6、§7 |
| `/:lang/themes`、`/:lang/themes/:slug` | 主题列表；主题页 = 问题 + 按地点并列的视角（可切换"并排比较"与"逐个阅读"） | §8 |
| `/:lang/stories`、`/:lang/stories/:slug` | 故事库（地点 / 主题 / 媒体类型筛选，状态在 URL 中）；故事页（标题、主题、参与者、播放器、About、Perspectives、Reflection 三问） | §9、§10 |
| `/:lang/method` | 方法：数字民族志、方法、反身性、主位与客位、文化表征、五条原则 | §11 |
| `/:lang/resources`、`/:lang/resources/:slug` | 资源（描述、年龄、时长、PDF、相关故事，正文可直接阅读） | §12 |
| `/:lang/map` | 地图（次要入口：从 PEOPLE 页、页脚与首页地点列表进入，不在主导航） | §13 |
| `/:lang/search` | 搜索结果 | §14 |
| `/:lang/about` | 关于项目、联系、隐私说明 | §5 |

主导航：HOME · PEOPLE · THEMES · STORIES · OUR METHOD · RESOURCES · ABOUT，外加语言切换与搜索。移动端为汉堡菜单（按钮 `aria-expanded`，打开后焦点进入菜单，`Esc` 关闭并把焦点还给按钮）。

### 9.2 视觉方向

"青年人做的数字人类学展览"，编辑风格，不是旅游网站：

- **排版**：标题用 Instrument Serif，正文用 Atkinson Hyperlegible（为易读性设计），"现场笔记"式的小标签（地点、日期、媒体类型）用 IBM Plex Mono；三者都是 OFL，**自托管** woff2（经 `@fontsource` 打包进站内，不连 Google Fonts：访客的 IP 不外泄）；
  中文、日文用系统字体栈（按 `:lang(zh)` / `:lang(ja)` 切换），不打包 CJK 字体。正文 18px 起，行宽 60–75 字符。
- **颜色**：纸色底 `#F2F1EC`、墨色文字 `#17181A`、一个强调色——荧光黄绿 `#D9F24A`，只作为"荧光笔"垫在墨色文字之下（不用作文字色）；不用任何国家的国旗色作为主题色。
  另有随系统切换的深色主题（纸与墨对调，荧光笔不变）。所有文字对比度 ≥ 4.5:1（大字 ≥ 3:1），以设计令牌定义并在测试中计算。
- **人是主视觉**：大幅人像与现场照片、视角卡片上的"来自某地的现场笔记"标签；旗帜只以文字大小出现在筛选与地点标签中。
- **版式**：留白充足的 12 栏网格；卡片（参与者 4:5 人像、故事 16:9 缩略图）；主题页的并排比较在桌面为 2–3 栏，在手机上为纵向列表。
- **动效**：滚动淡入与轻微位移（≤ 200 ms），`prefers-reduced-motion` 时全部关闭。
- **语气**：界面文字用第一人称复数、好奇而不权威（简报 §19），写入 `site/CLAUDE.md` 的文案规则。

视觉稿（首页、故事页、主题页的移动与桌面版）在 C3 开始时先做成可点击的静态原型，经你确认后再实现。
原型已在 C3 开始时确认（首页以六位参与者的拼贴为主视觉；主题页以问题本身为 `h1`，视角"先写人、后写地点"并按地点排列；故事页的反思三问为反色的独立区块）。

### 9.3 关键组件

| 组件 | 要点 |
|---|---|
| `VideoEmbed` | **点击后才加载**播放器（之前只显示我们自己的缩略图与播放按钮，不向 YouTube 发任何请求：隐私与性能）；YouTube 用 `youtube-nocookie.com`，参数 `cc_load_policy=1&hl=<语言>&rel=0`；Vimeo 带 `dnt=1`；`iframe` 有 `title`；下方可展开文字稿 |
| `ResponsiveImage` | 由变体 `w320` `w640` `w1280` 生成 `srcset`/`sizes`，放在固定比例的框中（人像 4:5、缩略图 16:9，`object-fit: cover`）防止布局跳动；原图较窄时变体不存在（14 §3），加载失败即改用原件；首屏以下懒加载，`alt` 必有（装饰图为空 `alt`） |
| `PerspectiveGrid` | 同一主题或故事下的视角并列；每个视角有参与者头部（头像、名、地点标签） |
| `ReflectionBlock` | 简报 §10 的三问，醒目的独立区块 |
| `FilterBar` | 地点 / 主题 / 媒体类型，状态写在 URL 查询串中（可分享），结果数以 `aria-live` 播报 |
| `Markdown` | 不渲染原始 HTML；外链 `rel="noopener"` 并标注"外部链接" |
| `LocalizedText` | 按界面语言取值，回退时在元素上加 `lang` 属性 |
| `WorldMap` | 见 §9.5 |

### 9.4 技术

- React 19、Vite、TypeScript、React Router、TanStack Query、i18next（界面文字 zh / ja / en）；CSS Modules + CSS 自定义属性（设计令牌）；
  `react-markdown`（渲染 Markdown）、`d3-geo` + `topojson-client` + `world-atlas`（地图，数据打包在站内）。不用 UI 组件库。
- 数据只来自 `/api/public/**`，类型由公开模板目录快照生成；不登录、不存令牌、不设 Cookie、不接第三方统计（以后需要统计时选不设 Cookie、不采集个人数据的方案，另行决定）。
- 页面标题与描述用 React 19 的文档元数据（`<title>`、`<meta>`）按页设置。
- 内容安全策略（17 §3.2）：`default-src 'self'; img-src 'self' data:; frame-src https://www.youtube-nocookie.com https://player.vimeo.com; style-src 'self'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'`。
- 性能目标（C4 以 Lighthouse 测量）：移动端首页 LCP < 2.5 s，首屏 JS < 200 KB（gzip）。除首页外的页面与 Markdown 渲染器按需加载；
  每次构建检查首屏 JS；CI 以 Lighthouse 检查 LCP 与无障碍（记录与方法见 `operations.md` §7）。

### 9.5 地图（简报 §13）

- 世界轮廓来自打包的 `world-atlas`（110m），用 `d3-geo` 在 SVG 中绘制；**不加载第三方地图瓦片**（没有外部请求，也不需要地图服务密钥）。
- 每个可见地点一个标记（按钮），键盘可达，名称为"地点名：N 位参与者，M 个故事"；点击后在侧栏显示该地点的参与者、故事、主题、视频与照片。
- 地图旁始终有同样内容的**地点列表**（无障碍替代与移动端的主要形式）。
- 实现（C4）：只画陆地轮廓（`land-110m`），不画国界；视野取各地点的范围加边距（至少一个区域大小）；相距太近的标记被推开到屏幕上
  相距 ≥ 46 px（按钮 44 px），以细线连回原位；手机宽度（≤ 640 px）下标记不可按，地点列表是入口。选中的地点在地址中（`?place=`），
  以 `role="status"` 播报；从列表选中时焦点移到侧栏，从地图选中时焦点留在地图上。

### 9.6 无障碍（简报 §18，目标 WCAG 2.2 AA）

| 要求 | 做法 |
|---|---|
| 替代文本 | 发布检查强制（§6.3）；头像、缩略图、照片都有 |
| 字幕 | 发布检查要求编辑确认；播放器默认打开字幕；文字稿 |
| 键盘 | 跳到正文的链接、可见的焦点样式、菜单与筛选全部可键盘操作、地图有列表替代 |
| 标题层级 | 每页一个 `h1`，层级不跳级（Playwright 中检查） |
| 对比度与字号 | 设计令牌 + 测试；支持 200% 缩放不出现横向滚动 |
| 语言 | `html lang` 随界面语言；回退内容标 `lang` |
| 动效 | `prefers-reduced-motion` |
| 触控 | 点击目标 ≥ 44 × 44 px |
| 自动检查 | 每个页面在三种语言、桌面与手机宽度下用 axe（`@axe-core/playwright`）检查，**严重与重大问题为 0** |

### 9.7 已知限制

单页应用在搜索引擎与社交分享预览上弱于服务端渲染：主流搜索引擎能执行脚本，但分享到社交平台时的预览（Open Graph）拿不到逐页标题与图片。
第一版接受这一限制；若需要，以后在平台上加"服务端注入页面元信息"的通用能力（不在本设计范围内）。

## 10. 部署与运行

- `deploy/culture/docker-compose.yml`：`db`（PostgreSQL 16，数据卷）、`culture`（jar；文件卷 `/var/lib/jabiz/files`；
  `JABIZ_PUBLIC_ENABLED=true`；首次启动的密钥生成与 D16 第 4 条相同）；可选 profile `edge`（反向代理与自动 TLS）与 `observability`（LGTM）。
- 生产所需环境变量：`JABIZ_JWT_SECRET`、首个管理员、数据库连接、`SERVER_FORWARD_HEADERS_STRATEGY`（在反向代理后）。
- 备份：每日 `pg_dump` + 文件卷归档（`tools/culture/backup.sh`，先数据库后文件），保留 30 天；恢复 `tools/culture/restore.sh`；
  演练脚本在 CI 中每次运行。**两者必须同时备份**（文件行与对象要一致）。见 `operations.md`。
- 规模：一台 2 vCPU / 4 GB 的虚拟机足够（内容量为数百个故事，访问以缓存的公开读取为主）。

## 11. 需求对照

| 简报 | 设计 |
|---|---|
| §1、核心原则 | §0；数据模型以人与视角为中心（§3.6）；国家只作筛选与地图 |
| §2、§19 | 语气规则（§9.2）、文案块可编辑（§3.9） |
| §3 | 视觉方向（§9.2） |
| §4 | 首页（§9.1），文字来自文案块，地点列表来自数据 |
| §5 | 主导航与汉堡菜单（§9.1） |
| §6、§7 | Participant（§3.3）、PEOPLE 与个人页 |
| §8 | Theme、Contribution、`theme_perspectives`（按地点并列） |
| §9、§10 | Story、筛选模板、故事页、Reflection 三问（§3.4） |
| §11 | 方法页，文字来自文案块 |
| §12 | Resource（§3.8），可随时增加 |
| §13 | 地图（§9.5），次要入口 |
| §14 | 搜索与筛选（§7.2） |
| §15 | 平台生成的后台（§8）；页面由数据自动生成 |
| §16 | 只存视频 ID，点击后加载嵌入播放器（§9.3） |
| §17 | 移动优先的响应式；Playwright 在手机宽度下测试 |
| §18 | §9.6，且发布检查强制替代文本与字幕 |
| §20 | 地点、主题、媒体类型、年龄段都是数据或字典；地点可以不是国家 |

## 12. 待确认的问题

1. **参与者的名字**：只用名（或化名）是否可以？（设计按"不公开姓氏"做。）
2. **视觉识别**：是否已有标志、配色或字体？没有的话按 §9.2 设计，C3 开始时给出原型确认。
3. **域名与托管**：部署在哪里（学校的服务器、云主机）？谁负责运维与备份？
4. ~~**抹除时同意书是否保留**~~：已决定（C1）：抹除时一并删除，不提供保留选项。
5. **本人同意是否始终必需**：设计中本人同意不受开关控制（只有监护人同意与审核有开关）。
6. **翻译**：内容的中文、日文由谁提供？（缺失时回退英语，不影响上线。）

## 13. 测试

各阶段的验收见 `ROADMAP.md`。要点：

- 后台与工作流（集成测试 + 场景回放，真实 PostgreSQL）：通讯员只能看到、改动自己的内容，提交后不能再改；`processOnly` 状态经数据视图写不进去；
  发布检查的每条违规；两个开关各取两种值；撤回同意即下线全部相关故事；抹除后行与文件都不存在；`input_summary` 中没有自由文本。
  场景"六个地点的 HOME 故事"：从起草、提交、退回、再提交、发布到撤回同意的全过程，固定时钟与快照。
- 公开接口：**任意状态组合下，公开模板只返回已发布的数据**（`PublicVisibilityPropertyIT`：jqwik 以固定种子随机生成各实体的状态与可见性，
  经后台与流程造出；以编辑视角读出的数据库为准，断言每个模板对每个 slug 与筛选值的每一行都属于 §7.1 的公开集合，故事、参与者、地点列表与之相等，
  每个文件恰在被公开行引用时可匿名获取）；同意书文件永远 404；草稿与下线内容的文件 404，下线、撤回、隐藏后立即 404（`PublicFilesIT`）；
  各页面的模板、筛选、中日英搜索与通配符转义（`PublicTemplatesIT`）；目录快照（`PublicQueriesSnapshotIT`）。
- 公开网站：Vitest（组件、语言回退、Markdown 安全、筛选与 URL 同步）；Playwright（全部页面在三种语言、桌面与 375 px 宽度下；axe；只用键盘完成主要路径；
  减少动效；视频在点击前没有第三方请求）。

## 14. C1 中的调整（计划确认时一并确认）

1. 平台 13e：状态机的显式初始状态 `st.initial(...)` 与 `CallProcess.forEach(...)`，由本应用发现，先在 `platform` 上实现。
2. `Consent` 不设 `recordedBy`（§3.10）。
3. `visibility` 是显式初始状态为 `PRIVATE` 的状态机；`editable` 可空（§3.5–3.7）。
4. `MediaItem` 的文件分为 `imageFileId` 与 `audioFileId` 两列（§3.7）。
5. 公开数据视图与公开模板在 C2 实现（依赖平台 13c）。
6. 发布检查的错误码补全（§6.3）；参与者可以从 `DRAFT` 直接因撤回同意变为 `WITHDRAWN`（§6.1）。
7. 通讯员不再有可写的"自己的资料"视图（§5）；抹除包括通讯员自己起草的独立故事与自己添加的媒体（§6.4）。
8. 主键以文本存储（§3）；数据库字典的初始值在迁移中，不在 `CULTURE_SETUP` 中（§6.6）；`CULTURE_SETUP` 另需 `security.menu.write`。
9. 平台 13e 另修正了"累积的违规在 `SaveChanges.now` 前后被报告两次"。

## 15. C2 中的调整（计划确认时一并确认）

1. 可见性的连带（§7.1）：视角与媒体要求参与者 `ACTIVE`；隐藏的主题、地点离开页面，隐藏地点的参与者照常显示、地点为空。
2. 主题页的视角为平铺的行，分组由网站完成；故事涉及的地点为文本列 `locationSlugs`（§7.2）。
3. `q` 的长度在 SQL 中判断（平台不检查文本参数的长度）；通配符由 `cu_like_pattern` 转义（§7.2）。
4. C1 的下线类流程补上文件判定的失效（C1 时平台 13c 尚未合入）；`HIDE` 与 `RESOURCE_UNPUBLISH` 也失效（§6.3、§7.2）。
5. 已知限制：隐藏参与者的视角文件在知道 id 时仍可取得（公开文件只由公开视图推导，范围不能跨实体）；
   平台的约定插件在配置测试任务时就读取 `jabizApp.publicQueriesSnapshot`，应用的设置因此不生效，`backend/culture/build.gradle.kts`
   另行设置测试的系统属性（平台修正后删除）。

## 16. C3 中的调整（计划确认时一并确认）

1. 字体与强调色（§9.2）：Instrument Serif、Atkinson Hyperlegible、IBM Plex Mono 代替示例中的 Fraunces 与 Inter；荧光笔式的强调色。
2. 字典的显示名：公开接口不提供数据库字典（媒体类型、活动类型、年龄段）的显示名。网站的语言文件为现有的值写三种语言的名称，
   新增的值显示为由代码生成的可读文字（`DIGITAL_ZINE` → "Digital zine"），不出错、不隐藏。国家、参与者、主题都来自数据，不受此影响。
   筛选（故事库的媒体类型、资源的活动类型与年龄段）只提供已命名的值；新增的值要在网站的语言文件中命名后才出现在筛选中。
3. 图片（§9.3 `ResponsiveImage`）：公开模板不返回图片尺寸，因此用固定比例的框；变体按宽度生成、原图较窄时不存在，失败即回退到原件。
4. 视角的视频没有自己的缩略图：播放前的封面用所属故事的缩略图，没有时用参与者头像，再没有时只显示播放按钮。
5. 地图（`/:lang/map`）与搜索页（`/:lang/search`）在 C4 实现；C3 中页头没有搜索按钮，页脚没有地图链接，首页的地点列表链接到按地点筛选的故事库。
6. 首页的精选故事：没有标为精选的故事时显示最新的三个。关于页只有项目与隐私两块文案（没有联系方式的文案块，需要时由编辑新增文案块并改页面）。

## 17. C4 中的调整（计划确认时一并确认）

1. 地图侧栏的两个公开模板 `location_themes`、`location_media`（§7.2）：简报 §13 要求点击地点后显示主题、视频与照片，已有模板没有按地点的这些数据。
   两者遵守 §7.1 的连带（隐藏的地点、参与者、主题都不出现），属性测试覆盖。
2. 地图只画陆地、不画国界（§9.5），不对有争议的边界表态，也与"国家不是主角"一致。
3. 首屏（§9.4）：页面与 Markdown 渲染器按需加载，构建检查首屏 JS ≤ 200 KB；应用对静态资源压缩传输（不压缩 JSON）；
   首页标题立即绘制（未取到文案块时用迁移预置的标题），其余部分到齐后一起出现，故事库筛选与地图页同样整体出现（避免布局偏移）。
   为此桌面版首页的标题与拼贴改为顶端对齐（原型中垂直居中）。
4. 搜索入口在页头（放大镜）与页脚，地图入口在 PEOPLE 页与页脚（不在主导航，§9.1）。
5. 备份保留 30 天；恢复会带回备份之后被撤回、抹除的个人数据，因此编辑在系统之外登记撤回与抹除，恢复后重做（`operations.md` §5、编辑指南 §7）。
6. 恢复演练发现 `deploy/culture` 的 compose 以空值设置 OTLP 端点，应用无法启动；改为只在 `.env` 中设置（`operations.md` §8）。
