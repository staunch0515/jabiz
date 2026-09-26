import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { App as AntApp, ConfigProvider } from 'antd'
import enUS from 'antd/locale/en_US'
import jaJP from 'antd/locale/ja_JP'
import zhCN from 'antd/locale/zh_CN'
import { useTranslation } from 'react-i18next'
import { createBrowserRouter, Navigate, Outlet, RouterProvider, useLocation } from 'react-router'
import { ApiError } from './api/problem'
import { AuthProvider, useAuth } from './auth/AuthContext'
import AppLayout from './layout/AppLayout'
import DatasetCatalogPage from './pages/DatasetCatalogPage'
import DatasetListPage from './pages/DatasetListPage'
import EntityHistoryPage from './pages/EntityHistoryPage'
import LoginPage from './pages/LoginPage'
import ProcessCatalogPage from './pages/ProcessCatalogPage'
import ProcessFormPage from './pages/ProcessFormPage'
import { Spin } from 'antd'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // Client errors (403, 404, 400) do not get better by asking again.
      retry: (count, error) => !(error instanceof ApiError && error.status < 500) && count < 2,
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
  {
    element: <RequireSignIn />,
    children: [
      {
        element: <AppLayout />,
        children: [
          { index: true, element: <Navigate to="/data" replace /> },
          { path: '/data', element: <DatasetCatalogPage /> },
          { path: '/data/:datasetId', element: <DatasetListPage /> },
          { path: '/data/:datasetId/:entityId/history', element: <EntityHistoryPage /> },
          { path: '/processes', element: <ProcessCatalogPage /> },
          { path: '/processes/:name/:version', element: <ProcessFormPage /> },
          { path: '*', element: <Navigate to="/data" replace /> },
        ],
      },
    ],
  },
])

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
