# 给应用加自己的后台页面（扩展）

当一个工作流用元数据生成的页面表达不了时（例如多行分录的录入网格、对账、结账工作区），应用在自己的目录里写一个**扩展**，
构建时编入通用后台。规则见决策 D22 与 `docs/design/12-frontend.md` §9；可运行的示范是 `backend/app/admin-extension/`。

先想清楚是否真的需要：主数据的增删改查、历史、流程表单都已由元数据生成，扩展只做生成页面做不到的部分。

## 1. 建目录

以 finance 为例（目录名由应用决定，必须在应用的 `.jabiz-app-paths` 中）：

```
finance-web/
  tsconfig.json
  src/index.tsx
  src/JournalGridPage.tsx
  src/JournalGridPage.test.tsx
```

`tsconfig.json`（路径指向平台前端）：

```json
{
  "extends": "../frontend/tsconfig.extension.json",
  "include": ["src"]
}
```

扩展没有 `package.json`，也不 `pnpm install`：React、antd、ProComponents、TanStack Query、dayjs、i18next 等都用平台前端的。

## 2. 写入口

```tsx
import { BookOutlined } from '@ant-design/icons'
import { defineExtension } from '@jabiz/admin'
import JournalGridPage from './JournalGridPage'

export default defineExtension({
  routes: [{ path: '/gl/journals/new', element: <JournalGridPage /> }],
  menu: [{ key: 'journal', label: 'menu.journal', path: '/gl/journals/new', icon: <BookOutlined />,
           permission: 'fin.journal.prepare' }],
  messages: {
    en: { menu: { journal: 'New journal entry' } },
    zh: { menu: { journal: '新建分录' } },
    ja: { menu: { journal: '仕訳の入力' } },
  },
  home: '/gl/journals/new',
})
```

- 路由是绝对路径，不能用 `/`、`/login`、`/data…`、`/processes…`；写错时应用启动即报告全部问题。
- `permission` 只决定菜单项是否显示；页面的每个请求都由服务端检查。
- 文案在命名空间 `app`：页面里 `const { t } = useTranslation(EXTENSION_NAMESPACE)`。

## 3. 写页面

只从 `@jabiz/admin` 引用平台：

```tsx
import { ApiError, EXTENSION_NAMESPACE, runProcess, runQuery, useAuth } from '@jabiz/admin'

const rows = await runQuery<Row>('finance.gl.account_activity', { params: { account: '1010' }, limit: 100 })
const out = await runProcess<Output>('FIN_JOURNAL_SUBMIT', { journalId })   // 自动带幂等键
```

- 读数据：SQL 模板用 `runQuery`，数据视图用 `api.POST('/api/datasets/{id}/query', …)`，元数据用 `useEntityMeta` 等 hooks。
- 写数据：只经流程（`runProcess`）或数据视图接口；失败时 `ApiError` 带服务端的全部违规（`display`、`forField`）。
- 通用组件：`EntityFormDrawer`、`ReferenceSelect`、`FieldErrors`、`FilePreview`、`MarkdownView`。
- 不要 import `frontend/src/...`（lint 会拒绝），不要自己存令牌，不要访问后端以外的地址。

## 4. 测试

页面测试与平台前端相同（Vitest + Testing Library）；调用可用 `vi.mock('@jabiz/admin', …)` 替换，示范见
`backend/app/admin-extension/src/StockOverviewPage.test.tsx`。端到端测试放在应用自己的 Playwright 目录，或（平台示范）`frontend/e2e/`。

在 `frontend/` 下：

```sh
JABIZ_ADMIN_EXTENSION=../finance-web pnpm ext:check        # 类型、lint、测试
JABIZ_ADMIN_EXTENSION=../finance-web pnpm dev              # 开发服务器（5173），扩展的页面随改随刷新
```

## 5. 打包

在应用模块的 `build.gradle.kts`（路径相对于该模块）：

```kotlin
jabizApp {
    mainClass = "com.jabiz.finance.FinanceApp"
    spa("/", "../../frontend", extension = "../../finance-web")
}
```

`./gradlew :finance:bootJar` 会先对扩展做类型检查，再把它编入后台；扩展改动后会重新构建。

## 6. 需要平台没有的东西时

- 缺一个通用组件或接口：在平台的工作分支上加进 `@jabiz/admin`（带平台自己的测试与 `app` 中的示范），合入后再合并到应用。
- 缺一个第三方依赖：同样先加到平台前端的 `package.json`。
- `@jabiz/admin` 的导出是约定；改变已有导出是不兼容改动，需要新的版本线（决策 D21）。
