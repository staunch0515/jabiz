import { addMessages } from '@jabiz/client'

/** The i18next namespace of this package's texts; it never overlaps the admin's (`translation`) or an extension's (`app`). */
export const UI_NAMESPACE = 'ui'

const en = {
  close: 'Close',
  loading: 'Loading…',
  more: 'More',
  toggleSidebar: 'Toggle sidebar',
  sidebar: 'Navigation',
  breadcrumb: 'Breadcrumb',
  dataTable: {
    expandRow: 'Expand row',
    collapseRow: 'Collapse row',
    empty: 'No data',
    sortAscending: 'Sort ascending',
    sortDescending: 'Sort descending',
    clearSort: 'Clear sorting',
    total: '{{count}} items',
    rowsPerPage: 'Rows per page',
  },
  pagination: {
    label: 'Pagination',
    previous: 'Previous page',
    next: 'Next page',
    previousShort: 'Previous',
    nextShort: 'Next',
    page: 'Page {{page}} of {{pages}}',
    morePages: 'More pages',
  },
  confirm: { ok: 'OK', cancel: 'Cancel' },
  appearance: { label: 'Appearance', light: 'Light', dark: 'Dark', system: 'System' },
  date: { placeholder: 'Pick a date', clear: 'Clear', time: 'Time', previousMonth: 'Previous month', nextMonth: 'Next month' },
  notify: { dismiss: 'Dismiss', notifications: 'Notifications' },
}

type Messages = typeof en

const zh: Messages = {
  close: '关闭',
  loading: '加载中…',
  more: '更多',
  toggleSidebar: '展开或收起侧栏',
  sidebar: '导航',
  breadcrumb: '面包屑',
  dataTable: {
    expandRow: '展开行',
    collapseRow: '收起行',
    empty: '暂无数据',
    sortAscending: '升序排列',
    sortDescending: '降序排列',
    clearSort: '取消排序',
    total: '共 {{count}} 条',
    rowsPerPage: '每页行数',
  },
  pagination: {
    label: '分页',
    previous: '上一页',
    next: '下一页',
    previousShort: '上一页',
    nextShort: '下一页',
    page: '第 {{page}} 页，共 {{pages}} 页',
    morePages: '更多页',
  },
  confirm: { ok: '确定', cancel: '取消' },
  appearance: { label: '外观', light: '浅色', dark: '深色', system: '跟随系统' },
  date: { placeholder: '选择日期', clear: '清除', time: '时间', previousMonth: '上个月', nextMonth: '下个月' },
  notify: { dismiss: '关闭', notifications: '通知' },
}

const ja: Messages = {
  close: '閉じる',
  loading: '読み込み中…',
  more: 'その他',
  toggleSidebar: 'サイドバーの表示を切り替え',
  sidebar: 'ナビゲーション',
  breadcrumb: 'パンくずリスト',
  dataTable: {
    expandRow: '行を展開',
    collapseRow: '行を折りたたむ',
    empty: 'データがありません',
    sortAscending: '昇順で並べ替え',
    sortDescending: '降順で並べ替え',
    clearSort: '並べ替えを解除',
    total: '全 {{count}} 件',
    rowsPerPage: '1 ページの行数',
  },
  pagination: {
    label: 'ページ送り',
    previous: '前のページ',
    next: '次のページ',
    previousShort: '前へ',
    nextShort: '次へ',
    page: '{{page}} / {{pages}} ページ',
    morePages: 'その他のページ',
  },
  confirm: { ok: 'OK', cancel: 'キャンセル' },
  appearance: { label: '外観', light: 'ライト', dark: 'ダーク', system: 'システムに合わせる' },
  date: { placeholder: '日付を選択', clear: 'クリア', time: '時刻', previousMonth: '前の月', nextMonth: '次の月' },
  notify: { dismiss: '閉じる', notifications: '通知' },
}

export const uiMessages = { zh, ja, en }

// Registered when the package is first imported: every component that shows text imports this module.
addMessages(UI_NAMESPACE, uiMessages)
