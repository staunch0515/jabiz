# Q31 招商网站（计划，2026-10-25 确认）

需求方（2026-10-25）：按讨论开发招商网站；按浏览器语言自动跳到相应语言页；页面要有技术与商业气息，参考美国同类网站；同时面向答题人（答题得奖励）与商户（广告费不浪费，给到真正了解你产品的人）；突出问卷调查；
首页有 QuizBuks 自己作为平台主、三种语言各自的答题链接按钮与二维码（先留白）；答题人注册在非手机上以手机大小的独立窗口打开，商户注册在新标签页打开。
确认：域名只有 `quizbuks.com`，页面上可切换语言；品牌 QuizBuks；联系方式为电子邮箱；线下领礼品免费（平台不收不付，成本可忽略），其他项目与模式都收费并给出估价；其余由实现方决定；UI 要专业、漂亮、像大平台。

## 1. 已确认的决定

| # | 决定 |
|---|---|
| D-Q31-1 | **静态站点，无框架**：`quizbuks-web/biz` 由 `scripts/build.mjs`（Node，无依赖）从 `src/templates/page.mjs`（版式与各页主体）与 `src/content/{en,zh,ja}.mjs`（三语文案，结构相同，测试检查）渲染 18 页：6 页（首页、商户、答题人、问卷调查、价格、联系）× 3 语言；样式 `src/assets/site.css`（设计令牌，亮暗两种外观）与脚本 `src/assets/site.js` 带内容哈希放在 `assets/`（平台缓存一年），页面 `no-cache`。不用 Vite、React；`app:check` 只要求共用的构建工具（本站一个也不声明）。`vitest` 只用于单元测试 |
| D-Q31-2 | **地址**：英文在 `/biz/<页>/`，中文 `/biz/zh/<页>/`，日文 `/biz/ja/<页>/`（平台 16zf-b：没有入口的站点每个目录一页）；每页带 `hreflang` 互指与 `x-default`（站内路径；完整地址要知道主机名，留到部署时） |
| D-Q31-3 | **语言**：只有一个域名，不按域名区分。首次打开英文页时，脚本按 `localStorage` 中记住的选择，否则按 `navigator.languages`（`zh*` → 中文，`ja*` → 日文，其余英文）`location.replace` 到同一页的该语言版本（保留查询串与锚点）；直接打开的中文/日文页（分享的链接）从不被改走；页头的语言切换记住选择。搜索引擎看到英文页与替代链接 |
| D-Q31-4 | **注册按钮**（`data-signup`）：答题人在桌面浏览器打开 `/taker/signup` 的独立窗口（390×844，`popup`，被拦截时退回新标签页），在手机（粗指针或窄屏）上直接跳转；商户一律在新标签页打开 `/sponsor/signup`。其他链接照常 |
| D-Q31-5 | **首页的试答区**：`src/content/try-quizzes.json` 给出三种语言各自的分享链接与二维码图片（`public/` 下的文件）；为空时显示虚线占位与"即将推出"；填上后即为按钮与二维码，不改页面 |
| D-Q31-6 | **价格**（推广期估价，日元、不含税，页面注明可变更）：线下奖品免费（Starter ¥0：不限问卷、二维码海报、每天 2,000 封邀请、统计与 CSV、助手起草每月 50 题）；Pro ¥9,800/月（Kudos 与现金奖励、每天 20,000 封邀请、助手 300 题/月、品牌化问卷页、XLSX 与存档报表）；Business ¥49,800/月（多门店与团队、100,000 封/天、1,500 题/月、API 与 Webhook、优先支持）；Enterprise 定制。用量费：平台支付的 Kudos 奖励抽 20%，现金奖励 10% + 支付手续费，首页推荐位 ¥30,000/周/地区，超量邀请 ¥2/封，超量助手起草 ¥500/100 题，答题人提现 ¥100/次（答题人承担） |
| D-Q31-7 | **联系**：邮箱 `staunch0515@gmail.com`（`mailto:`）、商户注册、App 帮助页；公司信息与法定声明指向 App 的法律页面 |
| D-Q31-8 | 内容安全策略保持平台缺省：没有内联脚本与事件属性；唯一的内联样式是调查示意条的宽度（`style-src 'unsafe-inline'` 在缺省策略内） |

## 2. 改动

| 部分 | 改动 |
|---|---|
| 站点 | `quizbuks-web/biz/`：`package.json`（只有 `typescript`、`vitest`、`@types/node`）、`scripts/build.mjs`、`src/templates/page.mjs`、`src/content/{index,en,zh,ja}.mjs`、`src/content/try-quizzes.json`、`src/assets/{site.css,site.js}`、`public/`（图标）、`test/{site,content}.test.ts` |
| 平台 | 16zf-b（`StaticPages`） |
| e2e | `biz.spec.ts`：每页每语言 200 与 `lang`、无斜杠与未知目录、中文浏览器的转向与记住的切换、英文浏览器不动、桌面的手机窗口与商户新标签页、手机上直接跳转、亮暗两种外观的 axe |
| 文档 | 本文、`03-plan.md`、`02-design.md` §7.5、`operations.md`、`deploy/quizbuks/README.md`、`backend/quizbuks/CLAUDE.md`、`Q30-paths.md`（一页版被取代） |

## 3. 以后

- 真实的试答链接与二维码（发布 QuizBuks 自己的三份问卷后填入 `try-quizzes.json`）。
- `hreflang` 的完整地址、`sitemap.xml`、Open Graph 图片。
- 客户案例、商户后台的截图或短视频。
- 日文页的特定商取引法表示（收费开始前）。
