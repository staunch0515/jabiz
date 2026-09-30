import {
  BarChartOutlined,
  CloudUploadOutlined,
  CheckSquareOutlined,
  DatabaseOutlined,
  LogoutOutlined,
  NodeIndexOutlined,
  TranslationOutlined,
  UserOutlined,
} from '@ant-design/icons'
import { ProLayout, type MenuDataItem } from '@ant-design/pro-components'
import { Badge, Dropdown, Space } from 'antd'
import { useTranslation } from 'react-i18next'
import { Link, Outlet, useLocation, useNavigate } from 'react-router'
import { useAuth } from '../auth/AuthContext'
import { extension } from '../extension'
import { EXTENSION_NAMESPACE } from '../extension/api'
import { extensionMenu } from '../extension/registry'
import { changeLanguage, languages, type Language } from '../i18n'
import { LANGUAGE_NAMES } from '../i18n/languages'
import { useImportCatalog, useMenus, useMyTasks, useQueryCatalog } from '../meta/hooks'
import type { MenuItem } from '../meta/types'


/** Menu items from the server (SecMenu, already filtered by permission) as ProLayout routes. */
function toRoutes(items: MenuItem[] | undefined): MenuDataItem[] {
  return (items ?? []).map((item) => ({
    key: item.code,
    name: item.label,
    path: item.path || `/menu/${item.code}`,
    children: item.children && item.children.length > 0 ? toRoutes(item.children) : undefined,
  }))
}

/**
 * The frame of every signed-in page: the dynamic menu (docs/design/10-security.md section 3), the application's own
 * menu entries (decision D22), the user's tasks (with their count in the header), the reports, the imports and the two catalogs,
 * which list only what the user may use. Hiding is navigation, not access: every call is checked again.
 */
export default function AppLayout() {
  const { t, i18n } = useTranslation()
  const { userId, signOut, can } = useAuth()
  const menus = useMenus()
  const tasks = useMyTasks()
  const catalog = useQueryCatalog()
  const hasReports = (catalog.data ?? []).some((q) => q.report)
  const hasImports = (useImportCatalog().data ?? []).length > 0
  const openTasks = tasks.data?.total ?? 0
  const location = useLocation()
  const navigate = useNavigate()

  const routes: MenuDataItem[] = [
    ...toRoutes(menus.data),
    ...extensionMenu(extension.menu, can, (key) => t(key, { ns: EXTENSION_NAMESPACE })),
    { key: 'tasks', name: t('nav.tasks'), path: '/tasks', icon: <CheckSquareOutlined /> },
    // Only when there is a report the user may run (docs/design/19-reports.md section 3.3).
    ...(hasReports ? [{ key: 'reports', name: t('nav.reports'), path: '/reports', icon: <BarChartOutlined /> }] : []),
    // Only when there is an import the user may run (docs/design/20-imports.md section 6).
    ...(hasImports ? [{ key: 'imports', name: t('nav.imports'), path: '/imports', icon: <CloudUploadOutlined /> }] : []),
    { key: 'data', name: t('nav.datasets'), path: '/data', icon: <DatabaseOutlined /> },
    { key: 'processes', name: t('nav.processes'), path: '/processes', icon: <NodeIndexOutlined /> },
  ]

  return (
    <ProLayout
      title={t('app.title')}
      logo={false}
      layout="mix"
      fixSiderbar
      location={{ pathname: location.pathname }}
      route={{ path: '/', routes }}
      menuItemRender={(item, dom) => (item.path ? <Link to={item.path}>{dom}</Link> : dom)}
      // One language: nothing to switch (decision D22, item 7).
      actionsRender={() => [
        <Link key="tasks" to="/tasks" aria-label={t('tasks.open', { count: openTasks })} data-testid="task-count">
          <Badge count={openTasks} size="small" overflowCount={99}>
            <CheckSquareOutlined />
          </Badge>
        </Link>,
        ...(languages.length < 2 ? [] : [
        <Dropdown
          key="language"
          menu={{
            selectedKeys: [i18n.language],
            items: languages.map((lang) => ({ key: lang, label: LANGUAGE_NAMES[lang] })),
            onClick: ({ key }) => void changeLanguage(key as Language),
          }}
        >
          <Space data-testid="language-switch" aria-label={t('app.language')}>
            <TranslationOutlined />
            {LANGUAGE_NAMES[i18n.language as Language] ?? i18n.language}
          </Space>
        </Dropdown>,
      ]),
      ]}
      avatarProps={{
        icon: <UserOutlined />,
        title: <span data-testid="current-user">{userId}</span>,
        size: 'small',
        render: (_, dom) => (
          <Dropdown
            menu={{
              items: [{ key: 'logout', icon: <LogoutOutlined />, label: t('app.logout') }],
              onClick: async () => {
                await signOut()
                navigate('/login')
              },
            }}
          >
            {dom}
          </Dropdown>
        ),
      }}
    >
      <Outlet />
    </ProLayout>
  )
}
