# 14 文件存储

上传、保存、处理并提供图片、PDF、音频等文件（ROADMAP 阶段 13b）。约束性细则见【决策 D18】。
原则：**文件是数据的一部分，受同样的权限、审计与删除规则约束**；文件类型由内容判定而不是由客户端声明；
文件能否公开访问，由"引用它的数据是否公开"决定（15 §4），不另设一个需要同步的"公开"标记。

## 1. 总体结构

```
POST /api/files?policy=… ──► FileUploadService（runtime）
                               1. 权限：策略声明的上传权限
                               2. 流式写入临时区，边写边计数（超过上限即中止）、算 SHA-256
                               3. 按魔数判定类型 → 策略允许？
                               4. 图片：boundedElastic 上解码、去元数据、转正方向、生成尺寸变体
                               5. 移入 FileStore
                               6. 流程 FILE_REGISTER（internal）：插入 SysFile（一个操作，op_process）
                             失败时删除已写入的对象；清扫任务兜底（§6）
字段 `jabiz.file`（引用 SysFile）──► 写入时检查文件存在且策略相符
GET /api/files/{id}/content[/{variant}] ──► 已认证 + 策略的读取权限（后台预览）
GET /api/public/files/{id}[/{variant}]  ──► 匿名，仅当被公开数据引用（15 §4）
```

## 2. 平台实体 `SysFile`

普通实体（**不是**时态实体，理由见 D18 第 1 条），runtime `com.jabiz.runtime.file`，迁移 `db/jabiz/V<n>__sys_file.sql`，表 `sys_file`。

| 字段 | 语义类型 | 说明 |
|---|---|---|
| `fileId` | `SemanticIdentity`（UUIDv7，生成） | 主键；也是对外的文件标识 |
| `policy` | `Text(100)`（已注册的策略名；策略是 Bean 而不是字典，由上传与启动检查保证取值） | 上传时的策略，不可变 |
| `contentType` | `Text(100)` | 由内容判定的媒体类型，不可变 |
| `sizeBytes` | `Numeric(19,0)` | 原件（处理后）字节数，不可变 |
| `sha256` | `Text(64)` | 处理后原件的摘要，不可变 |
| `width` / `height` | `Numeric(9,0)` | 图片的像素尺寸（非图片为空） |
| `variants` | `Text(200)` | 已生成的变体名，逗号分隔（如 `w320,w640,w1280`） |
| `originalName` | `Text(255)` | 清洗后的原文件名（只用于下载时的文件名，从不用于存储路径）；**不写入 `op_process.input_summary`** |
| `uploadedBy` | `Text(64)` | 上传者（`RequestContext.actorId`） |
| `uploadedTime` | `Temporal(SYSTEM_RECORDED)` | 来自注入的 `Clock` |
| `version` | `Version` | 乐观锁 |

- 存储键不作为字段：由 `fileId` 与变体名推导（`<yyyy>/<mm>/<fileId>/<variant>`，年月取 `fileId`（UUIDv7）中时间戳的 UTC 年月——
  键因此只由 `fileId` 决定；原件的变体名为 `original`），库中不保存路径，避免路径注入与迁移时的不一致。上传时先生成 `fileId`，
  然后写对象、最后插入行。
- 引用文件的列（`jabiz.file` 字段）**不加外键**到 `sys_file`：历史版本可以指向已删除的文件（§6），引用一致性由写入检查（§4）
  与删除时的引用检查（`FILE_IN_USE`）保证。
- 默认数据视图 `urn:jabiz:dataset:platform:SysFile`：`processOnlyWrites()`，权限 `file.read` / `file.write`；
  只经文件服务与文件流程写入。后台可列出、查看文件元数据。
- 删除即真正删除（行与对象），见 §6、§7。

## 3. 文件策略（`FilePolicy`，core 中的声明）

```java
@Bean FilePolicy cultureImage() {
    return FilePolicy.define("culture.image")
        .allow(MediaTypes.JPEG, MediaTypes.PNG)            // 按魔数判定，拒绝其他一切
        .maxBytes(15 * MB)
        .image(i -> i.maxPixels(40_000_000)                 // 解码前按文件头检查，防解压炸弹
                     .variants(320, 640, 1280, 1920))       // 按宽度生成，不放大；图片一律重新编码，去掉全部元数据（含 EXIF/GPS）
        .permissions("culture.media.upload", "culture.media.read")   // 上传 / 读取
        .build();
}
```

- 可判定的类型（`MediaTypes`）：`JPEG`、`PNG`、`PDF`、`MP3`、`M4A`（AAC）、`OGG`（Opus/Vorbis）。判定从严：MP3 要求 ID3v2 头，
  或连续两个合法的 MPEG 音频帧头；M4A 只接受 `ftyp` 主品牌 `M4A ` / `M4B `（其他 MP4 品牌可能是视频）；OGG 要求首页含 `OpusHead`
  或 Vorbis 标识头。**永不允许** SVG、HTML 与任何脚本类型（可以在同源下执行脚本）；XML 只作为导入文件（下一条）。WebP、HEIC 不在第一版（JDK 不能解码；iOS 在 `accept="image/jpeg,image/png"` 时会自动转码为 JPEG）。
- **导入专用类型**（决策 D26，见 20 §1）：`TEXT`（无 NUL 与控制字符的文本，CSV、定宽等）、`XLSX`（按整个文件判定：ZIP 中有工作簿、
  内容类型为无宏工作簿、没有 VBA 工程；只看文件头无法判定）、`XML`（XML 声明或首个元素；HTML、SVG、含 `<script` 的一律拒绝）。
  它们只能出现在只允许这三种类型的策略中（`FilePolicy` 构建时拒绝混用），原样保存，下载一律 `attachment` 加 `sandbox`，公开接口永远不提供。
- 客户端声明的 `Content-Type` 与扩展名只作参考：与判定结果不符时以判定结果为准，判定结果不在允许列表中 → 400 `FILE_TYPE_NOT_ALLOWED`。
- 超过 `maxBytes` → 413 `FILE_TOO_LARGE`（写入过程中即中止，不先读完）；图片像素超过 `maxPixels` 或无法解码 → 400 `FILE_INVALID`。
- 一个策略最多 12 个变体（变体名合存在 `sys_file.variants` 中）；同时解码的图片数有上限 `jabiz.files.image-concurrency`
  （默认处理器数的一半，至少 1）：每张图片处理时持有数份全尺寸位图，40 MP 的照片约数百 MB。
- 图片处理（`ImageProcessor`，runtime，JDK ImageIO，在 `boundedElastic` 上执行）：按 EXIF 方向转正后**重新编码**（这就去掉了全部元数据），
  JPEG 质量 0.85，PNG 仍为 PNG（保留透明）；生成宽度变体（原图更窄时不生成）。原件也保存处理后的版本，**不保留**带元数据的原始字节。
  JDK 不解析 EXIF：平台自带只读方向标签的小解析器（JPEG APP1 → TIFF IFD0 的 `0x0112`），不引入第三方库；PNG 不处理方向。
  JDK 不能解码的图片（如 CMYK JPEG）按无法解码处理（400 `FILE_INVALID`）。
- PDF、音频不做内容处理：PDF 以 `Content-Disposition: attachment` 与 `Content-Security-Policy: sandbox` 提供，不在同源下内联渲染。
- 策略名在应用内唯一；每个策略必须声明上传与读取权限（`FilePolicy` 构建时即拒绝缺少权限的策略）。
- 病毒扫描不在本阶段：允许的类型都经过解码或只以附件形式提供；以后可在 §1 第 4 步加入扫描 SPI。

## 4. 语义类型 `jabiz.file`

扩展类型（`Custom`，core `com.jabiz.file.FileKindSupport`，经 ServiceLoader 注册——转换只需解析 UUID，不依赖运行时），字段声明：

```java
f.kind(FileKind.of("culture.image"))        // 参数 {policy: "culture.image"}
```

- 规范 Java 类型 `UUID`（即 `fileId`）；物理列 `uuid`；允许的运算符 `EQ` `NE` `IN` `IS_NULL` `IS_NOT_NULL`。
- 写入检查（运行时 `FileFieldCheck`，平台内部的 `FieldWriteCheck`，与引用检查在同一位置运行，覆盖数据视图 API、通用实体流程与 `ChangeSet`、
  普通与时态实体）：文件存在 → 否则 400 `FILE_NOT_FOUND`；
  文件的 `policy` 等于字段声明的策略 → 否则 400 `FILE_POLICY_MISMATCH`。只检查本次**改变**的值。
- 导出（02 §8）：`{type: custom, kindId: jabiz.file, policy, accept: [...], maxBytes, image: true|false, variants: [...]}`，
  后台据此渲染上传控件（12 §5 增加一行：`jabiz.file` → 上传 + 预览）。`FileKindSupport` 没有状态、拿不到 Spring 中的策略，
  自身只导出 `policy`；其余几项由 runtime 在元数据接口（`/api/meta/entities/{name}`）中按已注册的策略补全。
- 启动自检（`FILE` 类别，一次报告全部）：字段引用的策略存在；策略名不重复；策略的 `maxBytes` 不超过 `jabiz.files.max-request-bytes`；
  注册了任何策略时，存储根目录已配置、存在（或可创建）且可写。
- 一个字段引用一个文件；多个文件用子实体（例如一组照片，每张一行，带替代文本与排序）。

## 5. 接口

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/files?policy={name}` | 策略的上传权限 | `multipart/form-data`，恰好一个名为 `file` 的部分；201 `{fileId, contentType, sizeBytes, width, height, variants}` |
| GET | `/api/files/{fileId}` | `file.read` 或策略的读取权限 | 元数据 |
| GET | `/api/files/{fileId}/content[/{variant}]` | 同上 | 内容；支持单段 `Range`（音频拖动）；`Cache-Control: private, no-store` |
| GET | `/api/public/files/{fileId}[/{variant}]` | 匿名 | 见 15 §4 |

- 请求体大小由 `jabiz.files.max-request-bytes`（默认 100 MB，且不小于各策略上限）限制：声明的 `Content-Length` 超过它立即 413，
  否则边读边计数；只接受一个名为 `file` 的部分（多余的部分或缺少它 → 400 `FILE_INVALID`）；文件名按
  NFC 规范化（macOS 发送分解形式）后按 `[\p{L}\p{M}\p{N} ._()-]{1,255}` 清洗，其余字符替换为 `_`。
- 响应总是带 `X-Content-Type-Options: nosniff` 与判定出的 `Content-Type`；变体不存在时 404。
- 上传按操作人限流（`jabiz.files.upload-rate-per-minute`，默认 30，进程内令牌桶）→ 429 `RATE_LIMITED`，带 `Retry-After`。
- 后台前端的图片预览：访问令牌只在内存中（D15），`<img>` 不能带 `Authorization`，因此预览以 `fetch` 取得 blob 再用 `URL.createObjectURL` 显示。

## 6. 存储、清扫与删除

- `FileStore` SPI（runtime 内部，返回 `Mono`/`Flux`，**不对业务开放**）：`write(key, Flux<DataBuffer>)`、`size(key)`、
  `read(key, offset, length)`、`deleteAll(文件的前缀)`、`list()`（流式、深度优先，不在内存中收集全部键）。
  实现：`LocalFileStore`（根目录 `jabiz.files.local.root`，非阻塞 `AsynchronousFileChannel`；目录创建与移动在 `boundedElastic` 上；
  键只能由平台推导，仍校验其字符并拒绝逃出根目录）。根目录没有默认值：注册了策略而未配置时启动失败（默认拒绝）；
  compose 挂载专用卷，测试每个类使用独立的临时目录。本地目录只适合单实例（多实例需共享卷，或以后接入 S3）。
  S3 兼容存储以后按同一接口加入，不在本阶段。
- **一致性**：先写对象、后插入行；插入失败时删除已写的对象。删除时先删行（事务提交）、后删对象（`AFTER_COMMIT`）。
  写入与删除的并发由行锁排序（没有外键）：写入检查以 `FOR KEY SHARE` 读 `sys_file`；`FILE_DELETE` 先 `FOR UPDATE` 锁行再查引用，
  清扫对候选行 `FOR UPDATE SKIP LOCKED` 后再查一次引用。因此正在引用某文件的写入与该文件的删除，总有一方看到另一方的结果。
  清扫按上传时间分页，直到凑满一批孤儿或没有更旧的文件，仍被引用的旧文件不会挡住较新的孤儿。
  月、年目录即使变空也保留（删除它们会与正在为新文件建目录的上传竞争）。
  两种顺序在崩溃时都只会留下"有对象无行"的孤儿，由清扫任务处理，不会出现"有行无对象"（除非存储被外部改动）。
- **清扫任务** `FILE_SWEEP`（`JobDefinition`，默认每天，D14）：
  1. 删除上传超过 `jabiz.files.orphan-after`（默认 24 小时）且**未被任何当前数据引用**的 `SysFile` 行（引用 = 任一实体中 `jabiz.file` 字段的当前值；
     时态实体只看当前与预定版本，历史版本中的引用不阻止删除——历史里显示"文件已删除"）；
  2. 列出存储中没有对应行、且早于清扫开始时间一小时的对象并删除。对象的"年龄"取自键中 `fileId`（UUIDv7）的时间戳——它来自注入的
     `Clock`，与业务时间一致；不用文件系统的修改时间（真实时钟，场景回放中对不上）。
  3. 删除崩溃的上传留下的临时内容（上传区的文件与工作目录、对象旁的部分文件），超过 6 小时的。这些是基础设施的残留而不是业务数据，
     只有文件系统的时间，因此按文件系统时间判断（与集群锁的时间同理）。
  任务名 `FILE_SWEEP`，它只调用流程 `FILE_PURGE_ORPHANS`（有操作记录，输出含删除的 `fileId`；在保留期内或受法律保全的文件跳过，列在 `keptFileIds`，21 §3.2）；各项删除数量记入日志，
  观测 `jabiz.file.sweep` 只带结果标签（D16 第 3 条）。
  引用的判定只看数据库中的当前值（时态实体：当前与预定版本；普通实体：所有行，含逻辑删除的行），不经数据视图范围。
- **手动删除** `FILE_DELETE`（`file.delete`）：文件仍被当前数据引用时 422 `FILE_IN_USE`（参数 `entity`、`field`）。
  业务的"删除个人数据"流程（例如 culture 的抹除）先删除（或清空）引用它的行，**执行 `SaveChanges.now`**，再以
  `CallProcess.of("FILE_DELETE", …)` 在同一流程中删除文件——子流程的引用检查读数据库，看不到父流程尚未保存的变更。
  对象在根流程提交后删除（子流程的 `AFTER_COMMIT` 推迟到根流程提交后，06 §4）。示范见 app 的 `SUPPLIER_CONTRACT_REMOVE`。
- `op_process.input_summary` 只记录 `fileId`、策略与大小，不记录文件名与内容（文件名可能含个人信息）。

## 7. 与只追加原则的关系

`sys_file` 是**可以删除**的普通表：文件常含个人信息（照片、声音），删除权要求真正删除内容，而时态表与对象存储里的"只追加"
无法做到（04 §10 的受控清除只处理字段，不处理对象存储）。审计仍然完整：每次上传、删除都是一次流程操作，`op_process` 记录谁在何时
对哪个 `fileId` 做了什么；被删除的只是内容本身。

## 8. 观测

`PlatformObservations`：`jabiz.file.upload`（标签：策略、结果）、`jabiz.file.serve`（标签：公开 / 后台、结果）、`jabiz.file.sweep`（标签：结果）。
标签不含 `fileId`、文件名、操作人（D16 第 3 条）。

## 9. 测试

- core 单元测试：`FilePolicy` 构建期校验；魔数判定（每种类型、伪装的扩展名、SVG/HTML 被拒）；文件名清洗。
- runtime 集成测试（真实 PostgreSQL + 临时目录）：上传各类型；超限中止（未读完请求体即返回 413）；解压炸弹被拒；
  **重新编码后不含 EXIF/GPS**（用带 GPS 的样例图断言）；方向转正；变体尺寸；`jabiz.file` 字段写入检查（不存在 / 策略不符）；
  权限（无上传权限 403、无读取权限 403）；`Range`；删除时被引用 → 422；清扫删除孤儿行与孤儿对象、不删除被引用的；
  插入失败时对象被删除；`input_summary` 中没有文件名；BlockHound（图片处理不在事件循环上）。
- 前端：上传控件（策略导出 → `accept`、大小提示）、blob 预览、失败提示（Vitest）；Playwright：在示范实体上上传并保存。

## 10. 生成的文件【决策 D31，阶段 14k】

银行付款文件（NACHA）、正向支付文件、税务申报文件这类**服务器为业务流程生成、交给外部的文件**，与上传的文件（§1–§9）不同：
内容由平台生成而不是由人上传，不能删除（是付款与申报的证据），格式是银行或机关要求的文本。它们存放在只追加表 `sys_generated_file` 中，
与单据（22，`sys_document_run`）一样原样保存字节，但不是 PDF、没有版式。

- **保存**：子流程 `FILE_ARCHIVE`（内部流程，不在流程目录中；权限 `file.generated.archive`，不授予任何角色——只由业务流程以 `CallProcess.of("FILE_ARCHIVE", 1, …)` 调用，
  业务流程自己的权限即是生成的权限）。输入：文件名（字母、数字、`. - _` 与空格，≤ 200 个字符）、媒体类型（`text/plain`、`text/csv`、`application/xml`、
  `application/json`，其他 422 `GENERATED_FILE_NOT_ALLOWED`）、内容（1 字节至 `jabiz.files.generated.max-bytes`，缺省 10 MiB，否则 422 `GENERATED_FILE_TOO_LARGE`）、
  读取所需的权限（1–20 个权限码，不能是 `*`）与可选的对象（实体、主键，各 ≤ 100 个字符）。子流程的输入不经 Bean 校验（`CallProcess`），所以这些都由步骤自己检查，违者 422 `GENERATED_FILE_NOT_ALLOWED`。保存内容的 SHA-256；输出 `{fileId, sha256, size}`。
  内容（组件名 `fileContent`——`@Sensitive` 按名字在整个 JSON 中遮蔽，不用 `content` 这类通用名字）是 `@Sensitive`：`op_process.input_summary` 与日志中不出现内容（可能含账号）；文件名照常记录。
- **读取**：`GET /api/generated-files/{fileId}` 需要 `file.generated.read` **与**文件保存时声明的全部权限；缺任何一项即 404（与单据相同，不透露存在）。
  没有权限保存下来的文件谁也读不到（dev 中也一样）。返回前以存档的哈希核对内容（不符则 500，不返回），并在 `sys_reveal_record` 写一条 `kind = FILE` 的记录（文件名不进记录，只记对象与 `fileId`）——
  这类文件含明文账号，下载就是一次"显示明文"（10 §13.1）。响应为附件，带 `X-Jabiz-Sha256` 与 `nosniff`。
- **只追加**：`jabiz_protect_append_only('sys_generated_file')`；由 21 §2 的封存自动覆盖。保留期照常以 `RetentionPolicy` 声明。
- **示范**：app 的 `ORDER_PICK_LIST_ARCHIVE` 把订单行生成 CSV 拣货单并保存（文本单元格加引号、以 `= + - @` 开头的加 `'`，防止表格软件当作公式）。
- **测试**：`GeneratedFileIT`（原样下载与哈希、下载记录、`input_summary` 中没有内容、缺权限 404、类型 / 文件名 / 大小 / 权限 / 对象被拒（经业务流程调用也一样）、存档被改不返回、只追加、业务流程经子流程保存）。
