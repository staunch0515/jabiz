import { api, runProcess, runQuery, unwrap, type Filter } from '@jabiz/admin'
import type { AccountOption, Dimensions, LineInput, StoredLine } from './grid'

/** The finance backend's names (backend/finance, gl/JournalEntities, gl/JournalProcesses). */
export const DATASETS = {
  journal: 'urn:jabiz:dataset:default:FinJournal',
  line: 'urn:jabiz:dataset:default:FinJournalLine',
  attachment: 'urn:jabiz:dataset:default:FinJournalAttachment',
  department: 'urn:jabiz:dataset:default:FinDepartment',
  location: 'urn:jabiz:dataset:default:FinLocation',
} as const

export const PROCESSES = {
  save: 'FIN_JOURNAL_SAVE',
  delete: 'FIN_JOURNAL_DELETE',
  submit: 'FIN_JOURNAL_SUBMIT',
  reverse: 'FIN_JOURNAL_REVERSE',
  exception: 'FIN_JOURNAL_GRANT_CONTROL_EXCEPTION',
  attach: 'FIN_JOURNAL_ATTACH',
  detach: 'FIN_JOURNAL_DETACH',
} as const

export const QUERIES = {
  accounts: 'finance.gl.account_lookup',
  register: 'finance.gl.journal_register',
} as const

export const PERMISSIONS = {
  read: 'fin.journal.read',
  prepare: 'fin.journal.prepare',
  approve: 'fin.journal.approve',
  decide: 'approval.decide',
  exception: 'fin.journal.control-exception',
  attach: 'fin.journal.attach',
} as const

/** The file policies of supporting documents: documents and images, or spreadsheets (FIN-GL-016). */
export const FILE_POLICIES = { support: 'fin.journal.support', sheets: 'fin.journal.sheets' } as const

/** Spreadsheets go under their own policy: an import type does not share a policy with documents (14e). */
export function isSpreadsheet(file: { name: string; type: string }): boolean {
  return /\.(csv|xlsx)$/i.test(file.name) || file.type === 'text/csv'
    || file.type === 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
}

export type JournalStatus = 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'POSTED' | 'REJECTED'

/** The attributes of a FinJournal, as the dataset returns them. */
export interface Journal {
  journalId: string
  journalNo?: string | null
  postingDate: string
  documentDate?: string | null
  description: string
  source: string
  status: JournalStatus
  preparer: string
  adjusting?: boolean
  adjustmentPeriod?: boolean
  autoReverseDate?: string | null
  reversesJournalId?: string | null
  reversedById?: string | null
  totalDebit?: number | string
  totalCredit?: number | string
  periodKey?: string | null
  approvalRequestId?: string | null
  exceptionBy?: string | null
  exceptionReason?: string | null
  glNo?: string | null
  transactionId?: string | null
}

export interface Attachment {
  attachmentId: string
  fileId?: string | null
  sheetFileId?: string | null
  sha256: string
  description?: string | null
}

/** A row of the journal register (finance.gl.journal_register). */
export interface RegisterRow {
  journalId: string
  journalNo?: string | null
  postingDate: string
  documentDate?: string | null
  description: string
  source: string
  status: JournalStatus
  totalDebit: number | string
  totalCredit: number | string
  preparer: string
  periodKey?: string | null
  glNo?: string | null
}

/** What FIN_JOURNAL_SAVE / SUBMIT / REVERSE answer. */
export interface JournalOutput {
  journalId: string
  journalNo?: string | null
  status: JournalStatus
  approval?: 'NOT_REQUIRED' | 'PENDING' | 'APPROVED' | null
  approvalRequestId?: string | null
  glNo?: string | null
}

export interface JournalInput {
  journalId?: string
  postingDate: string
  documentDate?: string | null
  description: string
  adjusting?: boolean
  adjustmentPeriod?: boolean
  autoReverseDate?: string | null
  lines: LineInput[]
}

async function queryDataset<T>(datasetId: string, filters: Filter[], limit = 500): Promise<T[]> {
  const page = await unwrap(
    api.POST('/api/datasets/{resourceId}/query', {
      params: { path: { resourceId: datasetId } },
      body: { filters, limit },
    }),
  )
  return (page.items ?? []).map((item) => item.attributes as T)
}

export async function loadAccounts(): Promise<Map<string, AccountOption>> {
  const page = await runQuery<AccountOption>(QUERIES.accounts, { limit: 5000 })
  return new Map(page.items.map((account) => [account.accountCode, account]))
}

export async function loadDimensions(): Promise<Dimensions> {
  const [departments, locations] = await Promise.all([
    queryDataset<{ departmentCode: string }>(DATASETS.department, [{ field: 'active', op: 'eq', value: true }]),
    queryDataset<{ locationCode: string }>(DATASETS.location, [{ field: 'active', op: 'eq', value: true }]),
  ])
  return {
    departments: new Set(departments.map((d) => d.departmentCode)),
    locations: new Set(locations.map((l) => l.locationCode)),
  }
}

export interface LoadedJournal {
  journal: Journal
  version: number
  lines: StoredLine[]
  attachments: Attachment[]
}

export async function loadJournal(journalId: string): Promise<LoadedJournal> {
  const [instance, lines, attachments] = await Promise.all([
    unwrap(
      api.GET('/api/datasets/{resourceId}/entities/{id}', {
        params: { path: { resourceId: DATASETS.journal, id: journalId } },
      }),
    ),
    queryDataset<StoredLine>(DATASETS.line, [{ field: 'journalId', op: 'eq', value: journalId }]),
    queryDataset<Attachment>(DATASETS.attachment, [{ field: 'journalId', op: 'eq', value: journalId }]),
  ])
  return {
    journal: instance.attributes as unknown as Journal,
    version: Number(instance.version ?? 0),
    lines,
    attachments,
  }
}

export function saveJournal(input: JournalInput, idempotencyKey?: string): Promise<JournalOutput> {
  return runProcess<JournalOutput>(PROCESSES.save, { ...input }, { idempotencyKey })
}

export function submitJournal(journalId: string, idempotencyKey?: string): Promise<JournalOutput> {
  return runProcess<JournalOutput>(PROCESSES.submit, { journalId }, { idempotencyKey })
}

export async function loadRegister(params: { from: string; to: string; status?: string | null }) {
  const page = await runQuery<RegisterRow>(QUERIES.register, {
    params: { from: params.from, to: params.to, status: params.status ?? null },
    limit: 500,
    count: true,
  })
  return page
}
