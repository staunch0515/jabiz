# Culture, Unfiltered — 运维

部署、升级、备份与恢复、监控，以及上线前的测量记录（路线图 C4）。设计见 `00-design.md` §10；编辑的日常操作见 `editor-guide.md`。
以下命令都在仓库根目录执行（`culture` 分支的检出）。

## 1. 组成

| 部分 | 内容 | 位置 |
|---|---|---|
| `db` | PostgreSQL 16 | 卷 `culture-db`；只在本机的 5437 端口可达 |
| `culture` | 一个 jar：公开网站 `/`、后台 `/admin/`、接口 `/api/**` | 端口 8080（只在本机）；文件卷 `culture-files`（`/var/lib/jabiz/files`）；密钥卷 `culture-secrets` |
| `edge`（profile `edge`） | Caddy：自动 HTTPS、压缩，反向代理到 `culture` | 80、443；证书在卷 `culture-caddy` |
| `lgtm`（profile `observability`） | Grafana + 指标、链路、日志 | 只在本机的 3000 端口 |

一台 2 vCPU / 4 GB 的虚拟机足够（设计 §10）。**数据库与文件卷是全部的数据**，两者一起备份（§4）。

## 2. 首次部署

1. 服务器装 Docker（含 compose 插件）、`git`；域名的 A/AAAA 记录指向服务器；防火墙只开 80、443（和 SSH）。
2. 检出 `culture` 分支，在 `deploy/culture/.env` 写入（**不提交**；另存一份在密码管理器中——它不在备份里）：

   ```sh
   DB_PASSWORD=<长随机串>
   JABIZ_JWT_SECRET=<openssl rand -base64 48 的输出>
   JABIZ_BOOTSTRAP_ADMIN_USER=admin
   JABIZ_BOOTSTRAP_ADMIN_PASSWORD=<首个管理员的密码>
   CULTURE_DOMAIN=<域名>
   SERVER_FORWARD_HEADERS_STRATEGY=native
   ```

   不写 `JABIZ_JWT_SECRET` 与管理员密码时，首次启动会生成并保存在卷 `culture-secrets` 中（适合本机试用，不适合服务器：
   这个卷丢失后，恢复出的系统里管理员的密码无人知道）。
3. 启动：`docker compose -f deploy/culture/docker-compose.yml --profile edge up -d --build`。
   镜像从源码构建（Gradle 与 pnpm 在构建阶段下载依赖），首次约 5–10 分钟。
4. 打开 `https://<域名>/admin/`，以管理员登录，执行一次 `CULTURE_SETUP`（`editor-guide.md` "Before you start"）；再次执行不改变已有数据。
   之后为编辑与通讯员建账号（化名，设计 §4）。
5. 数据库账号需要对数据库有 `CREATE` 权限，以创建扩展 `pg_trgm`（搜索，设计 §7.2）；compose 中的账号即是。
   使用托管数据库时，事先以管理账号执行 `CREATE EXTENSION pg_trgm;`。

公开访问由 compose 打开（`JABIZ_PUBLIC_ENABLED=true`）；不在 compose 中运行时，要自行设置它，否则网站的所有数据请求都是 404。

## 3. 升级

1. 先备份（§4）。
2. `git pull`（`culture` 分支），`docker compose -f deploy/culture/docker-compose.yml --profile edge up -d --build`。
   数据库迁移在启动时自动执行；启动检查不通过时应用不会启动，日志（`docker compose … logs culture`）一次列出全部问题。
3. 打开网站与后台各看一眼。出问题时回到上一版本：检出上一个提交重新构建；迁移已执行过的，从第 1 步的备份恢复（§5）。

## 4. 备份

`tools/culture/backup.sh [目录]`（默认 `./backups`）写出 `<目录>/culture-<UTC 时间>/`：

| 文件 | 内容 |
|---|---|
| `db.dump` | `pg_dump` 的自定义格式 |
| `files.tar.gz` | 文件卷的全部内容 |
| `SHA256SUMS`、`manifest.txt` | 校验和；时间、迁移版本、文件数与大小 |

- 系统照常运行。**先导出数据库、再打包文件**：平台先写文件对象、后插入行（`docs/design/14-files.md` §6），因此两步之间上传的文件
  只会是"有对象无行"（清扫任务以后删除），不会是"有行无对象"。脚本最后核对：导出中的每个文件行在归档中都有对象，否则失败并提示重做。
- 目录权限 700：内容里有未成年人的个人信息。复制到服务器以外时先加密（例如 `age` 或 `gpg`），密钥与 `.env` 放在同一个密码管理器中。
- compose 以外的部署：`CULTURE_BACKUP_MODE=direct`，数据库经 `PGHOST` `PGPORT` `PGUSER` `PGPASSWORD` 与 `CULTURE_DB`，
  文件目录经 `JABIZ_FILES_LOCAL_ROOT`（需要本机的 `pg_dump` 16）。

**计划**（设计 §10：每日）：root 的 crontab 中

```cron
17 3 * * * cd /srv/culture && tools/culture/backup.sh /srv/backups >> /var/log/culture-backup.log 2>&1 && find /srv/backups -maxdepth 1 -name 'culture-*' -mtime +30 -exec rm -rf {} +
```

**保留 30 天**，不更久：备份里有被撤回或抹除之前的个人数据（§5 的最后一步），保留越久，恢复后要重做的抹除越多，
而删除权要求抹除在合理期限内也在备份中生效（30 天后随备份轮换消失）。

## 5. 恢复

`tools/culture/restore.sh --yes <备份目录>`：先核对校验和（损坏的备份被拒绝），再停止应用、以备份**替换**数据库与文件卷、启动应用。
备份之后新增的内容全部丢失。

从零开始（新服务器或卷全部丢失）：

1. 按 §2 准备服务器与 `.env`（同一个 `JABIZ_JWT_SECRET` 与管理员密码，来自密码管理器），构建镜像：
   `docker compose -f deploy/culture/docker-compose.yml build`，只启动数据库：`… up -d --wait db`。
2. 取回备份（解密），`tools/culture/restore.sh --yes <备份目录>`。它最后启动应用；`… --profile edge up -d` 启动代理。
3. 检查：网站首页与一个故事页，后台登录，上传一张图片。
4. **重做备份之后的撤回与抹除**：恢复会带回备份时刻之后被撤回同意、被抹除的参与者的数据。对照撤回与抹除的登记
   （编辑在执行 `CULTURE_CONSENT_WITHDRAW`、`CULTURE_PARTICIPANT_ERASE` 时在系统之外登记日期与参与者的化名或主键；
   操作记录不能用：它也回到了备份时刻），对每一项重新执行撤回、抹除。然后再做一次备份。
5. 脚本报告"file row(s) without an object"时：这些文件在备份导出之后被删除（抹除、删除），对应的页面图片为 404；按第 4 步处理后即不再被引用。

## 6. 演练记录

脚本 `tools/culture/test/backup-restore.test.sh` 就是演练：起一套系统 → 经后台接口加地点、主题、有头像的参与者与同意、有缩略图的故事并发布 →
备份 → **删除全部数据**（compose：`down -v`，包括密钥卷；direct：删除数据库与文件目录）→ 恢复 → 断言公开页面的数据与文件的字节
与备份前完全相同、管理员能登录、`CULTURE_SETUP` 与新的上传可用；再断言损坏的备份与没有 `--yes` 的恢复被拒绝。
CI 的 `restore-drill` 作业（`.github/workflows/culture.yml`）每次推送都以 compose 方式从源码构建并运行它。

| 日期 | 方式 | 环境 | 结果 |
|---|---|---|---|
| 2026-09-28 | direct | 本机 PostgreSQL 16.13，打包的 jar | 通过 |
| 2026-09-28 | compose | Docker 29 / Compose 5.1，`deploy/culture` 的 compose（镜像以本机构建的 jar 装入同一运行阶段：沙盒的 TLS 代理挡住了镜像内的 Gradle 下载） | 通过；并由此发现 compose 部署无法启动（空的 OTLP 端点，已修正，§8） |

从源码构建镜像的完整演练由 CI 的 `restore-drill` 作业执行。

## 7. 性能（Lighthouse）

`site/` 下 `E2E_BASE_URL=… pnpm lighthouse`（`scripts/lighthouse.mjs`）：Lighthouse 的移动端预设（412 px 宽，模拟慢速 4G：RTT 150 ms、
约 1.6 Mbps，CPU 降速 4 倍），测首页（三种语言）与故事库、地图、搜索页。**首页 LCP ≥ 2.5 s 或任一页无障碍 < 100 即失败**。
CI 的 `e2e` 作业在端到端测试之后运行它（数据库里有端到端测试造出的内容），报告作为构件 `culture-lighthouse` 保存。

首屏 JS 由构建检查：每次 `vite build` 计算 `index.html` 立即加载的脚本（入口与 modulepreload）的 gzip 大小，超过 200 KB 构建失败
（`scripts/check-bundle.mjs`）。

**记录**（2026-09-28，Lighthouse 13.5.0，HeadlessChrome 141，打包的 jar + PostgreSQL 16，端到端测试造出的内容）：

| 页面 | 性能 | 无障碍 | LCP | FCP | TBT | CLS | 传输的 JS |
|---|---|---|---|---|---|---|---|
| `/en/` | 98 | 100 | 1.96 s | 1.96 s | 56 ms | 0.001 | 176 KB |
| `/zh/` | 98 | 100 | 2.09 s | 1.87 s | 71 ms | 0.000 | 176 KB |
| `/ja/` | 98 | 100 | 1.85 s | 1.85 s | 55 ms | 0.003 | 176 KB |
| `/en/stories` | 95 | 100 | 2.72 s | 1.96 s | 40 ms | 0.000 | 177 KB |
| `/en/map` | 92 | 100 | 2.87 s | 1.92 s | 139 ms | 0.000 | 208 KB |
| `/en/search?q=home` | 95 | 100 | 2.58 s | 1.96 s | 43 ms | 0.000 | 178 KB |

- **首屏 JS 136.6 KB（gzip）**：入口 69.2 KB（React、路由、查询）+ 网站的共用模块 67.0 KB（组件、界面文字）+ 运行时 0.4 KB。
  "传输的 JS"另含加载完成后预取的 Markdown 渲染器（35 KB）；地图页另有地图与世界轮廓（33 KB），只在打开地图时加载。
- C4 之前（C3 结束时）：首屏 JS 176.5 KB；首页 LCP 5.3 s、CLS 0.9、性能 43。所做的调整：
  1. 除首页外的页面与 Markdown 渲染器按需加载；网站的共用小模块合为一个块（少十个请求）。
  2. 应用对静态资源（HTML、CSS、JS、SVG）压缩传输（`server.compression`，只限这些类型：接口的 JSON 不压缩，避免 BREACH）。
     经 Caddy 时由 Caddy 压缩。
  3. 首页标题立即绘制（它是 LCP 元素），其下的部分在文案、地点与人都到齐后一起出现；故事库的筛选、地图页同样整体出现：
     不再有内容一块块出现、把下面的内容往下推（CLS）。桌面版首页的标题因此改为顶端对齐（原型中与拼贴垂直居中）。
- 数字随机器浮动（同一台机器上多次运行 ±0.2 s）；CI 的阈值为 2.5 s。

## 8. 监控与日志

- 健康检查：`/actuator/health`（镜像的 `HEALTHCHECK` 与 compose 的 `--wait` 用它；只返回 `UP` / `DOWN`，不含细节）。
- 日志：`docker compose -f deploy/culture/docker-compose.yml logs -f culture`，JSON（ECS）格式，一行一条。
- 指标、链路、日志的推送（`docs/design/13-observability-ops.md`）：以 `--profile observability` 启动 `lgtm`，并在 `.env` 中写

  ```sh
  MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT=http://lgtm:4318/v1/traces
  MANAGEMENT_OPENTELEMETRY_LOGGING_EXPORT_OTLP_ENDPOINT=http://lgtm:4318/v1/logs
  MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED=true
  MANAGEMENT_OTLP_METRICS_EXPORT_URL=http://lgtm:4318/v1/metrics
  ```

  Grafana 在本机的 3000 端口（经 SSH 端口转发访问）。不写时不推送；**不要写空值**：空的端点使应用无法启动
  （C4 的恢复演练发现 compose 曾这样写，已修正）。
- 撤回与下线之后，公开文件最迟在"判定缓存 60 s + 浏览器缓存 300 s"后不可得（设计 §6.4、§7.2），编辑指南中如实说明。

## 9. 已知限制

- 文件在本机目录（卷）中：只适合单实例（`docs/design/14-files.md` §6）。
- 备份是整库与整卷：恢复是整体替换，不能只恢复一个故事。
- 撤回与抹除的登记在系统之外（§5 第 4 步）；需要时再在平台上设计"恢复后重做抹除"的能力。
