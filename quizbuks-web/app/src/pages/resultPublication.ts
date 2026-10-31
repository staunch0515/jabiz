/**
 * The quiz a result shown in this tab belongs to (Q65): the header's back goes from a result opened first (the
 * history's link, a mail) to that quiz's page.
 */
const publications = new Map<string, string>()

export const resultPublication = {
  get: (attemptId: string): string | undefined => publications.get(attemptId),
  remember: (attemptId: string, publicationId: string) => {
    publications.set(attemptId, publicationId)
  },
}
