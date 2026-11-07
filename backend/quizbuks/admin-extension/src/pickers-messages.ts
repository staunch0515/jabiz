/**
 * Texts of the pickers and the filter by operator (F-S1b), in every platform language, merged into the extension's
 * messages under `pickers`.
 */
export const pickersMessages = {
  en: {
    operator: 'Choose an operator',
    operatorSearch: 'Search operators by name',
    operatorNone: 'No operator by that name.',
    byOperator: 'Operator',
    allOperators: 'All operators',
    operatorColumn: 'Operator',
    user: 'Choose a person',
    userSearch: 'Name, address or company',
    userType: 'Type a name, address or company.',
    userNone: 'Nobody found.',
    unverified: 'address not verified',
  },
  zh: {
    operator: '选择运营商',
    operatorSearch: '按名称搜索运营商',
    operatorNone: '没有这个名称的运营商。',
    byOperator: '运营商',
    allOperators: '全部运营商',
    operatorColumn: '运营商',
    user: '选择用户',
    userSearch: '姓名、邮箱或公司',
    userType: '输入姓名、邮箱或公司。',
    userNone: '没有找到。',
    unverified: '邮箱未验证',
  },
  ja: {
    operator: '運営パートナーを選択',
    operatorSearch: '名前で運営パートナーを検索',
    operatorNone: 'その名前の運営パートナーはありません。',
    byOperator: '運営パートナー',
    allOperators: 'すべての運営パートナー',
    operatorColumn: '運営パートナー',
    user: 'ユーザーを選択',
    userSearch: '名前、メールアドレス、会社名',
    userType: '名前、メールアドレス、会社名を入力してください。',
    userNone: '見つかりません。',
    unverified: 'メールアドレス未確認',
  },
} as const
