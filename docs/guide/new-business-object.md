# 教程：新增一个业务对象

目标：从建表到后台可用（列表、表单、历史、权限、测试），只写声明。示例是仓库中真实存在的 **`Supplier`（供应商）**：
本教程中的每段代码都来自这些文件，照着做得到的就是它们。

| 步骤 | 文件 | 参考用时* |
|---|---|---|
| 1 表结构 | `backend/app/src/main/resources/db/migration/V9__supplier.sql` | 20 分钟 |
| 2 实体声明 | `backend/app/src/main/java/com/jabiz/app/commerce/SupplierDefinitions.java` | 30 分钟 |
| 3 数据视图与权限 | 同上 | 10 分钟 |
| 4 文案（三种语言） | `backend/app/src/main/resources/messages_{zh,ja,en}.properties` | 15 分钟 |
| 5 启动与静态校验 | — | 10 分钟 |
| 6 集成测试 | `backend/app/src/test/java/com/jabiz/app/it/commerce/SupplierIT.java` | 45 分钟 |
| 7 授权给角色、在页面上使用 | 通过后台页面 | 10 分钟 |
| 8（可选）业务流程、SQL 模板、场景回放 | `CommerceProcesses`、`queries/commerce/*.sql`、`scenarios/commerce/*.yml` | 各 1–3 小时 |

\* 参考用时是给第一次接触本平台、熟悉 Java 与 SQL 的开发者的估计，不是实测；实测记录见 `docs/demo/dev-time-log.md`。

开始前：能按 `docs/guide/quickstart.md` 第 2 节在本地启动，并读过 `CLAUDE.md` 第 3、4 节（分层与编码约定）。

---

## 1. 表结构（Flyway 迁移）

业务表的迁移放在 `backend/app/src/main/resources/db/migration/`，版本号接着已有的编号（`V9__…`）。
业务对象默认做成**时态实体**（修改即插入新版本，自动有历史、按时间点查询、撤销，04）：

```sql
CREATE TABLE supplier_version (
    row_id            bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    supplier_id       uuid         NOT NULL REFERENCES entity_registry (entity_id),
    version_no        integer      NOT NULL,
    effect_start_time timestamptz  NOT NULL,
    created_time      timestamptz  NOT NULL,
    process_seq_id    bigint       NOT NULL REFERENCES op_process (process_seq_id),
    is_deleted        boolean      NOT NULL DEFAULT false,
    supplier_code     varchar(10)  NOT NULL,     -- 以下是业务列
    supplier_name     varchar(100) NOT NULL,
    country_code      varchar(2)   NOT NULL,
    lead_time_days    numeric(3,0) NOT NULL,
    active            boolean      NOT NULL,
    CONSTRAINT supplier_version_uk UNIQUE (supplier_id, version_no)
);
CREATE INDEX supplier_version_current_idx ON supplier_version (supplier_id, effect_start_time DESC, version_no DESC);
CREATE INDEX supplier_version_process_idx ON supplier_version (process_seq_id);
CREATE INDEX supplier_version_code_idx ON supplier_version (supplier_code);
SELECT jabiz_protect_append_only('supplier_version');
```

- 前七列与两个索引、唯一约束、最后一行的触发器是时态表的**固定写法**，只替换表名和主键列名（CLAUDE.md §4）。忘了任何一项，启动自检会指出。
- 业务唯一性（供应商代码不重复）**不**建唯一索引（历史版本会冲突），在第 2 步用 `eb.unique` 声明（D6）；这里只建普通索引加速查找。
- 金额列用 `numeric(19,s)`；时间用 `timestamptz`；引用其他时态实体用 `uuid REFERENCES entity_registry (entity_id)`。
- **以后的迁移只能给时态表加列**（D58，21 §2.1）：它的行会被完整性封存，改名、删列、改类型或删表都会使此前封存的行在校验时报告为被改。
  应用在自己的测试中用夹具检查自己的迁移（平台的示范见 `app` 的 `SealedColumnsHistoryIT`）：
  `assertThat(SealedColumnsHistory.application(PostgresTestDatabase.get(), "classpath:db/migration").check().lost()).isEmpty();`

## 2. 实体声明

在 `app` 的包里写一个 `@Configuration`（或像 `CommerceEntities` 那样把常量与配置分开）：

```java
public static final EntityDefinition SUPPLIER_ENTITY = EntityDefinition.define("Supplier", eb -> {
    eb.physicalTable("supplier_version");
    eb.primaryKey("supplierId");
    eb.field("supplierId", f -> f.physicalColumn("supplier_id").immutable(true).required(true).generated(true)
        .asSemanticIdentity("urn:jabiz:entity:commerce:supplier"));
    eb.field("supplierCode", f -> f.physicalColumn("supplier_code").immutable(true).required(true).asText(10)
        .apply(Rules.pattern("SUPPLIER_CODE_FORMAT", "[A-Z0-9]{2,10}")));
    eb.field("supplierName", f -> f.physicalColumn("supplier_name").required(true).asText(100)
        .apply(Rules.notBlank("SUPPLIER_NAME_BLANK")));
    eb.field("countryCode", f -> f.physicalColumn("country_code").required(true)
        .asCode(CarrierEntityDefinitions.COUNTRY_DICTIONARY));
    eb.field("leadTimeDays", f -> f.physicalColumn("lead_time_days").required(true).asNumeric(3, 0)
        .apply(Rules.range("SUPPLIER_LEAD_TIME_RANGE", BigDecimal.ONE, new BigDecimal("365"))));
    eb.field("active", f -> f.physicalColumn("active").required(true).asBool());
    eb.unique("uk_supplier_code", "supplierCode");
    eb.listView("default", lv -> lv
        .columns("supplierCode", "supplierName", "countryCode", "leadTimeDays", "active")
        .filters("supplierCode", "supplierName", "countryCode", "leadTimeDays", "active")
        .sorts("supplierCode", "supplierName", "leadTimeDays")
        .defaultSort("supplierCode", true));
    eb.temporal(t -> t.allowScheduled(true));
});

@Bean
EntityDefinition supplierEntityDefinition() {
    return SUPPLIER_ENTITY;
}
```

要点：

- **逻辑名与物理名分开**：API、查询、规则、模板只用逻辑名（`supplierCode`），物理列只出现在这里（02 §2）。
- **语义类型**决定转换、校验、可用的查询运算符和前端控件（02 §1）：`asText` `asNumeric` `asMonetary(币种, 小数位)` `asTemporal(角色)`
  `asCode(字典)` `asBool` `asReference(实体)` `asSemanticIdentity`。金额只用 `asMonetary`（`BigDecimal`）。
- **规则**用 `Rules` 工厂（`range` `scale` `length` `pattern` `notFuture` `notBlank`）：同一份参数同时生成前端校验与服务端判断，两端报同一个错误码（D15）。
  依赖数据库或其他实体的判断不写成字段规则，写在流程里（第 8 步）或写成实体级校验 `eb.check(...)`（02 §4.1）。
- **字典**：`asCode(urn)` 的取值来自字典注册表；静态字典用 `StaticDictionary.define(...)` 声明为 Bean（见 `DictionariesConfig`），
  需要运行时维护的用平台的数据库字典（`SysDictItem`，02 §5）。
- **状态机**（本例没有）：`eb.stateTransitions("status", st -> st.from("PLACED").to("SHIPPED", "CANCELLED"))`，参见 `CommerceEntities.ORDER_ENTITY`。
  状态机会回到起点（如退回草稿）时用 `st.initial("DRAFT")` 声明初始状态（02 §4）。
- **列表视图**：只有列在 `filters` / `sorts` 中的字段可以筛选、排序（白名单）。
- `eb.temporal(t -> t.allowScheduled(true))` 允许预定生效（例如"下月起交货周期改为 10 天"）。
- 需要其他系统感知变化时加 `eb.publishChanges()`（实体变更事件，11 §2）。

## 3. 数据视图与权限

所有读写都经过数据视图；每个实体恰好一个默认视图，并且**必须声明读、写权限码**（未声明在非 dev 环境下启动失败）：

```java
@Bean
DatasetDefinition supplierDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
    return DatasetDefinition.define("urn:jabiz:dataset:default:Supplier", d -> d
        .targetEntityType("Supplier")
        .asDefault()
        .permissions("commerce.supplier.read", "commerce.supplier.write")
        .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
}
```

- 数据只能由业务流程改变（例如库存）时加 `.policy(p -> p.processOnlyWrites())`（03 §2.6）。
- 按操作人或租户限定范围：`.scope(s -> s.fromContext("ownerId", RequestContext::actorId))`（03 §2.2）；取不到范围值即拒绝。

## 4. 文案（zh / ja / en）

在三个 `messages_*.properties` 中加入规则的错误文案（键 = 规则代码，占位符为规则参数与 `{field}`）以及实体、字段的显示名：

```properties
SUPPLIER_CODE_FORMAT=供应商代码必须是 2 到 10 位大写字母或数字。
SUPPLIER_LEAD_TIME_RANGE=交货周期必须在 {min} 到 {max} 天之间。
entity.Supplier=供应商
entity.Supplier.supplierCode=代码
entity.Supplier.leadTimeDays=交货周期（天）
```

缺少任一语言的规则文案，启动自检会列出（02 §3.1）；缺少显示名时页面退回显示逻辑名。

## 5. 启动与静态校验

```bash
cd backend
./gradlew :app:platformCheck        # 迁移一个新 schema，运行全部启动自检，逐行输出问题
```

常见输出与处理：

| 输出（节选） | 原因 |
|---|---|
| `METAMODEL \| Supplier.leadTimeDays \| column lead_time_days not found` | 迁移中列名与 `physicalColumn` 不一致 |
| `METAMODEL \| Supplier \| … trigger …` | 忘了 `SELECT jabiz_protect_append_only(...)` |
| `MESSAGES \| SUPPLIER_CODE_FORMAT \| missing in ja` | 某种语言缺文案 |
| `DATASET \| urn:…:Supplier \| no permissions declared` | 视图没有 `permissions(...)` |

全部问题一次性报告；有错误时退出码非 0（CI 中 `./gradlew check` 包含它）。

## 6. 集成测试

没有测试的功能不算完成（CLAUDE.md §5）。至少覆盖：经 API 写入与读取、规则报出的错误码、历史、权限、**时态表上没有 UPDATE / DELETE**。
`SupplierIT` 就是这样一个完整的例子（约 100 行）：

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "it.sql-log.enabled=true")
class SupplierIT extends PostgresIntegrationTest {   // 每个测试类一个独立 schema
    ...
    post(COMMIT, writer(), Map.of("changes", List.of(Map.of("action", "INSERT", "attributes", Map.of(
        "supplierCode", code, "supplierName", "Acme", "countryCode", "JP", "leadTimeDays", 7, "active", true)))))
        .expectStatus().isOk();
    ...
    assertThat(SqlStatementLog.STATEMENTS ... .filter(s -> s.contains("SUPPLIER_VERSION")))
        .allSatisfy(s -> assertThat(s).startsWith("INSERT"));
}
```

```bash
./gradlew :app:test --tests '*SupplierIT'
```

时态表不能 `DELETE`，测试之间用各自唯一的数据（随机代码）隔离。令牌用 `TestTokens.bearer(jwtService, 操作人, 权限…)` 签发。

如果改变了字段元数据中带规则的字段，并希望把它加入前后端共享校验用例，见 `spec/validation-cases.json` 与 12 §5.1。

## 7. 授权并在页面上使用

1. 启动应用（`bootRun` 或 compose），以管理员登录。
2. 在"数据视图目录"中打开 `SecRole`，新建角色（例如 `BUYER`），在 `SecRolePermission` 中授予 `commerce.supplier.read`、`commerce.supplier.write`；
   在 `SecUserRole` 中把角色分配给用户（可以预定生效时间）。管理员角色 `ADMIN` 有 `*`，无需授权。
3. 打开 `Supplier`：列表（筛选、排序、分页）、新建 / 编辑表单（前端校验与服务端错误码一致）、历史时间线（回看任意时间点、撤销）都已可用。
   **不需要写前端代码**；想出现在菜单里，在 `SecMenu` 中加一项（`path` 为 `/data/urn%3Ajabiz%3Adataset%3Adefault%3ASupplier`（数据视图 id 经 URL 编码），并指定查看所需的权限）。

## 8. （可选）业务流程、SQL 模板、场景回放

当一次业务操作要读多个实体、做计算、改多条数据时，写**流程**，不要让前端调用多次通用写入：

```java
public static final ProcessDefinition<ReceiveInput, ReceiveOutput, ProcessContext> RECEIVE_PROCESS =
    ProcessDefinition.define("STOCK_RECEIVE", 1, ReceiveInput.class, ReceiveOutput.class, ProcessContext.class, pb -> pb
        .permissions("commerce.stock.receive")                       // 必须声明
        .contextFactory(CommerceProcesses::withInput)
        .outputMapper(ctx -> ctx.get(OUTPUT, ReceiveOutput.class))
        .step("Load the warehouse", QueryEntities.of(WAREHOUSE_DATASET, ctx -> byCode(...), WAREHOUSES))  // 平台 I/O
        .step("Load the stock", QueryEntities.of(STOCK_LEVEL_DATASET, CommerceProcesses::stockQuery, STOCK))
        .compute("Receive the goods", (metadata, ctx) -> receive(ctx)));   // 同步计算：检查规则、登记变更
```

- 计算步骤是普通的同步 Java：`ctx.reject(new Violation(...))` 累积违规（结束时一次性 422），`ctx.changes().insert/update(...)` 登记变更，
  平台在流程结束时在同一个事务里提交。**业务代码不写 `Mono`/`Flux`，不直接访问数据库**（ArchUnit 检查）。
- 预定生效：`ctx.changes().effectiveAt(时间).update(...)`（见 `PRODUCT_REPRICE`）；调用其他流程：`CallProcess.of(...)`（同一事务，见 `ORDER_SHIP` 过账）。
- 输入是带 Bean Validation 注解的 record，前端据此生成表单；执行接口 `POST /api/processes/{name}/latest`，支持 `Idempotency-Key`。
- 纯计算可以不启动 Spring 直接单元测试（`CommerceProcessesTest`），平台部分用集成测试（`CommerceIT`）。

复杂查询写 **SQL 模板**（`queries/**/*.sql`，05）：表和列只写占位符 `{{Supplier}}`、`{{Supplier.supplierCode}}`，平台负责数据视图范围、
时态"当前版本"、外层分页筛选与预编译校验；列表参数写 `= ANY(:name)`。例子：`queries/commerce/stock_availability.sql`。

需要**图片或文档**时（14）：声明一个策略（`FilePolicy` Bean：允许的类型、大小上限、图片变体、上传与读取权限），字段写
`f.kind(FileKind.of("commerce.document"))`，迁移中加一列 `uuid`（不加外键，建议建部分索引 `WHERE col IS NOT NULL`）。
后台表单自动得到上传控件与预览；删除文件的业务流程先清空引用、`SaveChanges.now`，再 `CallProcess.of("FILE_DELETE", …)`
（例子：`CommerceFiles` 的 `SUPPLIER_CONTRACT_REMOVE`）。

要**调用其他系统**时（06 §2.3，D38）：声明一个外部服务（`ExternalService` Bean：基础地址、主机白名单、密钥的环境变量、超时），
在阻塞步骤中注入 `ExternalServices`，经 `services.http("服务").send(ExternalHttp.Request.post("/v1/…").json(…))` 调用，
密钥用 `services.secret(...)`，有副作用的调用放提交后步骤或带 `services.idempotencyKey(...)`。不要自己用 `HttpClient` 等网络客户端（ArchUnit 检查）；
测试与开发以 `jabiz.external-services.<名>.base-url | allowed-hosts` 指向本地桩服务。例子：`ModerateText` 与 `PRODUCT_RENAME`、测试 `ExternalServiceIT`。

调用**耗时数十秒**（生成文案、图片）时改用**异步外部作业**（11 §4a），不要放在阻塞步骤中占着事务：
1. 输入与结果写成 record（输入中不放密钥：作业会保存它），处理器实现 `ExternalJobHandler<输入, 结果>`（`@Component`），经 `ctx.http(服务)` 调用，
   带 `ctx.idempotencyKey()`，用 `ctx.recordUsage(…)` 或 `ExternalJobResult.of(值).usage(…)` 记用量；不会因再试而改变的失败抛 `ExternalJobException.permanent(码, 消息)`。
2. 声明 `ExternalJobType.define("应用.作业", 输入.class, 结果.class, t -> t.handler(…).services("服务").timeout(…).retry(次数, 初始, 最长)
   .maxConcurrency(…).onCompleted("完成流程").onFailed("失败流程"))` Bean；限制同一请求人未结束的作业数（`maxOpenPerRequester`，缺省 10）、
   同一对象只有一个未结束的作业（`oneOpenPerReference()`）。作业的超时须长于所用服务的请求超时。
3. 请求流程中 `.step("…", StartExternalJob.of("应用.作业", ctx -> 输入).reference(ctx -> 对象编号).outputKey("jobId"))`，把作业号返回给调用方。
4. 完成流程以 `ExternalJobCompletion` 为输入、失败流程以 `ExternalJobFailure` 为输入，都声明 `internal()`，**第一个步骤拒绝系统以外的操作人**
   （D41 决定之前 `internal()` 只是不进目录），再写结果、以 `CreateTask` / `SendMail` 告诉 `requestedBy`。
5. 测试：集成测试调用 `ExternalJobExecutor.runPending()` 与 `OutboxDeliverer.deliverPending()`；场景用 `completeExternalJob` 写入结果，不依赖外部服务。
例子：`ProductCopy`、`ProductCopyHandler`、测试 `ExternalJobIT`、场景 `scenarios/commerce/product_copy.yml`。

**命名锁**（06 §4.1）：两个流程各自读了对方要改的状态再提交时（过账与关账、支付与过期），以 `HoldLock.exclusive|shared(ctx -> 名称)` 取同名的锁，
**先取锁、再读取**；名称由一个辅助方法生成（例子：`OrderExpiry.lockName`）。锁内的等待最长 `jabiz.process.lock-timeout`，超时 409。
**热门的锁**（许多人同时操作同一个对象，压测或运行中看到等这把锁的请求占满连接、无关的读取变慢）再在流程上声明**准入**（06 §4.2，D45）：

```java
.admission(OrderExpiry.LOCK_FAMILY, input -> OrderExpiry.orderKey(input.orderId()))   // 与锁名同一个辅助方法
.step("Lock the order", HoldLock.<ProcessContext>exclusive(ctx -> OrderExpiry.lockName(ctx.get(ORDER_ID))))
```

- 族名是常量，键只从输入算出（事务之前，不能读数据）；算不出时返回 null，即不排队（照旧可用，只是等锁时占连接）。只为流程**最外层**的锁声明；锁内读了数据才知道的锁不声明。
- 子流程从不排队；正确性仍只靠 `HoldLock`：键算错或多实例只影响排队的效果，不影响结果。
- 过载时客户端收到 503 `PROCESS_BUSY`（带 `Retry-After`），按它稍后再试；不要自动连续重试。
- 队列的大小与等待按族配置：`jabiz.process.admission.families."[族名]".{concurrency, max-queue, max-wait}`；按用户或地址刷请求的流程另配 `jabiz.process.rate-limits."[流程]"`（10 §15.10）。
- 例子：`ORDER_CANCEL`、`ORDER_SHIP`（声明准入），`ORDER_EXPIRE`（任务项，不声明；其子流程 `ORDER_CANCEL` 不排队），`ORDER_RECORD_PAYMENT`（锁名在读了支付之后才知道，不声明）；
  测试 `CommerceAdmissionIT`；压测的热门订单模式 `LOAD_HOT_ORDER=yes ./gradlew :app:loadTest`。

跨天、跨月的业务写成**场景回放**（07 §3）：`src/test/resources/scenarios/**/*.yml`，可控时钟 + 快照对比。
例子：`scenarios/commerce/order_lifecycle.yml`（预定调价在月初生效、取消释放库存、发货过账）。

## 9. （可选）内容编辑：多语言文本、显示字段、行操作

以内容为主的对象（说明、文章、资质）还可以声明（16）。例子：`commerce/SupplierCertifications.java`。

```java
eb.field("title", f -> f.physicalColumn("title").required(true).apply(I18nText.of(200).required("en")));  // jsonb 列
eb.field("body",  f -> f.physicalColumn("body").apply(I18nText.markdown(20_000)));
eb.field("status", f -> f.physicalColumn("status").required(true).processOnly().asCode(...));  // 只由流程改变
eb.display("title");                                     // 被引用时显示标题；供应商用 eb.display("supplierName")

ProcessDefinition.define("CERTIFICATION_SUBMIT", ...)
    .actsOn("SupplierCertification", "certificationId", a -> a.whenField("status", "DRAFT", "REJECTED"))
```

- 后台随之得到：每种语言一个标签页（Markdown 带预览）、按显示字段搜索的引用下拉、列表中引用列显示名称、行上的"提交"等操作、
  父实体详情中的子实体列表（引用字段须在子实体列表视图的 `filters` 中）。不写前端代码。
- `processOnly` 字段经数据视图与通用实体流程写入 → 400 `PROCESS_ONLY_FIELD`；插入时状态字段自动取初始状态；必填的 `processOnly` 字段
  必须是状态字段或视图范围字段（启动检查）。`when` 只是显示提示，状态机照常裁决。

## 检查清单

- [ ] 迁移：时态表固定列、`UNIQUE(主键, version_no)`、两个索引、`jabiz_protect_append_only`；改已有的时态表只加列（`SealedColumnsHistory`）
- [ ] 实体：逻辑名 / 物理列、语义类型、`Rules` 规则、唯一性、列表视图、`temporal`
- [ ] 数据视图：唯一默认视图，读写权限码
- [ ] 文案：规则代码与显示名，zh / ja / en
- [ ] `./gradlew :app:platformCheck` 无错误
- [ ] 集成测试：写入、读取、规则错误码、历史、权限、只插入
- [ ] 角色授权；需要时加菜单
- [ ] （如有流程）权限声明、违规累积、单元测试 + 集成测试；（如有跨时间的规则）场景回放与快照
- [ ] （如有文件字段）策略及其权限、`uuid` 列与索引、文案；上传与写入检查的集成测试
- [ ] （如有内容）多语言字段的列为 `jsonb`；被引用的实体声明 `display`；流程维护的字段 `processOnly()`；实体上的流程 `actsOn`
