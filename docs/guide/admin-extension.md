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

扩展没有 `package.json`，也不 `pnpm install`：React、TanStack Query、dayjs、i18next、lucide-react 等都用平台前端的；组件经 `@jabiz/admin` 取自 `@jabiz/ui`
（线 1.2 起，决策 D34；Ant Design 已从平台删除（阶段 15d），扩展的 lint 拒绝引用 antd 与 `@ant-design/*`）。
页面随亮、暗外观变化：颜色只用主题 token（`bg-card`、`text-muted-foreground` …），不要写死，写死的颜色在暗色外观下会显得不对。
Tailwind 的 preflight 是全局的（阶段 15d 起）：扩展自己写的 HTML 元素（`<h2>`、`<ul>`、`<a>` …）没有浏览器的默认样式（标题不加粗放大、列表没有符号、链接不变色），
样式全用工具类写。后台的内容区（平台页面与扩展的路由）是 `text-sm`（14px，与 antd 时期相同），没有写字号的文字都是这个大小。`UI_SCOPE` 仍由 `@jabiz/admin` 导出，但已不需要，新代码不要再用。

## 2. 写入口

```tsx
import { defineExtension } from '@jabiz/admin'
import { BookOpen } from 'lucide-react'
import JournalGridPage from './JournalGridPage'

export default defineExtension({
  routes: [{ path: '/gl/journals/new', element: <JournalGridPage /> }],
  menu: [{ key: 'journal', label: 'menu.journal', path: '/gl/journals/new', icon: BookOpen,
           permission: 'fin.journal.prepare' }],
  messages: {
    en: { menu: { journal: 'New journal entry' } },
    zh: { menu: { journal: '新建分录' } },
    ja: { menu: { journal: '仕訳の入力' } },
  },
  home: '/gl/journals/new',
})
```

- 路由是绝对路径，不能用 `/`、`/login`、`/data…`、`/processes…`、`/tasks…`、`/auth…`（验证邮箱、加入、找回密码的落地页）等平台路径；写错时应用启动即报告全部问题。
- `permission` 只决定菜单项是否显示；页面的每个请求都由服务端检查。
- 文案在命名空间 `app`：页面里 `const { t } = useTranslation(EXTENSION_NAMESPACE)`。

## 3. 写页面

只从 `@jabiz/admin` 引用平台：

```tsx
import { ApiError, EXTENSION_NAMESPACE, runProcess, runQuery, useAuth } from '@jabiz/admin'

const rows = await runQuery<Row>('finance.gl.account_activity', { params: { account: '1010' }, limit: 100 })
const out = await runProcess<Output>('FIN_JOURNAL_SUBMIT', { journalId })   // 自动带幂等键
```

示范页 `backend/app/admin-extension/src/StockOverviewPage.tsx` 的骨架（只用 `@jabiz/ui`）：

```tsx
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { DataTable, EXTENSION_NAMESPACE, PageHeader, runQuery, type ColumnDef } from '@jabiz/admin'

export default function StockOverviewPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const stock = useQuery({ queryKey: ['ext', 'stock'], queryFn: () => runQuery<StockRow>('commerce.stock_availability', { limit: 100 }) })
  const columns: ColumnDef<StockRow, unknown>[] = [
    { id: 'sku', header: t('stock.sku'), cell: ({ row }) => row.original.sku },
    { id: 'onHand', header: t('stock.onHand'), meta: { className: 'text-right tabular-nums' }, cell: ({ row }) => row.original.onHand },
  ]
  return (
    <div className="flex flex-col gap-4">
      <PageHeader title={t('stock.title')} />                    {/* 页面唯一的 <h1> */}
      <div data-testid="stock-table">
        <DataTable label={t('stock.title')} columns={columns} data={stock.data?.items ?? []} loading={stock.isLoading} />
      </div>
    </div>
  )
}
```

入库表单是 react-hook-form（`useForm`、`Form` / `FormField` / `FormItem` / `FormLabel` / `FormControl` / `FormMessage`，都由 `@jabiz/admin` 导出）加 `Input` / `DecimalInput`（`scale={0}`）；
成功与失败用 `notify.success` / `notify.error`（应用根部的 `Toaster` 由平台外壳提供；页面测试中自己放一个 `<Toaster />`，外面包 `AppearanceProvider`）。

- 读数据：SQL 模板用 `runQuery`，数据视图用 `api.POST('/api/datasets/{id}/query', …)`，元数据用 `useEntityMeta` 等 hooks。
- 写数据：只经流程（`runProcess`）或数据视图接口；失败时 `ApiError` 带服务端的全部违规（`display`、`forField`）。
- 通用组件：`EntityFormDrawer`、`ReferenceSelect`、`FieldErrors`、`FilePreview`、`MarkdownView`、`ApprovalPanel`（批准 / 驳回一个审批请求）、`DocumentPanel`（一个对象的单据：已签发的副本、预览、签发，docs/design/22-documents.md §6）；
  我的待办用 `useMyTasks()`（18 §5.3）。
  这些通用组件都以 `@jabiz/ui` 构建，不需要外面包任何 provider（原来 antd 的 `App` 已不存在）；提示经 `notify`，需要应用根部的 `Toaster`（平台外壳已有）。
  `EntityFormDrawer` 是右侧的 Radix 模态 `Sheet`，表单值由 react-hook-form 保存，校验仍只走元数据的规则；
  `ReferenceSelect` 是远程 `Combobox`（`role="combobox"`，选项 `role="option"`），新增 `id`、`aria-label` / `aria-labelledby` / `aria-describedby` /
  `aria-invalid` / `aria-required` 与 `placeholder`（以 `<Label htmlFor={id}>` 命名）；`FieldErrors` 新增可选的 `id`（输入框以 `aria-describedby` 指向它），
  每条违规仍带 `data-rule-code`，不再借用 antd 的类名；`FilePreview` 的图片点击（或旁边"放大预览"按钮）在 `Dialog` 中放大，非图片为下载按钮。`FilePreview` 与 `MarkdownView` 16n 起在 `@jabiz/ui` 中（应用 SPA 也可用），`@jabiz/admin` 照旧以同名、同属性导出，扩展不用改。
  e2e 中原来按 antd 类名找的，改为 `getByTestId('entity-form')`（表单）、`getByRole('option')`（引用的选项）、`getByRole('alertdialog')`（确认）。
  `ApprovalPanel`、`DocumentPanel` 的 props 与 antd 时期相同；`DocumentPanel` 的发送对话框是 Radix `Dialog`（名称"以邮件发送"），地址是 `TagsInput`。
- `@jabiz/ui` 的组合组件（阶段 15b-1 起，经 `@jabiz/admin` 一并导出）：`Combobox`（可搜索的选择，静态或远程）、`FilterBar`（列表上方的筛选：文本、选项、数值 / 时间 / 日期区间）、
  `FileUpload`（真实的文件输入，上传前查类型与大小）、`Timeline`、`DescriptionList`、`TagsInput`、`CopyButton`、`PageState`（加载 / 不存在 / 出错 / 警告）、`Spinner`；
  `DatePicker` / `DateTimePicker` 可以键入（应用区域的格式，ISO `YYYY-MM-DD` / 本地时间 `YYYY-MM-DD HH:mm:ss` 也可；失焦或 Enter 读取；无效时 `aria-invalid`、框下说明，
  不交给调用方而经 `onInvalidChange(true)` 告知，表单据此不提交；`resetKey` 改变时丢弃键入的文字），
  `disabledDays` 限定可选的日子，`DateTimePicker` 的 `toDate` 限定最晚时刻；原来的按钮式触发改为文本框加"打开日历"按钮，`id` / `aria-*` 落在文本框上。
  `DataTable` 的列可用 `meta: { className, pinned: 'right' }`，数据行带 `data-slot="data-table-row"`。
  表单状态用 `useForm`、`useFieldArray`、`useWatch`、`Controller`（react-hook-form，由 `@jabiz/ui` 再导出，扩展不另加依赖）；字段规则仍只来自元数据。
- 登录与账户的积木（16c-1 起，`@jabiz/ui` 的 `auth/`，经 `@jabiz/admin` 一并导出，见 12 §12）：扩展页面里可直接用的是 `ChangePasswordForm`、
  `MfaEnrollment`、`VerifyEmailBanner`、`OfflineBanner`；登录、注册、邮件链接页与 step-up 由后台自己提供，扩展不要另写。
  文案在平台的命名空间 `auth`（三语）。`useAuth()` 另有 `entry`、`email`、`emailVerified` 与
  `refreshSession({ force: true })`（验证邮箱后取新令牌）。
- 不要 import `frontend/src/...`，也不要直接 import `@jabiz/ui`、`@jabiz/client`（16w-1 起 lint 都会拒绝，`import()` 与 `require()` 也一样，并提示改用 `@jabiz/admin`）。
  `@jabiz/admin` 导出 `@jabiz/ui` 的全部，但 `@jabiz/client` 只导出其中给扩展用的部分（接口与流程的调用、`useAuth`、格式化函数、`UserName` 等，见 `frontend/src/lib/index.ts`）；
  缺的先加进 `@jabiz/admin`（第 6 节）；
  不要自己存令牌，不要访问后端以外的地址。

## 4. 测试

页面测试与平台前端相同（Vitest + Testing Library）；调用可用 `vi.mock('@jabiz/admin', …)` 替换，示范见
`backend/app/admin-extension/src/StockOverviewPage.test.tsx`。端到端测试放在应用自己的 Playwright 目录，或（平台示范）`frontend/e2e/`。

无障碍检查（16w-1）：测试从 `@jabiz/admin/testing` 取 `expectAccessible`（即 `@jabiz/ui/testing` 的，亮暗两种外观各过一次 axe，jsdom 中不查颜色对比度），不复制辅助函数：

```tsx
import { expectAccessible } from '@jabiz/admin/testing'

it('passes axe in the light and the dark appearance', async () => {
  renderPage()
  await screen.findByText('Bolt')
  await expectAccessible()
})
```

`@jabiz/admin/testing` 只能在测试中引用：`*.test.*`、`*.spec.*`，以及 **`test/` 目录下的共用辅助文件**（如 `src/test/render.tsx`）——
几个测试共用的辅助函数放在那里，不要命名为 `*.test-utils.*` 或放在页面旁边（那些算生产代码，lint 拒绝其中的 `@jabiz/admin/testing`；`*.stories.*` 也是）。
它带入 Vitest 与 vitest-axe：`pnpm ext:check` 的 `bundle` 步骤（`pnpm ext:bundle`）带扩展构建一次并检查产出的 JavaScript 中没有它们，通用后台的每次构建也如此检查。
`@jabiz/ui/testing` 不直接引用（lint 提示改用 `@jabiz/admin/testing`）。没有 Playwright 的版本。

e2e 按角色、名称或稳定的 `data-*` 找元素（平台的辅助函数在 `frontend/e2e/support.ts`）；平台的 e2e 由 lint 拒绝 `.ant-*` 选择器。
按钮名称写精确的文字（`{ name: '查询', exact: true }`），不再写为 antd 两字按钮准备的 `/查\s*询/`。从 antd 时期的写法改过来的对照：

| 原来（antd） | 现在（`@jabiz/ui`） |
|---|---|
| `locator('tr.ant-table-row')` | `dataRows(scope)`（`[data-slot="data-table-row"]`）；展开行用 `getByRole('button', { name: '展开行' })` |
| `locator('.ant-modal')`、`.ant-drawer` | `dialog(page, 标题)`（`role="dialog"`，含 `Sheet`） |
| `locator('.ant-select-item-option')` | 先点 `getByRole('combobox', { name })`，再 `option(page, 名称)` |
| `Popconfirm` 的"确定" | `confirm(page)`；按钮另有文字时 `confirmWith(page, '签发')`（`role="alertdialog"`） |

在 `frontend/` 下：

```sh
JABIZ_ADMIN_EXTENSION=../finance-web pnpm ext:check        # 类型、lint、测试、生产包中没有测试代码
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
