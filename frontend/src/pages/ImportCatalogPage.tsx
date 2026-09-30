import { PageContainer } from '@ant-design/pro-components'
import { Card, Empty, List, Spin, Typography } from 'antd'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { useImportCatalog } from '../meta/hooks'
import { paths } from './paths'

/** The imports the user may run (docs/design/20-imports.md section 6); the server checks the permissions again. */
export default function ImportCatalogPage() {
  const { t } = useTranslation()
  const catalog = useImportCatalog()
  if (catalog.isLoading) return <Spin style={{ margin: 48 }} />
  const imports = catalog.data ?? []
  return (
    <PageContainer
      title={t('imports.title')}
      extra={[<Link key="runs" to={paths.importRuns()} data-testid="import-history-link">{t('imports.runs.title')}</Link>]}
    >
      {imports.length === 0 ? <Empty description={t('imports.empty')} /> : (
        <Card>
          <List
            dataSource={imports}
            renderItem={(entry) => (
              <List.Item>
                <List.Item.Meta
                  title={<Link to={paths.importRun(entry.id!)} data-testid={`import-${entry.id}`}>{entry.title}</Link>}
                  description={
                    <Typography.Text type="secondary">
                      {(entry.fields ?? []).map((f) => f.label).join(' · ')}
                    </Typography.Text>
                  }
                />
                <Typography.Text type="secondary">{(entry.extensions ?? []).join(' ')}</Typography.Text>
              </List.Item>
            )}
          />
        </Card>
      )}
    </PageContainer>
  )
}
