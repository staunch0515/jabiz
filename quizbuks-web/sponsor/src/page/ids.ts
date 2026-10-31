/** The names of the sponsor's page editor (`page.SponsorPageProcesses`, `SponsorPageConfig`; Q60a). */
export const SPONSOR_PAGE = {
  save: 'QB_SPONSOR_PAGE_SAVE',
  submit: 'QB_SPONSOR_PAGE_SUBMIT',
  unpublish: 'QB_SPONSOR_PAGE_UNPUBLISH',
  /** The sponsor's own page, read only. */
  myDataset: 'urn:jabiz:dataset:me:QbSponsorPage',
  /** A page's logo and cover, and a product's image: public once shown. */
  imagePolicy: 'qb.sponsor-page-image',
} as const
