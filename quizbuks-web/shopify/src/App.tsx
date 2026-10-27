import { AuthProvider, useAuth } from '@jabiz/client'
import {
  Alert,
  AlertDescription,
  Badge,
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@jabiz/ui'
import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query'
import { useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { check, connect, join, transferAdmin, type Shop } from './api'
import { NotEmbeddedError, sessionToken } from './appBridge'
import { SHOPIFY } from './messages'

/**
 * QuizBuks for Shopify's embedded pages (docs/quizbuks/shopify/S1-install.md section 6): sign in with Shopify's
 * session token (the platform's external token sign-in, decision D59), join the store (QBS_SHOP_JOIN, whose answer is
 * what the page shows), and the store's overview: its connection to Shopify and its members, the admin handing the
 * role on. Every rule is the server's; the page only offers what it may.
 */
export default function App() {
  const [queryClient] = useState(() => new QueryClient({ defaultOptions: { queries: { retry: false } } }))
  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <main className="mx-auto max-w-3xl p-4">
          <ShopPage />
        </main>
      </AuthProvider>
    </QueryClientProvider>
  )
}

function reasonOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

function ShopPage() {
  const { t } = useTranslation(SHOPIFY)
  const auth = useAuth()
  // The latest auth state for the async work below (signing in changes it while that work runs).
  const authRef = useRef(auth)
  useEffect(() => {
    authRef.current = auth
  })

  // Signs in when not yet (a session token is used once) and joins the store: its answer is the overview.
  const opened = useQuery({
    queryKey: ['qbs.shop'],
    enabled: auth.ready,
    // Opening runs a process: only when the page opens and when asked, never on focus.
    staleTime: Infinity,
    refetchOnWindowFocus: false,
    queryFn: async (): Promise<Shop> => {
      if (!authRef.current.signedIn) {
        const step = await authRef.current.signInWithExternalToken(await sessionToken())
        if (step.status !== 'SIGNED_IN') throw new Error(step.status)
      }
      return join()
    },
  })
  const reload = useCallback(async () => {
    await opened.refetch()
  }, [opened])

  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-2xl font-semibold">{t('title')}</h1>
      {(opened.isPending || (opened.isFetching && !opened.data)) && <p role="status">{t('starting')}</p>}
      {opened.error instanceof NotEmbeddedError && (
        <Alert>
          <AlertDescription>{t('notEmbedded')}</AlertDescription>
        </Alert>
      )}
      {opened.error && !(opened.error instanceof NotEmbeddedError) && !opened.isFetching && (
        <Alert variant="destructive">
          <AlertDescription className="flex flex-col gap-2">
            <span>{t('failed', { reason: reasonOf(opened.error) })}</span>
            <Button variant="outline" className="self-start" onClick={() => void reload()}>
              {t('retry')}
            </Button>
          </AlertDescription>
        </Alert>
      )}
      {opened.data && <Overview shop={opened.data} userId={auth.userId} reload={reload} />}
    </div>
  )
}

function Overview({ shop, userId, reload }: { shop: Shop; userId: string | null; reload: () => Promise<void> }) {
  const { t } = useTranslation(SHOPIFY)
  const [notice, setNotice] = useState<string | null>(null)
  const [problem, setProblem] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const admin = shop.role === 'ADMIN'

  async function act(work: () => Promise<string | null>) {
    setBusy(true)
    setProblem(null)
    setNotice(null)
    try {
      const done = await work()
      await reload()
      setNotice(done)
    } catch (error) {
      setProblem(reasonOf(error))
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      {shop.reinstalled && (
        <Alert>
          <AlertDescription>{t('reinstalled')}</AlertDescription>
        </Alert>
      )}
      <Card>
        <CardHeader>
          <CardTitle>{shop.shopName ?? shop.shopDomain}</CardTitle>
          <CardDescription>
            {t('shop')}: {shop.shopDomain}
          </CardDescription>
        </CardHeader>
        <CardContent>
          <p>{t(`you.${shop.role}`)}</p>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>{t('connection.title')}</CardTitle>
          <CardDescription>{t('connection.explain')}</CardDescription>
        </CardHeader>
        <CardContent className="flex flex-wrap items-center gap-3">
          <Badge variant={shop.connection === 'CONNECTED' ? 'default' : 'secondary'}>
            {t(`connection.${shop.connection}`)}
          </Badge>
          {shop.connection === 'CONNECTED' ? (
            <Button variant="outline" disabled={busy} onClick={() => void act(async () => {
              const state = await check()
              return state.connection === 'CONNECTED' ? t('connection.checked') : t('connection.lost')
            })}>
              {t('connection.check')}
            </Button>
          ) : (
            <Button disabled={busy} onClick={() => void act(async () => {
              await connect()
              return null
            })}>
              {t('connection.connect')}
            </Button>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>{t('members.title')}</CardTitle>
        </CardHeader>
        <CardContent>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t('members.staff')}</TableHead>
                <TableHead>{t('members.role')}</TableHead>
                <TableHead>{t('members.joined')}</TableHead>
                {admin && <TableHead>{t('members.actions')}</TableHead>}
              </TableRow>
            </TableHeader>
            <TableBody>
              {shop.members.map((member) => (
                <TableRow key={member.userId}>
                  <TableCell>
                    {member.staffId} {member.userId === userId && t('members.me')}
                  </TableCell>
                  <TableCell>{t(`members.${member.role}`)}</TableCell>
                  <TableCell>{new Date(member.joinedAt).toLocaleString()}</TableCell>
                  {admin && (
                    <TableCell>
                      {member.role !== 'ADMIN' && (
                        <Button size="sm" variant="outline" disabled={busy} onClick={() => {
                          if (!window.confirm(t('members.confirm', { staff: member.staffId }))) return
                          void act(async () => {
                            await transferAdmin(member.userId)
                            return t('members.transferred')
                          })
                        }}>
                          {t('members.makeAdmin')}
                        </Button>
                      )}
                    </TableCell>
                  )}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      {notice && <p role="status">{notice}</p>}
      {problem && (
        <Alert variant="destructive">
          <AlertDescription>{problem}</AlertDescription>
        </Alert>
      )}
    </>
  )
}
