/** The finance pages' paths (outside the platform's own, decision D22). */
export const JOURNALS_PATH = '/gl/journals'
export const NEW_JOURNAL_PATH = '/gl/journals/new'
export const journalPath = (journalId: string) => `/gl/journals/${encodeURIComponent(journalId)}`
