#!/usr/bin/env python3
# F-F5: the one-off replacement of the old terms (docs/quizbuks/plans/impl/F-F5.md). Run from the repository root:
#   python3 tools/quizbuks/terms/rename.py .
# then review the diff (English capitalisation is by rule), revert anything under db/, and run terms.test.mjs.
# Not part of the build; kept so new texts can be passed through it the same way.
# F-F5b (no 后台 / 管理画面 / console) is not here: whether a text needs the name or the in-sentence form of a part
# (docs/quizbuks/glossary.md) depends on the sentence, so those were edited by hand; terms.test.mjs checks them.
import os, re, sys

ROOT = sys.argv[1]
KANA = re.compile(r'[぀-ヿ]')
CJK = re.compile(r'[぀-ヿ一-鿿]')

DIRS = [
    ('quizbuks-web/app/src', ('.ts', '.tsx')),
    ('quizbuks-web/sponsor/src', ('.ts', '.tsx')),
    ('quizbuks-web/maintenance', ('.mjs', '.js', '.html')),
    ('quizbuks-web/biz/src', ('.mjs', '.js', '.json')),
    ('quizbuks-web/biz/test', ('.mjs', '.js')),
    ('quizbuks-web/e2e/tests', ('.ts',)),
    ('backend/quizbuks/admin-extension/src', ('.ts', '.tsx')),
    ('backend/quizbuks/src/main/resources', ('.properties', '.txt', '.json', '.sql', '.html')),
    ('backend/quizbuks/src/main/java', ('.java',)),
    ('backend/quizbuks/src/test/java', ('.java',)),
    ('backend/quizbuks/src/test/resources', ('.yml', '.json')),
    ('docs/quizbuks/legal', ('.md',)),
    ('docs/quizbuks/store', ('.md',)),
    ('tools/quizbuks/store', ('.mjs',)),
]
SKIP = {'docs/quizbuks/legal/README.md'}

def cjk(line):
    line = line.replace('スポンサー', '主催者')
    line = re.sub(r'商户|商家', '发起方', line)
    line = re.sub(r'答题者|答题人', '学者', line)
    if '回答者' in line:
        line = line.replace('回答者', 'テイカー' if KANA.search(line) else '学者')
    return line

# English: the old words, then the role nouns as terms ("the Sponsor", "a Taker").
MERCHANT = re.compile(r'\b[Mm]erchant(s?)\b')
QUIZ_TAKER = re.compile(r'\b[Qq]uiz[- ]taker(s?)\b')
NOUN = re.compile(r"(?<![-/_.:{}@#A-Za-z0-9`'<$])(sponsor|taker)(?=(s|'s|s'|’s|s’)?(?![-/_.:{}A-Za-z0-9`(]))")
NOUN_DOT_OK = re.compile(r"(?<![-/_.:{}@#A-Za-z0-9`'<$])(sponsor|taker)(?=(s|'s|s'|’s|s’)?\.(\s|$|['\"`]))")

def english_text(text):
    text = MERCHANT.sub(lambda m: 'Sponsor' + m.group(1), text)
    text = QUIZ_TAKER.sub(lambda m: 'Taker' + m.group(1), text)
    text = NOUN.sub(lambda m: m.group(1).capitalize(), text)
    text = NOUN_DOT_OK.sub(lambda m: m.group(1).capitalize(), text)
    text = re.sub(r'\b(May|may|to|To|can|Can) Sponsor\b', lambda m: m.group(1) + ' sponsor', text)
    return text

STRING = re.compile(r"'(?:[^'\\\n]|\\.)*'|\"(?:[^\"\\\n]|\\.)*\"|`(?:[^`\\]|\\.)*`")

COMMENT_LINE = re.compile(r'^\s*(//|/\*|\*)')

def english_sentence_literals(line):
    """Only string literals that are sentences (a space, no CJK); placeholders are kept; comments are left."""
    if COMMENT_LINE.match(line):
        return line
    code, sep, comment = line.partition(' // ')
    if sep and code.count("'") % 2 == 0 and code.count('"') % 2 == 0 and code.count('`') % 2 == 0:
        return english_sentence_literals(code) + sep + comment
    def fix(m):
        s = m.group(0)
        if ' ' not in s or CJK.search(s):
            return s
        parts = re.split(r'(\{\{[^}]*\}\}|\$\{[^}]*\}|<[^>]*>)', s)
        return ''.join(p if i % 2 else english_text(p) for i, p in enumerate(parts))
    return STRING.sub(fix, line)

def english_markdown(line):
    parts = re.split(r'(`[^`]*`|https?://\S+|\(\S*\)|\{[^}]*\})', line)
    return ''.join(p if i % 2 else english_text(p) for i, p in enumerate(parts))

def english_properties(line):
    if line.lstrip().startswith('#') or '=' not in line:
        return line
    key, value = line.split('=', 1)
    parts = re.split(r'(\{[^}]*\}|<[^>]*>|https?://\S+)', value)
    return key + '=' + ''.join(p if i % 2 else english_text(p) for i, p in enumerate(parts))

changed = 0
for d, exts in DIRS:
    for base, dirs, names in os.walk(os.path.join(ROOT, d)):
        dirs[:] = [x for x in dirs if x not in ('node_modules', 'dist', 'build')]
        for n in names:
            if not n.endswith(exts):
                continue
            path = os.path.join(base, n)
            rel = os.path.relpath(path, ROOT)
            if rel in SKIP:
                continue
            with open(path, encoding='utf-8') as f:
                lines = f.read().split('\n')
            out = []
            for line in lines:
                new = cjk(line)
                if n == 'messages_en.properties':
                    new = english_properties(new)
                elif n.endswith('.md') and ('.en.' in n or n in ('appstore-review.md', 'play-review.md',
                                                                 'appstore-privacy.md', 'play-data-safety.md',
                                                                 'play-content-rating.md')):
                    new = english_markdown(new) if not CJK.search(new) else new
                elif n.endswith(('.ts', '.tsx', '.mjs', '.js')):
                    new = english_sentence_literals(new)
                elif n.endswith('.java'):
                    # Java: only the old English words in string literals (role names, dictionaries).
                    new = STRING.sub(lambda m: QUIZ_TAKER.sub(lambda q: 'Taker' + q.group(1),
                                     MERCHANT.sub(lambda q: 'Sponsor' + q.group(1), m.group(0))), new)
                out.append(new)
            if out != lines:
                changed += 1
                with open(path, 'w', encoding='utf-8') as f:
                    f.write('\n'.join(out))
print('files changed', changed)
