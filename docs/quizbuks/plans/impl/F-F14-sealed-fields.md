# F-F14 密封字段的声明：实施计划（S3）

平台决策 D78 第 3 条、`docs/guide/key-rotation.md`"应用要做的事"：以 `SealSecret` 保存的字段都要在 `SealedFields` Bean 中声明，上下文与密封时完全一致；
没有声明的，旧的 `JABIZ_SEAL_KEY` 撤下后就再也解不开。本计划只做分给 S3 的四处（发布的共享奖品等其他几处由 S2 声明，Bean 各自独立，平台取并集，每个字段在全部 Bean 中只能声明一次）。

## 四处与核对结果

| # | 实体（表） | 字段 | 密封时的上下文 | 声明 |
|---|---|---|---|---|
| 1 | `QbSponsorMailServer`（`qb_sponsor_mail_server`） | `sealedPassword` | `MailServerProcesses.set`：`SPONSOR_ID`（发起方编号或 `prospect:<所有者>`），同一个值存为 `sponsorId` | `SealedField.of(…, "sealedPassword", "sponsorId")`，`mailserver.MailServerSealedFields` |
| 6 | `QbsShopToken`（`qbs_shop_token`） | `sealedAccessToken` | `ConnectionProcesses.shopDomain`：**店铺**行（`QbsShop`）的 `shopDomain` | **未声明**：令牌行只有 `shopId`，没有 `shopDomain`；上下文要从另一张表读出，`SealedField` 只能由本行算出（无 I/O）。见"待决定" |
| 7 | `QbsShopToken` | `sealedRefreshToken` | 同上 | **未声明**，同上 |
| 8 | `QbsDiscountCode`（`qbs_discount_code`） | `sealedCode` | `DiscountPool.CallShopify`：`domain(ctx)`，同一个值存为 `shopDomain` | `SealedField.of(…, "sealedCode", "shopDomain")`，`shopify.ShopifySealedFields`；字段由 `immutable` 改为可改（仍 `sensitive`，仍只由流程写：数据视图 `processOnlyWrites`） |

四处都有数据视图、都是 `sensitive`。`sensitive` 与 `processOnly` 不能同时声明（平台元模型拒绝），所以 #8 只去掉 `immutable`。

## 另需的改动

- 发起方邮件服务器的默认数据视图原来 `maxWriteBatchSize(1)`：轮换任务在一次提交中写一批（`jabiz.key-rotation.batch-size`，缺省 500，至多 10000），有两台以上服务器时整批被拒（`Batch size [2] exceeds dataset limit [1]`）。
  改为 10000（`MailServerConfig.MAX_WRITE_BATCH`）；写入仍只经流程。
- 迁移：无（`immutable` 只在元数据中，表上没有触发器或约束）。

## 测试

- `SealedFieldsRotationIT`（新）：以新的封存密钥启动、测试密钥作 `jabiz.security.seal.previous-keys`；以旧密钥按各自的上下文写入两台邮件服务器（发起方的、`prospect:` 的）与一个折扣码，
  运行任务 `jabiz.key-rotation`：三个值都改在当前密钥下、仍能以声明的上下文解开，经普通写入路径（版本 +1）；再运行一次不再改动。启动检查 `KEYS` 只有"轮换进行中"的警告。
- `:quizbuks:test`、`:quizbuks-shopify:test`、`:quizbuks:platformCheck`。

## 待决定

- #6、#7（Shopify 的访问与刷新令牌）：要能轮换，可以 ① 给 `qbs_shop_token` 加列 `shop_domain`（迁移按 `qbs_shop` 回填，`ConnectionProcesses.keep` 插入时写入），再以 `shopDomain` 声明；
  或 ② 不声明，轮换时这些令牌解不开，`ConnectionProcesses.access` 已把解不开当作"需要重新连接"（`RECONNECT`），店员下次打开内嵌页时重新交换令牌（`QBS_SHOP_CONNECT`）。由需求方 / 协调会话决定。

删除的功能：无。
