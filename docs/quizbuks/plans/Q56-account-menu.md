# Q56 答题者的账户页改为菜单（2026-10-30）

需求方（2026-10-30）："答题者的账户页内容太多，考虑加菜单分页处理。"（`Q55-Q60-overview.md`）

## 1. 现状

`/account` 一页依次是：资料、关于我（匹配用，Q47）、我的 ID 与店员（Q39）、安装 App、邮箱、密码、两步验证、关联账号、邮件、登录设备、最近登录、帮助与信息，
右上角是界面语言。手机上要滚动很久才能找到想改的一项。

## 2. 设计

- `/account` 是**菜单**：顶部是名字与邮箱，下面两组列表——"设置"（六个子页面）与"帮助与信息"（帮助、用户条款、隐私政策、关于本 App，直接链接到原页面）。
  每项有图标、标题、一行说明与箭头，整行可点（至少 48 px 高）。
- 子页面 `/account/<部分>`，顶部是"← 账户"与标题：

| 路径 | 标题 | 内容 |
|---|---|---|
| `/account/profile` | 资料 | 名字、国家或地区、城市、货币（`ProfileForm`） |
| `/account/details` | 关于我 | 匹配用的资料（`ProfileDetails`，Q47） |
| `/account/security` | 登录与安全 | 邮箱（不可改）、密码、两步验证、关联账号、登录设备、最近登录 |
| `/account/mail` | 邮件 | 接收哪些邮件与邮件语言（`MailPreferences`） |
| `/account/preferences` | App 设置 | 界面语言、安装 App；Q57 的音效开关与 Q59 的内容语言加在这里 |
| `/account/id` | 我的 ID 与店员 | 我的 ID（复制）、所在商家的店员身份（`StaffAccount`，Q39） |

- 底部导航在菜单与全部子页面上都标出"账户"。没有的部分（`/account/xxx`）回到菜单。
- **旧链接照常可用**：`/account?section=details`（旧版"补充资料"按钮的地址）转到 `/account/details`；`/account` 仍是账户的入口（服务器的 `/account → /taker/account` 转向不变）。
- 关联外部账号的往返回到 `/account/security`，二次验证的"去设置"（`StepUpProvider` 的 `securityPath`）也指向它；店员柜台的"前往账户"指向 `/account/id`。
- 共用的 `qb/AccountSections.tsx` 拆出 `SecuritySections` 与 `MailSection`；商家后台仍在一页上用 `AccountSections`（邮件移到最近登录之后）。

## 3. 测试

- `App.test.tsx`：菜单列出六个部分（顺序与地址）、点开"登录与安全"显示邮箱且底部导航仍标出账户、"← 账户"回到菜单、axe；
  旧地址 `?section=details` 转到 `/account/details`，没有的部分回到菜单；原有的资料、关联账号、邮件、语言的测试改为打开各自的子页面。
- `staff.test.tsx`、`Play.test.tsx`（Q47 的"补充资料"按钮到 `/account/details`）。
- e2e：`reward-methods`（菜单的 axe，从菜单打开"我的 ID 与店员"）、`auth`、`share`、`install`、`audience` 改为子页面的地址。
