/** Paths of the generic pages. Dataset ids are URNs, hence encoded. */
export const paths = {
  dataset: (id: string) => `/data/${encodeURIComponent(id)}`,
  history: (datasetId: string, entityId: string) =>
    `/data/${encodeURIComponent(datasetId)}/${encodeURIComponent(entityId)}/history`,
  process: (name: string, version: number | string) => `/processes/${encodeURIComponent(name)}/${version}`,
  // Template ids contain dots, which the server takes for file names in a path: the id goes in the query.
  report: (id: string) => `/reports/run?id=${encodeURIComponent(id)}`,
  // Import ids contain dots, as template ids do.
  importRun: (id: string) => `/imports/run?id=${encodeURIComponent(id)}`,
  importRuns: (id?: string) => (id ? `/imports/runs?import=${encodeURIComponent(id)}` : '/imports/runs'),
  reportArchive: (id?: string) => (id ? `/reports/archive?template=${encodeURIComponent(id)}` : '/reports/archive'),
  audit: (entityType: string, entityId: string) =>
    `/audit?entityType=${encodeURIComponent(entityType)}&entityId=${encodeURIComponent(entityId)}`,
}
