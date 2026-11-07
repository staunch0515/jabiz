# F-F7 扫一扫的修复合并与确认：实施计划（S3）

规格：`docs/quizbuks/first-launch-spec.md` F-F7。来源：troubleshooting T6、T12；PR 458（Q52b，`docs/quizbuks/plans/Q52-scan.md` §6）。

## 现状核对（2026-11-07）

- PR 458（分支 `1.3/quizbuks-52b-scan-resolution`）**已合入** `1.3/quizbuks`（合并提交 `7e098644`），CI 当时为绿。不需要再合并代码。
- 规格"参数"一条：拍摄的分辨率与中间截取的比例已是前端的导出常量，各带一段说明理由，不是业务参数，无需再改：
  - `quizbuks-web/app/src/scan/ScanPage.tsx` 的 `CAMERA`（`ideal` 1920 × 1080）；
  - `quizbuks-web/app/src/scan/decoder.ts` 的 `SCRIPT_EDGE`（800）、`MIDDLE_SHARE`（0.8）、`NATIVE_MISSES_BEFORE_SCRIPT`；
  - 测试 `decoder.test.ts`、`scan.test.tsx` 断言这些值。
- 链接格式（`scanTarget`）未改。

## 要改的

只改文档（不改代码、不加迁移）：

1. `docs/quizbuks/troubleshooting.md`：
   - T6 与 T12 的帮助页文案合成一条（三语，对象：学者、店员；`APP` / `REWARDS`），写在 T12 下；T6 指向 T12。用新称呼（学者），为 F-F5 先行。
   - T12 的状态改为"已修复，待在中国的安卓手机与 iPhone 上确认"，附确认记录的表（日期、机型、系统与浏览器、结果、确认人）。
   - 需求方确认后（见下），把 T6、T12 移入"已归档"。
2. `docs/quizbuks/first-launch-progress.md`：F-F7 一行。

## 测试

- 无代码改动；CI 照常（`quizbuks.yml` 的 `web` 作业含扫码的 Vitest）。

## 未完成（需要需求方）

- **真机确认**：需求方或样板人员在一台国产安卓（没有 Google Play 服务）与一台 iPhone 上，用 staging 的 App"扫一扫"扫商户后台"兑换"页的轮换码，把结果填进 T12 的确认记录。确认后由 S3 归档 T6、T12（一个小提交）。
- **样板指南**（Claude Doc"QuizBuks 样板发起方：工作要求与指南"，不在仓库中）：删去"已知问题"中扫码的一条——由需求方在文档中删，或给 S3 文档的编辑权限。
- 帮助中心的 FAQ（`QbFaqEntry`）由内容管理员在后台把合并后的文案发布为一条，并撤下 T6 旧的一条。

## 风险

- 真机仍扫不到：退回 T12 继续查（不自写解码程序，规格"不做"）。
