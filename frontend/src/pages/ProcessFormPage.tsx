import { PageContainer, ProForm } from '@ant-design/pro-components'
import { Alert, App, Card, Descriptions, Result, Spin, Typography } from 'antd'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useParams, useSearchParams } from 'react-router'
import { api, unwrap } from '../api/client'
import { ApiError } from '../api/problem'
import { useProcesses } from '../meta/hooks'
import { renderNode } from '../components/SchemaInputs'
import { InvalidJson, inputNodes, toProcessInput, type JsonSchema } from '../meta/processForm'
import type { Violation } from '../meta/types'

/**
 * Runs one process with a form generated from its input schema (docs/design/12-frontend.md section 6). Every
 * submission carries an Idempotency-Key made when the form opened, so a double click or a retried request runs the
 * process once (decision D4); the key changes after a success.
 */
export default function ProcessFormPage() {
  const { t } = useTranslation()
  const { message } = App.useApp()
  const { name = '', version = '' } = useParams()
  const [searchParams] = useSearchParams()
  const processes = useProcesses()
  const process = processes.data?.find((p) => p.name === name && String(p.version) === version)
  const nodes = useMemo(() => (process ? inputNodes(process.input as JsonSchema) : []), [process])
  // The key of the instance an action was started on arrives as a query parameter named after its input.
  const preset = useMemo(() => {
    const values: Record<string, string> = {}
    for (const node of nodes) {
      const value = searchParams.get(node.name)
      if (value !== null && node.name === process?.actsOn?.input) values[node.name] = value
    }
    return values
  }, [nodes, searchParams, process])
  const fixed = useMemo(() => new Set(Object.keys(preset)), [preset])
  const [idempotencyKey, setIdempotencyKey] = useState(() => crypto.randomUUID())
  const [violations, setViolations] = useState<Violation[]>([])
  const [result, setResult] = useState<{ processSeqId?: number; output?: unknown } | null>(null)

  if (processes.isLoading) return <Spin style={{ margin: 48 }} />
  if (!process) return <Result status="404" title={`${name}@${version}`} />

  return (
    <PageContainer title={process.label} subTitle={`${process.name} v${process.version}`} content={process.description}>
      <Card>
        {violations.length > 0 && (
          <Alert
            type="error"
            showIcon
            style={{ marginBottom: 16 }}
            data-testid="process-error"
            message={
              <ul style={{ margin: 0, paddingLeft: 16 }}>
                {violations.map((v, i) => (
                  <li key={i} data-rule-code={v.ruleCode}>
                    {v.field ? `${v.field}: ` : ''}
                    {v.message}
                  </li>
                ))}
              </ul>
            }
          />
        )}
        <ProForm
          name="process"
          initialValues={preset}
          // Dates stay Dayjs until processForm converts them (ISO-8601 with offset, or YYYY-MM-DD for dates).
          dateFormatter={false}
          submitter={{ searchConfig: { submitText: t('process.run') }, resetButtonProps: false }}
          onFinish={async (values: Record<string, unknown>) => {
            setViolations([])
            let body: Record<string, unknown>
            try {
              body = toProcessInput(nodes, values)
            } catch (e) {
              if (e instanceof InvalidJson) {
                setViolations([{ field: e.path, ruleCode: 'INVALID_VALUE', message: t('process.invalidJson') }])
                return false
              }
              throw e
            }
            try {
              const response = await unwrap(
                api.POST('/api/processes/{name}/{version}', {
                  params: { path: { name, version }, header: { 'Idempotency-Key': idempotencyKey } },
                  body,
                }),
              )
              setResult(response)
              setIdempotencyKey(crypto.randomUUID())
              message.success(t('process.succeeded'))
              return true
            } catch (e) {
              if (e instanceof ApiError) {
                setViolations(e.violations.length > 0 ? e.violations : [{ field: null, ruleCode: String(e.status), message: e.display }])
                return false
              }
              throw e
            }
          }}
        >
          {nodes.map((node) => renderNode(node, t, [], fixed))}
        </ProForm>
      </Card>
      {result && (
        <Card title={t('process.result')} style={{ marginTop: 16 }} data-testid="process-result">
          <Descriptions column={1} size="small">
            <Descriptions.Item label={t('history.process')}>
              {t('process.operation', { seq: result.processSeqId })}
            </Descriptions.Item>
          </Descriptions>
          <Typography.Paragraph>
            <pre style={{ margin: 0 }}>{JSON.stringify(result.output, null, 2)}</pre>
          </Typography.Paragraph>
        </Card>
      )}
    </PageContainer>
  )
}
