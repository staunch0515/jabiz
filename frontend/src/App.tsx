import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { App as AntApp, ConfigProvider } from 'antd'
import enUS from 'antd/locale/en_US'
import jaJP from 'antd/locale/ja_JP'
import zhCN from 'antd/locale/zh_CN'
import { useTranslation } from 'react-i18next'
import { createBrowserRouter, Navigate, Outlet, RouterProvider, useLocation } from 'react-router'
import { ApiError } from './api/problem'
import { routerBasename } from './base'
import { AuthProvider, useAuth } from './auth/AuthContext'
import { extension } from './extension'
import { homePath } from './extension/registry'
import AppLayout from './layout/AppLayout'
import AccountSecurityPage from './pages/AccountSecurityPage'
import AccessReviewPage from './pages/AccessReviewPage'
import AuditPage from './pages/AuditPage'
import DatasetCatalogPage from './pages/DatasetCatalogPage'
import DatasetListPage from './pages/DatasetListPage'
import DocumentsPage from './pages/DocumentsPage'
import EntityHistoryPage from './pages/EntityHistoryPage'
import ImportCatalogPage from './pages/ImportCatalogPage'
import ImportPage from './pages/ImportPage'
import ImportRunsPage from './pages/ImportRunsPage'
import IntegrityPage from './pages/IntegrityPage'
import LoginPage from './pages/LoginPage'
import OidcCallbackPage from './pages/OidcCallbackPage'
import ProcessCatalogPage from './pages/ProcessCatalogPage'
import ProcessFormPage from './pages/ProcessFormPage'
import ReportArchivePage from './pages/ReportArchivePage'
import RetentionPage from './pages/RetentionPage'
import ReportCatalogPage from './pages/ReportCatalogPage'
import ReportPage from './pages/ReportPage'
import TasksPage from './pages/TasksPage'
import { Spin } from 'antd'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // Client errors (403, 404, 400) do not get better by asking again, nor does a query stopped at its time limit
      // (503): asking again only loads the database further.
      retry: (count, error) => !(error instanceof ApiError && (error.status < 500 || error.status === 503))
        && count < 2,
      refetchOnWindowFocus: false,
    },
  },
})

function RequireSignIn() {
  const { ready, signedIn } = useAuth()
  const location = useLocation()
  if (!ready) return <Spin fullscreen />
  if (!signedIn) return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />
  return <Outlet />
}

const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  // Where identity providers send users back (docs/design/10-security.md section 12).
  { path: '/login/oidc', element: <OidcCallbackPage /> },
  {
    element: <RequireSignIn />,
    children: [
      {
        element: <AppLayout />,
        children: [
          { index: true, element: <Navigate to={homePath(extension)} replace /> },
          { path: '/data', element: <DatasetCatalogPage /> },
          { path: '/data/:datasetId', element: <DatasetListPage /> },
          { path: '/data/:datasetId/:entityId/history', element: <EntityHistoryPage /> },
          { path: '/processes', element: <ProcessCatalogPage /> },
          { path: '/processes/:name/:version', element: <ProcessFormPage /> },
          { path: '/tasks', element: <TasksPage /> },
          { path: '/imports', element: <ImportCatalogPage /> },
          { path: '/imports/run', element: <ImportPage /> },
          { path: '/imports/runs', element: <ImportRunsPage /> },
          { path: '/reports', element: <ReportCatalogPage /> },
          { path: '/reports/run', element: <ReportPage /> },
          { path: '/reports/archive', element: <ReportArchivePage /> },
          { path: '/documents', element: <DocumentsPage /> },
          { path: '/audit', element: <AuditPage /> },
          { path: '/access-review', element: <AccessReviewPage /> },
          { path: '/integrity', element: <IntegrityPage /> },
          { path: '/retention', element: <RetentionPage /> },
          { path: '/account/security', element: <AccountSecurityPage /> },
          // The application's own pages (decision D22), checked at startup not to take a platform path.
          ...(extension.routes ?? []),
          { path: '*', element: <Navigate to={homePath(extension)} replace /> },
        ],
      },
    ],
  },
], { basename: routerBasename(import.meta.env.BASE_URL) })

const antdLocales = { zh: zhCN, ja: jaJP, en: enUS } as const

export default function App() {
  const { i18n } = useTranslation()
  const locale = antdLocales[i18n.language as keyof typeof antdLocales] ?? zhCN
  return (
    <ConfigProvider locale={locale}>
      <AntApp>
        <QueryClientProvider client={queryClient}>
          <AuthProvider>
            <RouterProvider router={router} />
          </AuthProvider>
        </QueryClientProvider>
      </AntApp>
    </ConfigProvider>
  )
}
