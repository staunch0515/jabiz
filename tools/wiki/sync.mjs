#!/usr/bin/env node
// Writes one section of the repository's GitHub wiki from Markdown files of a branch (docs/guide/wiki.md).
//
// The wiki is flat: a page is addressed by its name alone, wherever its file lies, so every page gets a name made
// from its section and its path ("Platform-design-09-decisions"), and links between documents are rewritten to
// those names. Each section owns a folder of the wiki checkout and its block in Home and _Sidebar; a run replaces
// exactly those, so the platform and an application can publish from their own branches without touching each
// other's pages.
//
//   node tools/wiki/sync.mjs --wiki <wiki checkout> --repo <owner/name> --branch <branch> \
//     --section <Prefix>:<base>:<include>[,<include>...] --title <section title> \
//     [--link-section <Prefix>:<base>:<include>[,...]]...
//
// <base> is a directory of the source tree; <include> are files or directories under it. --link-section declares
// another section's pages so that links into it become wiki links too (its pages are published by another run).

import fs from 'node:fs';
import path from 'node:path';

/** A section: its page-name prefix, the base directory, and the files and directories under it that are published. */
export function parseSection(spec) {
  const m = /^([A-Za-z][A-Za-z0-9]*):([^:]+):(.+)$/.exec(spec);
  if (!m) throw new Error(`section must be <Prefix>:<base>:<include>[,...]: ${spec}`);
  const base = normalise(m[2]);
  return { prefix: m[1], base, includes: m[3].split(',').map((i) => normalise(path.posix.join(base, i))) };
}

function normalise(p) {
  return path.posix.normalize(p.replaceAll('\\', '/')).replace(/^\.\//, '').replace(/\/$/, '');
}

/** Whether a repository path is published by the section. */
export function inSection(section, repoPath) {
  return repoPath.endsWith('.md') && section.includes.some((i) => repoPath === i || repoPath.startsWith(i + '/'));
}

/** The wiki page name of a published file: the prefix and its path under the base, '/' and spaces as '-'. */
export function pageName(section, repoPath) {
  const rel = path.posix.relative(section.base, repoPath).replace(/\.md$/, '');
  return `${section.prefix}-${rel.replace(/[/\s]+/g, '-')}`;
}

/** The Markdown files of the section in the source tree, sorted. */
export function listFiles(root, section) {
  const out = [];
  for (const inc of section.includes) {
    const abs = path.join(root, inc);
    if (!fs.existsSync(abs)) continue;
    if (fs.statSync(abs).isFile()) {
      if (inc.endsWith('.md')) out.push(inc);
      continue;
    }
    const walk = (dir) => {
      for (const e of fs.readdirSync(path.join(root, dir), { withFileTypes: true })) {
        const p = path.posix.join(dir, e.name);
        if (e.isDirectory()) walk(p);
        else if (e.name.endsWith('.md')) out.push(p);
      }
    };
    walk(inc);
  }
  return [...new Set(out)].sort();
}

const LINK = /(!?)\[([^\]]*)\]\(([^)\s]+)((?:\s+"[^"]*")?)\)/g;

/**
 * Rewrites the links of one document: to a document of a known section, the wiki page (anchors kept); to any other
 * file of the tree, its address on GitHub (images: the raw file). Absolute addresses, anchors within the page, and
 * targets that are no file of the tree (placeholders such as {entryUrl}) stay as written; code is never touched.
 */
export function rewriteLinks(markdown, { repoPath, sections, exists, repo, branch }) {
  const dir = path.posix.dirname(repoPath);
  const target = (bang, raw) => {
    if (/^[a-z][a-z0-9+.-]*:/i.test(raw) || raw.startsWith('#') || raw.startsWith('/')) return null;
    const hash = raw.indexOf('#');
    const file = decodeURI(hash < 0 ? raw : raw.slice(0, hash));
    const anchor = hash < 0 ? '' : raw.slice(hash);
    const resolved = normalise(path.posix.join(dir, file));
    if (resolved.startsWith('..') || !exists(resolved)) return null;
    const section = sections.find((s) => inSection(s, resolved));
    if (section && !bang) return pageName(section, resolved) + anchor;
    const kind = bang ? 'raw' : 'blob';
    const encoded = resolved.split('/').map(encodeURIComponent).join('/');
    return `https://github.com/${repo}/${kind}/${branch.split('/').map(encodeURIComponent).join('/')}/${encoded}${anchor}`;
  };
  let fence = null;
  return markdown
    .split('\n')
    .map((line) => {
      const f = /^\s*(`{3,}|~{3,})/.exec(line);
      if (f) {
        if (fence === null) fence = f[1][0];
        else if (f[1][0] === fence) fence = null;
        return line;
      }
      if (fence !== null) return line;
      return line
        .split(/(`+[^`]*`+)/)
        .map((part, i) =>
          i % 2 === 1
            ? part
            : part.replace(LINK, (whole, bang, text, raw, title) => {
                const t = target(bang, raw);
                return t === null ? whole : `${bang}[${text}](${t}${title})`;
              }),
        )
        .join('');
    })
    .join('\n');
}

/** GitHub renders Markdown up to about 512 KB; a longer document is cut before level-two or level-three headings into parts below this. */
export const PAGE_LIMIT = 400_000;

/** The document as one page, or cut before level-two or level-three headings (outside code) into parts of about PAGE_LIMIT bytes (a
 * section longer than that between two headings stays whole). */
export function splitPages(markdown, limit = PAGE_LIMIT) {
  if (Buffer.byteLength(markdown) <= limit) return [markdown];
  const parts = [];
  let current = [];
  let size = 0;
  let fence = false;
  for (const line of markdown.split('\n')) {
    if (/^\s*(```|~~~)/.test(line)) fence = !fence;
    const bytes = Buffer.byteLength(line) + 1;
    if (!fence && /^#{2,3} /.test(line) && current.length > 0 && size + bytes > limit) {
      parts.push(current.join('\n'));
      current = [];
      size = 0;
    }
    current.push(line);
    size += bytes;
  }
  parts.push(current.join('\n'));
  return parts;
}

/** The document's first level-one heading, or its file name. */
export function titleOf(markdown, repoPath) {
  let fence = false;
  for (const line of markdown.split('\n')) {
    if (/^\s*(```|~~~)/.test(line)) fence = !fence;
    else if (!fence && /^# /.test(line)) return line.slice(2).trim();
  }
  return path.posix.basename(repoPath, '.md');
}

/** Replaces (or adds) the block between the section's markers, keeping the blocks in prefix order. */
export function upsertBlock(text, prefix, block) {
  const blocks = new Map();
  const re = /<!-- wiki-section:([A-Za-z0-9]+) -->\n?([\s\S]*?)<!-- \/wiki-section:\1 -->/g;
  let m;
  let head = null;
  while ((m = re.exec(text)) !== null) {
    if (head === null) head = text.slice(0, m.index);
    blocks.set(m[1], m[2].replace(/\n$/, ''));
  }
  blocks.set(prefix, block.replace(/\n$/, ''));
  const body = [...blocks.keys()]
    .sort()
    .map((k) => `<!-- wiki-section:${k} -->\n${blocks.get(k)}\n<!-- /wiki-section:${k} -->`)
    .join('\n\n');
  return (head ?? text) + body + '\n';
}

const HOME_HEAD = `# jabiz

本 Wiki 由仓库中的文档自动生成，只读：请在仓库中修改文档，合并后自动同步（[docs/guide/wiki.md](Platform-guide-wiki)）。每个版本线只有最新的一条线发布到这里。

`;

/** Groups the section's pages by their first directory under the base ("" for files directly in it). */
function groups(section, files) {
  const by = new Map();
  for (const f of files) {
    const rel = path.posix.relative(section.base, f);
    const g = rel.includes('/') ? rel.slice(0, rel.indexOf('/')) : '';
    if (!by.has(g)) by.set(g, []);
    by.get(g).push(f);
  }
  return [...by.entries()].sort(([a], [b]) => (a === '' ? -1 : b === '' ? 1 : a.localeCompare(b)));
}

/** Writes the section into the wiki checkout. Returns the page names written. */
export function sync({ root, wiki, repo, branch, section, title, linkSections = [] }) {
  const files = listFiles(root, section);
  const sections = [section, ...linkSections];
  const names = new Map();
  for (const f of files) {
    const n = pageName(section, f);
    if (names.has(n.toLowerCase())) throw new Error(`two files give the page ${n}: ${names.get(n.toLowerCase())}, ${f}`);
    names.set(n.toLowerCase(), f);
  }
  const exists = (p) => fs.existsSync(path.join(root, p));

  const dir = path.join(wiki, section.prefix);
  fs.rmSync(dir, { recursive: true, force: true });
  fs.mkdirSync(dir, { recursive: true });
  const titles = new Map();
  for (const f of files) {
    const text = fs.readFileSync(path.join(root, f), 'utf8');
    titles.set(f, titleOf(text, f));
    const source = `https://github.com/${repo}/blob/${branch}/${f}`;
    const banner = `> 自动生成，请勿在 Wiki 上编辑。来源：[\`${f}\`](${source})（分支 \`${branch}\`）。\n\n`;
    const name = pageName(section, f);
    const parts = splitPages(rewriteLinks(text, { repoPath: f, sections, exists, repo, branch }));
    parts.forEach((part, i) => {
      const partName = i === 0 ? name : `${name}-${i + 1}`;
      if (i > 0 && names.has(partName.toLowerCase())) throw new Error(`a part of ${f} gives the page ${partName} of another file`);
      const nav =
        parts.length === 1
          ? ''
          : `> 第 ${i + 1} / ${parts.length} 部分（文档过长，按章节分页）：` +
            parts.map((_, j) => (j === i ? `**${j + 1}**` : `[${j + 1}](${j === 0 ? name : `${name}-${j + 1}`})`)).join(' · ') +
            '\n\n';
      const out = banner + nav + part;
      fs.writeFileSync(path.join(dir, partName + '.md'), out.endsWith('\n') ? out : out + '\n');
    });
  }

  const index = [`# ${title}`, '', `> 自动生成自分支 \`${branch}\`。`, ''];
  for (const [g, fs_] of groups(section, files)) {
    index.push(`## ${g === '' ? '概览' : g}`, '');
    for (const f of fs_) index.push(`- [${titles.get(f)}](${pageName(section, f)})`);
    index.push('');
  }
  fs.writeFileSync(path.join(dir, `${section.prefix}.md`), index.join('\n'));

  const homePath = path.join(wiki, 'Home.md');
  const home = fs.existsSync(homePath) ? fs.readFileSync(homePath, 'utf8') : '';
  const homeBase = home.includes('<!-- wiki-section:') ? home : HOME_HEAD;
  const homeBlock = [`## [${title}](${section.prefix})`, '', `分支 \`${branch}\`，共 ${files.length} 页。`].join('\n');
  fs.writeFileSync(homePath, upsertBlock(homeBase, section.prefix, homeBlock));

  const sidePath = path.join(wiki, '_Sidebar.md');
  const side = fs.existsSync(sidePath) ? fs.readFileSync(sidePath, 'utf8') : '';
  const sideBase = side.includes('<!-- wiki-section:') ? side : '[首页](Home)\n\n';
  const sideBlock = [`**[${title}](${section.prefix})**`, '']
    .concat(
      groups(section, files).map(([g, fs_]) =>
        g === '' ? fs_.map((f) => `- [${titles.get(f)}](${pageName(section, f)})`).join('\n') : `- [${g}](${section.prefix}#${g.toLowerCase()})`,
      ),
    )
    .join('\n');
  fs.writeFileSync(sidePath, upsertBlock(sideBase, section.prefix, sideBlock));

  return files.map((f) => pageName(section, f));
}

function args(argv) {
  const out = { linkSections: [] };
  for (let i = 0; i < argv.length; i += 2) {
    const [k, v] = [argv[i], argv[i + 1]];
    if (v === undefined) throw new Error(`missing value for ${k}`);
    if (k === '--link-section') out.linkSections.push(parseSection(v));
    else if (k === '--section') out.section = parseSection(v);
    else if (['--wiki', '--repo', '--branch', '--title', '--root'].includes(k)) out[k.slice(2)] = v;
    else throw new Error(`unknown option ${k}`);
  }
  for (const k of ['wiki', 'repo', 'branch', 'section', 'title']) if (!out[k]) throw new Error(`--${k} is required`);
  out.root ??= '.';
  return out;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  try {
    const pages = sync(args(process.argv.slice(2)));
    console.log(`${pages.length} pages`);
  } catch (e) {
    console.error(e.message);
    process.exit(1);
  }
}
