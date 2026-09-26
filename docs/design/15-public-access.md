# 15 公开只读访问

让未登录的访客读取**已声明为公开**的数据（ROADMAP 阶段 13c）。约束性细则见【决策 D17】（补充 D12 第 2 条，不取代）。
原则仍是**默认拒绝**：没有声明为公开的，匿名一律看不到；公开只意味着"可读"，从不意味着"可写"；
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
    return DatasetDefinition.define("urn:jabiz:dataset:public:Story", "Story")
        .scope(s -> s.fixed("status", "PUBLISHED"))                 // 只允许固定值范围
        .publicRead(p -> p.fields("storyId", "slug", "title", "summary", "thumbnailFileId", "publishedTime"))
        .permissions("culture.public.read", "culture.public.read")  // 已认证用户经数据视图 API 预览时使用
        .build();
}
```

`publicRead(...)` 的约束（构建期或启动时检查，全部问题一次报告，类别 `PUBLIC`）：
- 视图必须 `readOnly()`（`publicRead` 自动设置）、`allowTimeTravel(false)`（自动设置），且**不能是默认视图**（默认视图服务后台）。
- 范围**只能是固定值**（`fixed`）且至少一个：匿名上下文没有操作人和租户，`fromContext` 不能成立；没有范围的公开视图等于整表公开，必须显式写
  `publicRead(p -> p.allRows().fields(...))` 表明意图（例如主题、地点这类本身就公开的数据）。
- 白名单字段必须存在；不得包含敏感字段（02 §6.1）；范围字段不必在白名单中。
- **渲染时投影**：模板中 `{{Story}}` 对公开视图渲染为 `(SELECT <白名单字段的物理列> FROM 表 WHERE 范围条件)`。
  因此即使模板直接写物理列名，也读不到白名单以外的列。
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
3. `timeoutMs` ≤ `jabiz.public.max-timeout`（默认 2 s）；行数上限 ≤ `jabiz.public.max-limit`（默认 100）。
4. `cacheSeconds` 可选，0–3600，默认 `jabiz.public.default-cache-seconds`（60）。

## 4. 公开文件

`GET /api/public/files/{fileId}[/{variant}]`：文件**当且仅当**被某个公开数据视图中可见的行、以白名单中的 `jabiz.file` 字段引用时才提供，
否则 404（不区分"不存在"与"不公开"）。

- 启动时由元数据得出全部（公开视图，`jabiz.file` 白名单字段）对，渲染为一条 `SELECT EXISTS(... UNION ALL ...)` 查询，参数只有 `fileId`。
- 判定结果在进程内缓存 `jabiz.public.file-decision-ttl`（默认 60 s，有界 LRU）；响应 `Cache-Control: public, max-age=300`。
  因此内容下线后，文件最迟在"判定缓存 + 浏览器缓存"时间后不再可得——这是有意的取舍，写入用户文档。
- 需要立即撤下时（例如撤回同意），业务流程在删除或下线后调用平台步骤使判定缓存失效（`FileAccess.invalidate(fileId…)`，平台 I/O 步骤），
  并在文档中提示浏览器缓存的上限。
- 与 14 §5 相同的响应头；PDF 为附件；支持单段 `Range`。

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
- 缓存：`Cache-Control: public, max-age=<cacheSeconds>`、`Vary: Accept-Language`、强 `ETag`（响应体 SHA-256）；`If-None-Match` 命中 → 304。
- 限流：按客户端地址的进程内令牌桶，`jabiz.public.rate-limit.per-minute`（默认 300）→ 429 `RATE_LIMITED` + `Retry-After`。
  客户端地址只在配置了 `server.forward-headers-strategy` 时取自转发头；桶的数量有上限（LRU，默认 100 000），防止内存被耗尽。多实例时各自限流（近似值，已知限制）。
- 公开读取不是操作：不写 `op_process`（与已认证的读取一致）。
- 总开关 `jabiz.public.enabled`（默认 **false**）：关闭时 `/api/public/**` 一律 404，但公开视图与模板的启动检查照常执行。

## 6. 安全配置

- `SecurityConfig`：`/api/public/**` 的 `GET`/`HEAD` 放行（`permitAll`），位于"`/api/**` 必须已认证"之前；认证过滤器不处理这些路径。
- 其他方法对 `/api/public/**` 仍然拒绝（405，从不进入任何写入路径）。
- 公开页面本身（静态资源）与后台一样由 SPA 提供，内容安全策略按 SPA 配置（17 §3）。

## 7. 前端类型契约

公开模板的目录不经 OpenAPI 暴露（D15 第 5 条不变）。平台提供 `PublicQueryCatalog`：由公开模板生成
`{id, params: {name: {kind, list, required}}, results: {name: kind}, list: {filters, sorts, defaultSort}, cacheSeconds}` 的 JSON。
应用的测试 `PublicQueriesSnapshotIT` 把它与仓库中的快照比较（`-Dpublic-queries.update-snapshot=true` 重写），公开前端据此生成 TS 类型
（与 OpenAPI 快照同一套做法，12 §3）。

## 8. 观测

`jabiz.public.query`（标签：模板 id、结果：ok / not_modified / rejected / error）、`jabiz.public.rate_limited`（无标签）。
不记录客户端地址（日志中也不记录）。

## 9. 测试

- core：`publicRead` 构建期校验（敏感字段、`fromContext`、默认视图、白名单字段不存在）；投影渲染；公开模板检查（引用非公开视图、
  使用白名单外字段、写物理列名读到的只有白名单列）。
- runtime 集成测试：匿名可读公开模板；**范围外的行永远不出现**（草稿、下线的数据，经模板、外层筛选、计数三种途径）；
  非公开模板 404；非 GET 405；带过期令牌仍然 200；ETag / 304；限流 429；开关关闭时 404；
  公开文件：被公开行引用 → 200，只被草稿引用 → 404，被非白名单字段引用 → 404，下线后缓存失效即 404；目录快照。
