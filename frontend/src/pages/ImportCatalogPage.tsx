import { Button, Card, CardContent, PageState } from '@jabiz/ui'
import { History } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { ListSearch } from '../components/ListSearch'
import { matches } from '../components/useLocalList'
import AdminPage from '../layout/AdminPage'
import { useImportCatalog } from '../meta/hooks'
import { paths } from './paths'

/** The imports the user may run (docs/design/20-imports.md section 6); the server checks the permissions again. */
export default function ImportCatalogPage() {
  const { t } = useTranslation()
  const catalog = useImportCatalog()
  const [query, setQuery] = useState('')
  if (catalog.isLoading) return <PageState kind="loading" />
  const all = catalog.data ?? []
  // Phase 17w: searched by title, id and fields.
  const imports = all.filter((entry) => matches([entry.title, entry.id, ...(entry.fields ?? []).map((f) => f.label)],
    query))
  return (
    <AdminPage
      title={t('imports.title')}
      actions={
        <Button variant="outline" asChild>
          <Link to={paths.importRuns()} data-testid="import-history-link">
            <History aria-hidden />
            {t('imports.runs.title')}
          </Link>
        </Button>
      }
    >
      {all.length > 0 && (
        <ListSearch value={query} onChange={setQuery} matched={imports.length} total={all.length} />
      )}
      {imports.length === 0 ? (
        <PageState kind="empty" title={all.length === 0 ? t('imports.empty') : t('list.noMatch')} />
      ) : (
        <Card>
          <CardContent>
            <ul className="divide-border divide-y">
              {imports.map((entry) => (
                <li key={entry.id} className="flex flex-wrap items-start justify-between gap-x-4 gap-y-1 py-2.5">
                  <span className="flex min-w-0 flex-col gap-0.5">
                    <Link
                      to={paths.importRun(entry.id!)}
                      data-testid={`import-${entry.id}`}
                      className="text-primary w-fit font-medium hover:underline"
                    >
                      {entry.title}
                    </Link>
                    <span className="text-muted-foreground text-sm">
                      {(entry.fields ?? []).map((f) => f.label).join(' · ')}
                    </span>
                  </span>
                  <span className="text-muted-foreground font-mono text-xs">{(entry.extensions ?? []).join(' ')}</span>
                </li>
              ))}
            </ul>
          </CardContent>
        </Card>
      )}
    </AdminPage>
  )
}
