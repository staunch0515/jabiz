# Q60 商户主页（2026-10-31）

需求方（2026-10-30）：商户在答题者端有自己的小站（主页、商品与价格、电商链接；Quiz 与题目关联商品；浏览与点击统计）。
需求方的决定：首次发布需审核，之后修改直接生效、可事后下架（同 Quiz）；2026-10-31 同意以下安排。

| 期 | 内容 |
|---|---|
| **Q60a-1** | 主页的数据、流程与审核、公开读取（本节） |
| Q60a-2 | 商家后台的编辑页（"我的主页"）、App 的主页（`/s/<handle>`）与发布页、分享页上的商户链接 |
| Q60a-3 | 管理后台与运营商后台：主页列表、审核时查看主页内容、下架 |
| Q60b | 商品（名称、图片、价格与币种、商店链接）；Quiz 与题目关联商品 |
| Q60c | 主页浏览与商品链接点击的按日统计（商家的分析） |

## 1. Q60a-1 设计

- **主页**：`QbSponsorPage`（普通表，历史见审计），每个商户一个：
  - `handle`：公开链接 `/s/<handle>` 的名字（3–30 个小写字母、数字或连字符，全平台唯一，不分大小写）；
  - `language`：主页的语言（平台语言之一，Q59 的规则：一个主页一种语言）；
  - `headline`（120 字以内）、`story`（Markdown，5000 字以内，前端渲染，不允许 HTML）、`logo` 与 `cover`（文件，新策略 `qb.sponsor-page-image`，
    主页公开后随之公开；原名与上传者不公开）、`website`（https 链接，300 字以内）；
  - 公司名取自已审核的入驻资料。标志是主页自己的：入驻的头像不公开（`privateMetadata` 且只对登录者），把它放进公开白名单会让所有已入驻商户的头像都公开。
- **状态**：`DRAFT`（未发布）→ `PENDING`（首次发布审核中）→ `PUBLISHED`；`REJECTED`（可修改后再提交）；`TAKEN_DOWN`（管理员或运营商下架，再上线需重新审核）。
  - 已发布的主页修改后直接生效（需求方的决定），仍为 `PUBLISHED`；`handle` 也可改（旧链接失效）。
  - 审核中修改：回到 `DRAFT` 并撤回审核（审核人会批准与主页不同的内容），重新提交。
  - 商户可自己撤下（回到 `DRAFT`；再发布时内容与批准时相同即直接通过，否则需审核）。
  - 封禁商户时在同一事务中下架其主页（已发布或审核中，理由为固定的"账号已停用"）；解封后需重新审核。
- **流程**：
  - `QB_SPONSOR_PAGE_SAVE`（商户）：保存内容与 handle（重名 422 `QB_SPONSOR_PAGE_HANDLE_TAKEN`），按修订号防并发；
  - `QB_SPONSOR_PAGE_SUBMIT`（商户）：入驻已通过才能提交；审批对象 `qb.sponsor-page`，规则 `qb.sponsor-page.review`（权限 `qb.review.sponsor`，
    参数 `qb.review.sponsor.auto` 为真时不需审核，同入驻）；没有规则且不自动即 422 `REVIEW_NOT_CONFIGURED`；审批记商户的运营商（Q59c-2）；
  - 下架后再提交：下架设 `reviewRequired`，只有发布才清除（其间提交后又修改或撤下也不例外，自查发现的绕过）；为真时事实 `autoApprove` 一律为假，
    且审批内容含下架时间（同样的文字不会因先前的批准而直接通过）；没有人审即 422 `QB_SPONSOR_PAGE_REVIEW_REQUIRED`；
  - `QB_SPONSOR_PAGE_APPROVAL_RESULT`（内部，审批事件）：通过且内容未变即发布，驳回记理由；
  - `QB_SPONSOR_PAGE_UNPUBLISH`（商户）；`QB_SPONSOR_PAGE_TAKEDOWN`（总部 `qb.admin.content.write`，二次验证）与
    `QB_OPERATOR_SPONSOR_PAGE_TAKEDOWN`（运营商 `qb.operator.use`，只对自己的商户，`OperatorChecks.ownSponsor`）。
  - Q60a-1 不发邮件、不建待办：审核结果与下架理由显示在商家后台的编辑页（Q60a-2）；需要时再加通知。
- **公开读取**：公开数据视图 `urn:jabiz:dataset:public:QbSponsorPage`（固定 `status = PUBLISHED`，白名单字段）；公开模板
  `qb.public.sponsor-page`（按 handle：主页与公司名）与 `qb.public.sponsor-page-publications`（该商户正在进行的公开发布）。封面的文件随之公开。
- **读取权限**：缺省数据视图的读权限 `qb.admin.publication.read`，按运营商约束（Q59c-2 的 `OperatorScopes`）；商户读自己的（`me` 数据视图）。
- **迁移**：V46 建 `qb_sponsor_page`（主键 `user_id`；`handle` 唯一索引，另有检查约束保证小写）。

## 2. 测试（Q60a-1）

- `SponsorPageIT`：保存与 handle 规则（格式、重名、大小写）、修订号、未入驻不能提交、提交进入审核、审核通过即公开（公开模板）、驳回、
  发布后修改直接生效、改 handle 后旧链接为空、审核中修改即撤回、撤下与下架后公开读取为空、下架后即使开关打开也需审核、封禁即下架；
  运营商只能看到并下架自己的商户的主页、其审批记在运营商名下；`OperatorScopesIT` 覆盖新的数据视图；`SetupIT` 覆盖新的审批规则。

## 3. Q60a-2 设计

- **商家后台** "我的主页"（`/page`，设置组，`sponsor/src/page/`）：链接名（显示完整链接）、语言、标题、介绍（Markdown）、标志、封面、网站；
  状态与驳回、下架理由（封禁的下架显示"账户已被停用"）；"保存"、"发布"（有未保存的修改时不可用）、"撤下"（确认后）；已发布时"打开我的主页"与"复制链接"。
- **App** `/s/<handle>`：登录前后都可看（公开模板），封面、标志、公司名、标题、介绍、网站（只链接 https）、进行中的公开发布（链接到 `/p/<id>`，
  未登录即分享页）。页面标注主页的语言（`lang`）。
- **商户链接**：`qb.public.publication` 与 `qb.app.publication` 返回 `sponsorHandle`（主页已发布时）；分享页与发布页上的"来自 xx"链接到主页（商家后台的预览不链接）。
- 共用的调用与类型在 `src/qb/page.ts`（`app` 与 `sponsor` 的共用目录）。
- **测试**：`sponsor/src/page/page.test.tsx`（创建草稿后发布、未保存不能发布、理由、撤下、字段错误、三种语言）；
  `app/src/share/sponsorPage.test.tsx`（主页与发布列表、https 以外不成链接、没有发布与没有主页、分享页的商户链接）；
  `SponsorPageIT` 断言两个发布模板的 `sponsorHandle`（发布后为链接名，撤下后为空）。
