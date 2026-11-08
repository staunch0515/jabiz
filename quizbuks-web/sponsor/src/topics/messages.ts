import { addMessages } from '@jabiz/client'
import { SPONSOR } from '../messages'

/**
 * The topics' texts (F-S9): a quiz's topics in the quiz editor and a publication's in the wizard, part of the console's
 * namespace `sponsor` (added here, next to the components).
 */
const en = {
  topics: {
    title: 'Topics',
    quizHint: 'Choose up to {{max}} topics from the platform\'s word list, and add words of your own if none fits. Takers who show only the topics they like see your publications when one of the words of the list matches; your own words are only for search. Each version keeps the topics, and its publications start from them.',
    publicationHint: 'The publication starts from the topics of its quiz version: leave out those that do not fit and add others (up to {{max}} in all). Takers who show only the topics they like see it when one of the words of the list matches; your own words are only for search.',
    add: 'Add a topic',
    addPlaceholder: 'Choose from the word list',
    search: 'Search topics',
    noMatch: 'No topic matches.',
    custom: 'Your own word',
    customHint: 'Up to {{max}} characters; for search only.',
    customAdd: 'Add',
    own: 'Own word',
    fromVersion: 'From the version',
    noLonger: 'No longer offered',
    remove: 'Remove {{name}}',
    leaveOut: 'Leave out {{name}}',
    restore: 'Use {{name}} again',
    left: 'Left out',
    none: 'No topics yet.',
    count: '{{count}} of {{max}}',
    full: 'That is the most topics: remove one to add another.',
    saved: 'Topics saved.',
    chooseVersion: 'Choose the quiz version first: the publication\'s topics start from it.',
  },
}

const zh: typeof en = {
  topics: {
    title: '主题词',
    quizHint: '从平台的词表中最多选 {{max}} 个主题词；没有合适的词时，可以加自定义词。打开"只看喜欢的主题"的学者，只有在词表中的词相符时才会看到你的发布；自定义词只用于搜索。每个版本都会保存主题词，版本的发布以它为基础。',
    publicationHint: '发布以 Quiz 版本的主题词为基础：可以去掉不合适的，再加别的（合计最多 {{max}} 个）。打开"只看喜欢的主题"的学者，只有在词表中的词相符时才会看到这个发布；自定义词只用于搜索。',
    add: '添加主题词',
    addPlaceholder: '从词表中选择',
    search: '搜索主题词',
    noMatch: '没有符合的主题词。',
    custom: '自定义词',
    customHint: '最多 {{max}} 个字；只用于搜索。',
    customAdd: '添加',
    own: '自定义',
    fromVersion: '来自版本',
    noLonger: '已不再提供',
    remove: '去掉 {{name}}',
    leaveOut: '此发布不用 {{name}}',
    restore: '重新使用 {{name}}',
    left: '已去掉',
    none: '还没有主题词。',
    count: '{{count}} / {{max}}',
    full: '主题词已达上限：去掉一个才能再加。',
    saved: '已保存主题词。',
    chooseVersion: '请先选择 Quiz 的版本：发布的主题词以它为基础。',
  },
}

const ja: typeof en = {
  topics: {
    title: 'トピック',
    quizHint: 'プラットフォームの語彙リストからトピックを {{max}} 個まで選べます。合う語がなければ独自の語も追加できます。「好きなトピックだけ表示」にしているテイカーには、語彙リストの語が一致したときだけあなたの公開が表示されます。独自の語は検索だけに使います。バージョンごとにトピックが保存され、そのバージョンの公開はそれを基にします。',
    publicationHint: '公開はクイズのバージョンのトピックを基にします。合わないものを外し、ほかを追加できます（合計 {{max}} 個まで）。「好きなトピックだけ表示」にしているテイカーには、語彙リストの語が一致したときだけこの公開が表示されます。独自の語は検索だけに使います。',
    add: 'トピックを追加',
    addPlaceholder: '語彙リストから選ぶ',
    search: 'トピックを検索',
    noMatch: '該当するトピックがありません。',
    custom: '独自の語',
    customHint: '{{max}} 文字まで。検索だけに使います。',
    customAdd: '追加',
    own: '独自',
    fromVersion: 'バージョンから',
    noLonger: '提供終了',
    remove: '{{name}} を外す',
    leaveOut: 'この公開では {{name}} を使わない',
    restore: '{{name}} をまた使う',
    left: '除外',
    none: 'まだトピックがありません。',
    count: '{{count}} / {{max}}',
    full: 'トピックが上限に達しました。追加するには 1 つ外してください。',
    saved: 'トピックを保存しました。',
    chooseVersion: '先にクイズのバージョンを選んでください。公開のトピックはそれを基にします。',
  },
}

export const topicsMessages = { en, zh, ja }

addMessages(SPONSOR, topicsMessages)
