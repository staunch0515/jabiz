# 17 平台与应用：分支、模块、部署

一个仓库里同时有**平台**（可复用的公共部分）和若干**应用**（具体项目，例如 `culture`）。本文件规定二者怎样分开开发、
怎样构建成各自的可部署产物（ROADMAP 阶段 13a），以及平台与应用怎样按版本线协同演进（阶段 13f）。约束性细则见【决策 D19】【决策 D21】。

## 1. 分支模型

平台的每个不兼容版本是一条**版本线**；每条线有一个平台分支，用到这条线的应用各有一个应用分支【决策 D21】：

```
                 1.0/platform ──●──────●(fix)───────────────►      线 1.0
                   │  \ merge    \         \ 向前合并
                   │   1.0/culture ─●───────●───────────────►
                   │   1.0/finance ─●──┐（升级后冻结）
                   │                   │
                   └► 1.1/platform ─────●───●(1.0 的 fix)────►     线 1.1（不兼容的改动，如阶段 14）
                         \ merge        \ merge
                          1.1/finance ◄─┘（从 1.0/finance 拉出，再合并 1.1/platform）
工作分支：
  平台：<线>/phase-<N><x>-<名>   从 <线>/platform 拉出，PR 合回 <线>/platform
  应用：<线>/<应用>-<N>-<名>      从 <线>/<应用> 拉出，PR 合回 <线>/<应用>
```

- **平台分支 `<线>/platform`**：长期分支。内容：`backend/core`、`backend/runtime`、`backend/ext-geo`、`backend/app`（示范应用）、
  `backend/build-logic`、`frontend/`（通用后台）、`spec/`、`docs/design/`、`docs/guide/`、`tools/`（`tools/<应用>/` 除外）、根目录的构建与 CI 文件，
  以及线号文件 `.jabiz-platform-line`。
- **应用分支 `<线>/<应用>`**（如 `1.0/culture`）：只**增加**应用专有目录（第 2 节），不修改平台目录。
- **线内的合并方向只有一个**：`<线>/platform` → `<线>/<应用>`（合并提交，不变基、不拣选）。应用分支从不合回平台。
- 做应用时发现平台缺能力或有缺陷：先在该线的平台工作分支上实现（带平台自己的测试与示范，不提应用），合入 `<线>/platform`，再合并进应用分支。
- **开新线**只为不兼容的改动：从上一条线的平台分支拉出 `<新线>/platform`，第一个提交把 `.jabiz-platform-line` 改为新线号。兼容的新增与修复留在当前线。
- **修复向前合并**：修复做在最旧的受影响线上，再合并到更新的线（`1.0/platform` → `1.1/platform`），各线再合并到自己的应用。
- **应用升级**：`<新线>/<应用>` 从 `<旧线>/<应用>` 拉出，合并 `<新线>/platform` 并适配；此后应用只在新线上开发，旧线上的应用分支冻结。
- **迁移**：不是最新的线不增加迁移（平台与应用都不加），需要改表结构的修复只做在最新线上。
- **发布**：标签 `platform-v<主>.<次>.<修订>` 打在对应线的平台分支上。
- 各项操作的命令见 `docs/guide/version-lines.md`。
- 过渡：原有的 `platform`、`culture`、`finance` 即线 1.0（由它们建立 `1.0/*`），之后不再使用；`main`、`alpha` 与 `phase-*-baseline` 保持现状，不再作为开发基线。

## 2. 应用专有目录与检查

应用分支在仓库根目录放一个 `.jabiz-app-paths`，列出它拥有的路径（glob），例如 culture：

```
.jabiz-app-paths
backend/culture/**
site/**
docs/culture/**
deploy/culture/**
tools/culture/**
.github/workflows/culture.yml
```

- 平台提供 `tools/check-app-paths.sh`：在应用分支上计算 `git diff --name-only --no-renames origin/<线>/platform...HEAD`（自共同祖先以来应用分支的改动，
  改名按删除 + 新增计），任何不匹配 `.jabiz-app-paths` 的路径都报错。合并平台分支带来的改动不计入（它们在共同祖先之后同时出现在两边）。
  - 基准：参数，否则 `$APP_PATHS_BASE`，否则由 `.jabiz-platform-line` 得到 `origin/<线>/platform`；没有该文件的旧分支用 `origin/platform`。
  - 线的检查（对所有分支，包括平台分支）：以线命名的分支（`1.1/…`）必须与 `.jabiz-platform-line` 一致，否则失败——应用漏了合并新线的平台，
    或新线的平台分支忘了改文件。分支名取 `$APP_PATHS_BRANCH`（CI 中 HEAD 是游离的），否则取当前分支。`.jabiz-platform-line` 是平台文件，
    应用改它按越界处理。
  - 模式：`#` 起注释；`**` 可跨目录，`*`、`?` 只在一段路径内；`.jabiz-app-paths` 本身总是允许。
  - 模式不得覆盖平台分支（共同祖先）上已有的文件（例如 `backend/**`），否则同样报错。
  - 平台自己的目录（`backend/core` `backend/runtime` `backend/ext-geo` `backend/app` `backend/build-logic` `frontend` `spec`
    `docs/design` `docs/guide`）下的任何改动（包括新文件）都算越界，不论 `.jabiz-app-paths` 如何写。
  - 检查用的脚本与工作流来自被检查的分支本身，因此只有在分支保护把 `app-paths` 设为必需检查、且对这两个文件的修改经过评审时才有约束力。
  - 退出码：0 通过（或没有 `.jabiz-app-paths`，不是应用分支）；1 有越界或线不一致；2 无法检查（找不到平台分支或共同祖先、线号格式不对），不放行。
- 平台 CI（`ci.yml`）对推送到任何分支都运行（应用分支不能改 `ci.yml`）；其 `app-paths` 作业取完整历史，存在 `.jabiz-app-paths` 时取该线的平台分支，
  在每个分支上运行检查（线的检查对平台分支同样有效）与脚本自己的测试 `tools/test/check-app-paths.test.sh`。
- 应用自己的开发规则写在应用目录内的 `CLAUDE.md`（如 `backend/culture/CLAUDE.md`、`site/CLAUDE.md`）与 `docs/<应用>/` 中；
  根目录的 `CLAUDE.md` 只属于平台，应用分支不改它（避免合并冲突）。

## 3. 多个可部署应用

### 3.1 构建

- `backend/settings.gradle.kts` 自动包含 `backend/` 下每个带 `build.gradle.kts` 的直接子目录（平台模块照常显式列出，其余自动发现），
  应用分支增加模块不必修改 settings。
- 约定插件 `jabiz.boot-app`（`backend/build-logic`）承担现在根构建脚本中 `project(":app")` 的全部配置：Spring Boot、`bootRun` 的虚拟线程属性、
  `platformCheck` 任务、场景快照与 OpenAPI 快照的测试属性，以及前端打包：

```kotlin
plugins { id("jabiz.boot-app") }
jabizApp {
    mainClass = "com.jabiz.culture.CultureApp"
    spa("/", "../../site")                  // 公开网站 → static/
    spa("/admin", "../../frontend")         // 通用后台，以 VITE_BASE=/admin/ 构建 → static/admin/
}
```

- `app` 改用同一插件（`spa("/", "../../frontend")`），行为不变；这是本阶段对现有构建的唯一改动，由现有测试与 CI 证明。

### 3.2 多个单页应用（SPA）

- `SpaFallbackFilter` 改为按配置工作：`jabiz.web.spa[i].path`（前缀）与 `.index`（该前缀的 `index.html`），最长前缀优先；
  缺省只有 `/` → `/index.html`，与现在相同。`/api`、`/actuator` 与带扩展名的路径不回退（不变）。
- 前缀按路径段匹配（`/admin` 匹配 `/admin` 与 `/admin/…`，不匹配 `/administrator`）；`index` 缺省为 `<前缀>/index.html`。
- 每个 SPA 可配置内容安全策略 `jabiz.web.spa[i].content-security-policy`，写入该前缀下全部响应（`/api`、`/actuator` 除外）的响应头；
  未配置时用后台的缺省值
  `default-src 'self'; img-src 'self' blob: data:; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'`
  （antd 的 CSS-in-JS 需要内联样式；未配置即取最严格的现有策略，默认拒绝）。公开网站在应用中配置自己的策略（例如允许嵌入视频的 `frame-src`）。
- 配置由 `SpaConfigCheck`（启动检查，类别 `WEB`）一次性报告：前缀格式、重复、落在 `/api` `/actuator` 下，`index` 格式，CSP 为空或多行。
- 同源的多个 SPA 共享 `localStorage` / `sessionStorage`：公开前端不存令牌（第 4 节），也不得读写后台的会话键。
- 构建：插件为每个 `spa(...)` 注册 `pnpmInstall<名>` 与 `spaBuild<名>`（`VITE_BASE=<前缀>/`，输出到本模块的 `build/spa/<名>`，
  因此两个应用可用不同的 base 构建同一个 `frontend/`；全部 pnpm 任务串行），`bootJar` 把输出放到 `static/<前缀>`。

### 3.3 部署与 CI

- 每个应用一个 compose 文件（`deploy/<应用>/docker-compose.yml`）与一个 CI 工作流（`.github/workflows/<应用>.yml`），都在应用目录中。
- 平台的 `ci.yml` 运行 `./gradlew check`：在应用分支上它自动覆盖应用模块（自动包含），因此平台改动合并到应用分支后，应用的测试会立即执行。

## 4. 公开前端

公开网站（面向匿名访客、以内容与视觉为主）与通用后台是不同的产品：

- 工具链与后台相同：pnpm、React 19、TypeScript、Vite、React Router、TanStack Query、i18next、Vitest、Playwright。
- **不使用 Ant Design / ProComponents**：视觉由应用自己设计（CSS Modules + CSS 自定义属性），以减小体积并满足编辑风格的设计要求。
- 数据只来自公开接口（15），类型由公开模板目录快照生成（15 §7）；不登录、不存令牌。
- 无障碍（WCAG 2.2 AA）与移动端适配是公开前端的验收项（axe 自动检查纳入 Playwright）。

## 5. 测试

- `check-app-paths.sh`（`tools/test/check-app-paths.test.sh`）：在临时仓库中构造"只改应用目录"与"改了平台目录"两种分支，断言前者通过、后者失败；
  另有非 ASCII 与含空格的文件名、平台目录下的新文件、删除与移动平台文件、`*` 不跨目录、合并平台之后、模式覆盖平台文件、找不到平台分支。
  版本线（13f）：基准取自 `.jabiz-platform-line`；线的工作分支；应用自己改线号；以新线命名但未合并新线的平台；升级到新线后按新线检查；
  旧线的修复经向前合并到达新线的应用；新线的平台工作分支未改线号；线号格式不对、新线的平台分支未取得（退出码 2）；`APP_PATHS_BRANCH` 与游离的 HEAD；
  没有线号文件的旧分支以 `origin/platform` 为基准。
- 构建：`app` 改用约定插件后 `./gradlew check`、`:app:bootJar`、端到端与 compose 作业全部照常通过。
- `SpaFallbackFilter`（`SpaFallbackFilterTest`、`SpaConfigCheckTest`）：多个前缀的回退、最长前缀优先、路径段边界、CSP 头；缺省配置与现在行为相同；
  `MultiSpaIT`（app）经完整的 WebFlux 与安全过滤链验证 `/` 与 `/admin/` 两个 SPA。
