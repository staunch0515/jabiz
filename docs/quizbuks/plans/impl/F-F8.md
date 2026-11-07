# F-F8 AI 获客的平台开关与受控变更列表：实施计划（S3）

规格：`docs/quizbuks/first-launch-spec.md` F-F8。来源：troubleshooting T9。

## 范围

- **应用（本 PR）**：「AI 获客 → 设置」（平台管理系统扩展 `ProspectSettingsPage.tsx`）新增"平台开关"一栏，照 `/qb/ai`（`AiPage.tsx` 的 `proposeSwitch`）的做法。
- **平台（P7，S1）**：平台管理系统的通用页面 `SysControlChange` 列表显示参数名 `paramKey` 并可按它筛选。在 `1.3/platform` 上做，本 PR 不碰。

## 要改的

| 位置 | 改动 |
|---|---|
| `queries/qb/admin/prospect-switch.sql`（新） | 模板 `qb.admin.prospect-switch`：`qb.prospect.enabled` / `model` / `effort` 的现值与是否已存在（照 `qb.admin.ai-settings`）；权限 `qb.admin.prospect`（总部有，运营商成员没有） |
| `messages_{en,zh,ja}.properties` | `query.qb.admin.prospect-switch` |
| `admin-extension/src/prospect-switch-api.ts`（新） | 读现值；读待发布的提案（`SysControlChange` 数据视图，`PROPOSED` 且 `paramKey` 是三者之一，照 `settings-api.ts` 的 `readOpenProposals`，需要 `approval.read`）；提出（只为改动了的参数各提一个 `CONTROL_CHANGE_PROPOSE`，用同一个理由）；搜索运行时刻的常量 `FIND_RUN_UTC`（与 `ProspectConfig` 的 `0 17 23 * * *` UTC 一致） |
| `admin-extension/src/ProspectSwitch.tsx`（新） | 一栏，内容有：说明；运行时刻（按读者浏览器的时区显示，同时给出 UTC，不写死时区，并注明"错过的时刻不补跑"）；三个参数的现值；待发布的提案（有 `approval.read` 时）；开关为关而所有者勾了"每天获客"时的警告；表单（有 `control.propose` 时：开关、模型、强度、必填的理由）；"受控变更"的链接 |
| `ProspectSettingsPage.tsx` | 总部（`qb.admin.prospect`）显示该栏 |
| `ids.ts`、`prospect-messages.ts` | 模板名、`approval.read`；三语文字 |
| `operations.md` §7.14、troubleshooting T9 | 改成按页面操作；更新 T9 的状态 |

不加表、不加迁移、不加流程、不改权限：`control.propose` / `control.publish` / `approval.read` 照 `QbRoles` 现有的授予。

## 测试

- `prospect.test.tsx`：
  - 显示现值；提案只列三者之一；运行时刻含 UTC；开关关着时显示警告；axe 通过；
  - 只提出改动了的一项，理由必填；
  - 从未发布过的三项都会提出；
  - 不能提出的人只看到值；没有 `approval.read` 时不读提案；
  - 运营商成员看不到这一栏，也不读模板。
- `ProspectIT`：总部读到三项的现值；运营商成员得到 403。
- `MessagesTest`、`platformCheck`（模板的消息与权限）。

## 偏离与说明

- "运行时刻按部署时区显示"：前端读不到部署的时区（`jabiz.fiscal-year-zone`），所以按读者浏览器的时区显示，同时写出 UTC，不写死任何时区。管理员一般与部署在同一时区。如果要严格按部署时区显示，需要平台把这个配置交给前端（已告诉需求方，本 PR 未做）。

## 风险

- 模型与强度的可选值在前端照 `QbParams` 抄了一份。服务端照常校验，抄错的值只会被拒绝。
