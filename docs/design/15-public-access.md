# 15 公开只读访问

让未登录的访客读取**已声明为公开**的数据（ROADMAP 阶段 13c）。约束性细则见【决策 D17】（补充 D12 第 2 条，不取代）。
原则仍是**默认拒绝**：没有声明为公开的，匿名一律看不到；公开只意味着"可读"，从不意味着"可写"（唯一的例外是声明过的公开计数器，§10、决策 D65）；
公开的范围、字段和查询都在元数据里写明，并在启动时检查。

## 1. 三层声明

```
公开数据视图（数据视图的一种）   决定"哪些行、哪些列"可以被公开读取：固定范围 + 字段白名单
        ▲
公开 SQL 模板（access: public）  决定"怎样读"：只能读公开数据视图、只能用白名单字段
        ▲
GET /api/public/queries/{id}     匿名执行公开模板；缓存头、ETag、限流
```

公开读取**只经公开模板**，不提供匿名的通用数据视图查询：模板的结果列全部声明（05 §2.1），天然就是对外契约。

## 2. 公开数据视图

```java
@Bean DatasetDefinition publicStory() {
    return DatasetDefinition.define("urn:jabiz:dataset:public:Story", d -> d
        .targetEntityType("Story")
        .scope(s -> s.fixed("status", "PUBLISHED"))                 // 只允许固定值范围
        .publicRead(p -> p.fields("storyId", "slug", "title", "summary", "thumbnailFileId", "publishedTime"))
        .permissions("culture.story.read", "culture.story.write")   // 已认证用户经数据视图 API 预览时使用
        .storage(s -> s.connectionPoolRef(pool)));
}
```

`publicRead(...)` 的约束（启动时检查 `PublicReadChecks`，全部问题一次报告，类别 `PUBLIC`）：
- 视图必须 `readOnly()`（`publicRead` 自动设置，并去掉 `processOnlyWrites`）、`allowTimeTravel(false)`（自动设置），且**不能是默认视图**（默认视图服务后台）。
- `maxQueryBatchSize` 不超过 `jabiz.public.max-limit`（默认都是 100）。
- 范围**只能是固定值**（`fixed`）且至少一个：匿名上下文没有操作人和租户，`fromContext` 不能成立；没有范围的公开视图等于整表公开，必须显式写
  `publicRead(p -> p.allRows().fields(...))` 表明意图（例如主题、地点这类本身就公开的数据）。
- 白名单字段必须存在；不得包含敏感字段（02 §6.1）；范围字段不必在白名单中。
- **渲染时投影**：模板中 `{{Story}}` 对公开视图渲染为 `(SELECT <白名单字段的物理列> FROM 表 WHERE 范围条件)`
  （时态实体先取生效版本、去掉墓碑，再投影；没有范围也照样投影）。因此即使模板直接写物理列名，也读不到白名单以外的列（数据库报"列不存在"）；
  白名单外的 `{{Story.field}}` 在渲染与启动检查时都报错。
- 子实体没有自己的发布状态时，应由业务在子实体上维护一个可作范围的字段（例如 `visibility = PUBLIC`，由发布流程设置），
  而不是依赖模板里的 JOIN：公开视图的范围必须单独成立。

## 3. 公开 SQL 模板

头部以 `access: public` 代替 `permissions`（两者都写或都不写 → 启动失败）：

```sql
/*---
id: culture.public.story_detail
access: public
cacheSeconds: 60
entities: [Story, Participant]
datasets: { Story: "urn:jabiz:dataset:public:Story", Participant: "urn:jabiz:dataset:public:Participant" }
params:
  slug: { like: Story.slug, required: true }
results:
  storyId: { from: Story.storyId }
  title:   { from: Story.title }
---*/
SELECT s.{{Story.storyId}} AS storyId, s.{{Story.title}} AS title
FROM {{Story}} s
WHERE s.{{Story.slug}} = :slug
```

启动检查（在 05 §5 已有检查之外）：
1. `entities` 中每个实体都经 `datasets` 指向**公开数据视图**（默认视图从不公开，因此必须写 `datasets`）。
2. 每个占位符 `{{Entity.field}}` 都在该视图的白名单中；`results` 的 `from` 同样。
3. `timeoutMs` ≤ `jabiz.public.max-timeout`（默认 2 s）；行数上限 ≤ `jabiz.public.max-limit`（默认 100，见 §2）。
   执行时超时取 `min(timeoutMs, 视图超时, max-timeout)`，每页行数取 `min(limit, 视图 maxQueryBatchSize, max-limit)`。
4. `cacheSeconds` 可选，0–3600，默认 `jabiz.public.default-cache-seconds`（60）；只用于公开模板（非公开模板写了即启动失败）。

公开模板也可由已认证用户经 `/api/queries/{id}` 执行，不需要权限（后台预览；D17 第 8 条）。

## 4. 公开文件

`GET /api/public/files/{fileId}[/{variant}]`：文件**当且仅当**被某个公开数据视图中可见的行、以白名单中的 `jabiz.file` 字段引用时才提供，
否则 404（不区分"不存在"与"不公开"）。

- 由元数据得出全部（公开视图，`jabiz.file` 白名单字段）对（`PublicFileAccess`），渲染为一条 `SELECT EXISTS(... UNION ALL ...)` 查询，
  每个分支按与公开模板相同的方式读取视图（生效版本、范围、投影），参数只有 `fileId`，超时 `jabiz.public.max-timeout`。
- 判定结果（可取与不可取都）在进程内缓存 `jabiz.public.file-decision-ttl`（默认 60 s，有界 LRU，`jabiz.public.file-decision-entries`
  默认 10 000）；响应 `Cache-Control: public, max-age=<jabiz.public.file-cache-seconds>`（默认 300）。
  因此内容下线后，文件最迟在"判定缓存 + 浏览器缓存"时间后不再可得——这是有意的取舍。
- 需要立即撤下时（例如撤回同意），业务流程在下线后以提交后步骤使判定缓存失效：
  `.afterCommit("…", FileAccess.invalidate(ctx -> 文件 id 列表))`（`FileAccess.fromContext(key)` 取上下文中的单个 id）。
  放在提交之后，下一次请求才会看到已提交的数据。只清本实例的缓存；多实例时其他实例在判定有效期内失效（已知限制），浏览器缓存另计。
  `FILE_DELETE` 同样在提交后使判定失效。示范：`app` 的 `PRODUCT_WITHDRAW`。
- 与 14 §5 相同的响应头与变体规则（策略声明的宽度不小于图片宽度时返回原件）；PDF 为附件；支持单段 `Range`。下载名由文件 id 与扩展名组成（`<id>[-变体].jpg`），**从不使用上传时的原始文件名**（可能含个人信息）。

## 5. 接口

`GET /api/public/queries/{id}`（`HEAD` 同样允许；其他方法 405）：

| 查询参数 | 含义 |
|---|---|
| `p.<name>=value` | 模板参数（列表参数重复出现：`p.theme=home&p.theme=food`） |
| `filter=<field>:<op>:<value>` | 外层筛选（模板 `list.filters` 白名单；`op` 同 03 §3；`in` 的值以逗号分隔，`between` 为 `from,to`） |
| `sort=<field>:asc|desc` | 排序（白名单） |
| `offset`、`limit`、`count` | 分页与是否计数 |

- 响应与已认证的模板接口相同：`{items, total, offset, limit}`。
- 未知或非公开的模板 → 404（不暴露私有模板是否存在）；参数错误 → 400 `ProblemDetail`（文案按 `Accept-Language`）。
- 匿名上下文：`RequestContext.anonymous(locale, requestId)`，不看 `Authorization` 头（过期的令牌不会让公开页面 401）。
- 缓存：`Cache-Control: public, max-age=<cacheSeconds>`、`Vary: Accept-Language`、强 `ETag`（响应体 SHA-256）；`If-None-Match` 命中 → 304
  （比较时忽略 `W/`，RFC 9110 的弱比较）。错误响应不带公开缓存头。
- 分页：`limit` 默认 20，上限见 §3；`count` 默认 `true`。未知的查询参数（不是 `p.*`、`filter`、`sort`、`offset`、`limit`、`count`）→ 400。
- 限流：按客户端地址的进程内令牌桶，`jabiz.public.rate-limit.per-minute`（默认 300）→ 429 `RATE_LIMITED` + `Retry-After`。
  客户端地址与登录记录同一来源（`ClientAddresses`，10 §15.5、决策 D36 第 7 条）：只在连接来自 `jabiz.security.trusted-proxies` 时才读 `X-Forwarded-For`
  （从右向左跳过可信地址，取第一个不可信地址）；不配置可信代理时取连接地址（`server.forward-headers-strategy` 不是 `none` 时启动即失败，`dev` 下为警告，D47 第 4 条）。
  桶的数量有上限（LRU，默认 100 000），防止内存被耗尽。多实例时各自限流（近似值，已知限制）。
- 公开读取不是操作：不写 `op_process`（与已认证的读取一致）。
- 总开关 `jabiz.public.enabled`（默认 **false**；`app` 以环境变量 `JABIZ_PUBLIC_ENABLED` 打开）：关闭时 `/api/public/**` 一律 404（任何方法），
  但公开视图与模板的启动检查照常执行。
- 开关、方法（405，带 `Allow: GET, HEAD`）与限流（429）由 `PublicAccessWebFilter` 在安全过滤链之前处理，从不进入控制器或写入路径。
  唯一放行的写方法是公开计数器的 `POST /api/public/counters/{计数器}/{键}`（§10）。

## 6. 安全配置

- `SecurityConfig`：`/api/public/**` 的 `GET`/`HEAD` 放行（`permitAll`），位于"`/api/**` 必须已认证"之前；认证过滤器不处理这些路径。
- 其他方法对 `/api/public/**` 仍然拒绝（405，从不进入任何写入路径），公开计数器的 `POST`（§10）除外。
- 公开页面本身（静态资源）与后台一样由 SPA 提供，内容安全策略按 SPA 配置（17 §3）。
- 入站 Webhook `POST /api/inbound/{提供方}`（11 §2.5、决策 D37 第 5 条）同样匿名、同样在安全过滤链之前限流：`InboundWebhookWebFilter` 与
  `PublicAccessWebFilter` 写法相同（客户端地址取自 `ClientAddresses`，进程内令牌桶，429 `RATE_LIMITED` + `Retry-After`），但每个提供方一个桶
  （`jabiz.inbound-webhooks.providers.<名>.rate-limit.per-minute`，缺省 120），且不受 `jabiz.public.enabled` 影响。
  在未列入 `jabiz.security.trusted-proxies` 的代理之后，所有发送方的地址都是代理的地址，共用一个桶。

## 7. 前端类型契约

公开模板的目录与 `/api/public/**` 接口都不进入 OpenAPI 文档（`springdoc.paths-to-exclude`；D15 第 5 条不变）。平台提供 `PublicQueryCatalog`：由公开模板生成
`{id, params: {name: {kind, list, required}}, results: {name: kind}, list: {filters, sorts, defaultSort}, cacheSeconds}` 的 JSON。
应用的测试 `PublicQueriesSnapshotIT`（平台测试夹具 `PublicQueriesSnapshot.verify`）把它与仓库中的快照比较
（`-Dpublic-queries.update-snapshot=true` 重写；路径 `jabizApp.publicQueriesSnapshot`，`app` 为 `frontend/openapi/public-queries.json`），公开前端据此生成 TS 类型
（与 OpenAPI 快照同一套做法，12 §3）。

## 8. 观测

`jabiz.public.query`（标签：模板 id、`result`＝`ok` / `not_modified`，以及所有平台观测都有的 `outcome` / `status`，被拒绝或出错时由它们表示）、
`jabiz.public.rate_limited`（无标签）、`jabiz.public.counter`（标签：计数器名、`result`＝`counted` / `repeated` / `ignored`）；公开文件沿用 `jabiz.file.serve`（`channel=public`）。不记录客户端地址（日志中也不记录），未知模板 id 不产生观测。

## 9. 测试

- core（`PublicReadTest`）：`publicRead` 的策略与校验（敏感字段、`fromContext`、默认视图、白名单字段不存在、`allRows`、行数上限）；
  投影渲染（普通、时态、无范围）；公开模板检查（引用非公开视图或默认视图、白名单外的占位符与结果列、权限、超时、缓存时间）；头部解析；目录导出。
- app 集成测试（真实 PostgreSQL，`PublicAccessIT` 等）：匿名可读公开模板；**范围外的行永远不出现**（下线的数据，经模板参数、外层筛选、计数三种途径）；
  写物理列名读不到白名单外的列；非公开模板 404；写方法 405；带过期令牌仍然 200；ETag / 304；参数错误 400；不写 `op_process`；
  限流 429（`PublicRateLimitIT`）；开关关闭时 404（`PublicSwitchedOffIT`）；
  公开文件：被公开行引用 → 200，只被下线行引用 → 404，被非白名单字段引用 → 404（普通表 `ItAttachment`），经 `PRODUCT_WITHDRAW` 下线即 404，
  未失效时在判定有效期内仍可取；目录快照（`PublicQueriesSnapshotIT`）；启动检查一次报告全部违规（`PlatformCheckIT`）；观测（`ObservabilityIT`）；
  场景回放 `scenarios/commerce/public_catalog.yml`。
- 公开计数器（§10）：core `PublicCounterTest`（声明与校验）；runtime `PublicCountersTest`（去重、日期按时区、合并与写入失败后保留）；
  app 集成测试 `PublicCounterIT`（匿名计入并写入、重复与不合法的键不计、下线的行不计、未知计数器 404、其他写方法与路径仍 405、开关关闭时 404、不写 `op_process`、
  平台数据视图需要 `public-counter.read`）、启动检查（`PlatformCheckIT`）；前端 `countPublic` 的单元测试。

## 10. 公开计数器

匿名访客的浏览与点击按日计数（决策 D65）：

```java
@Bean
PublicCounter productViews() {
    return PublicCounter.define("commerce.product-view", c -> c
        .keys(CommerceEntities.PUBLIC_PRODUCT_DATASET, "productId")   // 只计公开视图中此刻可见的行
        // .owner("sellerId")                                           // 可选：键所在行的所有人，存入 ownerKey
        .zone("Asia/Tokyo"));                                           // 日期的时区，缺省 UTC
}
```

```ts
import { countPublic } from '@jabiz/client'
countPublic('commerce.product-view', product.productId)   // sendBeacon；失败忽略
```

- 接口 `POST /api/public/counters/{计数器}/{键}`（没有正文）：未知计数器 404；其余一律 204（计入、重复、键不合法不区分）。开关与限流同 §5。
- 键的合法性：此刻在该公开视图中可见的行（与公开文件同样的读法，§4）；判定记住 `jabiz.public.file-decision-ttl`。键至多 100 字符。
- 去重：同一计数器、键、日期，同一访客（客户端地址与 `User-Agent` 的摘要，只在内存中）只计一次；`jabiz.public.counters.max-visitors`（缺省 200 000）。
- 两次写入之间至多记 `jabiz.public.counters.max-pending`（缺省 50 000）个键（计数器、键、日期），超出的新键不计（内存有上限）。
- 写入：内存中累加，每 `jabiz.public.counters.flush-interval`（缺省 10 s）与停止时写入 `sys_public_counter_day`（`counter_name`、`counter_key`、`day`、`hits`；
  `ON CONFLICT DO UPDATE` 累加）；失败的留到下一次。平台的测试配置关闭定时写入（`jabiz.public.counters.flush-interval=0s`），测试直接调用 `PublicCounters.flush()`。
- 读取：平台实体 `PublicCounterDay`、平台数据视图 `urn:jabiz:dataset:platform:PublicCounterDay`（`public-counter.read`，只读）；
  应用以自己的只读数据视图（带范围）与模板给各自的读者。
- 所有人（D65 第 7 条）：声明了 `owner(字段)` 的计数器在计数时同时读出键所在行的该字段，存入 `owner_key`（迁移 V53；已有的行保留最早的值）。
  应用给每个所有人一个数据视图：`scope(s -> s.fixed("counterName", 名).fromContext("ownerKey", RequestContext::actorId))`，只读。
- 近似值：多实例各自去重、异常退出丢失最后一个间隔、NAT 后的同款浏览器计为一人；展示处应注明。
- 部署在反向代理之后时必须把代理列入 `jabiz.security.trusted-proxies`（10 §15.5）：否则所有访客的地址都是代理的地址，同一款浏览器每天只计一次。
- 观测：每次计数 `jabiz.public.counter`（§8），每次写入 `jabiz.public.counter.flush`（没有待写的计数时不产生）。

