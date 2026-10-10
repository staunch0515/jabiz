import {
  Alert,
  AlertDescription,
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  DescriptionList,
  notify,
  PageState,
} from '@jabiz/ui'
import { TriangleAlert } from 'lucide-react'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useParams, useSearchParams } from 'react-router'
import { api, unwrap } from '../api/client'
import { ApiError } from '../api/problem'
import SchemaForm from '../components/SchemaForm'
import AdminPage from '../layout/AdminPage'
import { useProcesses } from '../meta/hooks'
import { InvalidJson, inputNodes, toProcessInput, type JsonSchema } from '../meta/processForm'
import type { Violation } from '../meta/types'

/**
 * Runs one process with a form generated from its input schema (docs/design/12-frontend.md section 6). Every
 * submission carries an Idempotency-Key made when the form opened, so a double click or a retried request runs the
 * process once (decision D4); the key changes after a success. A request answered with a step-up is sent again
 * with the same key by @jabiz/client once the user has confirmed.
 */
export default function ProcessFormPage() {
  const { t } = useTranslation()
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
  const [idempotencyKey, setIdempotencyKey] = useState(() => crypto.randomUUID())
  const [violations, setViolations] = useState<Violation[]>([])
  const [result, setResult] = useState<{ processSeqId?: number; output?: unknown } | null>(null)

  if (processes.isLoading) return <PageState kind="loading" />
  if (!process) return <PageState kind="notFound" titleAs="h1" title={`${name}@${version}`} />

  const run = async (values: Record<string, unknown>) => {
    setViolations([])
    let body: Record<string, unknown>
    try {
      body = toProcessInput(nodes, values)
    } catch (e) {
      if (e instanceof InvalidJson) {
        setViolations([{ field: e.path, ruleCode: 'INVALID_VALUE', message: t('process.invalidJson') }])
        return
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
      notify.success(t('process.succeeded'))
    } catch (e) {
      if (e instanceof ApiError) {
        setViolations(
          e.violations.length > 0 ? e.violations : [{ field: null, ruleCode: String(e.status), message: e.display }],
        )
        return
      }
      // No answer at all (the network, the server unreachable): said, not thrown out of the form's handler.
      notify.error(e instanceof Error && e.message ? e.message : t('app.error'))
    }
  }

  return (
    <AdminPage
      title={process.label}
      description={
        <>
          <code className="font-mono">{`${process.name} v${process.version}`}</code>
          {process.description && <span className="block">{process.description}</span>}
        </>
      }
      breadcrumb={[{ label: t('nav.processes'), to: '/processes' }, { label: process.label }]}
    >
      <Card>
        <CardContent className="flex flex-col gap-4">
          {violations.length > 0 && (
            <Alert variant="destructive" data-testid="process-error">
              <TriangleAlert aria-hidden />
              <AlertDescription>
                <ul className="list-disc pl-4">
                  {violations.map((v, i) => (
                    <li key={i} data-rule-code={v.ruleCode}>
                      {v.field ? `${v.field}: ` : ''}
                      {v.message}
                    </li>
                  ))}
                </ul>
              </AlertDescription>
            </Alert>
          )}
          <SchemaForm
            key={`${name}@${version}?${JSON.stringify(preset)}`}
            nodes={nodes}
            preset={preset}
            submitLabel={t('process.run')}
            submitTestId="process-run"
            onSubmit={run}
          />
        </CardContent>
      </Card>
      {result && (
        <Card data-testid="process-result">
          <CardHeader>
            <CardTitle>
              <h2>{t('process.result')}</h2>
            </CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-3">
            <DescriptionList
              items={[{ label: t('history.process'), value: t('process.operation', { seq: result.processSeqId }) }]}
            />
            <pre className="bg-muted overflow-x-auto rounded-md p-3 font-mono text-xs">
              {JSON.stringify(result.output, null, 2)}
            </pre>
          </CardContent>
        </Card>
      )}
    </AdminPage>
  )
}
