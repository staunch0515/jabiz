# 依赖与镜像的已知漏洞评估（首次上线）

对应 `launch-checklist.md`"安全"中的"依赖与镜像的已知漏洞已评估"。评估日 2026-11-08，评估人 S1。只看高（HIGH）与严重（CRITICAL）；中、低的列在各节末尾作参考。

结论分三种：**升级**（已开 PR）、**不受影响**（写明理由）、**需要决定**。本次没有"需要决定"的条目。

## 1. 做法

| 对象 | 工具与数据 | 范围 |
|---|---|---|
| 后端（`:app`、`:quizbuks` 的运行时依赖） | `./gradlew :<模块>:dependencies --configuration runtimeClasspath`，对照 GitHub Advisory Database 中已审核的条目（`github/advisory-database`，Maven） | 运行时实际解析出的版本（含传递依赖），共 175 个 |
| 前端（平台 `frontend/`、示范门户 `backend/app/portal`、`quizbuks-web/*` 七个、`quizbuks-mobile/ios`） | `pnpm audit`（npm 公共库的漏洞数据） | 各目录的锁文件，含开发依赖 |
| 镜像（`deploy/quizbuks/Dockerfile` 的应用镜像、`deploy/quizbuks/backup/Dockerfile.postgres` 的数据库镜像） | Trivy 0.76.0，QuizBuks CI 的 `deploy` 作业中的"Image vulnerability report"一步（只报告，不使作业失败） | 操作系统的包、jar 中的库、可执行文件 |

以后：镜像的报告在每个 QuizBuks PR 的 CI 中都有，发现新的高或严重条目时按本页的方式补一节。后端与前端在升级平台或依赖时重做一次。

## 2. 后端

| 库（修改前的版本） | 条目 | 级别 | 结论 |
|---|---|---|---|
| Jackson 3.1.5 / 2.21.5（`tools.jackson.core`、`com.fasterxml.jackson.core`） | GHSA-7hhh-6rmp-j9qf、GHSA-p6pp-m3f8-5c89、GHSA-cxp5-3px4-pw24、GHSA-q4xh-88c3-wmh7、GHSA-wv8q-qhhj-9h54（另有中级 5 条） | 高 | **升级**。解析请求正文时可被拖慢或耗尽内存，处在请求路径上。平台 17zz6：Spring Boot 4.0.8 → 4.0.9，随它到 3.1.7 / 2.21.7 |
| SCRAM 3.2（`com.ongres.scram`，两个 PostgreSQL 驱动所带） | GHSA-p9jg-fcr6-3mhf | 高 | **升级**。实际上不受影响：只在中间人能冒充数据库、证书用 Ed25519 且客户端要求通道绑定时才降级，而本站与数据库在同一主机的内部网络上。修改便宜，平台 17zz6 以依赖约束定为 3.4 |

17zz6 之后重新对照：高、严重为 0。剩下的中级：

- OpenTelemetry API 1.55.0（GHSA-ccrj-gw8r-854m、GHSA-rcgg-9c38-7xpx）：**不受影响**。解析请求头 `tracestate`、`baggage` 的耗费随头的长度增长，而请求头总长受 8 KB 的上限约束（`server.max-http-request-header-size` 缺省）。随 Spring Boot 的版本表升级，不单独越过它。

升级时附带的变化：Reactor Netty 1.3.7 → 1.3.8 修好了平台以 `PipelinedReadResumer` 绕过的问题（流水线请求排空后不再读连接）。平台的探针测试因此改为断言 Reactor Netty 自己继续读；`PipelinedReadResumer` 暂留，删不删待总控确认（平台 17zz6）。

## 3. 前端

修改前各目录的高级条目都在构建与测试工具中：

| 包（修改前的版本） | 条目 | 级别 | 所在 | 结论 |
|---|---|---|---|---|
| Vite 8.3.1 | GHSA-587h-7wmj-h5pm（另有中、低 6 条） | 高 | 开发服务器（`pnpm dev`）打开编辑器、读取 `server.fs` 之外的文件 | **升级**到 8.3.5。生产只发打包出的静态文件，不运行 Vite |
| undici 8.11.2（jsdom 所带） | GHSA-2m5v-pch6-74hx、GHSA-4w7w-5xrc-9h26、GHSA-qrxm-fqhp-jwvm、GHSA-xjj6-f33q-929c（另有中级 4 条） | 高 | 只在 Vitest 的 jsdom 环境中 | **升级**：jsdom 30.1.3、undici 8.12.3 |
| zod 4.6.5（eslint-plugin-react-hooks 所带） | GHSA-mrhx-c7xc-m3w5 | 高 | 只在 lint 时 | **升级**到 4.6.7（锁文件） |
| source-map-js 1.2.1（Tailwind 的 Vite 插件所带） | GHSA-68fv-2mgg-jv7q | 高 | 只在构建时 | **升级**到 1.2.2（锁文件） |
| @vitest/mocker 5.0.2 | GHSA-qxg6-89mg-rqv8、GHSA-xp26-3454-mjgp | 中 | 测试运行器 | **升级**：Vitest 5.0.5 |

只选发布满两周的版本（`pnpm --config.minimum-release-age=20160`）。平台的 `frontend/` 与门户在 17zz6；QuizBuks 的 `app`、`sponsor`、`storefront`、`shopify`、`biz`、`shopify-site` 在平台 17zz6 同步过来之后的 QuizBuks PR 中（共用依赖的版本须与平台一致，`app:check`）。各目录的 lint、类型检查、测试、构建都通过。

升级后剩下的（都是中级，都**不受影响**）：

- eslint 带的 minimatch 10（GHSA-g3hv-75wg-m249）：只在 lint 时匹配仓库内的路径模式；eslint 自己的版本范围不允许 11。
- `quizbuks-mobile/ios` 中 `@capacitor/cli` 所带的 glob、minimatch、uuid（GHSA-3g3m-2fjr-6pw4、GHSA-g3hv-75wg-m249、GHSA-w5hq-g745-h8pq）：只在开发者的机器上生成 Xcode 工程时运行，不进 App。iOS App 不在首次上线中。
- `quizbuks-web/e2e`：没有条目。

## 4. 镜像

### 4.1 应用镜像（`eclipse-temurin:21-jre`，Ubuntu 26.04）

| 位置 | 条目 | 级别 | 结论 |
|---|---|---|---|
| `app.jar` 中的 Jackson、SCRAM（12 条） | 同第 2 节 | 高 | **升级**（平台 17zz6，同步后随镜像重建消失） |
| 操作系统的包 `apt`（2 条，如 CVE-2026-109518） | 已有修正版 3.2.1ubuntu0.1 | 高 | **升级**：Dockerfile 在安装字体之前先 `apt-get upgrade`，取得基础镜像构建之后发布的安全修正 |

### 4.2 数据库镜像（`postgres:16` + pgBackRest，Debian 13）

| 位置 | 条目 | 级别 | 结论 |
|---|---|---|---|
| `zlib1g`（CVE-2026-76844、CVE-2026-85091） | 已有修正版 deb13u1 | 高 | **升级**：Dockerfile 先 `apt-get upgrade`（同上）。这也会把 PostgreSQL 16 升到 PGDG 当时的最新小版本，小版本之间数据目录照用 |
| util-linux 一族（`bsdutils`、`libblkid1`、`libmount1`、`libuuid1`、`mount`、`login` 等，CVE-2026-76642、-78408～-78410） | Debian 尚无修正 | 高 | **不受影响**：涉及挂载、登录与终端工具，容器中只运行 PostgreSQL 与 pgBackRest，没有交互登录、不挂载 |
| `libxml2`（CVE-2026-6653 严重，另 7 条高） | Debian 尚无修正 | 严重 / 高 | **不受影响**：解析不可信的 XML 才会触发。PostgreSQL 的 `xml` 类型与函数本站不用（迁移与模板中没有），连接数据库的只有本站的应用与维护登录 |
| `libsqlite3-0`、`libsystemd0` / `libudev1`、`libncursesw6` / `libtinfo6`、`libacl1`、`dirmngr` / `gpg` | 尚无修正 | 高 | **不受影响**：容器中不用 SQLite、systemd、终端界面、ACL 与 GnuPG 的网络功能；它们是基础镜像带来的 |
| `perl` / `libperl5.40`（CVE-2026-9538） | Debian 推迟修正 | 高 | **不受影响**：pgBackRest 2.x 是 C 程序，不用 Perl；PostgreSQL 的 PL/Perl 没有安装 |
| `/usr/local/bin/gosu`（Go 1.24.6 的标准库，26 条，其中 CVE-2025-68121 严重） | 需要以新的 Go 重新编译 gosu | 严重 / 高 | **不受影响**：gosu 只在容器启动时把身份从 root 换成 postgres，随即 `exec` PostgreSQL；它不开网络、不解析外部输入，条目所在的 `net/http`、`crypto/tls`、`archive` 等它都不调用（gosu 维护者对这类报告的一贯说明）。官方 `postgres` 镜像更新 gosu 后随基础镜像得到 |

部署时数据库镜像在服务器上构建（`docker build -t postgres-pgbackrest:16 …`，`DEPLOY.md`"备份的做法"），每次重建即取得最新的基础镜像与 `apt-get upgrade` 的修正。

## 5. 未完成与跟进

- 平台 17zz6（Spring Boot 4.0.9、SCRAM 3.4、前端工具）合入并同步后，开 QuizBuks 的 SPA 升级 PR；之后 CI 的镜像报告中 `app.jar` 的条目应为 0，届时在本页补记。
- `PipelinedReadResumer` 是否删去：待总控确认。
- 正式服务器上已在运行的镜像：上线前按 `DEPLOY.md` 重新构建并部署一次（应用镜像随部署工作流，数据库镜像在服务器上构建）。
