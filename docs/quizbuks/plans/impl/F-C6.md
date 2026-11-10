# F-C6 链接图片的下载：QuizBuks 一侧的接入（S4）

规格：`docs/quizbuks/first-launch-spec.md` F-C6（来源 `Q104-site-to-profile.md` §3.3、`Q100-profile-package.md`）。平台能力是 D75（进度表 P6，`POST /api/files/fetch`、`@jabiz/client` 的 `fetchFileFromLink`、`ExternalJobContext.remoteImages()`），已在 1.3/platform 做完并同步过来。本项只做 QuizBuks 一侧。总控 2026-11-09 批准计划，并决定：Quiz 文件不扩展链接图片（格式不变，提示词照旧写明不放图片）；外部作业处理器的接入随 F-C7。

## 做什么

1. **资料包的链接图片**。检查（`QB_PROFILE_IMPORT_CHECK`）的计划里，每个链接图片带着下载所用的策略（`Link.policy`：头像 `qb.sponsor-avatar`，封面与商品图 `qb.sponsor-page-image`），且只列本次导入会写的部分（基本信息或主页没保存时不列）。导入（`QB_PROFILE_IMPORT`）对链接图片：`files` 里有该路径的文件编号就用它，没有就保留现有的图片。文件的所有权与策略由各保存流程的现有检查拒绝（别人的文件仍是 `QB_FILE_NOT_OWN`）。
2. **服务器从不在导入的事务里下载**（D75）。发起方页面在点「导入」后先逐张调 `fetchFileFromLink`（平台每人同时至多 2 个，所以顺序下载），得到的文件是发起方自己的，再放进导入的 `files`。
3. **确认页**逐张显示状态（将会下载 / 正在下载 / 已下载 / 使用你自己的文件 / 下载不了 + 服务器的原因）。下载不了的每张可「重试」「改为上传图片」（走同一策略的上传）；仍有下载不了的，须勾选「保持现状」才能导入（总控要求：不静默丢图）。点「导入」不会重试失败的图片（重试只经按钮）。导入仍不改变审核。
4. **护栏**：QuizBuks 的 `ArchitectureTest` 加 `PlatformArchRules.noRemoteImageInternals`（业务代码不得直接用下载器）。
5. **运维文档**：`operations.md` §7.36（失败原因表、平台参数与限流的缺省、观测）。
6. **文案同步**：确认页消息（三语）、`profile-package-prompts.md`、帮助底稿 `help/profile-package.md`、`plans/impl/F-C5.md`、`backend/quizbuks/CLAUDE.md`。

## 不做

- Quiz 文件（`.quizbuks.json`）的链接图片（总控定）。
- 外部作业处理器的 `remoteImages()`（F-C7：从官网生成）。
- 任何新参数或迁移：下载的限制是平台的 `jabiz.files.fetch.*` 与 `jabiz.process.rate-limits.FILE_FETCH`，用缺省。

## 测试

- 后端 `ProfileLinkImagesIT`：测试配置放 `@Primary` 的 `RemoteFetchNetwork`（本机 HTTPS 桩，照 `RemoteImageFetchIT`）。检查给出链接与策略；经 `/api/files/fetch` 下载 → 文件记为发起方自己的（`sys_file.uploaded_by`）；用该文件编号导入后封面与商品图就是它；再导入同一个包（链接、没有文件）保留现有的商品图；别人的文件编号被拒。
- `ArchitectureTest` 新规则。
- 页面 `transfer.test.tsx`：下载成功（带策略与链接）、被拒时显示原因且不导入、重试、改为自己上传（同一策略）、保持现状（不带该图）；axe。
- e2e `profile-links.spec.ts`（亮、暗）：封面指向 `https://127.0.0.1/…` → 被拒并显示原因、导入键不可用 → 改为自己上传 → 导入完成、主页有封面。**只测失败路径**：e2e 没有公网图片源，D75 没有"只在测试中放行"的属性，有意如此（S1 的结论：那等于改 D75、正式 jar 里多一个 SSRF 开关）。

## 风险

- 成功路径在 e2e 里没有覆盖：由后端 IT（真实的下载流水线、本机桩）与页面测试覆盖。
- 链接很多时下载是顺序的（每张最长 60 秒）：包里至多 `qb.products.max` + 2 张图；导入按钮在下载期间不可用，页面显示每张的状态。
- 对方网站拒绝机器访问时只能由发起方自己上传：确认页的原因与 FAQ 说明了这一点。
