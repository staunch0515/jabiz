# F-F14 密封字段的声明：实施计划（S3）

平台决策 D78 第 3 条、`docs/guide/key-rotation.md`"应用要做的事"：以 `SealSecret` 保存的字段都要在 `SealedFields` Bean 中声明，上下文与密封时完全一致；
没有声明的，旧的 `JABIZ_SEAL_KEY` 撤下后就再也解不开。本计划只做分给 S3 的四处（发布的共享奖品等其他几处由 S2 声明，Bean 各自独立，平台取并集，每个字段在全部 Bean 中只能声明一次）。

## 四处与核对结果

| # | 实体（表） | 字段 | 密封时的上下文 | 声明 |
|---|---|---|---|---|
| 1 | `QbSponsorMailServer`（`qb_sponsor_mail_server`） | `sealedPassword` | `MailServerProcesses.set`：`SPONSOR_ID`（发起方编号或 `prospect:<所有者>`），同一个值存为 `sponsorId` | `SealedField.of(…, "sealedPassword", "sponsorId")`，`mailserver.MailServerSealedFields` |
| 6 | `QbsShopToken`（`qbs_shop_token`） | `sealedAccessToken` | `ConnectionProcesses.shopDomain`：**店铺**行（`QbsShop`）的 `shopDomain` | 令牌行原来只有 `shopId`；V81 加列 `shop_domain`（字段 `shopDomain`，不可变、可为空），`QBS_SHOP_CONNECT` 插入时写入。**声明暂缓**：见"平台的缺口" |
| 7 | `QbsShopToken` | `sealedRefreshToken` | 同上 | 同上 |
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

## Shopify 令牌的地址（V81，协调会话 2026-11-07 的决定）

- 迁移 `V81__quizbuks_shopify_token_domain.sql`（`backend/quizbuks-shopify/src/main/resources/db/migration`，与引擎共用版本序列；V80 已被另一个工作分支 `quizbuks-fs11-foreign-video` 占用）
  **只加列**，可为空，不在迁移中以 SQL 回填（实体的行只经平台的写入路径写，审计照旧）。之后的 `QBS_SHOP_CONNECT` 插入令牌行时写入店铺地址（更新令牌时不改，字段不可变）。
- 之前保存的令牌行地址为空。平台的重新加密以空的上下文打不开它们：`SealedSecrets` 拒绝空的上下文（`IllegalArgumentException`），
  `StoreUnderCurrentKey` 把它计为 `unreadable`、记日志、原样留在旧密钥下；撤下旧密钥后它们解不开，`ConnectionProcesses.access` 把解不开当作"需要重新连接"。
  Shopify 不在首次上线中，这样的行只在测试库里：**更换 `JABIZ_SEAL_KEY` 之前先重置测试库，或重新连接店铺**（写在 `launch-checklist.md`"测试环境"）。

## 平台的缺口（声明 #6、#7 暂缓）

声明了令牌的两个字段后，`SealedFieldsRotationIT` 中的轮换任务整批失败：`Optimistic lock conflict on QbsShopToken …: expected version [1], stored version [2]`。
平台的 `StoreUnderCurrentKey` 对每个声明的字段各发一个 `update`，都带读到的同一个版本；同一行有两个密封字段时第二个更新冲突，整批回滚（其他实体的值也不重新加密）。
在应用分支上不能改平台；声明照旧写好但先不启用（`ShopifySealedFields` 的注释），等平台把同一行的几个字段合成一次更新（并加一个两个字段在同一行的测试）后启用两行声明，
并把 `SealedFieldsRotationIT` 中令牌的断言改为"已在当前密钥下、仍能打开"。

## 测试（补充）

- `SealedFieldsRotationIT`：另写入一个店铺及其令牌（带地址），轮换后令牌仍在旧密钥下（未声明）。
- `ShopifyIT`：连接后令牌行的 `shop_domain` 是店铺地址，访问令牌以它解开即 Shopify 给的令牌。
- 令牌的数据视图 `maxWriteBatchSize` 改为 10000（与邮件服务器同理，供以后的声明）。

删除的功能：无。
