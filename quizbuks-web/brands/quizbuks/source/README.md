# 图标与标志的源文件（2026-11-24 换为强调色上的白色大对号，Q113 §9.5）

- `icon-master.svg`：圆角方块的 App 图标（`public/icon-512.png`、`icon-192.png`，Android 的 `ic_launcher` 与启动画面，iOS 的启动画面）。
- `icon-maskable.svg`：铺满、图形在中心 80 % 的圆内（`public/icon-maskable-512.png`、Android 的 `ic_maskable`）。
- `icon-store.svg`：铺满、无圆角（iOS 的 `AppIcon`、商店上架）。
- `build.py` 生成这三份与 `../mark.svg`（页面上的标志，颜色用 token `--qb-mark` / `--qb-mark-foreground`）；改图形或颜色先改它。
- `render.mjs` 由 SVG 按目标尺寸渲染全部 PNG（不缩放现有的 PNG；用 `quizbuks-web/e2e` 的 Playwright）：`public/` 的三个、Android 各密度的 `ic_launcher` / `ic_maskable` / `splash`、iOS 的 `AppIcon` 与启动画面（启动画面与 iOS 图标不带透明通道）。
  改了 `mark.svg` 或 `public/` 的图标之后运行 `node quizbuks-web/brands/admin.mjs`（平台管理系统扩展的标志与标签页图标）。
