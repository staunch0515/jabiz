import {
  PageContainer,
  ProForm,
  ProFormDatePicker,
  ProFormDateTimePicker,
  ProFormDigit,
  ProFormGroup,
  ProFormList,
  ProFormSelect,
  ProFormSwitch,
  ProFormText,
  ProFormTextArea,
} from '@ant-design/pro-components'
import { Alert, App, Card, Descriptions, Result, Spin, Typography } from 'antd'
import { useMemo, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { useParams } from 'react-router'
import { api, unwrap } from '../api/client'
import { ApiError } from '../api/problem'
import { useProcesses } from '../meta/hooks'
import { InvalidJson, inputNodes, toProcessInput, type InputNode, type JsonSchema } from '../meta/processForm'
import type { Violation } from '../meta/types'

/** The schema's pattern, when this browser can compile it; otherwise the server checks it alone. */
function compiled(pattern: string | undefined): RegExp[] {
  if (!pattern) return []
  try {
    return [new RegExp(pattern, 'u')]
  } catch {
    return []
  }
}

function renderNode(node: InputNode, t: (key: string) => string, path: (string | number)[] = []): ReactNode {
  const name = [...path, node.name]
  const rules = node.required ? [{ required: true, message: `${node.name}: REQUIRED` }] : []
  const pattern = compiled(node.schema.pattern).map((regex) => ({ pattern: regex, message: `${node.name}: INVALID_VALUE` }))
  const common = { key: node.name, name: name.length === 1 ? node.name : name, label: node.name, rules: [...rules, ...pattern] }
  switch (node.kind) {
    case 'password':
      return <ProFormText.Password {...common} fieldProps={{ autoComplete: 'new-password' }} />
    case 'datetime':
      return <ProFormDateTimePicker {...common} fieldProps={{ style: { width: '100%' } }} />
    case 'date':
      return <ProFormDatePicker {...common} fieldProps={{ style: { width: '100%' } }} />
    case 'decimal':
      return <ProFormDigit {...common} fieldProps={{ stringMode: true, style: { width: '100%' } }} min={Number.MIN_SAFE_INTEGER} />
    case 'integer':
      return <ProFormDigit {...common} fieldProps={{ precision: 0, style: { width: '100%' } }} min={Number.MIN_SAFE_INTEGER} />
    case 'number':
      return <ProFormDigit {...common} fieldProps={{ style: { width: '100%' } }} min={Number.MIN_SAFE_INTEGER} />
    case 'boolean':
      return <ProFormSwitch {...common} />
    case 'enum':
      return <ProFormSelect {...common} options={(node.options ?? []).map((v) => ({ label: v, value: v }))} />
    case 'tags':
      return <ProFormSelect {...common} mode="tags" />
    case 'object':
      return (
        <ProFormGroup key={node.name} title={node.name}>
          {(node.children ?? []).map((child) => renderNode(child, t, name))}
        </ProFormGroup>
      )
    case 'list':
      return (
        <ProFormList
          key={node.name}
          name={node.name}
          label={node.name}
          creatorButtonProps={{ creatorButtonText: t('process.addItem') }}
          initialValue={node.required ? [{}] : []}
        >
          <ProFormGroup>{(node.children ?? []).map((child) => renderNode(child, t))}</ProFormGroup>
        </ProFormList>
      )
    case 'json':
      return <ProFormTextArea {...common} placeholder={t('process.json')} />
    default:
      return <ProFormText {...common} />
  }
}

/**
 * Runs one process with a form generated from its input schema (docs/design/12-frontend.md section 6). Every
 * submission carries an Idempotency-Key made when the form opened, so a double click or a retried request runs the
 * process once (decision D4); the key changes after a success.
 */
export default function ProcessFormPage() {
  const { t } = useTranslation()
  const { message } = App.useApp()
  const { name = '', version = '' } = useParams()
  const processes = useProcesses()
  const process = processes.data?.find((p) => p.name === name && String(p.version) === version)
  const nodes = useMemo(() => (process ? inputNodes(process.input as JsonSchema) : []), [process])
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
          {nodes.map((node) => renderNode(node, t))}
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
