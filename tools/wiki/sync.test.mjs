import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { PAGE_LIMIT, pageName, parseSection, rewriteLinks, splitPages, sync, titleOf, upsertBlock } from './sync.mjs';

const platform = parseSection('Platform:docs:design,guide,ROADMAP.md');
const app = parseSection('QuizBuks:docs/quizbuks:.');
const files = new Set([
  'docs/design/09-decisions.md',
  'docs/guide/wiki.md',
  'docs/ROADMAP.md',
  'docs/quizbuks/03-plan.md',
  'docs/quizbuks/plans/Q7 legal.md',
  'docs/quizbuks/img/flow.png',
  'backend/core/src/Main.java',
]);
const ctx = (repoPath) => ({
  repoPath,
  sections: [app, platform],
  exists: (p) => files.has(p),
  repo: 'o/r',
  branch: '1.3/quizbuks',
});

test('page names: section prefix and the path under the base', () => {
  assert.equal(pageName(platform, 'docs/design/09-decisions.md'), 'Platform-design-09-decisions');
  assert.equal(pageName(platform, 'docs/ROADMAP.md'), 'Platform-ROADMAP');
  assert.equal(pageName(app, 'docs/quizbuks/plans/Q7 legal.md'), 'QuizBuks-plans-Q7-legal');
  assert.throws(() => parseSection('nope'), /section must be/);
});

test('links to documents become wiki pages, anchors kept, across sections', () => {
  const out = rewriteLinks(
    'See [plan](plans/Q7%20legal.md#d-1), [D9](../design/09-decisions.md) and [road](../ROADMAP.md#17h).',
    ctx('docs/quizbuks/03-plan.md'),
  );
  assert.equal(out, 'See [plan](QuizBuks-plans-Q7-legal#d-1), [D9](Platform-design-09-decisions) and [road](Platform-ROADMAP#17h).');
});

test('links to other files go to GitHub on the branch; images to the raw file', () => {
  const out = rewriteLinks('[code](../../backend/core/src/Main.java) ![f](img/flow.png "t")', ctx('docs/quizbuks/03-plan.md'));
  assert.equal(
    out,
    '[code](https://github.com/o/r/blob/1.3/quizbuks/backend/core/src/Main.java) ' +
      '![f](https://github.com/o/r/raw/1.3/quizbuks/docs/quizbuks/img/flow.png "t")',
  );
});

test('absolute addresses, anchors, placeholders, missing files and code stay as written', () => {
  const text = [
    '[a](https://example.com/x.md) [b](#here) [c]({entryUrl}auth/verify) [d](missing.md) [e](mailto:x@y)',
    'inline `[f](plans/Q7%20legal.md)` code',
    '```md',
    '[g](plans/Q7%20legal.md)',
    '```',
    '[h](plans/Q7%20legal.md)',
  ].join('\n');
  const out = rewriteLinks(text, ctx('docs/quizbuks/03-plan.md')).split('\n');
  assert.equal(out[0], '[a](https://example.com/x.md) [b](#here) [c]({entryUrl}auth/verify) [d](missing.md) [e](mailto:x@y)');
  assert.equal(out[1], 'inline `[f](plans/Q7%20legal.md)` code');
  assert.equal(out[3], '[g](plans/Q7%20legal.md)');
  assert.equal(out[5], '[h](QuizBuks-plans-Q7-legal)');
});

test('title: the first level-one heading outside code, else the file name', () => {
  assert.equal(titleOf('```\n# not\n```\ntext\n# Real title\n', 'a/b.md'), 'Real title');
  assert.equal(titleOf('no heading', 'a/b.md'), 'b');
});

test('blocks: each section replaces only its own, kept in prefix order', () => {
  let t = upsertBlock('# Home\n\n', 'QuizBuks', 'q1');
  t = upsertBlock(t, 'Platform', 'p1');
  t = upsertBlock(t, 'QuizBuks', 'q2');
  assert.equal(
    t,
    '# Home\n\n<!-- wiki-section:Platform -->\np1\n<!-- /wiki-section:Platform -->\n\n' +
      '<!-- wiki-section:QuizBuks -->\nq2\n<!-- /wiki-section:QuizBuks -->\n',
  );
});

function tree(entries) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'wiki-src-'));
  for (const [p, text] of Object.entries(entries)) {
    fs.mkdirSync(path.dirname(path.join(root, p)), { recursive: true });
    fs.writeFileSync(path.join(root, p), text);
  }
  return root;
}

test('sync: writes the section, its index and blocks, and replaces it wholly on the next run', () => {
  const wiki = fs.mkdtempSync(path.join(os.tmpdir(), 'wiki-'));
  fs.writeFileSync(path.join(wiki, 'Home.md'), 'The first page, created by hand\n');
  fs.mkdirSync(path.join(wiki, 'QuizBuks'));
  fs.writeFileSync(path.join(wiki, 'QuizBuks', 'QuizBuks-03-plan.md'), 'other section\n');

  const src = tree({
    'docs/design/09-decisions.md': '# Decisions\n\n[guide](../guide/wiki.md)\n',
    'docs/guide/wiki.md': '# Wiki\n',
    'docs/ROADMAP.md': '# Roadmap\n',
    'docs/other.md': '# not published\n',
  });
  const run = () =>
    sync({ root: src, wiki, repo: 'o/r', branch: '1.3/platform', section: platform, title: '平台' });
  assert.deepEqual(run(), ['Platform-ROADMAP', 'Platform-design-09-decisions', 'Platform-guide-wiki']);

  const page = fs.readFileSync(path.join(wiki, 'Platform', 'Platform-design-09-decisions.md'), 'utf8');
  assert.match(page, /^> 自动生成，请勿在 Wiki 上编辑。来源：\[`docs\/design\/09-decisions.md`\]\(https:\/\/github.com\/o\/r\/blob\/1.3\/platform\/docs\/design\/09-decisions.md\)/);
  assert.match(page, /\[guide\]\(Platform-guide-wiki\)/);
  const index = fs.readFileSync(path.join(wiki, 'Platform', 'Platform.md'), 'utf8');
  assert.match(index, /## 概览\n\n- \[Roadmap\]\(Platform-ROADMAP\)/);
  assert.match(index, /## design\n\n- \[Decisions\]\(Platform-design-09-decisions\)/);
  const home = fs.readFileSync(path.join(wiki, 'Home.md'), 'utf8');
  assert.doesNotMatch(home, /created by hand/);
  assert.match(home, /<!-- wiki-section:Platform -->\n## \[平台\]\(Platform\)\n\n分支 `1.3\/platform`，共 3 页。/);
  assert.match(fs.readFileSync(path.join(wiki, '_Sidebar.md'), 'utf8'), /\*\*\[平台\]\(Platform\)\*\*/);
  assert.equal(fs.readFileSync(path.join(wiki, 'QuizBuks', 'QuizBuks-03-plan.md'), 'utf8'), 'other section\n');

  fs.rmSync(path.join(src, 'docs/guide/wiki.md'));
  assert.deepEqual(run(), ['Platform-ROADMAP', 'Platform-design-09-decisions']);
  assert.equal(fs.existsSync(path.join(wiki, 'Platform', 'Platform-guide-wiki.md')), false);
  assert.match(fs.readFileSync(path.join(wiki, 'Home.md'), 'utf8'), /共 2 页/);
});

test('sync: two files giving one page name are refused', () => {
  const wiki = fs.mkdtempSync(path.join(os.tmpdir(), 'wiki-'));
  const src = tree({ 'docs/guide/a b.md': '', 'docs/guide/a-b.md': '' });
  assert.throws(
    () => sync({ root: src, wiki, repo: 'o/r', branch: 'b', section: platform, title: 't' }),
    /two files give the page Platform-guide-a-b/,
  );
});

test('a long document is cut before level-two or level-three headings, parts linked to each other', () => {
  const doc = ['# Big', 'intro', '## A', 'a'.repeat(30), '## B', '```', '## not a cut', '```', 'b'.repeat(30), '## C', 'c'].join('\n');
  assert.deepEqual(splitPages(doc, 1000), [doc]);
  const parts = splitPages(doc, 50);
  assert.equal(parts.length, 3);
  assert.match(parts[1], /^## B\n```\n## not a cut/);
  assert.match(parts[2], /^## C/);

  const wiki = fs.mkdtempSync(path.join(os.tmpdir(), 'wiki-'));
  const src = tree({ 'docs/ROADMAP.md': '# Roadmap\n## One\n' + 'x'.repeat(PAGE_LIMIT) + '\n## Two\ny\n' });
  assert.deepEqual(sync({ root: src, wiki, repo: 'o/r', branch: 'b', section: platform, title: 't' }), ['Platform-ROADMAP']);
  const second = fs.readFileSync(path.join(wiki, 'Platform', 'Platform-ROADMAP-2.md'), 'utf8');
  assert.match(second, /> 第 2 \/ 2 部分（文档过长，按章节分页）：\[1\]\(Platform-ROADMAP\) · \*\*2\*\*\n\n## Two/);
});
