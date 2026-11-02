import { runProcess } from '@jabiz/client'
import { REPORT_PROCESS, type ReportReason } from './ids'

/** Reports a quiz (typed by hand after `ReportProcesses.SubmitInput` / `SubmitOutput`). */
export const reportQuiz = (publicationId: string, reason: ReportReason, note: string) =>
  runProcess<{ reportId: string }>(REPORT_PROCESS.submit, { publicationId, reason, note: note.trim() || null })

/** Q82: reports what a sponsor wrote in one of the caller's conversations about the quiz (reason MESSAGE). */
export const reportConversation = (publicationId: string, conversationId: string, note: string) =>
  runProcess<{ reportId: string }>(REPORT_PROCESS.submit, { publicationId, reason: 'MESSAGE', conversationId,
    note: note.trim() || null })
