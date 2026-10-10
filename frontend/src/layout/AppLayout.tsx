import {
  AppShell,
  Badge,
  Button,
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
  ShellNav,
  ThemeToggle,
  Tooltip,
  TooltipContent,
  TooltipTrigger,
  UI_NAMESPACE,
  type ShellMenuItem,
} from '@jabiz/ui'
import {
  Archive,
  ChartColumn,
  Database,
  FileCheck,
  Languages,
  ListChecks,
  LogOut,
  ScrollText,
  ShieldCheck,
  ShieldEllipsis,
  Upload,
  User,
  Users,
  Workflow,
} from 'lucide-react'
import { useCallback } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, Outlet, useLocation, useNavigate } from 'react-router'
import { refreshSession } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import { StepUpProvider } from '../auth/StepUp'
import { useIdleLock } from '../auth/useIdleLock'
import { extension } from '../extension'
import { EXTENSION_NAMESPACE } from '../extension/api'
import { extensionMenu } from '../extension/registry'
import { changeLanguage, languages, type Language } from '../i18n'
import { LANGUAGE_NAMES } from '../i18n/languages'
import { useImportCatalog, useMenus, useMyTasks, useQueryCatalog } from '../meta/hooks'
import type { MenuItem } from '../meta/types'

/** Menu items from the server (SecMenu, already filtered by permission) as entries of the shell's menu. */
function serverMenu(items: MenuItem[] | undefined): ShellMenuItem[] {
  return (items ?? []).map((item) => ({
    key: item.code ?? '',
    label: item.label ?? item.code ?? '',
    path: item.path || `/menu/${item.code}`,
    children: item.children && item.children.length > 0 ? serverMenu(item.children) : undefined,
  }))
}

/** Whether `path` is the current page or a page under it. */
function activeIn(pathname: string) {
  return (path: string) => pathname === path || pathname.startsWith(`${path}/`)
}

/**
 * The frame of every signed-in page: the dynamic menu (docs/design/10-security.md section 3), the application's own
 * menu entries (decision D22), the user's tasks (with their count in the header), the reports, the imports and the
 * two catalogs, which list only what the user may use. Hiding is navigation, not access: every call is checked again.
 *
 * Built with @jabiz/ui (decision D34). The pages inside are still Ant Design's until phases 15b and 15c, and stay
 * in the light appearance (`light`) whatever the user chose, for Ant Design's colours assume a light background.
 */
export default function AppLayout() {
  const { t, i18n } = useTranslation()
  const { userId, displayName, signOut, can, idleTimeoutSeconds, dataPeriod } = useAuth()
  const menus = useMenus()
  const tasks = useMyTasks()
  const catalog = useQueryCatalog()
  const hasReports = (catalog.data ?? []).some((q) => q.report)
  const hasImports = (useImportCatalog().data ?? []).length > 0
  const openTasks = tasks.data?.total ?? 0
  const location = useLocation()
  const navigate = useNavigate()

  // Locked after inactivity (docs/design/10-security.md section 11): signed out, back to the sign-in page.
  const lockIdle = useCallback(() => {
    void signOut().then(() => navigate('/login', { state: { idle: true } }))
  }, [signOut, navigate])
  const keepAlive = useCallback(() => void refreshSession(), [])
  useIdleLock(idleTimeoutSeconds, lockIdle, keepAlive)

  const items: ShellMenuItem[] = [
    ...serverMenu(menus.data),
    ...extensionMenu(extension.menu, can, (key) => t(key, { ns: EXTENSION_NAMESPACE })),
    { key: 'tasks', label: t('nav.tasks'), path: '/tasks', icon: ListChecks, badge: openTasks },
    // Only when there is a report the user may run (docs/design/19-reports.md section 3.3).
    ...(hasReports ? [{ key: 'reports', label: t('nav.reports'), path: '/reports', icon: ChartColumn }] : []),
    // Issued documents, for those who may read them (docs/design/22-documents.md section 6).
    ...(can('document.archive.read')
      ? [{ key: 'documents', label: t('nav.documents'), path: '/documents', icon: FileCheck }]
      : []),
    // Only when there is an import the user may run (docs/design/20-imports.md section 6).
    ...(hasImports ? [{ key: 'imports', label: t('nav.imports'), path: '/imports', icon: Upload }] : []),
    // Only for auditors (docs/design/21-audit-retention.md section 1).
    ...(can('audit.read') ? [{ key: 'audit', label: t('nav.audit'), path: '/audit', icon: ScrollText }] : []),
    // The seals (docs/design/21-audit-retention.md section 2.4).
    ...(can('integrity.read')
      ? [{ key: 'integrity', label: t('nav.integrity'), path: '/integrity', icon: ShieldCheck }]
      : []),
    // The periodic access review (docs/design/10-security.md section 13.3).
    ...(can('security.access-review.read')
      ? [{ key: 'access-review', label: t('nav.accessReview'), path: '/access-review', icon: Users }]
      : []),
    // Retention, legal holds and the export (docs/design/21-audit-retention.md sections 3 and 4).
    ...(['retention.read', 'legal.hold.read', 'legal.hold.write', 'data.export'].some((p) => can(p))
      ? [{ key: 'retention', label: t('nav.retention'), path: '/retention', icon: Archive }]
      : []),
    { key: 'data', label: t('nav.datasets'), path: '/data', icon: Database },
    { key: 'processes', label: t('nav.processes'), path: '/processes', icon: Workflow },
  ]

  const formatDay = (value: string | null | undefined) =>
    value ? new Date(value).toLocaleDateString(i18n.language) : '…'

  const header = (
    <div className="ml-auto flex items-center gap-1">
      {/* A session limited to a period says so: the lists and reports show that period only (section 13.2). */}
      {dataPeriod && (
        <Tooltip>
          <TooltipTrigger asChild>
            <Badge variant="warning" tabIndex={0} data-testid="data-period">
              {t('app.dataPeriod', { from: formatDay(dataPeriod.from), to: formatDay(dataPeriod.to) })}
            </Badge>
          </TooltipTrigger>
          <TooltipContent>{t('app.dataPeriodHint')}</TooltipContent>
        </Tooltip>
      )}
      <Button variant="ghost" size="sm" asChild>
        <Link to="/tasks" aria-label={t('tasks.open', { count: openTasks })} data-testid="task-count">
          <ListChecks aria-hidden />
          {openTasks > 0 && (
            <Badge className="h-5 min-w-5 rounded-full px-1 tabular-nums">{openTasks > 99 ? '99+' : openTasks}</Badge>
          )}
        </Link>
      </Button>
      {/* One language: nothing to switch (decision D22, item 7). */}
      {languages.length > 1 && (
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="ghost" size="sm" data-testid="language-switch">
              <Languages aria-hidden />
              <span className="sr-only">{t('app.language')}: </span>
              {LANGUAGE_NAMES[i18n.language as Language] ?? i18n.language}
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end">
            <DropdownMenuLabel>{t('app.language')}</DropdownMenuLabel>
            <DropdownMenuRadioGroup value={i18n.language} onValueChange={(lang) => void changeLanguage(lang as Language)}>
              {languages.map((lang) => (
                <DropdownMenuRadioItem key={lang} value={lang} lang={lang}>
                  {LANGUAGE_NAMES[lang]}
                </DropdownMenuRadioItem>
              ))}
            </DropdownMenuRadioGroup>
          </DropdownMenuContent>
        </DropdownMenu>
      )}
      <ThemeToggle />
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="ghost" size="sm">
            <User aria-hidden />
            <span data-testid="current-user">{displayName ?? userId}</span>
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end">
          <DropdownMenuItem onSelect={() => navigate('/account/security')}>
            <ShieldEllipsis aria-hidden />
            {t('app.security')}
          </DropdownMenuItem>
          <DropdownMenuSeparator />
          <DropdownMenuItem
            onSelect={() => {
              void signOut().then(() => navigate('/login'))
            }}
          >
            <LogOut aria-hidden />
            {t('app.logout')}
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  )

  return (
    <AppShell
      brand={
        <Link to="/" className="flex h-8 items-center px-2 text-base font-semibold">
          {t('app.title')}
        </Link>
      }
      navigation={
        <ShellNav
          label={t('sidebar', { ns: UI_NAMESPACE })}
          items={items}
          isActive={activeIn(location.pathname)}
          renderLink={(item, props) => <Link to={item.path} {...props} />}
        />
      }
      header={header}
      contentClassName="light bg-muted text-foreground"
    >
      <StepUpProvider>
        <Outlet />
      </StepUpProvider>
    </AppShell>
  )
}
