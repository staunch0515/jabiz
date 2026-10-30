# Q54 品牌档案（2026-10-29）

需求方（2026-10-29）："对系统中全部的标识和标识图片抽出来统一放在一个位置，通过更换这个 profile，系统看上去就另一个系统。"
需求方选 **A**：每个品牌单独部署一套（自己的域名、数据库、商店上架），换品牌就是以另一份档案构建与部署。
同一套部署中同时服务几个品牌（按域名切换）不做。

编号：Q52 已是答题者 App 的扫码（另一会话，#337），本项为 Q54。安排见 `Q53-admin-polish.md` §1。

## 1. 现状（2026-10-29 盘点）

| 类别 | 位置 |
|---|---|
| 名称 | 各前端的消息（App 约 50 处、商家后台约 45 处、Shopify 28 处、官网与 Shopify 介绍站每种语言 22 / 11 处、店面组件 1 处、管理后台扩展约 16 处）；App 的 `install.tsx`；三个 `index.html` 的 `<title>`；PWA 清单（`app/vite.config.ts`）；后端消息 `messages_*.properties` 约 13 个键（`app.name` 之外仍写死）；维护页 `deploy/quizbuks/server/maintenance.html` |
| 图标 | 同一套图 `icon-192/512/maskable-512.png` 复制在五个前端的 `public/` 中；标志是 `src/qb/Illustrations.tsx` 的内联 SVG（`Wordmark`、`CoinMark`），官网另有一份 |
| 配色 | `src/qb/brand.css`（App 与商家后台共用，CI 比较）；官网与 Shopify 介绍站的 `site.css` 各有一份；PWA 的 `themeColor` |
| 联系方式与域名 | 客服邮箱 `quizbuks@gmail.com`（`QbParams` 缺省、`application.yml`、两个静态站）；`quizbuks.com`（静态站与店面组件的 `SITE_URL` 缺省、部署工作流） |
| 发出方 | 收据与结算单的发出方已是受控参数 `qb.issuer.*`；发信人只来自环境变量 `JABIZ_MAIL_FROM`；法律文本是数据库中的记录。三者已经不在代码中，不动 |

## 2. 设计

### 2.1 档案目录

`quizbuks-web/brands/<品牌>/`，缺省 `quizbuks`；以环境变量 `QB_BRAND` 选择（Gradle、Vite、静态站构建、Docker 构建参数都读它）。

```
brands/quizbuks/
  brand.json        名称（可另给 shortName）、各语言的说明、主题色与背景色、客服邮箱、站点地址
  brand.css         配色 token（--primary 等与 --qb-*，亮暗两套）与圆角——由原来的 src/qb/brand.css 移来
  site.css          两个静态站（官网、Shopify 介绍站）的品牌色（--accent 等与插画的 --leaf/--coin…，亮暗两套，Q54b）
  mark.svg          标志图形（原来的金币），Wordmark 内联显示（颜色取 token，只用 brand.css 与 site.css 都有的 token），静态站共用
  public/           图标：icon-192.png、icon-512.png、icon-maskable-512.png
brands/sample/      见本：新品牌复制它；CI 以它构建，检查页面上不再出现 QuizBuks
brands/brand.mjs    读取与检查；brands/vite.mjs 是 SPA 的 Vite 插件；brands/brand.test.mjs 检查档案与其余前端（Q54b）
```

- `brands/brand.mjs`：读取所选档案并检查（缺字段、缺图标、颜色格式、名称为空即构建失败），供 Vite 配置与静态站的构建脚本使用。
- 前端以 Vite 插件 `qbBrand()` 使用所选档案：别名 `@qb-brand` 指向档案目录，`brand.css` 由 `src/index.css` 引入；`publicDir` 指向档案的 `public/`（各前端的 `public/` 中只有这些图标，移走）；
  `src/qb/brand.ts` 导出构建时写入的 `BRAND`（`define`，只含页面可显示的部分）；`index.html` 中的 `__QB_BRAND_NAME__` 换成名称。
  原 `brand.css` 中与品牌无关的部分（`qb-*` 颜色类的映射、Q34b 的标签布局）留在共用的 `src/qb/theme.css`。
- 消息中的名称写成 `BRAND.name` 的模板字符串，不再写死；PWA 清单（名称、说明、颜色）取 `brand.json`。
- 后端（Q55b）：消息资源中的名称写成记号 `@QB_BRAND_NAME@`，`processResources` 以所选档案替换（Gradle 读 `brand.json`）；`app.name` 同样来自档案。
  客服邮箱与站点地址的缺省也取自档案（`@QB_BRAND_SUPPORT_EMAIL@`、`@QB_BRAND_SITE_URL@`：`quizbuks/brand.properties` 由 `setup.Brand` 读取，
  参数 `qb.support.email` 的缺省、分录摘要的前缀；`application.yml` 中 Shopify 的客服邮箱）。
- 管理后台与运营商后台（Q55b，平台 17v 的 `brand`）：扩展由平台前端编译，平台前端不提供扩展目录以外的文件，所以档案经生成的
  `admin-extension/src/brands.generated.ts`（`node quizbuks-web/brands/admin.mjs`；名称、标志与图标，标志的 token 换成亮色外观的值）进入扩展；
  构建时 Gradle 把 `QB_BRAND` 作为 `VITE_QB_BRAND` 交给 `spaBuildAdmin` / `spaBuildOperator`，扩展的文字以 `BRAND_NAME` 称呼产品。管理后台的配色仍是平台的。
- 维护页（Q55a）由档案生成；部署时按所选品牌重新生成后上传。
- 检查：测试扫描各前端与后端消息资源，除档案目录外不得再出现品牌名（标识符、包名、`qb.` 前缀等不算）；以测试用的第二份档案构建，名称、配色与图标都换掉。

### 2.2 不在档案中的

- 点数的名称 "Kudos"：遍及全部页面与邮件，换品牌时是否也要换，待需求方决定（见 §4）。
- 移动端外壳（`quizbuks-mobile/`，包名、图标、启动图）属另一会话；档案中给出图标原图，外壳如何取用由该会话决定。
- 商店上架文字（`docs/quizbuks/store/`）、Shopify 应用的配置（`shopify.app.toml`、店面扩展的 `shopify.extension.toml` 与 `locales`）随各品牌在 Shopify 上架另做。
- 官网首页的"试答"链接（`biz/src/content/try-quizzes.json`）是部署的数据，各品牌填自己的。
- 标识符：包名 `com.quizbuks.app`、iOS 的 User-Agent `QuizBuksApp/ios`、字典的 URN、Quiz 文件的格式名 `quizbuks.quiz` 与扩展名 `.quizbuks.json`、模板与流程的 `qb.` 前缀、数据库名，
  以及店面组件的全局名（`QuizbuksQuiz`、`QuizbuksPlayer`）、浏览器存储的键（`quizbuks:…`）、窗口名 `quizbuks-taker`、扩展名 `quizbuks-quiz`、应用代理路径 `/apps/quizbuks`、
  meta `quizbuks-support-email`，不随品牌变化（改了会丢失浏览器存的数据或破坏已部署的扩展）。

## 3. 分期

| 期 | 内容 |
|---|---|
| Q54a | 档案目录与读取、检查、见本档案；答题者 App 与商家后台：名称、标题、PWA 清单、图标、配色、标志；Gradle 的构建输入；CI 以见本档案构建并检查；测试（#342） |
| **Q54b** | 官网与 Shopify 介绍站：名称、标志、图标、品牌色（档案的 `site.css`）、站点地址与客服邮箱的缺省；Shopify 内嵌页：名称、标题、图标（配色仍是 Shopify 后台的）；店面组件的"Powered by"与链接；`brands/brand.test.mjs`；Gradle 与 CI（本 PR） |
| Q54c | 改为 Q55a（维护页，#347）与 Q55b：后端消息资源与 `app.name`、客服邮箱与站点地址的缺省、管理后台扩展（平台 17v 的 `brand`）、Docker 构建参数与部署工作流；§7 新建一个品牌 |

## 4. 待需求方决定

- 换品牌时点数名称 "Kudos" 是否也换。不换则保持现状；要换就作为档案的一项，工作量与名称相当。

## 5. 测试

- `src/qb/brand-profile.test.ts`（两个 SPA 共用）：每份档案完整；检查说出缺什么；没有的品牌被拒绝；构建的 `BRAND` 是 `QB_BRAND` 所选的；
  SPA 的源码与 `index.html` 中除标识符外不再出现品牌名。
- `src/qb/brand.test.ts`：每份档案的 `brand.css` 在亮暗两种外观下都满足对比度。
- 商店的宣传图（`e2e/store/content.ts`）取档案的图标、名称与主题色。
- `brands/brand.test.mjs`（`node --test`，Q54b）：每份档案完整；`site.css` 的文字配色在亮暗两种外观下满足 AA（`--accent` 与页面、`--accent-fg` 与 `--accent`、`--chip-fg` 与 `--accent-soft`）；
  `mark.svg` 用到的 token 在 `brand.css` 与 `site.css` 中都有；官网、Shopify 介绍站、Shopify 内嵌页、店面组件的源码中除标识符外不再出现品牌名。
- CI：以 `QB_BRAND=sample` 构建官网、Shopify 介绍站与内嵌页，名称、站点地址与图标为见本档案的，产物中没有 QuizBuks。
- CI（`quizbuks.yml` 的 web）：以 `QB_BRAND=sample` 构建两个 SPA，标题、清单、图标为见本档案的，产物中除 iOS 外壳的 User-Agent 外没有 QuizBuks。

- 后端（Q55b）：`setup.BrandTest`：构建的 `Brand` 与参数缺省是所选档案的，打包的消息与 `application.yml` 中没有剩下的记号、`app.name` 为档案名称，
  源消息中（注释除外）没有写死的 QuizBuks；以 `QB_BRAND=sample` 打包时消息为见本档案的名称。
- 管理后台扩展（Q55b）：`brand.test.ts`（缺省是 QuizBuks，见本档案的名称、标志与图标，没有的品牌被拒绝，菜单以品牌名称呼）；
  `brands/admin.test.mjs`：提交的 `brands.generated.ts` 与档案一致，标志中没有剩下的 token。

## 6. 移动端外壳（另一会话）

- Android 外壳的一致性测试与 README 已改为读档案（需求方同意由 #342 修改）。

## 7. 新建一个品牌

1. 复制 `quizbuks-web/brands/sample` 为 `quizbuks-web/brands/<品牌>`（小写字母、数字与 `-`），改 `brand.json`（名称、三种语言的说明、主题色与背景色、客服邮箱、站点地址）、
   `brand.css` 与 `site.css` 的配色（亮暗两套，测试检查对比度）、`mark.svg`（颜色只用 token）与 `public/` 的三个图标。
2. 在 `quizbuks-web/` 下 `node --test brands/*.test.mjs maintenance/*.test.mjs`（档案完整、对比度），再 `node brands/admin.mjs` 重新生成管理后台扩展的档案并提交。
3. 本地试看：`QB_BRAND=<品牌>` 下 `./gradlew :quizbuks:bootRun`（后端消息、管理后台与全部前端），`QB_BRAND=<品牌> node quizbuks-web/maintenance/build.mjs <文件>` 看维护页。
4. 部署：仓库变量 `QUIZBUKS_BRAND=<品牌>`（`deploy/quizbuks/DEPLOY.md` 一次性设置第 1 条）；镜像以构建参数 `QB_BRAND` 构建，维护页在上传前按它重新生成。
5. 不随品牌改变的见 §2.2：移动端外壳、商店文字、Shopify 应用的配置与标识符另做。
