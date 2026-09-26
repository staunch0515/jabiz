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
| `policy` | `Code`（取值为已注册的策略名） | 上传时的策略，不可变 |
| `contentType` | `Text(100)` | 由内容判定的媒体类型，不可变 |
| `sizeBytes` | `Numeric(19,0)` | 原件（处理后）字节数，不可变 |
| `sha256` | `Text(64)` | 处理后原件的摘要，不可变 |
| `width` / `height` | `Numeric(9,0)` | 图片的像素尺寸（非图片为空） |
| `variants` | `Text(200)` | 已生成的变体名，逗号分隔（如 `w320,w640,w1280`） |
| `originalName` | `Text(255)` | 清洗后的原文件名（只用于下载时的文件名，从不用于存储路径）；**不写入 `op_process.input_summary`** |
| `uploadedBy` | `Text(64)` | 上传者（`RequestContext.actorId`） |
| `uploadedTime` | `Temporal(SYSTEM_RECORDED)` | 来自注入的 `Clock` |
| `version` | `Version` | 乐观锁 |

- 存储键不作为字段：由 `fileId` 与变体名推导（`<yyyy>/<mm>/<fileId>/<variant>`），库中不保存路径，避免路径注入与迁移时的不一致。
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
                     .variants(320, 640, 1280, 1920)        // 按宽度生成，不放大
                     .stripMetadata())                      // 去掉 EXIF（含 GPS）、XMP、ICC 以外的全部元数据
        .permissions("culture.media.upload", "culture.media.read")   // 上传 / 读取
        .build();
}
```

- 可判定的类型（`MediaTypes`）：`JPEG`、`PNG`、`PDF`、`MP3`、`M4A`（AAC）、`OGG`（Opus/Vorbis）。**永不允许** SVG、HTML、XML 与任何脚本类型
  （可以在同源下执行脚本）。WebP、HEIC 不在第一版（JDK 不能解码；iOS 在 `accept="image/jpeg,image/png"` 时会自动转码为 JPEG）。
- 客户端声明的 `Content-Type` 与扩展名只作参考：与判定结果不符时以判定结果为准，判定结果不在允许列表中 → 400 `FILE_TYPE_NOT_ALLOWED`。
- 超过 `maxBytes` → 413 `FILE_TOO_LARGE`（写入过程中即中止，不先读完）；图片像素超过 `maxPixels` 或无法解码 → 400 `FILE_INVALID`。
- 图片处理（`ImageProcessor`，runtime，JDK ImageIO，在 `boundedElastic` 上执行）：按 EXIF 方向转正后**重新编码**（这就去掉了全部元数据），
  JPEG 质量 0.85；生成宽度变体（原图更窄时不生成）。原件也保存处理后的版本，**不保留**带元数据的原始字节。
- PDF、音频不做内容处理：PDF 以 `Content-Disposition: attachment` 与 `Content-Security-Policy: sandbox` 提供，不在同源下内联渲染。
- 策略名在应用内唯一；每个策略必须声明上传与读取权限（未声明 → 非 `dev` 下启动失败，与数据视图一致）。
- 病毒扫描不在本阶段：允许的类型都经过解码或只以附件形式提供；以后可在 §1 第 4 步加入扫描 SPI。

## 4. 语义类型 `jabiz.file`

扩展类型（`Custom`，runtime 提供 `FileKindSupport`），字段声明：

```java
f.custom(FileKind.of("culture.image"))      // 参数 {policy: "culture.image"}
```

- 规范 Java 类型 `UUID`（即 `fileId`）；物理列 `uuid`；允许的运算符 `EQ` `NE` `IN` `IS_NULL` `IS_NOT_NULL`。
- 写入检查（运行时，在提交前异步加载所需文件后同步判断，与字典校验同一方式）：文件存在 → 否则 400 `FILE_NOT_FOUND`；
  文件的 `policy` 等于字段声明的策略 → 否则 400 `FILE_POLICY_MISMATCH`。只检查本次**改变**的值。
- 导出（02 §8）：`{type: custom, kindId: jabiz.file, params: {policy, accept: [...], maxBytes, image: true|false, variants: [...]}}`，
  后台据此渲染上传控件（12 §5 增加一行：`jabiz.file` → 上传 + 预览）。
- 启动自检：策略存在（`FILE` 类别）。
- 一个字段引用一个文件；多个文件用子实体（例如一组照片，每张一行，带替代文本与排序）。

## 5. 接口

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/files?policy={name}` | 策略的上传权限 | `multipart/form-data`，恰好一个名为 `file` 的部分；201 `{fileId, contentType, sizeBytes, width, height, variants}` |
| GET | `/api/files/{fileId}` | `file.read` 或策略的读取权限 | 元数据 |
| GET | `/api/files/{fileId}/content[/{variant}]` | 同上 | 内容；支持单段 `Range`（音频拖动）；`Cache-Control: private, no-store` |
| GET | `/api/public/files/{fileId}[/{variant}]` | 匿名 | 见 15 §4 |

- 请求体大小由 `jabiz.files.max-request-bytes`（默认 100 MB，且不小于各策略上限）限制；只接受一个部分；文件名按
  `[\p{L}\p{N} ._()-]{1,255}` 清洗，其余字符替换为 `_`。
- 响应总是带 `X-Content-Type-Options: nosniff` 与判定出的 `Content-Type`；变体不存在时 404。
- 上传按操作人限流（`jabiz.files.upload-rate-per-minute`，默认 30，进程内令牌桶）→ 429 `RATE_LIMITED`，带 `Retry-After`。
- 后台前端的图片预览：访问令牌只在内存中（D15），`<img>` 不能带 `Authorization`，因此预览以 `fetch` 取得 blob 再用 `URL.createObjectURL` 显示。

## 6. 存储、清扫与删除

- `FileStore` SPI（runtime 内部，返回 `Mono`/`Flux`，**不对业务开放**）：`write(key, Flux<DataBuffer>)`、`read(key, range)`、`delete(key)`、`list(prefix)`。
  实现：`LocalFileStore`（根目录 `jabiz.files.local.root`，非阻塞 `AsynchronousFileChannel`；目录创建与移动在 `boundedElastic` 上）。
  S3 兼容存储以后按同一接口加入，不在本阶段。
- **一致性**：先写对象、后插入行；插入失败时删除已写的对象。删除时先删行（事务提交）、后删对象（`AFTER_COMMIT`）。
  两种顺序在崩溃时都只会留下"有对象无行"的孤儿，由清扫任务处理，不会出现"有行无对象"（除非存储被外部改动）。
- **清扫任务** `FILE_SWEEP`（`JobDefinition`，默认每天，D14）：
  1. 删除上传超过 `jabiz.files.orphan-after`（默认 24 小时）且**未被任何当前数据引用**的 `SysFile` 行（引用 = 任一实体中 `jabiz.file` 字段的当前值；
     时态实体只看当前与预定版本，历史版本中的引用不阻止删除——历史里显示"文件已删除"）；
  2. 列出存储中没有对应行、且早于清扫开始时间一小时的对象并删除。
  清扫只调用流程（`FILE_PURGE_ORPHANS`），有操作记录；删除的文件数记入观测。
- **手动删除** `FILE_DELETE`（`file.delete`）：文件仍被当前数据引用时 422 `FILE_IN_USE`（参数 `entity`、`field`）。
  业务的"删除个人数据"流程（例如 culture 的抹除）先删除引用它的行，再在同一流程中登记文件删除。
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
