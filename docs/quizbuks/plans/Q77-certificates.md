# Q77 答题者的认证页与可验证的证书（计划，2026-11-02，待需求方确认）

`04-product-direction.md` §5 的候选 E；需求方 2026-11-01 定下顺序 A → D → E，A（Q75，#411）已合并，D（Q76）待合并。编号 Q77 由需求方 2026-11-02 定下。
**这只是计划**：确认 §9 的决定后开发，工作分支 `1.3/quizbuks-77-certificates`。

## 0. 为什么

- 认证（Q69、Q70）现在只是"门槛的钥匙"：持有 `SPONSOR_APPLY` 可以申请成为商户、持有 `REFERRER` 可以生成推荐链接。答题者自己看不到持有哪些、何时到期，也没法向别人证明。
- 对话中的招聘场景（S10）与"品牌官方认证"都需要一样东西：**本人愿意时，别人能核实"这个人持有这个认证、在有效期内"**，而不暴露分数和个人资料。
- 平台已有签发单据（`DOCUMENT_ISSUE`：存档、内容哈希、重印取存档字节，收据就是这样做的），证书照此做，不另写 PDF。

## 1. 现状

| 部分 | 现在 |
|---|---|
| 认证目录 | `certification.Certifications`：`SPONSOR_APPLY`、`REFERRER`，各一年有效、版本 1；只在代码中 |
| 持有 | 不另存，按需算出（`CertificationHolding`）：已认领的记录 `QbCertificationRecord`（`PASSED` / `GRANTED` / `REVOKED`，只插入）加上还没认领的合格会话（`RoundHolders.loadHolding`）；收回压过此前的一切 |
| 轮 | `QbCertificationRound`：只有官方账号（`QbOfficialPublisher`）的发布能登记为轮（D-Q69-5） |
| App | 账户页只有"成为商户"（`mysponsor/BecomeSponsor.tsx`）与推荐面板用到认证；没有"我的认证"列表 |
| 设置 | `QbUserSettings`（普通表：`soundOff`、`contentLanguages`） |
| 单据 | 版式 `DocumentLayout`：编号、当事方、事实、表格、合计、文字；**不能放图片或二维码** |
| 删除账号 | `TakerErasure`（`PersonalDataEraser`）按用户删除或清空应用的个人数据 |

## 2. 决定（草案，待确认）

| # | 决定 |
|---|---|
| D-Q77-1 | **"我的认证"**：App 账户页列出本人现在持有的每个认证：名字、来源（合格于哪一轮 / 人工授予）、起始与到期日、是否已签发证书；已到期或被收回的不列（历史不在这一步）。读取是只读流程 `QB_MY_CERTIFICATIONS`（复用 `RoundHolders.loadHolding` 与 `CertificationHolding`，不再写第三份 SQL 规则） |
| D-Q77-2 | **证书是签发的单据**：本人对一个持有中的认证运行 `QB_CERTIFICATE_ISSUE`（三语之一），平台以版式 `qb.certificate` 签发 PDF 并存档；同一认证的同一段持有（到期日相同）只签发一张，再次请求返回同一张（同收据）。证书上：持有人的显示名（签发时的，冻结在 PDF 里）、认证名、起始与到期日、证书编号、核实地址 `…/taker/verify/<键>` 与"在此核实"的说明 |
| D-Q77-3 | **证书的记录** `QbCertificate`（普通表，只经签发流程插入，没有更新）：用户、认证、版本、起始、到期、持有人的显示名（签发时冻结，与 PDF 上的相同）、单据运行、编号、核实键（随机 128 位，平台 `RandomBytesStep`，不是用户编号也推不出用户编号）、签发时刻 |
| D-Q77-4 | **核实页** `/taker/verify/<键>`（公开模板 `qb.public.certificate`，不需登录）：认证名、持有人的显示名、起始与到期日、状态 **有效 / 已过期 / 已收回**（收回 = 该用户该认证在签发后有 `REVOKED` 记录）。不显示分数、国家、邮箱、用户编号；不知道键就查不到（模板只按键取一行） |
| D-Q77-5 | **公开的认证页** `/taker/u/<handle>`（公开模板 `qb.public.taker-page`）：本人在设置中开启并取一个公开名（`handle`：`[a-z0-9-]{3,30}`，全站唯一，保留词不能用；可改，旧名立即失效）后，显示显示名与**已签发、仍有效、未收回**的证书（各链到核实页）。**缺省关闭**；关闭即 404（与不存在同样回答）。不列没有签发证书的认证（本人决定哪些公开：签发即公开） |
| D-Q77-6 | **不显示分数**（合格就是合格；分数是个人的答题记录） |
| D-Q77-7 | **删除账号**：`TakerErasure` 加上：设置的公开名与开关清空、证书记录删除（核实页从此"查无此证"）；证书的单据存档按平台单据的抹除（`.documents("qb.certificate")`）覆盖持有人的名字 |
| D-Q77-8 | **"商户认证"（§5-E 第 3 项）不在这一步**：它要放宽 D-Q69-10（轮只能是官方账号的发布、目录只在代码中），涉及商户可以发出的身份、审核与收回的责任，单独出计划（Q77b）。本步的证书上写的认证名来自目录的三语文案 |
| D-Q77-9 | **二维码不在这一步**：平台的单据版式不能放图片。证书印核实地址与编号；二维码需要平台先给版式加"二维码"元素（在 `1.3/platform` 的工作分支上，带平台测试与 `app` 的示范），之后再加，见 §8 |
| D-Q77-10 | **隐私政策要改**：公开认证页与核实页是答题者主动公开的信息（显示名、认证、有效期）；上线前改隐私政策的相应段落（法务确认文字），本计划只改代码与草稿说明 |

## 3. 后端（`backend/quizbuks`）

### 3.1 迁移 V61 `quizbuks_certificates`（Q75 用了 V59，Q76 用 V60）

```sql
ALTER TABLE qb_user_settings ADD COLUMN public_handle varchar(30);
ALTER TABLE qb_user_settings ADD COLUMN public_page boolean NOT NULL DEFAULT false;
CREATE UNIQUE INDEX qb_user_settings_public_handle ON qb_user_settings (public_handle) WHERE public_handle IS NOT NULL;

CREATE TABLE qb_certificate (
    certificate_id        varchar(36)  PRIMARY KEY,
    user_id               varchar(128) NOT NULL,
    certification         varchar(40)  NOT NULL,
    certification_version numeric(4, 0) NOT NULL,
    held_from             timestamptz  NOT NULL,
    holder_name           varchar(100) NOT NULL,
    valid_until           timestamptz  NOT NULL,
    run_id                varchar(36)  NOT NULL,
    document_no           varchar(100) NOT NULL,
    verify_key            varchar(32)  NOT NULL UNIQUE,
    language              varchar(10)  NOT NULL,
    issued_at             timestamptz  NOT NULL,
    version               bigint       NOT NULL,
    CONSTRAINT uk_qb_certificate_holding UNIQUE (user_id, certification, valid_until)
);
CREATE INDEX qb_certificate_user_idx ON qb_certificate (user_id);
```

### 3.2 认证（`certification`）

- `QbCertificates`：实体 `QbCertificate`、数据视图（默认 `processOnlyWrites`，读权限 `qb.admin.users.read`；本人 `me:`；公开 `public:`，白名单只有核实所需的列，不含 `userId`）。
- `CertificateProcesses`：
  - `QB_MY_CERTIFICATIONS`（`qb.me`）：对目录的每个认证 `RoundHolders.loadHolding` + `heldUntil`，加上本人的证书记录 → `[{certification, heldFrom, heldUntil, source, certificateRunId?}]`。
  - `QB_CERTIFICATE_ISSUE`（`qb.me`，`requiresVerifiedEmail`、`refuseBanned`，锁 `qb.certificate:<用户>:<认证>`）：确认此刻持有（不持有 422 `QB_CERTIFICATE_NOT_HELD`）→ 已有同一段持有的证书即返回它 → 随机核实键 → 子流程 `DOCUMENT_ISSUE`（版式 `qb.certificate`，主体 `QbCertificate`）→ 插入记录。持有的起点：合格会话的提交时刻或授予记录的时刻（`CertificationHolding` 给出最晚的有效一条）。
- 版式 `qb.certificate`（`DocumentLayout`）：编号、当事方"持有人"（显示名）、事实（认证名、起始、到期、核实地址），标题与文字在消息 `document.qb.certificate[.键]`（三语）。版式的权限是 `qb.me`、主体经本人的证书视图，商户读不到（`backend/quizbuks/CLAUDE.md`："新的单据版式必须把对象限定在本人"）。
- `TakerErasure` 加 `ErasedEntity.delete(QbCertificate)` 与 `.documents("qb.certificate")`。

### 3.3 设置（`settings`）

- `QbUserSettings` 加 `publicHandle`、`publicPage`；`QB_USER_SETTINGS_SAVE`（已有的保存流程）收这两个：公开名的格式、保留词（`admin`、`verify`、`help` 等，清单在 `PublicHandles`）、唯一（422 `QB_PUBLIC_HANDLE_TAKEN`）；开启时须已有公开名。

### 3.4 模板

- `qb.public.certificate`（公开，`p.key`）：按核实键取一张证书：认证、版本、持有人的显示名（证书记录上冻结的）、起始、到期、编号，与收回时刻 `revokedAt`（该用户该认证签发之后最早的 `REVOKED` 记录；`QbCertificationRecord` 的公开视图只有用户、认证、种类与时刻，模板只按证书连接）。公开模板不用数据库的 `now()`（业务时间只来自 `Clock`）：模板只给到期日与收回时刻，**是否已过期由页面按服务端 `Date` 头的时钟判断**（同 `qb/legal.ts`）。
- `qb.public.taker-page`（公开，`p.handle`）：开启的设置 → 显示名（资料的公开视图，只显示名）与该用户已签发、未收回的证书及各自的到期日（到期判断同上）。
- 管理员的 `qb.admin.certificates`（`qb.admin.users.read`）：用户页列出签发过的证书（支持人员回答"我的证书呢"）。

### 3.5 文案

- 错误码 `QB_CERTIFICATE_NOT_HELD`、`QB_PUBLIC_HANDLE_FORMAT`、`QB_PUBLIC_HANDLE_RESERVED`、`QB_PUBLIC_HANDLE_TAKEN`、`QB_PUBLIC_PAGE_NO_HANDLE`；实体与流程名；版式 `document.qb.certificate.*`；三语。

## 4. 前端

### 4.1 App（`quizbuks-web/app`）

- 账户页加"我的认证"（`certificates/MyCertifications.tsx`）：每个持有的认证一行（名字、到期日、来源），"签发证书"（选证书的语言，缺省界面语言）/ "下载证书"（`GET /api/documents/runs/{id}/pdf`）与"复制核实链接"；没有认证时说明怎样得到（链接到当前一轮，同"成为商户"）。
- 账户页的设置加"公开认证页"：开关、公开名（实时显示地址 `/taker/u/<名>`）、"复制链接"、说明公开的内容（显示名、已签发的证书）。
- 公开路由（不需登录，同 `/s/<handle>`）：`/u/<handle>`（`certificates/PublicTakerPage.tsx`）与 `/verify/<key>`（`certificates/VerifyPage.tsx`：状态的大标记、认证名、持有人、期间；已过期 / 已收回醒目说明）。`jabiz.web.redirects` 不加（新路径在 `/taker/` 下）。
- 三语文案；axe（亮暗）。

### 4.2 后台扩展

- 用户页加"证书"一节（`qb.admin.certificates`）：编号、认证、期间、签发时刻、单据下载（`document.archive.read`）。收回仍用已有的 `QB_CERTIFICATION_REVOKE`。

### 4.3 商户后台

- 不改。

## 5. 测试

- **单元**：`PublicHandlesTest`（格式、保留词、大小写）；`CertificateRulesTest`（持有的起点：合格与授予、收回之后再合格、到期日即段的键）。
- **集成** `CertificateIT`：我的认证（未认领的合格也列出）；签发（不持有 422、同一段只签一张、再请求同一张、到期后再合格是新的一张）；单据存档、本人可下载、别人 404、商户读不到；核实页（有效 / 已收回；过期由页面按时钟——模板返回到期日）；公开页（缺省 404、开启后只列已签发的、关闭或改名后旧名 404、唯一与保留词）；删除账号后核实页与公开页查无、存档中的名字被覆盖（`ErasureIT` 的全表搜索覆盖新表）。
- **前端**：App `MyCertifications`、`VerifyPage`、`PublicTakerPage`、设置的测试（axe）；后台扩展的用户页。
- **e2e** `certificates.spec.ts`：答题者合格一轮（官方账号的发布，`e2e/tests` 已有准备工具）→ 我的认证 → 签发并下载 PDF → 打开核实链接（未登录）显示有效 → 开启公开页并打开；管理员收回后核实页显示已收回。

## 6. 文档

- `02-design.md` §3（新实体与设置的两列）、§9（公开的内容与核实键）；`03-plan.md` 加 Q77 一行；`04-product-direction.md` §5-E 指向本计划；`backend/quizbuks/CLAUDE.md` 的"资格"一节加一段；Q69 计划 §7 注明证书由 Q77 做、商户认证仍是以后；隐私政策的草稿说明（法务定稿）。

## 7. 估计

| 部分 | 天 |
|---|---|
| 后端：迁移、实体与视图、两个流程、版式、设置、三个模板、抹除、IT | 1.5–2 |
| App：我的认证、设置、公开页与核实页、测试 | 1 |
| 后台扩展、e2e、文档 | 0.5 |
| **合计** | **3–3.5** |

## 8. 风险

| 等级 | 风险 | 对策 |
|---|---|---|
| 中 | 核实页只印地址不印二维码，招聘方要手输 | 地址短（`…/taker/verify/` + 22 字符）；证书同时印编号；二维码在平台给版式加元素后补（另一个平台阶段，约 0.5 天平台 + 0.5 天应用） |
| 中 | 公开显示名可能是真名，公开后被搜索引擎收录 | 缺省关闭；公开页与核实页的 `<meta name="robots" content="noindex">`；说明公开的内容；可随时关闭 |
| 低 | 认证到期判断按设备时钟会错 | 页面按服务端的 `Date` 头改正（`qb/legal.ts` 的做法） |
| 低 | 证书 PDF 的名字与改名后的显示名不同 | 证书是签发时的原样（单据不重排）；核实页显示证书记录上冻结的名字，与 PDF 相同，不跟随改名；公开认证页的显示名是现在的 |
| 低 | 收回后已下载的 PDF 仍在流传 | 核实页显示"已收回"：证书的意义在核实，不在 PDF 本身（证书上写明"以核实页为准"） |

## 9. 需要需求方确认的

1. D-Q77-2/5：**签发证书即公开**（公开页只列已签发的证书），还是公开页列出所有持有中的认证、证书只是可下载的副本？（建议前者：本人逐个决定公开哪些。）
2. D-Q77-5：公开认证页**缺省关闭**，开启要取一个公开名。
3. D-Q77-6：**不显示分数**。
4. D-Q77-8：**"商户认证"放到 Q77b** 另出计划（本步只有平台目录中的认证）。
5. D-Q77-9：**二维码先不做**（证书印核实地址与编号）；还是先做平台的"二维码元素"阶段再做 Q77？
