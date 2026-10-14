import { ApiError, AuthProvider, useAuth } from '@jabiz/client'
import { AppearanceProvider, PageState, Toaster } from '@jabiz/ui'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createBrowserRouter, Navigate, Outlet, RouterProvider, useLocation, type RouteObject } from 'react-router'
import { routerBasename } from './base'
import { extension } from './extension'
import { homePath } from './extension/registry'
import AppLayout from './layout/AppLayout'
import AccountSecurityPage from './pages/AccountSecurityPage'
import AccessReviewPage from './pages/AccessReviewPage'
import SessionsPage from './pages/SessionsPage'
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
import MailUnsubscribePage from './pages/MailUnsubscribePage'
import OidcCallbackPage from './pages/OidcCallbackPage'
import ProcessCatalogPage from './pages/ProcessCatalogPage'
import ProcessFormPage from './pages/ProcessFormPage'
import ReportArchivePage from './pages/ReportArchivePage'
import RetentionPage from './pages/RetentionPage'
import ReportCatalogPage from './pages/ReportCatalogPage'
import ReportPage from './pages/ReportPage'
import TasksPage from './pages/TasksPage'
import JoinPage from './pages/auth/JoinPage'
import PasswordResetPage from './pages/auth/PasswordResetPage'
import VerifyEmailPage from './pages/auth/VerifyEmailPage'

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
  if (!ready) return <PageState kind="loading" className="min-h-screen" />
  if (!signedIn) return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />
  return <Outlet />
}

/** The routes of the admin; exported for the test of which pages require signing in. */
// eslint-disable-next-line react-refresh/only-export-components
export const appRoutes: RouteObject[] = [
  { path: '/login', element: <LoginPage /> },
  // Where identity providers send users back (docs/design/10-security.md section 12).
  { path: '/login/oidc', element: <OidcCallbackPage /> },
  // The unsubscribe link of notification mail: its token is the credential (18 section 5.6).
  { path: '/mail/unsubscribe', element: <MailUnsubscribePage /> },
  // The links of the account mail: verifying an address, joining an entry, resetting a password (10 section 15).
  { path: '/auth/verify-email', element: <VerifyEmailPage /> },
  { path: '/auth/join', element: <JoinPage /> },
  { path: '/auth/password-reset', element: <PasswordResetPage /> },
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
          { path: '/security/sessions', element: <SessionsPage /> },
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
]

const router = createBrowserRouter(appRoutes, { basename: routerBasename(import.meta.env.BASE_URL) })

export default function App() {
  // The appearance and the toasts are @jabiz/ui's (decision D34).
  return (
    <AppearanceProvider>
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <RouterProvider router={router} />
        </AuthProvider>
      </QueryClientProvider>
      <Toaster />
    </AppearanceProvider>
  )
}
