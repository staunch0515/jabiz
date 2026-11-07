/** The names of the sponsor's products (`page.SponsorProductProcesses`, `SponsorProductConfig`; Q60b). */
export const PRODUCT = {
  save: 'QB_PRODUCT_SAVE',
  delete: 'QB_PRODUCT_DELETE',
  quizProducts: 'QB_QUIZ_PRODUCTS_SET',
  myDataset: 'urn:jabiz:dataset:me:QbSponsorProduct',
  myLinks: 'urn:jabiz:dataset:me:QbQuizProduct',
  /** F-C11: the most products the sponsor may have (`qb.products.max`), as the save reads it. */
  limitTemplate: 'qb.sponsor.product-limit',
} as const
