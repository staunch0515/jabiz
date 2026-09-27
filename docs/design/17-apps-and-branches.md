# 17 平台与应用：分支、模块、部署

一个仓库里同时有**平台**（可复用的公共部分）和若干**应用**（具体项目，例如 `culture`）。本文件规定二者怎样分开开发、
怎样构建成各自的可部署产物（ROADMAP 阶段 13a）。约束性细则见【决策 D19】。

## 1. 分支模型

```
phase-11-baseline ──► platform ──●────────●─────────●──────────►   公共分支（平台）
                                  \        \ merge   \ merge
                                   culture ─●─────────●─────●──►  应用分支（culture = platform + 应用专有目录）
工作分支：
  平台：phase-<N><x>-<名>   从 platform 拉出，PR 合回 platform
  应用：culture-<N>-<名>    从 culture 拉出，PR 合回 culture
```

- **`platform`**：长期分支，承接 `phase-11-baseline`。内容：`backend/core`、`backend/runtime`、`backend/ext-geo`、`backend/app`（示范应用）、
  `backend/build-logic`、`frontend/`（通用后台）、`spec/`、`docs/design/`、`docs/guide/`、`tools/`（`tools/culture/` 除外）、根目录的构建与 CI 文件。
- **应用分支**（如 `culture`）：从 `platform` 拉出，只**增加**应用专有目录（第 2 节），不修改平台目录。
- **合并方向只有一个**：`platform` → 应用分支（合并提交，不变基、不拣选）。应用分支从不合回 `platform`。
- 做应用时发现平台缺能力或有缺陷：先在平台工作分支上实现（带平台自己的测试与示范，不提应用），合入 `platform`，再把 `platform` 合并进应用分支。
- `main`、`alpha` 与 `phase-*-baseline` 保持现状，不再作为开发基线。

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

- 平台提供 `tools/check-app-paths.sh`：在应用分支上计算 `git diff --name-only --no-renames origin/platform...HEAD`（自共同祖先以来应用分支的改动，
  改名按删除 + 新增计），任何不匹配 `.jabiz-app-paths` 的路径都报错。合并 `platform` 带来的改动不计入（它们在共同祖先之后同时出现在两边）。
  - 模式：`#` 起注释；`**` 可跨目录，`*`、`?` 只在一段路径内；`.jabiz-app-paths` 本身总是允许。
  - 模式不得覆盖平台分支（共同祖先）上已有的文件（例如 `backend/**`），否则同样报错。
  - 退出码：0 通过（或没有 `.jabiz-app-paths`，不是应用分支）；1 有越界；2 无法检查（找不到平台分支或共同祖先），不放行。
- 平台 CI（`ci.yml`）对推送到任何分支都运行（应用分支不能改 `ci.yml`）；其 `app-paths` 作业取完整历史，存在 `.jabiz-app-paths` 时运行检查，
  并在每个分支上运行脚本自己的测试 `tools/test/check-app-paths.test.sh`。
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
  另有删除与移动平台文件、`*` 不跨目录、合并平台之后、模式覆盖平台文件、找不到平台分支。
- 构建：`app` 改用约定插件后 `./gradlew check`、`:app:bootJar`、端到端与 compose 作业全部照常通过。
- `SpaFallbackFilter`（`SpaFallbackFilterTest`、`SpaConfigCheckTest`）：多个前缀的回退、最长前缀优先、路径段边界、CSP 头；缺省配置与现在行为相同；
  `MultiSpaIT`（app）经完整的 WebFlux 与安全过滤链验证 `/` 与 `/admin/` 两个 SPA。
