# Q39 普通商户的店员（到店领奖）（2026-10-27 确认）

需求方（2026-10-27）："店员先按答题者注册。然后商户管理员可以把店员的 ID 设置成本商户店员。"
补充答复：针对**普通商户**（Shopify 店铺的成员仍按 S1，经 Shopify 自动加入）；店员**只做到店领奖**。
计划于 2026-10-27 确认（"开始"）。在 `1.3/quizbuks` 上开发；1.2 已冻结，生产升级到 1.3 后才可用。

## 1. 决定

| # | 决定 |
|---|---|
| D-Q39-1 | **店员怎么来**：店员先在答题 App 正常注册（邮箱已验证），App"账户"页显示"我的 ID"（用户编号，可复制）；商家在后台"店员"页粘贴 ID，先看到对方的显示名（`QB_SPONSOR_STAFF_FIND`），确认后加入（`QB_SPONSOR_STAFF_ADD`）。没有平台的角色或权限变化 |
| D-Q39-2 | **谁可以是店员**：有答题人资料、不是商家账号、不是商家本人；**一人同时只属于一家商家**（唯一约束兜底）；每家至多 50 位（`QB_STAFF_FULL`）。只有商家本人管理店员 |
| D-Q39-3 | **店员能做什么**：只在 App 的"领奖台"（`/staff`）出示商家的**轮换码**（`QB_STAFF_REDEEM_CODE_ISSUE`，记在店员名下）、处理顾客用**固定码**提出的申请（`QB_STAFF_REDEMPTION_CONFIRM` / `DECLINE`）、看今天已领取的。固定码的印制与更换、发布、钱包、报表与设置仍只属于商家 |
| D-Q39-4 | **谁经手**：码记下签发人（`qb_redeem_token.issued_by`；V32 之前的码记为商家）；轮换码领奖的 `redeemedBy` 是签发人，确认申请的 `redeemedBy` 是确认的人。商家后台的"奖品领取"页显示经手店员的名字（商家本人经手的不显示名字） |
| D-Q39-5 | **不能交付自己的奖品**：店员用自己签发的轮换码领奖、确认自己的申请都拒绝（`QB_STAFF_OWN_PRIZE`），须由其他店员或商家交付 |
| D-Q39-6 | **移除即生效**：商家移除（`QB_SPONSOR_STAFF_REMOVE`）或店员退出（`QB_STAFF_LEAVE`）后，此人签发的轮换码同时删除；下一次操作即 422 `QB_NOT_STAFF` |
| D-Q39-7 | **权限**：店员的流程沿用答题人的 `qb.me`，每次在流程中读此人的店员关系（共享锁 `qb.staff:<用户>`，加入与移除取排他锁，所以移除等进行中的操作结束、之后的操作都看到它）；读奖品走系统视图并核对属于本商家，其他商家的奖品一律 404 |

## 2. 实现

| 部分 | 内容 |
|---|---|
| 表 | 普通表 `qb_sponsor_staff`（实体 `QbSponsorStaff`：`sponsorId`、`userId`（唯一）、`addedAt`、`addedBy`）；`qb_redeem_token` 加列 `issued_by`（迁移 `V32`，旧码回填为商家） |
| 流程 | `reward.StaffProcesses`：商家的 `QB_SPONSOR_STAFF_FIND` / `ADD` / `REMOVE`（`qb.publication.write`，锁 `qb.sponsor-staff:<商家>` → `qb.staff:<用户>` → `qb.redeem-token:<商家>`）；店员的 `QB_STAFF_LEAVE`、`QB_STAFF_REDEEM_CODE_ISSUE`、`QB_STAFF_REDEMPTION_CONFIRM` / `DECLINE`（`qb.me`）。确认、拒绝与签发的业务规则与商家的流程共用 `RedemptionProcesses` 的同一段代码 |
| 数据视图 | 系统的缺省视图；商家自己的店员（`sponsor:QbSponsorStaff`）；本人的店员关系（`taker:QbSponsorStaff`） |
| 模板 | `qb.sponsor.staff`（店员与名字）、`qb.app.staff-of`（所属商家，零或一行）、`qb.app.staff-redemptions`（经本人的店员关系读所属商家已申请或已领取的奖品，含经手人）；`qb.sponsor.redemptions` 加 `redeemedByName` |
| 限流 | `QB_STAFF_REDEEM_CODE_ISSUE` 每分钟 20 次（同商家的轮换码）、`QB_SPONSOR_STAFF_FIND` 每分钟 30 次 |
| 商家后台 | 新页 `/staff`（导航"奖品"组）：粘贴 ID → 查找 → 显示名字 → 添加；店员列表（名字、ID、加入时间、确认后移除）。"奖品领取"页的今天已领取与全部奖品显示"经手人" |
| App | 账户页"我的 ID"（复制）；是店员时显示所属商家、"打开领奖台"、"退出店员"；新页 `/staff`（领奖台）：轮换码（每分钟更新）、待处理的申请（每 5 秒）、今天已领取 |
| 消息 | 后端三语：流程名、错误码、实体与模板的列名；前端三语：商家后台 `publishing.staff.*`、App `staff.*` |

刷新方式（计划中的风险）：领奖台的列表用模板 `qb.app.staff-redemptions` 每 5 秒读一次，与商家后台相同，不留操作记录——模板的范围经店员关系表连接即可表达"我所属的商家"，不需要改用只读流程。

## 3. 测试

- IT `SponsorStaffIT`（真实 PostgreSQL）：查找与加入（大小写与空格、重复加入无副作用）；拒绝：不是答题人、没有资料、商家账号、本人、已是别家店员、超过 50 位、答题人调用商家流程 403；退出后可加入别家；移除不存在的无副作用。店员签发轮换码、顾客领奖 `redeemedBy` 为店员、两个模板的经手人；商家自己的码仍记商家；店员不能交付自己的奖品；移除后码失效、不能再签发、模板为空。店员确认与拒绝固定码申请（消息照常）、其他商家的奖品 404、非店员 `QB_NOT_STAFF`、退出后不能再确认。`RedemptionIT` 原样通过。
- 场景 `qb/sponsor-staff.yml`：商家查找并加入店员、店员出示轮换码、顾客即时领取、顾客扫固定码由店员确认、商家移除后店员不能再签发；快照 `QbSponsorStaff`、`QbRedemption`、`QbMessage`。其他 13 个场景的快照不变（`QB_SETUP` 不变）。
- Vitest：商家后台（查找、显示名字、添加、服务器的拒绝原因、已是店员不显示按钮、确认后移除、"经手人"，axe）；App（三语文字齐全、"我的 ID"复制、所属商家与退出、领奖台的码与申请与今天已领取、非店员的说明，axe）。
- e2e `reward-methods.spec.ts`：答题人在账户页读出 ID → 商家在"店员"页查找并添加 → 店员从账户页进入领奖台 → 顾客扫码领取 → 领奖台与商家后台都显示经手人；新页面过 axe。

## 4. 风险与说明

- 店员的 ID 是 36 位的用户编号，只适合复制粘贴；要口头报的短码以后再说。
- 商家能查到任何答题人 ID 的显示名（输入 ID 才能查，编号不可猜；每分钟 30 次）。
- 店员不会收到"有新申请"的站内信：申请显示在领奖台上（每 5 秒刷新），商家的收件箱照旧收到。
