/** A draft of a mail text: its subject and body. */
export interface MailDraft {
  subject: string
  body: string
}

/**
 * The placeholders a draft must keep and lacks, as `{name}` in its subject or body. Only a convenience for the
 * editor: the server's rules (`MailTextRules`) decide.
 */
export function missingRequired(required: string[], draft: MailDraft): string[] {
  const text = `${draft.subject}\n${draft.body}`
  return required.filter((name) => !text.includes(`{${name}}`))
}
