# Q95 招商网站按首次上线更新（计划，2026-11-04 确认）

需求方（2026-11-04）：首次上线前，按 Q94 的范围把招商网站（`quizbuks-web/biz`，Q31）更新一次。

## 1. 决定

| # | 决定 |
|---|---|
| D-Q95-1 | **两条主线并列**：①问卷代替广告、答对到店领奖（原主线，首页主视觉保留）；②知识结构图（先学再做、点亮、证书）。首页主视觉之后紧接一个同等分量的知识结构图区块；导航第一层有"知识结构图"一页 |
| D-Q95-2 | **"商户"一律改为"发起方"**（日"主催者"、英"Sponsor"，Q91）；页面地址 `merchants/` 改为 `sponsors/`，旧地址（三种语言）以平台 `jabiz.web.redirects` 301 到新地址 |
| D-Q95-3 | **奖品**：首次上线只写线下奖品（两种领法：答题者扫店里的码，或店员扫答题者的领奖码，Q89c）与电子奖品（Q33）。学分写"即将开放"，不写学分奖励与提现；现金奖励不写（平台不管，Q92） |
| D-Q95-4 | **价格页改为"费用"**：现在全部免费；三条长期原则：交易永不抽成、工具与资源按价目收费、服务（如提现）按次收费（Q92）。去掉 Pro / Business / Enterprise 套餐与用量费表（与 Q92"交易不收费"矛盾） |
| D-Q95-5 | **AI 助手不提**（上线时 `qb.ai.enabled` 关着） |
| D-Q95-6 | **广告**：招商网站、答题者 App、发起方后台的 AdSense 首次上线即开（已实现，`723bb7b2`）；上线前须发布隐私政策的新版本、AdSense 账户审核通过、欧洲经济区等地的同意（Q94 §4 一并改正） |
| D-Q95-7 | 知识结构图在首次上线时才有（Q90 T1）。网站合并到 `1.3/quizbuks` 只出现在测试系统；正式系统随首次上线（`1.3/quizbuks-release`）发布，仍守 D-Q31-9"只说已有的功能" |

## 2. 改动

- `src/content/{en,zh,ja}.mjs`：全部文案；新增 `maps`、`home.maps`；`merchants` → `sponsors`；价格的新结构。
- `src/content/index.mjs`：页面 `home, maps, sponsors, takers, surveys, pricing, contact`（21 页）。
- `src/templates/page.mjs`：新页 `maps`、首页的图区块、插图 `map`、费用页的新版式。
- `src/assets/site.css`：图区块与插图。
- `backend/quizbuks/src/main/resources/application.yml`：三条旧地址的转向。
- 测试：`test/content.test.ts`、e2e `biz.spec.ts`（页面清单、转向、axe 的页面）。
- 文档：本文、`03-plan.md`、`Q94-first-launch.md`（广告）、`Q31-biz-site.md`（指向本文）。
