/**
 * Texts of a Sponsor's imports of sponsor packages (F-C4) on the Sponsor's page, in every platform language, merged
 * into the extension's messages under `packageImports`. The ways and states are the server's dictionaries.
 */
export const packageImportMessages = {
  en: {
    title: 'Sponsor package imports',
    hint: 'Each time the Sponsor imported a sponsor package: how, how far it got and when. Read only, to look into a problem; the Sponsor goes on with an import or gives it up on their own page. Records are deleted after the retention period.',
    table: 'The Sponsor’s sponsor package imports',
    none: 'No imports.',
    columns: { startedAt: 'Started', mode: 'Imported', status: 'State', steps: 'Steps', lastStep: 'Last step done',
      finishedAt: 'Finished', version: 'Format' },
    steps: '{{done}} of {{total}}',
    version: 'Version {{version}}',
  },
  zh: {
    title: '发起方包的导入记录',
    hint: '发起方每次导入发起方包的记录：导入的内容、进行到哪一步、时间。这里只读，用于排查；继续或放弃导入由发起方在自己的页面操作。记录过了保留期即删除。',
    table: '该发起方的发起方包导入记录',
    none: '没有导入记录。',
    columns: { startedAt: '开始时间', mode: '导入的内容', status: '状态', steps: '步数', lastStep: '最后完成的一步',
      finishedAt: '结束时间', version: '格式' },
    steps: '{{done}} / {{total}}',
    version: '版本 {{version}}',
  },
  ja: {
    title: '主催者パッケージのインポート記録',
    hint: '主催者が主催者パッケージをインポートした記録です。内容、どこまで進んだか、日時を表示します。調査のための閲覧専用で、インポートの再開や中止は主催者が自分のページで行います。記録は保存期間を過ぎると削除されます。',
    table: '主催者の主催者パッケージのインポート記録',
    none: 'インポート記録はありません。',
    columns: { startedAt: '開始', mode: 'インポートした内容', status: '状態', steps: 'ステップ', lastStep: '最後に完了したステップ',
      finishedAt: '終了', version: '形式' },
    steps: '{{done}} / {{total}}',
    version: 'バージョン {{version}}',
  },
}
