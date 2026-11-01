import { runProcess } from '@jabiz/client'
import { REPORT_PROCESS, type ReportReason } from './ids'

/** Reports a quiz (typed by hand after `ReportProcesses.SubmitInput` / `SubmitOutput`). */
export const reportQuiz = (publicationId: string, reason: ReportReason, note: string) =>
  runProcess<{ reportId: string }>(REPORT_PROCESS.submit, { publicationId, reason, note: note.trim() || null })
