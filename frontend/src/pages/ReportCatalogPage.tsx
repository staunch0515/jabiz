import { PageContainer } from '@ant-design/pro-components'
import { Card, Empty, List, Spin, Typography } from 'antd'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
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
  if (catalog.isLoading) return <Spin style={{ margin: 48 }} />
  const groups = reportGroups(catalog.data ?? [])
  return (
    <PageContainer title={t('reports.title')}>
      {groups.length === 0 && <Empty description={t('reports.empty')} />}
      {groups.map(({ group, reports }) => (
        <Card key={group} title={group} style={{ marginBottom: 16 }} data-testid={`report-group-${group}`}>
          <List
            dataSource={reports}
            renderItem={(report) => (
              <List.Item>
                <List.Item.Meta
                  title={
                    <Link to={paths.report(report.id!)} data-testid={`report-${report.id}`}>
                      {report.title}
                    </Link>
                  }
                  description={
                    <Typography.Paragraph type="secondary" ellipsis={{ rows: 2 }} style={{ margin: 0 }}>
                      {report.description}
                    </Typography.Paragraph>
                  }
                />
              </List.Item>
            )}
          />
        </Card>
      ))}
    </PageContainer>
  )
}
