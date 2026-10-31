/**
 * The texts of the sponsor's page and product statistics (Q60c), part of the console's namespace `sponsor` under
 * `stats` (`src/messages.ts` adds them).
 */
export const statsEn = {
  stats: {
    nav: 'Page statistics',
    title: 'Page statistics',
    intro: 'How many visitors saw your page and opened your products\' shop links, by day.',
    approximate: "Approximate: each visitor counts once a day per page or product, signed in or not; days follow the service's time zone.",
    period: 'Period',
    days: 'Last {{count}} days',
    views: 'Page views',
    clicks: 'Shop link clicks',
    viewsChart: 'Page views by day, {{total}} in all',
    clicksChart: 'Shop link clicks by day, {{total}} in all',
    table: 'Show the numbers',
    day: 'Day',
    products: 'Clicks by product',
    product: 'Product',
    noProducts: 'No products yet.',
    status: {
      LISTED: 'Shown',
      UNLISTED: 'Hidden',
      TAKEN_DOWN: 'Taken down',
    },
  },
}

export const statsZh: typeof statsEn = {
  stats: {
    nav: '主页统计',
    title: '主页统计',
    intro: '每天有多少访客看了您的主页、打开了商品的商店链接。',
    approximate: '近似值：每位访客（无论是否登录）每天对同一主页或商品只计一次；日期按服务的时区。',
    period: '期间',
    days: '最近 {{count}} 天',
    views: '主页浏览',
    clicks: '商店链接点击',
    viewsChart: '每天的主页浏览，共 {{total}}',
    clicksChart: '每天的商店链接点击，共 {{total}}',
    table: '显示数字',
    day: '日期',
    products: '各商品的点击',
    product: '商品',
    noProducts: '还没有商品。',
    status: {
      LISTED: '上架',
      UNLISTED: '未上架',
      TAKEN_DOWN: '已下架',
    },
  },
}

export const statsJa: typeof statsEn = {
  stats: {
    nav: 'ページの統計',
    title: 'ページの統計',
    intro: 'マイページを見た訪問者と、商品のショップリンクを開いた訪問者の数（日別）。',
    approximate: '概数です。訪問者（ログインの有無を問わず）は同じページや商品について1日1回だけ数えます。日付はサービスのタイムゾーンによります。',
    period: '期間',
    days: '過去 {{count}} 日',
    views: 'ページの閲覧',
    clicks: 'ショップリンクのクリック',
    viewsChart: '日別のページの閲覧（合計 {{total}}）',
    clicksChart: '日別のショップリンクのクリック（合計 {{total}}）',
    table: '数値を表示',
    day: '日付',
    products: '商品別のクリック',
    product: '商品',
    noProducts: '商品はまだありません。',
    status: {
      LISTED: '表示中',
      UNLISTED: '非表示',
      TAKEN_DOWN: '公開停止',
    },
  },
}
