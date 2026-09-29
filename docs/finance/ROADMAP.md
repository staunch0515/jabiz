# finance — 路线图

应用 finance 的阶段计划。设计见 `00-design.md`，需求原文见 `docs/finance-requirements/`（只读），总体计划与已确认的决定见 `docs/finance-work/00-development-plan.md`。
执行方式与平台相同（根目录 `CLAUDE.md` 第 7 节）：每个阶段先出实施计划、经确认后实现；PR 逐条对照本文件的验收标准。
平台能力来自 `platform` 分支的阶段 14a–14g（在平台的 `docs/ROADMAP.md` 中立项），合入 `platform` 后再合并到 `finance`。

| 阶段 | 名称 | 依赖（平台） | 预估 | 状态 |
|---|---|---|---|---|
| F0 | 设计与骨架 | — | 3–4 天 | ☑ 已完成（设计待确认） |
| F1 | 总账、期间、日记账与审批 | 14a 14b 14c | 8–10 天 | ☐ |
| F2 | 主数据导入、期初与迁移、工资导入 | 14e | 4–5 天 | ☐ |
| F3 | 应收与销售税 | — | 8–10 天 | ☐ |
| F4 | 应付、付款与 1099 | — | 8–10 天 | ☐ |
| F5 | 银行与对账 | 14e | 6–8 天 | ☐ |
| F6 | 固定资产 | — | 4–5 天 | ☐ |
| F7 | 多币种 | — | 4–5 天 | ☐ |
| F8 | 结账、重开、年结 | — | 5–6 天 | ☐ |
| F9 | 财务报表与报告 | 14d | 8–10 天 | ☐ |
| F10 | 控制、审计支持、安全配置 | 14f 14g | 4–5 天 | ☐ |
| F11 | 接口、性能、运维、全场景验收 | — | 6–8 天 | ☐ |

财务阶段之间的依赖：F2、F3、F4 在 F1 之后；F5 在 F3、F4 之后；F6 在 F4 之后；F7 在 F3–F5 之后；F8 在 F1–F7 之后；F9 在 F8 之后；F11 最后。

---

## F0 设计与骨架

**要求**
1. `.jabiz-app-paths`；根目录 `CLAUDE.md` 恢复为平台版本，财务规则写在 `backend/finance/CLAUDE.md`。
2. `docs/finance/00-design.md`（含需求覆盖表）、本路线图。
3. 模块 `backend/finance`（`jabiz.boot-app`，暂用平台后台前端），`deploy/finance/`（compose、Dockerfile），`.github/workflows/finance.yml`。
4. `docs/finance-work/work-items.csv`、`defects.csv`（由需求的 `templates/` 起始）。

**验收标准**
- [x] `./gradlew :finance:check`（`ArchitectureTest`、`FinanceAppIT`、`platformCheck` 0 错误）通过。
- [x] `tools/check-app-paths.sh origin/platform` 通过。
- [x] 打包的 jar 在空数据库上启动、迁移、健康检查 UP，`/` 提供后台页面，匿名调用 `/api` 为 401（本地冒烟；CI 作业 `Finance / package`）。
- [ ] 设计文档经确认。

说明：本地环境没有 Docker，集成测试以 `JABIZ_TEST_DB_*` 连接本地 PostgreSQL 16 运行；compose 与 Dockerfile 未在本地构建，由使用者或以后的 CI 作业验证。

## F1 — F11

范围、需求编号与验收口径见 `docs/finance-work/00-development-plan.md` §5.2；每个阶段开始时把详细要求与验收标准写入本节。
