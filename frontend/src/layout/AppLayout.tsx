import { DatabaseOutlined, LogoutOutlined, NodeIndexOutlined, TranslationOutlined, UserOutlined } from '@ant-design/icons'
import { ProLayout, type MenuDataItem } from '@ant-design/pro-components'
import { Dropdown, Space } from 'antd'
import { useTranslation } from 'react-i18next'
import { Link, Outlet, useLocation, useNavigate } from 'react-router'
import { useAuth } from '../auth/AuthContext'
import { changeLanguage, languages, type Language } from '../i18n'
import { useMenus } from '../meta/hooks'
import type { MenuItem } from '../meta/types'

const LANGUAGE_NAMES: Record<Language, string> = { zh: '中文', ja: '日本語', en: 'English' }

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
 * The frame of every signed-in page: the dynamic menu (docs/design/10-security.md section 3) followed by the two
 * catalogs, which list only what the user may use. Hiding is navigation, not access: every call is checked again.
 */
export default function AppLayout() {
  const { t, i18n } = useTranslation()
  const { userId, signOut } = useAuth()
  const menus = useMenus()
  const location = useLocation()
  const navigate = useNavigate()

  const routes: MenuDataItem[] = [
    ...toRoutes(menus.data),
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
      actionsRender={() => [
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
