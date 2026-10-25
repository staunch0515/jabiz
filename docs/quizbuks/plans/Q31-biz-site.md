# Q31 招商网站（计划，2026-10-25 确认）

需求方（2026-10-25）：按讨论开发招商网站；按浏览器语言自动跳到相应语言页；页面要有技术与商业气息，参考美国同类网站；同时面向答题人（答题得奖励）与商户（广告费不浪费，给到真正了解你产品的人）；突出问卷调查；
首页有 QuizBuks 自己作为平台主、三种语言各自的答题链接按钮与二维码（先留白）；答题人注册在非手机上以手机大小的独立窗口打开，商户注册在新标签页打开。
确认：域名只有 `quizbuks.com`，页面上可切换语言；品牌 QuizBuks；联系方式为电子邮箱；线下领礼品免费（平台不收不付，成本可忽略），其他项目与模式都收费并给出估价；其余由实现方决定；UI 要专业、漂亮、像大平台。

## 1. 已确认的决定

| # | 决定 |
|---|---|
| D-Q31-1 | **静态站点，无框架**：`quizbuks-web/biz` 由 `scripts/build.mjs`（Node，无依赖）从 `src/templates/page.mjs`（版式与各页主体）与 `src/content/{en,zh,ja}.mjs`（三语文案，结构相同，测试检查）渲染 18 页：6 页（首页、商户、答题人、问卷调查、价格、联系）× 3 语言；样式 `src/assets/site.css`（设计令牌，亮暗两种外观）与脚本 `src/assets/site.js` 带内容哈希放在 `assets/`（平台缓存一年），页面 `no-cache`。不用 Vite、React；`app:check` 只要求共用的构建工具（本站一个也不声明）。`vitest` 只用于单元测试 |
| D-Q31-2 | **地址**：英文在 `/biz/<页>/`，中文 `/biz/zh/<页>/`，日文 `/biz/ja/<页>/`（平台 16zf-b：没有入口的站点每个目录一页）；每页带 `hreflang` 互指、`x-default`、`canonical` 与 `og:url`，都是完整地址（`SITE_URL`，缺省 `https://quizbuks.com`，构建时校验为 https 源）；构建另写 `sitemap.xml`（18 页） |
| D-Q31-3 | **语言**：只有一个域名，不按域名区分。从站外打开英文页时，脚本按 `localStorage` 中记住的选择，否则按 `navigator.languages`（`zh*` → 中文，`ja*` → 日文，其余英文）`location.replace` 到同一页的该语言版本（保留查询串与锚点）；来自本站（`document.referrer` 同源）的访问是访客自己的选择，从不转向（没有 `localStorage` 时切到英文也站得住）；直接打开的中文/日文页（分享的链接）从不被改走；页头的语言切换记住选择，记住的是页面自己的语言（`data-language`，未知目录退回英文首页时不会记成中文）。脚本是 `<head>` 中的普通脚本（构建去掉 `export`），转向在页面绘制之前；DOM 的绑定等 `DOMContentLoaded`。搜索引擎看到英文页与替代链接 |
| D-Q31-4 | **注册按钮**（`data-signup`）：答题人在桌面浏览器打开 `/taker/signup` 的独立窗口（390×844，`popup`，不带 `noopener`：带它时 `window.open` 对打开成功的窗口也返回 null，拦截的退路会多开一个标签页；被拦截时退回新标签页），在手机（粗指针或窄屏）上直接跳转；商户一律在新标签页打开 `/sponsor/signup`。带修饰键的点击（Ctrl/⌘/Shift、中键）交给浏览器。其他链接照常 |
| D-Q31-5 | **首页的试答区**：`src/content/try-quizzes.json` 给出三种语言各自的分享链接与二维码图片（`public/` 下的文件）；为空时显示虚线占位与"即将推出"；填上后即为按钮与二维码，不改页面。链接只接受 `https://`，二维码只接受 `public/` 下的 PNG/SVG 路径，否则构建失败 |
| D-Q31-6 | **价格**（推广期估价，日元、不含税，页面注明可变更）：线下奖品免费（Starter ¥0，今天就有的功能：不限问卷、二维码海报、每天 2,000 封邀请、统计与 CSV/XLSX 导出、助手起草在每日合理用量内）；以下都标"计划中"（尚未开售、尚未计费，按钮是"联系我们"）：Pro ¥9,800/月（Kudos 与现金奖励、每天 20,000 封邀请、助手 300 题/月、品牌化问卷页）；Business ¥49,800/月（多门店与团队、100,000 封/天、1,500 题/月、API 与 Webhook、优先支持）；Enterprise 定制。用量费：平台支付的 Kudos 奖励抽 20%，柜台交付的现金奖励抽 5%（平台不经手现金，按登记为已领取的奖励每月结算），首页推荐位 ¥30,000/周/地区，超量邀请 ¥2/封，超量助手起草 ¥500/100 题，答题人提现 ¥100/次（答题人承担；目前免费）。价格页顶部注明币种为日元 |
| D-Q31-7 | **联系**：邮箱 `staunch0515@gmail.com`（`mailto:`）、商户注册、App 帮助页；公司信息与法定声明指向 App 的法律页面 |
| D-Q31-8 | 内容安全策略保持平台缺省：没有内联脚本与事件属性；唯一的内联样式是调查示意条的宽度（`style-src 'unsafe-inline'` 在缺省策略内） |
| D-Q31-9 | **文案只说已有的功能**：问卷是每题单选/多选、没有对错、答完整套才有奖励（Q12a）；每题各选项的分布（Q12b）、Kudos/现金的佣金、推荐位、超量计费都写"计划中/即将推出"；助手的来源是文字或 PDF（不是网页，Q8）；审核与回信的时限写"通常几天"。评审（2026-10-25）据此改正 |

## 2. 改动

| 部分 | 改动 |
|---|---|
| 站点 | `quizbuks-web/biz/`：`package.json`（只有 `typescript`、`vitest`、`@types/node`）、`scripts/build.mjs`、`src/templates/page.mjs`、`src/content/{index,en,zh,ja}.mjs`、`src/content/try-quizzes.json`、`src/assets/{site.css,site.js}`、`public/`（图标）、`test/{site,content}.test.ts` |
| 平台 | 16zf-b（`StaticPages`） |
| e2e | `biz.spec.ts`：每页每语言 200 与 `lang`、无斜杠与未知目录、中文浏览器的转向与记住的切换、英文浏览器不动、桌面的手机窗口与商户新标签页、手机上直接跳转、亮暗两种外观的 axe |
| 文档 | 本文、`03-plan.md`、`02-design.md` §7.5、`operations.md`、`deploy/quizbuks/README.md`、`backend/quizbuks/CLAUDE.md`、`Q30-paths.md`（一页版被取代） |

## 3. 以后

- 真实的试答链接与二维码（发布 QuizBuks 自己的三份问卷后填入 `try-quizzes.json`）。
- Open Graph 的专用图片（现在用 `icon-512.png`）、根目录的 `robots.txt`（站点在 `/biz/` 下，由应用提供）。
- 开售时去掉"计划中"，并按 Q12b 的完成改回问卷结果的文案。
- 客户案例、商户后台的截图或短视频。
- 日文页的特定商取引法表示（收费开始前）。
