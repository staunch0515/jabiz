import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ApiError,
  EXTENSION_NAMESPACE,
  formatAmount,
  formatDate,
  formatDateTime,
  useAuth,
  UserName,
} from '@jabiz/admin'
import { Alert, App, Button, Card, Col, Input, Popconfirm, Row, Select, Space, Table, Tag, Typography } from 'antd'
import { useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router'
import { sum } from '../receivables/money'
import {
  acceptProposals,
  entryFromLine,
  loadBanks,
  loadBookItems,
  loadHistory,
  loadOpenLines,
  matchByHand,
  PERMISSIONS,
  propose,
  unmatch,
  type BookItem,
  type HistoryRow,
  type OpenLine,
  type Proposal,
} from './api'

const money = (value: unknown) => formatAmount(value as number | string | null | undefined, { scale: 2 })
const message = (e: unknown) => (e instanceof ApiError ? e.display : String(e))
const itemKey = (i: BookItem) => `${i.refKind}:${i.refId}`

/**
 * Matching a bank account's statement to its books (FIN-BK-004…006): the matches the server proposes, with their
 * confidence and reasons, accepted together; statement lines and book items matched by hand when their totals agree;
 * a line no book item explains (a fee, interest) made into its entry by the bank entry rules; and every match and
 * undo with who, when and why, a match undone with a reason. The server decides what may be matched.
 */
export default function MatchingPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const [search, setSearch] = useSearchParams()
  const banks = useQuery({ queryKey: ['fin', 'bank', 'accounts'], queryFn: loadBanks })
  const bankCode = search.get('bank') ?? banks.data?.[0]?.bankCode ?? null

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Space align="center" wrap>
        <Typography.Title level={3} style={{ margin: 0 }} data-testid="page-title">{t('bank.matchingTitle')}</Typography.Title>
        <Select<string> aria-label={t('bank.account')} style={{ width: 260 }} value={bankCode ?? undefined}
          loading={banks.isLoading} data-testid="bank-select" placeholder={t('bank.chooseAccount')} showSearch
          onChange={(value) => setSearch({ bank: value })}
          options={(banks.data ?? []).map((b) => ({ value: b.bankCode,
            label: `${b.bankCode} · ${b.glAccount}${b.bankName ? ` · ${b.bankName}` : ''}` }))} />
      </Space>
      {banks.error && <Alert type="error" showIcon message={message(banks.error)} />}
      {bankCode && <Workbench bankCode={bankCode} key={bankCode} />}
    </Space>
  )
}

function Workbench({ bankCode }: { bankCode: string }) {
  const lines = useQuery({ queryKey: ['fin', 'bank', bankCode, 'lines'], queryFn: () => loadOpenLines(bankCode) })
  const items = useQuery({ queryKey: ['fin', 'bank', bankCode, 'items'], queryFn: () => loadBookItems(bankCode) })
  const history = useQuery({ queryKey: ['fin', 'bank', bankCode, 'history'], queryFn: () => loadHistory(bankCode) })
  return (
    <>
      <ProposalsCard bankCode={bankCode} />
      <ByHandCard bankCode={bankCode} lines={lines.data?.items ?? []} items={items.data?.items ?? []}
        capped={(lines.data?.total ?? 0) > (lines.data?.items.length ?? 0)
          || (items.data?.total ?? 0) > (items.data?.items.length ?? 0)}
        loading={lines.isLoading || items.isLoading} error={lines.error ?? items.error} />
      <HistoryCard bankCode={bankCode} rows={history.data?.items ?? []} loading={history.isLoading}
        capped={(history.data?.total ?? 0) > (history.data?.items.length ?? 0)} />
    </>
  )
}

/** Everything the page shows of a bank account is read again after a change. */
function useRefresh(bankCode: string) {
  const queryClient = useQueryClient()
  return () => queryClient.invalidateQueries({ queryKey: ['fin', 'bank', bankCode] })
}

function ProposalsCard({ bankCode }: { bankCode: string }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const { message: toast } = App.useApp()
  const refresh = useRefresh(bankCode)
  const proposals = useQuery({ queryKey: ['fin', 'bank', bankCode, 'proposals'], queryFn: () => propose(bankCode),
    enabled: can(PERMISSIONS.reconcile) })
  const rows = useMemo(() => proposals.data?.proposals ?? [], [proposals.data])
  const [chosen, setChosen] = useState<string[] | null>(null)
  // Only proposals still made: a refetch after another change may have taken some away.
  const selected = chosen?.filter((id) => rows.some((p) => p.lineId === id)) ?? rows.map((p) => p.lineId)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const key = useRef<{ key: string; body: string } | null>(null)
  if (!can(PERMISSIONS.reconcile)) return null

  const accept = async () => {
    const picked = rows.filter((p) => selected.includes(p.lineId))
    const body = JSON.stringify(picked.map((p) => p.lineId))
    if (key.current?.body !== body) key.current = { key: crypto.randomUUID(), body }
    setBusy(true)
    setError(null)
    try {
      const done = await acceptProposals(bankCode, picked, key.current.key)
      toast.success(t('bank.accepted', { count: done.matched }))
      setChosen(null)
      await refresh()
    } catch (e) {
      setError(message(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card size="small" title={t('bank.proposals')} extra={
      <Space>
        <Typography.Text type="secondary">
          {t('bank.leftOpen', { lines: proposals.data?.openLines ?? 0, items: proposals.data?.openItems ?? 0 })}
        </Typography.Text>
        <Button type="primary" disabled={busy || selected.length === 0} loading={busy} onClick={() => void accept()}
          data-testid="accept-proposals">{t('bank.acceptSelected', { count: selected.length })}</Button>
      </Space>
    }>
      {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 8 }} data-testid="match-error" />}
      <Table<Proposal>
        size="small"
        rowKey="lineId"
        data-testid="proposal-table"
        loading={proposals.isLoading}
        dataSource={rows}
        pagination={false}
        locale={{ emptyText: proposals.error ? message(proposals.error) : t('bank.noProposals') }}
        rowSelection={{ selectedRowKeys: selected, onChange: (keys) => setChosen(keys as string[]) }}
        columns={[
          { title: t('bank.date'), dataIndex: 'valueDate', render: (v: string) => formatDate(v) },
          { title: t('bank.reference'), dataIndex: 'bankReference' },
          { title: t('bank.description'), dataIndex: 'description', ellipsis: true },
          { title: t('bank.amount'), dataIndex: 'amount', align: 'right', render: money },
          { title: t('bank.bookItems'), key: 'items', render: (_: unknown, p) => p.items.map((i) => i.documentNo ?? i.id).join(', ') },
          { title: t('bank.confidence'), dataIndex: 'confidence', align: 'right',
            render: (v: number) => <Tag color={v >= 80 ? 'success' : v >= 60 ? 'warning' : 'default'}>{v}</Tag> },
          { title: t('bank.reasons'), dataIndex: 'reasons', render: (v: string[]) => v.join('; ') },
        ]}
      />
    </Card>
  )
}

interface ByHandProps {
  bankCode: string
  lines: OpenLine[]
  items: BookItem[]
  capped: boolean
  loading: boolean
  error: unknown
}

/** Open lines and open book items side by side: chosen ones of equal total are matched with a reason. */
function ByHandCard({ bankCode, lines, items, capped, loading, error }: ByHandProps) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const { message: toast } = App.useApp()
  const refresh = useRefresh(bankCode)
  const [lineIds, setLineIds] = useState<string[]>([])
  const [itemKeys, setItemKeys] = useState<string[]>([])
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState<string | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  const [account, setAccount] = useState('')
  const reconciling = can(PERMISSIONS.reconcile)
  const pickedLines = lines.filter((l) => lineIds.includes(l.lineId))
  const pickedItems = items.filter((i) => itemKeys.includes(itemKey(i)))
  const lineTotal = sum(pickedLines.map((l) => l.amount))
  const itemTotal = sum(pickedItems.map((i) => i.amount))
  const ready = pickedLines.length > 0 && pickedItems.length > 0 && lineTotal === itemTotal
    && !(pickedLines.length > 1 && pickedItems.length > 1)

  const act = async (name: string, work: () => Promise<string>) => {
    setBusy(name)
    setFailure(null)
    try {
      toast.success(await work())
      setLineIds([])
      setItemKeys([])
      setReason('')
      await refresh()
    } catch (e) {
      setFailure(message(e))
    } finally {
      setBusy(null)
    }
  }

  return (
    <Card size="small" title={t('bank.byHand')}>
      <Space direction="vertical" style={{ width: '100%' }}>
        {(failure !== null || Boolean(error)) && <Alert type="error" showIcon message={failure ?? message(error)} data-testid="hand-error" />}
        {capped && <Alert type="warning" showIcon message={t('bank.capped')} />}
        <Row gutter={16}>
          <Col xs={24} xl={12}>
            <Typography.Title level={5}>{t('bank.openLines', { count: lines.length })}</Typography.Title>
            <Table<OpenLine>
              size="small"
              rowKey="lineId"
              data-testid="line-table"
              loading={loading}
              dataSource={lines}
              pagination={{ pageSize: 20, showSizeChanger: false }}
              rowSelection={reconciling ? { selectedRowKeys: lineIds, onChange: (keys) => setLineIds(keys as string[]) } : undefined}
              locale={{ emptyText: t('bank.noOpenLines') }}
              columns={[
                { title: t('bank.date'), dataIndex: 'valueDate', render: (v: string) => formatDate(v) },
                { title: t('bank.reference'), dataIndex: 'bankReference' },
                { title: t('bank.description'), dataIndex: 'description', ellipsis: true },
                { title: t('bank.amount'), dataIndex: 'amount', align: 'right', render: money },
                ...(reconciling ? [{
                  title: '', key: 'entry',
                  render: (_: unknown, line: OpenLine) => (
                    <Button size="small" disabled={busy !== null} loading={busy === `entry:${line.lineId}`}
                      data-testid={`make-entry-${line.bankReference ?? line.lineId}`}
                      onClick={() => void act(`entry:${line.lineId}`, async () => {
                        const made = await entryFromLine(line.lineId, can(PERMISSIONS.settings) ? account : null)
                        return t('bank.entryMade', { number: made.entryNo })
                      })}>
                      {t('bank.makeEntry')}
                    </Button>
                  ),
                }] : []),
              ]}
            />
            {reconciling && can(PERMISSIONS.settings) && (
              <Input style={{ width: 280, marginTop: 8 }} value={account} onChange={(e) => setAccount(e.target.value)}
                placeholder={t('bank.entryAccount')} aria-label={t('bank.entryAccount')} />
            )}
          </Col>
          <Col xs={24} xl={12}>
            <Typography.Title level={5}>{t('bank.openItems', { count: items.length })}</Typography.Title>
            <Table<BookItem>
              size="small"
              rowKey={itemKey}
              data-testid="item-table"
              loading={loading}
              dataSource={items}
              pagination={{ pageSize: 20, showSizeChanger: false }}
              rowSelection={reconciling ? { selectedRowKeys: itemKeys, onChange: (keys) => setItemKeys(keys as string[]) } : undefined}
              locale={{ emptyText: t('bank.noOpenItems') }}
              columns={[
                { title: t('bank.date'), dataIndex: 'itemDate', render: (v: string) => formatDate(v) },
                { title: t('bank.document'), dataIndex: 'documentNo' },
                { title: t('bank.party'), dataIndex: 'party', ellipsis: true,
                  render: (v: string | null, i) => [v, i.checkNo && t('bank.check', { number: i.checkNo }), i.runNo]
                    .filter(Boolean).join(' · ') },
                { title: t('bank.amount'), dataIndex: 'amount', align: 'right', render: money },
              ]}
            />
          </Col>
        </Row>
        {reconciling && (
          <Space wrap align="center">
            <span data-testid="hand-totals">
              {t('bank.chosenTotals', { lines: money(lineTotal), items: money(itemTotal) })}
            </span>
            <Input style={{ width: 360 }} value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500}
              placeholder={t('bank.reason')} aria-label={t('bank.reason')} />
            <Button type="primary" disabled={!ready || busy !== null} loading={busy === 'match'} data-testid="match-by-hand"
              onClick={() => void act('match', async () => {
                await matchByHand(bankCode, pickedLines.map((l) => l.lineId), pickedItems, reason)
                return t('bank.matched')
              })}>
              {t('bank.match')}
            </Button>
          </Space>
        )}
      </Space>
    </Card>
  )
}

/** Every match and undo with who, when and why; a match not undone is undone here, with a reason. */
function HistoryCard({ bankCode, rows, loading, capped }: { bankCode: string; rows: HistoryRow[]; loading: boolean;
  capped: boolean }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const { message: toast } = App.useApp()
  const refresh = useRefresh(bankCode)
  // One reason per undo asked: a popup opened afresh starts empty.
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)
  const undone = useMemo(() => new Set(rows.map((r) => r.reversesMatchId).filter(Boolean)), [rows])

  const undo = async (row: HistoryRow) => {
    setError(null)
    try {
      await unmatch(row.matchId, reason)
      toast.success(t('bank.undone'))
      setReason('')
      await refresh()
    } catch (e) {
      setError(message(e))
    }
  }

  return (
    <Card size="small" title={t('bank.history')}>
      {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 8 }} data-testid="undo-error" />}
      {capped && <Alert type="warning" showIcon style={{ marginBottom: 8 }} message={t('bank.historyCapped')} />}
      <Table<HistoryRow>
        size="small"
        rowKey="matchId"
        data-testid="history-table"
        loading={loading}
        dataSource={rows}
        pagination={{ pageSize: 20, showSizeChanger: false }}
        locale={{ emptyText: t('bank.noHistory') }}
        columns={[
          { title: t('bank.when'), dataIndex: 'actionTime', render: (v: string) => formatDateTime(v) },
          { title: t('bank.action'), dataIndex: 'action', render: (v: string, row) => (
            <Tag color={v === 'UNMATCH' ? 'warning' : undone.has(row.matchId) ? 'default' : 'success'}>
              {t(`bank.actions.${v}`, v)}{row.method ? ` · ${t(`bank.methods.${row.method}`, row.method)}` : ''}
            </Tag>
          ) },
          { title: t('bank.statementSide'), dataIndex: 'statementItems' },
          { title: t('bank.bookSide'), dataIndex: 'bookItems' },
          { title: t('bank.amount'), dataIndex: 'amount', align: 'right', render: money },
          { title: t('bank.confidence'), dataIndex: 'confidence', align: 'right' },
          { title: t('bank.reason'), dataIndex: 'reason', ellipsis: true },
          { title: t('bank.by'), dataIndex: 'actor', render: (value?: string | null) => <UserName id={value} /> },
          { title: '', key: 'undo', render: (_: unknown, row) => row.action === 'MATCH' && !undone.has(row.matchId)
            && can(PERMISSIONS.reconcile) && (
            <Popconfirm
              title={t('bank.undoTitle')}
              description={<Input value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500}
                placeholder={t('bank.reason')} aria-label={t('bank.undoReason')} data-testid="undo-reason" />}
              okText={t('bank.undo')}
              okButtonProps={{ disabled: reason.trim() === '', ...{ 'data-testid': 'undo-confirm' } }}
              onOpenChange={() => setReason('')}
              onConfirm={() => void undo(row)}>
              <Button size="small" data-testid={`undo-${row.statementItems ?? row.matchId}`}>{t('bank.undo')}</Button>
            </Popconfirm>
          ) },
        ]}
      />
    </Card>
  )
}
