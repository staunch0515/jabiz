import { ApiError, EXTENSION_NAMESPACE, formatDateTime, paths, useAuth } from '@jabiz/admin'
import { Alert, Button, Card, Descriptions, Form, Input, Space, Table, Typography } from 'antd'
import { useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { downloadPackage, issuePackage, PERMISSIONS, QUERIES, saveAnswer, type Downloaded, type PackageInput,
  type PackageOutput, type PackageReport } from './api'

/** A refusal's messages, one per violation. */
const messages = (e: unknown): string[] => {
  if (e instanceof ApiError) return e.violations.length > 0 ? e.violations.map((v) => v.message) : [e.message]
  return [String(e)]
}

const DAY = /^\d{4}-\d{2}-\d{2}$/
/** A time in UTC as the server reads it (ISO-8601 with Z). */
const INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d{1,9})?)?Z$/

/**
 * The audit evidence package (FIN-CT-012; ROADMAP F10 decision D5, F10c): the controller enters the auditor's request
 * (the manual entries of a period above an amount, optionally the access review as of a time and a signed-off bank
 * reconciliation) and issues its reports in one operation; downloads the package through the platform's export,
 * exactly those reports with the entries and approvals and a manifest; and gets what the auditor checks it with
 * outside the system: the package's SHA-256, the process's answer and the command of tools/finance/verify-package.py.
 */
export default function AuditPackagePage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const [form] = Form.useForm<PackageInput>()
  const [answer, setAnswer] = useState<PackageOutput | null>(null)
  const [downloaded, setDownloaded] = useState<Downloaded | null>(null)
  const [errors, setErrors] = useState<string[]>([])
  const [busy, setBusy] = useState(false)
  // One key per request: the same request issued again is the same operation (as the journal entry page does).
  const last = useRef<{ body: string; key: string } | null>(null)
  const keyFor = (input: PackageInput) => {
    const body = JSON.stringify(input)
    if (last.current?.body !== body) last.current = { body, key: crypto.randomUUID() }
    return last.current.key
  }

  const act = async (action: () => Promise<void>) => {
    setBusy(true)
    setErrors([])
    try {
      await action()
    } catch (e) {
      setErrors(messages(e))
    } finally {
      setBusy(false)
    }
  }

  const issue = (input: PackageInput) => act(async () => {
    setAnswer(null)
    setDownloaded(null)
    setAnswer(await issuePackage(input, keyFor(input)))
  })

  const download = () => act(async () => {
    if (answer) setDownloaded(await downloadPackage(answer.export))
  })

  // A package shown is the request's as issued: changing the request takes it away.
  const changed = () => {
    setAnswer(null)
    setDownloaded(null)
  }

  const reportName = (r: PackageReport) => // Template ids hold dots, which the texts take for nesting.
    t(`auditPackage.template.${r.templateId.replaceAll('.', '_')}`, { defaultValue: r.templateId })

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Typography.Title level={3} style={{ margin: 0 }} data-testid="page-title">{t('auditPackage.title')}</Typography.Title>
      <Typography.Paragraph type="secondary" style={{ margin: 0 }}>
        {t('auditPackage.intro')}{' '}
        {can(PERMISSIONS.manualEntries) && (
          <Link to={paths.report(QUERIES.manualEntries)} data-testid="manual-entries-report">
            {t('auditPackage.manualEntriesReport')}
          </Link>
        )}
      </Typography.Paragraph>
      {errors.length > 0 && (
        <Alert type="error" showIcon data-testid="errors"
          message={errors.length === 1 ? errors[0] : <ul style={{ margin: 0 }}>{errors.map((m) => <li key={m}>{m}</li>)}</ul>} />
      )}
      <Card title={t('auditPackage.request')}>
        <Form<PackageInput> form={form} layout="vertical" onFinish={issue} onValuesChange={changed}
          disabled={!can(PERMISSIONS.package)}>
          <Form.Item name="request" label={t('auditPackage.requestText')} rules={[{ required: true }, { max: 500 }]}>
            <Input.TextArea rows={2} maxLength={500} data-testid="field-request" />
          </Form.Item>
          <Space wrap align="start">
            <Form.Item name="from" label={t('auditPackage.from')} rules={[{ required: true }, { pattern: DAY }]}>
              <Input placeholder="YYYY-MM-DD" style={{ width: 140 }} data-testid="field-from" />
            </Form.Item>
            <Form.Item name="to" label={t('auditPackage.to')} rules={[{ required: true }, { pattern: DAY }]}>
              <Input placeholder="YYYY-MM-DD" style={{ width: 140 }} data-testid="field-to" />
            </Form.Item>
            <Form.Item name="minAmount" label={t('auditPackage.minAmount')}
              rules={[{ required: true }, { pattern: /^\d+(\.\d{1,2})?$/ }]}>
              <Input style={{ width: 140 }} data-testid="field-minAmount" />
            </Form.Item>
          </Space>
          <Space wrap align="start">
            <Form.Item name="accessReviewAsOf" label={t('auditPackage.accessReviewAsOf')}
              extra={t('auditPackage.accessReviewHint')} rules={[{ pattern: INSTANT }]}>
              <Input placeholder="2026-02-01T06:00:00Z" style={{ width: 240 }} data-testid="field-accessReviewAsOf" />
            </Form.Item>
            <Form.Item name="bankCode" label={t('auditPackage.bankCode')}>
              <Input style={{ width: 140 }} data-testid="field-bankCode" />
            </Form.Item>
            <Form.Item name="statementDate" label={t('auditPackage.statementDate')} rules={[{ pattern: DAY }]}>
              <Input placeholder="YYYY-MM-DD" style={{ width: 140 }} data-testid="field-statementDate" />
            </Form.Item>
          </Space>
          {can(PERMISSIONS.package) && (
            <Button type="primary" htmlType="submit" loading={busy} data-testid="issue">{t('auditPackage.issue')}</Button>
          )}
        </Form>
      </Card>
      {answer && (
        <Card title={t('auditPackage.issued', { time: formatDateTime(answer.issuedTime) })} data-testid="package">
          <Table<PackageReport> rowKey="runId" size="small" pagination={false} dataSource={answer.reports}
            data-testid="reports"
            columns={[
              { title: t('auditPackage.report'), key: 'name',
                render: (_: unknown, r) => <Link to={paths.reportArchive(r.templateId)}>{reportName(r)}</Link> },
              { title: t('auditPackage.rows'), dataIndex: 'rows', align: 'right' },
              { title: t('auditPackage.run'), dataIndex: 'runId',
                render: (runId: string) => <span data-testid={`run-${runId}`}>{runId}</span> },
              { title: t('auditPackage.contentHash'), dataIndex: 'contentHash',
                render: (hash: string) => <Typography.Text code copyable>{hash}</Typography.Text> },
            ]} />
          <Space wrap style={{ marginTop: 16 }}>
            {PERMISSIONS.export.every((p) => can(p)) && (
              <Button type="primary" onClick={download} loading={busy} data-testid="download">
                {t('auditPackage.download')}
              </Button>
            )}
            <Button onClick={() => saveAnswer(answer)} data-testid="save-answer">{t('auditPackage.saveAnswer')}</Button>
          </Space>
          {downloaded && (
            <Descriptions column={1} size="small" style={{ marginTop: 16 }} data-testid="downloaded">
              <Descriptions.Item label={t('auditPackage.file')}>{downloaded.fileName}</Descriptions.Item>
              <Descriptions.Item label={t('auditPackage.sha256')}>
                <Typography.Text code copyable data-testid="package-sha256">{downloaded.sha256}</Typography.Text>
              </Descriptions.Item>
              <Descriptions.Item label={t('auditPackage.verify')}>
                <Typography.Text code copyable data-testid="verify-command">
                  {`python3 verify-package.py ${downloaded.fileName} --expect package.json --package-sha256 ${downloaded.sha256}`}
                </Typography.Text>
              </Descriptions.Item>
            </Descriptions>
          )}
        </Card>
      )}
    </Space>
  )
}
