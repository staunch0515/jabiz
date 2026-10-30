import { useAuth } from '@jabiz/client'
import { Button, Card, CardContent, CardHeader, CardTitle, PageState } from '@jabiz/ui'
import { Archive } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { ListSearch } from '../components/ListSearch'
import { matches } from '../components/useLocalList'
import AdminPage from '../layout/AdminPage'
import { useQueryCatalog } from '../meta/hooks'
import { reportGroups } from '../meta/reports'
import { paths } from './paths'

/**
 * The reports the user may run (docs/design/19-reports.md section 3.3): templates that declare `report`, grouped by
 * the first part of their id. Running one still needs the template's permissions on the server.
 */
export default function ReportCatalogPage() {
  const { t } = useTranslation()
  const catalog = useQueryCatalog()
  const { can } = useAuth()
  const [query, setQuery] = useState('')
  if (catalog.isLoading) return <PageState kind="loading" />
  const all = reportGroups(catalog.data ?? [])
  const total = all.reduce((sum, g) => sum + g.reports.length, 0)
  // Phase 17w: searched by title, id and description; a group without a match is left out.
  const groups = all
    .map((g) => ({ ...g, reports: g.reports.filter((r) => matches([r.title, r.id, r.description], query)) }))
    .filter((g) => g.reports.length > 0)
  const matched = groups.reduce((sum, g) => sum + g.reports.length, 0)
  return (
    <AdminPage
      title={t('reports.title')}
      actions={
        can('report.archive.read') && (
          <Button variant="outline" asChild>
            <Link to={paths.reportArchive()} data-testid="report-archive-link">
              <Archive aria-hidden />
              {t('reports.archive.title')}
            </Link>
          </Button>
        )
      }
    >
      {total > 0 && <ListSearch value={query} onChange={setQuery} matched={matched} total={total} />}
      {groups.length === 0 && <PageState kind="empty" title={total === 0 ? t('reports.empty') : t('list.noMatch')} />}
      {groups.map(({ group, reports }) => (
        <Card key={group} data-testid={`report-group-${group}`} className="gap-2">
          <CardHeader>
            <CardTitle>
              <h2 className="font-mono text-sm">{group}</h2>
            </CardTitle>
          </CardHeader>
          <CardContent>
            <ul className="divide-border divide-y">
              {reports.map((report) => (
                <li key={report.id} className="flex flex-col gap-0.5 py-2.5">
                  <Link
                    to={paths.report(report.id!)}
                    data-testid={`report-${report.id}`}
                    className="text-primary w-fit font-medium hover:underline"
                  >
                    {report.title}
                  </Link>
                  {report.description && (
                    <p className="text-muted-foreground line-clamp-2 text-sm">{report.description}</p>
                  )}
                </li>
              ))}
            </ul>
          </CardContent>
        </Card>
      ))}
    </AdminPage>
  )
}
