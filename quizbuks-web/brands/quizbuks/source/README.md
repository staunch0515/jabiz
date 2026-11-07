# 图标与标志的源文件（2026-11-05 换为对话气泡 + 对勾）

- `icon-master.svg`：圆角方块的 App 图标（`public/icon-512.png`、`icon-192.png`，Android 的 `ic_launcher` 与启动画面，iOS 的启动画面）。
- `icon-maskable.svg`：铺满、图形在中心 80 % 的圆内（`public/icon-maskable-512.png`、Android 的 `ic_maskable`）。
- `icon-store.svg`：铺满、无圆角（iOS 的 `AppIcon`、商店上架）。
- `build.py` 生成这三份与 `../mark.svg`（页面上的标志，颜色用 token `--qb-leaf` / `--qb-ink`）；改图形或颜色先改它。
- 由 SVG 渲染各尺寸的 PNG 时直接按目标尺寸渲染（不缩放现有的 PNG）；尺寸见 `quizbuks-mobile/android/README.md` 与 iOS 的 `Assets.xcassets`。
  改了 `mark.svg` 或 `public/` 的图标之后运行 `node quizbuks-web/brands/admin.mjs`（平台管理系统扩展的标志与标签页图标）。
