# DSH Web Chat UI — Design System Specification

Source of truth: the DSH checkout at `/home/user/bin/deepseek-harness` (read-only).
Every value below is quoted from the real source; the file path is given next to each group.
Where a CSS-module comment disagrees with the code, the **code** is quoted and the comment noted.

Primary files:

| Concern | File |
| --- | --- |
| Color tokens (static + alias, light & dark) | `packages/client/ui-theme/src/styles/design-platform.css` |
| Font families, easing, durations | `packages/client/ui-theme/src/styles/base.css` |
| Font tokens (markdown ladder + UI scale) | `packages/client/ui-theme/src/styles/gradient-shadow-text.css` |
| Shadows / elevation | `packages/client/ui-theme/src/styles/gradient-shadow-text.css` |
| Scrollbar skin | `packages/client/ui-theme/src/styles/scrollbar.css` |
| Syntax highlighting | `packages/client/ui-theme/src/styles/shiki.css` |
| Corner curvature | `packages/client/ui-theme/src/styles/corner-shape.css` |
| Theme bootstrap / palette switch | `packages/client/ui-theme/src/boot-theme.ts`, `packages/client/ui-layout/src/client/theme-presenter.ts` |
| App shell / columns | `packages/client/ui-layout/src/client/AppFrame.module.css`, `.../columns.ts` |
| Sidebar | `packages/client/ui-sidebar/src/client/SidebarRoot.module.css`, `packages/client/ui-workspace/src/client/rows/*.module.css` |
| Session header, transcript host, composer seat | `packages/client/ui-conversation/src/client/skeleton/ConversationRoot.module.css` |
| Composer card + toolbar | `packages/client/ui-conversation/src/client/skeleton/InputBar.module.css` |
| Transcript / messages | `packages/client/ui-chat/src/client/chat/*.module.css` |
| Markdown, code blocks, terminal, diff | `packages/client/ui-primitives/src/markdown/*.module.css`, `packages/client/ui-primitives/src/*.module.css` |
| Tool-call rows | `packages/client/ui-tool/src/client/tool/components/ToolRow.module.css`, `.../ToolCallTree.module.css` |
| Brand mark | `packages/client/ui-primitives/src/FishLogo.tsx`, `BrandWordmark.tsx` |

---

## 0. Theme mechanism (read this first)

- The palette is selected purely by a **body attribute**. Light is the default; dark is
  `document.body[data-ds-dark-theme]` (`design-platform.css` lines 80, 251; `theme-presenter.ts:14`).
- `document.documentElement.style.colorScheme` is `'light' | 'dark'` (native UA chrome, form controls,
  scrollbars). Boot CSS also paints the canvas before JS: `#fff` light, `#151517` dark
  (`boot-theme.ts:11-12`).
- **All tokens are CSS custom properties on `body`**, not `:root`. Only the font families, easings,
  and the Shiki palette (light half) live on `:root`.
- Preference is `light | dark | system`, default `system`; content font size is an integer 12–17 px,
  default 14 (`theme-settings.ts:6,24-30`). It is published as `--dsh-content-font-size` on `body`.
- There is **no `::selection` rule anywhere**. Selection is the browser default, tinted only by
  `color-scheme`. `accent-color` is used for checkboxes (`ui-primitives/src/markdown/MarkdownText.module.css:187`).
- Radii are circular by default. Where supported, `corner-shape: superellipse(1.5)` is applied to
  `*`, `*::before`, `*::after` (`corner-shape.css`). Compose has no equivalent — use plain rounded corners.

---

## 1. Color palette

### 1.1 Static scale (`--dsw-static-*`)

Light and dark blocks are byte-identical **except** `--dsw-static-neutral-bluish-60`
(light `rgb(245,246,247)`, dark `rgb(249,250,251)`). Everything below is therefore theme-independent.

**Neutral (pure grey)**

| Token | Value | Hex |
| --- | --- | --- |
| `--dsw-static-neutral-00` | `rgb(255,255,255)` | `#FFFFFF` |
| `--dsw-static-neutral-50` | `rgb(250,250,250)` | `#FAFAFA` |
| `--dsw-static-neutral-100` | `rgb(245,245,245)` | `#F5F5F5` |
| `--dsw-static-neutral-150` | `rgb(237,237,237)` | `#EDEDED` |
| `--dsw-static-neutral-200` | `rgb(229,229,229)` | `#E5E5E5` |
| `--dsw-static-neutral-250` | `rgb(220,220,220)` | `#DCDCDC` |
| `--dsw-static-neutral-300` | `rgb(212,212,212)` | `#D4D4D4` |
| `--dsw-static-neutral-400` | `rgb(162,164,166)` | `#A2A4A6` |
| `--dsw-static-neutral-500` | `rgb(127,130,135)` | `#7F8287` |
| `--dsw-static-neutral-550` | `rgb(101,103,107)` | `#65676B` |
| `--dsw-static-neutral-600` | `rgb(84,85,87)` | `#545557` |
| `--dsw-static-neutral-700` | `rgb(60,60,61)` | `#3C3C3D` |
| `--dsw-static-neutral-800` | `rgb(41,41,41)` | `#292929` |
| `--dsw-static-neutral-850` | `rgb(33,33,35)` | `#212123` |
| `--dsw-static-neutral-900` | `rgb(15,15,15)` | `#0F0F0F` |
| `--dsw-static-neutral-1000` | `rgb(0,0,0)` | `#000000` |

**Neutral-bluish (the workhorse UI grey — slightly blue-tinted)**

| Token | Value | Hex |
| --- | --- | --- |
| `--dsw-static-neutral-bluish-00` | `rgb(255,255,255)` | `#FFFFFF` |
| `--dsw-static-neutral-bluish-50` | `rgb(249,250,251)` | `#F9FAFB` |
| `--dsw-static-neutral-bluish-60` | light `rgb(245,246,247)` / dark `rgb(249,250,251)` | `#F5F6F7` / `#F9FAFB` |
| `--dsw-static-neutral-bluish-75` | `rgb(241,243,245)` | `#F1F3F5` |
| `--dsw-static-neutral-bluish-100` | `rgb(235,238,242)` | `#EBEEF2` |
| `--dsw-static-neutral-bluish-150` | `rgb(233,236,242)` | `#E9ECF2` |
| `--dsw-static-neutral-bluish-200` | `rgb(225,229,238)` | `#E1E5EE` |
| `--dsw-static-neutral-bluish-300` | `rgb(207,211,214)` | `#CFD3D6` |
| `--dsw-static-neutral-bluish-400` | `rgb(173,178,184)` | `#ADB2B8` |
| `--dsw-static-neutral-bluish-500` | `rgb(151,157,166)` | `#979DA6` |
| `--dsw-static-neutral-bluish-600` | `rgb(129,133,140)` | `#81858C` |
| `--dsw-static-neutral-bluish-700` | `rgb(97,102,107)` | `#61666B` |
| `--dsw-static-neutral-bluish-750` | `rgb(67,69,74)` | `#43454A` |
| `--dsw-static-neutral-bluish-800` | `rgb(53,54,56)` | `#353638` |
| `--dsw-static-neutral-bluish-850` | `rgb(44,44,46)` | `#2C2C2E` |
| `--dsw-static-neutral-bluish-875` | `rgb(35,35,36)` | `#232324` |
| `--dsw-static-neutral-bluish-900` | `rgb(27,27,28)` | `#1B1B1C` |
| `--dsw-static-neutral-bluish-950` | `rgb(21,21,23)` | `#151517` |
| `--dsw-static-neutral-bluish-1000` | `rgb(15,17,21)` | `#0F1115` |

**DeepSeek brand blue ramp (`--dsw-static-deepseek-*`)**

| Token | Value | Hex |
| --- | --- | --- |
| `--dsw-static-deepseek-50` | `rgb(237,243,254)` | `#EDF3FE` |
| `--dsw-static-deepseek-100` | `rgb(228,237,253)` | `#E4EDFD` |
| `--dsw-static-deepseek-200` | `rgb(211,226,255)` | `#D3E2FF` |
| `--dsw-static-deepseek-300` | `rgb(183,200,254)` | `#B7C8FE` |
| `--dsw-static-deepseek-400` | `rgb(103,158,254)` | `#679EFE` |
| `--dsw-static-deepseek-450` | `rgb(86,134,254)` | `#5686FE` |
| `--dsw-static-deepseek-500` | `rgb(65,118,230)` | `#4176E6` |
| `--dsw-static-deepseek-600` | `rgb(72,104,178)` | `#4868B2` |
| `--dsw-static-deepseek-700-delete` | `rgb(47,76,143)` | `#2F4C8F` |
| `--dsw-static-deepseek-800` | `rgb(52,65,91)` | `#34415B` |
| `--dsw-static-deepseek-900` | `rgb(40,49,66)` | `#283142` |

**Blue (`--dsw-static-blue-*`)** — `50 rgb(239,246,255)`, `50p rgb(234,243,255)`, `75 rgb(229,240,255)`,
`100 rgb(219,234,254)`, `300 rgb(147,197,253)`, `400 rgb(96,165,250)`, `450 rgb(77,147,248)`,
`500 rgb(59,130,246)`, `600 rgb(37,99,235)`, `800 rgb(30,64,175)`, `900 rgb(14,48,116)`,
`950 rgb(23,37,84)`.

**Red** — `50 rgb(254,242,242)`, `100 rgb(254,226,226)`, `400 rgb(242,90,90)`, `500 rgb(239,68,68)`,
`600 rgb(236,19,19)`, `900 rgb(87,12,12)`.

**Green** — `100 rgb(230,250,237)`, `400 rgb(78,209,126)`, `500 rgb(34,197,94)`, `900 rgb(35,60,44)`.

**Amber** — `100 rgb(254,245,231)`, `400 rgb(247,173,49)`, `500 rgb(245,158,11)`, `600 rgb(221,134,41)`,
`900 rgb(39,36,31)`.

### 1.2 Alias tokens — **the ones components actually use** (resolved to concrete values)

`#FFFFFF` in light means "same as the app background" — these tokens are intentionally aliased,
not literal, so a re-skin moves everything together.

#### Backgrounds

| Token | Light | Dark |
| --- | --- | --- |
| `--dsw-alias-bg-base` (app / conversation canvas) | `#FFFFFF` | `#151517` |
| `--dsw-alias-bg-layer-1` (popover/preview surface) | `#FFFFFF` | `#232324` |
| `--dsw-alias-bg-layer-2` (dialog surface) | `#FFFFFF` | `#2C2C2E` |
| `--dsw-alias-bg-layer-3` (= menu surface base) | `#FFFFFF` | `#353638` |
| `--dsw-alias-bg-overlay` | `#E9ECF2` | `#61666B` |
| `--dsw-alias-bg-module-platform` | `#F5F6F7` | `#353638` |
| `--dsw-alias-bg-multi-select` | `#F5F6F7` | `#212123` |
| `--dsw-alias-bg-skeleton` | `rgba(0,0,0,0.04)` | `rgba(255,255,255,0.08)` |
| `--dsw-alias-bg-mask-1` (modal scrim) | `rgba(0,0,0,0.24)` | `rgba(0,0,0,0.5)` |
| `--dsw-alias-bg-mask-2` | `rgba(0,0,0,0.12)` | `rgba(0,0,0,0.2)` |
| `--dsw-alias-bg-mask-3` | `rgba(0,0,0,0.48)` | `rgba(0,0,0,0.48)` |
| `--dsw-alias-bg-mask-photo` | `rgba(0,0,0,0.88)` | `rgba(0,0,0,0.88)` |
| `--dsw-alias-bg-mask-drop` | `rgba(255,255,255,0.7)` | `rgba(39,39,48,0.7)` |

Surface tokens (the ones a screen actually paints):

| Token | Light | Dark | Used by |
| --- | --- | --- | --- |
| `--dsw-specific-sidebar-fill` | `#F9FAFB` | `#1B1B1C` | sidebar column |
| `--dsw-specific-input-major` | `#FFFFFF` | `#2C2C2E` | composer card, file card, approval card |
| `--dsw-specific-menu` | `#FFFFFF` | `#353638` | menus, model dropdown, stat dialogs |
| `--dsw-specific-tip` | `#F9FAFB` | `#353638` | todo / queue / goal dock cards |
| `--dsw-specific-selector` | `#F9FAFB` | `#353638` | composer attach (+) circle |
| `--dsw-specific-login-input` | `#F9FAFB` | `#1B1B1C` | login/onboarding input |
| `--dsw-specific-menu` = `--dsw-alias-bg-layer-3` | | | |

#### Text / labels

| Token | Light | Dark | Role |
| --- | --- | --- | --- |
| `--dsw-alias-label-primary` | `#0F1115` | `#F9FAFB` | body text, titles |
| `--dsw-alias-label-primary-dimmed` | `#151517` | `#EBEEF2` | slightly softened primary |
| `--dsw-alias-label-primary-inverted` | `#FFFFFF` | `#353638` | text on inverted fill |
| `--dsw-alias-label-primary-foreground` | `#FFFFFF` | `#0F1115` | text on primary button |
| `--dsw-alias-label-primary-bluish` | `#0E3074` | `#F9FAFB` | hero badge text |
| `--dsw-alias-label-secondary` | `#61666B` | `#CFD3D6` | section text, tool titles |
| `--dsw-alias-label-tertiary` | `#81858C` | `#ADB2B8` | summaries, clocks, hints |
| `--dsw-alias-label-caption` | `#ADB2B8` | `#81858C` | dimmest label / "disabled-ish" |
| `--dsw-alias-label-dimmed` | `#E1E5EE` | `#43454A` | disabled text |
| `--dsw-alias-link` | `#4176E6` | `#679EFE` | markdown links, file mentions |
| `--dsw-alias-brand-primary` | `#0F1115` | `#F9FAFB` | **ink, not blue** |
| `--dsw-alias-brand-text` | `#0F1115` | `#F9FAFB` | wordmark ink |
| `--dsw-alias-brand-primary-new-colorprimary-new-color` | `#4176E6` | `#5686FE` | accent used by dockkit drop UI |
| `--dsw-alias-state-business-primary` | `#4176E6` | `#679EFE` | **the blue accent** (tabs, chips, focus, caret, send-adjacent) |
| `--dsw-alias-state-business-tertiary` | `#E4EDFD` | `#34415B` | business blue tint (reference chip hover, hero badge bg) |

> **Critical mapping trap:** in this sheet `--dsw-alias-brand-primary` is *ink* (near-black / near-white),
> not blue. Every blue accent in the product uses `--dsw-alias-state-business-primary` or the
> `--dsw-static-deepseek-*` ramp. Components that need blue say so in comments
> (e.g. `InputBar.module.css:180`, `ConversationRoot.module.css:198`).

#### Borders / dividers

| Token | Light | Dark |
| --- | --- | --- |
| `--dsw-alias-border-l1` (hairline, menus) | `rgba(0,0,0,0.04)` | `rgba(255,255,255,0.06)` |
| `--dsw-alias-border-l2` (input stroke, body divider) | `rgba(0,0,0,0.1)` | `rgba(255,255,255,0.12)` |
| `--dsw-alias-border-l2-darkmode-thin` | `rgba(0,0,0,0.1)` | `rgba(255,255,255,0.06)` |
| `--dsw-alias-border-l3` (column rule, outline button) | `rgba(0,0,0,0.12)` | `rgba(255,255,255,0.16)` |
| `--dsw-alias-border-l4` (dashed affordance, panel edge) | `rgba(0,0,0,0.16)` | `rgba(255,255,255,0.2)` |
| `--dsw-alias-border-inverted` | `rgba(0,0,0,0)` | `rgba(255,255,255,0.06)` |
| `--dsw-alias-border-inverted2` | `rgba(0,0,0,0)` | `rgba(255,255,255,0.08)` |

#### Semantic state

| Token | Light | Dark |
| --- | --- | --- |
| `--dsw-alias-state-success-primary` | `#22C55E` | `#22C55E` |
| `--dsw-alias-state-success-secondary` | `#4ED17E` | `#4ED17E` |
| `--dsw-alias-state-success-tertiary` | `#E6FAED` | `#233C2C` |
| `--dsw-alias-state-error-primary` | `#EC1313` | `#F25A5A` |
| `--dsw-alias-state-error-secondary` | `#F25A5A` | `#F25A5A` |
| `--dsw-alias-state-warn-primary` | `#F59E0B` | `#F59E0B` |
| `--dsw-alias-state-warn-secondary` | `#F7AD31` | `#F7AD31` |
| `--dsw-alias-state-warn-tertiary` | `#FEF5E7` | `#27241F` |
| `--dsw-alias-state-warn-label` | `#DD8629` | `#DD8629` |

#### Interaction fills

| Token | Light | Dark |
| --- | --- | --- |
| `--dsw-alias-interactive-bg-hover` | `rgba(38,49,72,0.06)` | `rgba(255,255,255,0.08)` |
| `--dsw-alias-interactive-bg-active` | `rgba(38,49,72,0.1)` | `rgba(255,255,255,0.14)` |
| `--dsw-alias-interactive-bg-hover-accent` | `rgba(38,49,72,0.14)` | `rgba(255,255,255,0.24)` |
| `--dsw-alias-interactive-bg-hover-danger` | `rgba(236,19,19,0.05)` | `rgba(242,90,90,0.15)` |
| `--dsw-alias-interactive-bg-hover-solid` | `#F1F3F5` | `#353638` |

#### Buttons / toast / tooltip

| Token | Light | Dark |
| --- | --- | --- |
| `--dsw-alias-button-primary-fill` | `#0F1115` | `#F9FAFB` |
| `--dsw-alias-button-primary-hover` | `#43454A` | `#EBEEF2` |
| `--dsw-alias-button-primary-dimmed` | `#EBEEF2` | `#43454A` |
| `--dsw-alias-button-info-fill` | `#4176E6` | `#679EFE` |
| `--dsw-alias-button-info-hover` | `#679EFE` | `#4176E6` |
| `--dsw-alias-button-elevated-fill` | `#FFFFFF` | `#43454A` |
| `--dsw-alias-button-floating-fill` | `#FFFFFF` | `#2C2C2E` |
| `--dsw-alias-button-floating-hover` | `#F1F3F5` | `#353638` |
| `--dsw-alias-button-contrast-fill` | `#61666B` | `#F9FAFB` |
| `--dsw-alias-button-ghost-active-fill` | `#EBEEF2` | `#43454A` |
| `--dsw-alias-button-ghost-active-hover` | `#E9ECF2` | `#61666B` |
| `--dsw-alias-button-ghost-active-border` | `#979DA6` | `#81858C` |
| `--dsw-alias-button-tool-bar-fill` | `rgba(84,85,87,0.5)` | `rgba(84,85,87,0.5)` |
| `--dsw-alias-button-tool-bar-hover` | `rgba(84,85,87,0.6)` | `rgba(84,85,87,0.6)` |
| `--dsw-alias-button-tool-bar-fill-invisible` | `rgba(31,31,31,0.36)` | `rgba(31,31,31,0.36)` |
| `--dsw-alias-toast-bg` | `#353638` | `#43454A` |
| `--dsw-alias-tooltip-bg` | `#2C2C2E` | `#43454A` |

#### Sidebar item + bubble + markdown surfaces

| Token | Light | Dark |
| --- | --- | --- |
| `--dsw-specific-sidebar-nav-item-hover` | `#F1F3F5` | `#2C2C2E` |
| `--dsw-specific-sidebar-nav-item-active` | `#EBEEF2` | `#43454A` |
| `--dsw-specific-sidebar-nav-item-active-accent` | `#E4EDFD` | `#353638` |
| `--dsw-specific-bubble` (user message) | `#EDF3FE` | `#2C2C2E` |
| `--dsw-specific-bubble-highlight` | `#D3E2FF` | `#43454A` |
| `--dsw-alias-markdown-code-block` | `#F9FAFB` | `#1B1B1C` |
| `--dsw-alias-markdown-code-block-banner` | `#F9FAFB` | `#2C2C2E` |
| `--dsw-alias-markdown-inline-code` | `#FAFAFA` | `#292929` |
| `--dsw-alias-markdown-code-segment-selected` | `#FFFFFF` | `#353638` |
| `--dsw-alias-markdown-code-segment-unselected` | `#F1F3F5` | `#1B1B1C` |
| `--dsw-alias-markdown-citation` | `#EBEEF2` | `#353638` |
| `--dsw-alias-markdown-tag` | `#F1F3F5` | `#2C2C2E` |
| `--dsw-alias-markdown-placeholder` | `#F5F6F7` | `#2C2C2E` |

#### Scrollbar

| Token | Light | Dark |
| --- | --- | --- |
| `--dsw-alias-scrollbar-bg-l1` (base surfaces) | `#E5E5E5` | `#3C3C3D` |
| `--dsw-alias-scrollbar-hover-l1` | `#D4D4D4` | `#545557` |
| `--dsw-alias-scrollbar-bg-l2` (elevated surfaces) | `#E5E5E5` | `#545557` |
| `--dsw-alias-scrollbar-hover-l2` | `#D4D4D4` | `#65676B` |

Scrollbar geometry (`scrollbar.css:16-26, 67-94`): width/height **8px** (`--dsh-scrollbar-width`),
thumb `border-radius: 999px`, transparent track, track margin `0px` by default (2px in the transcript
and session list), thumb color via the `l1` pair on base surfaces and the `l2` pair on elevated ones.
Firefox path: `scrollbar-width: thin`.

### 1.3 Syntax highlighting (Shiki CSS-variables theme)

`shiki.css` — background/foreground alias the markdown code-block tokens, so the plain and highlighted
blocks agree. These are the exact token colors:

| Shiki variable | Light | Dark |
| --- | --- | --- |
| `--shiki-foreground` | `var(--dsw-alias-label-primary)` | same |
| `--shiki-background` | `var(--dsw-alias-markdown-code-block)` | same |
| `--shiki-token-constant` | `#1c7ed6` | `#4dabf7` |
| `--shiki-token-string` | `#2f9e44` | `#69db7c` |
| `--shiki-token-comment` | `#868e96` | `#adb5bd` |
| `--shiki-token-keyword` | `#d6336c` | `#faa2c1` |
| `--shiki-token-parameter` | `#e8590c` | `#ffa94d` |
| `--shiki-token-function` | `#6741d9` | `#b197fc` |
| `--shiki-token-string-expression` | `#2b8a3e` | `#8ce99a` |
| `--shiki-token-punctuation` | `#495057` | `#ced4da` |
| `--shiki-token-link` | `#1971c2` | `#74c0fc` |

### 1.4 Diff colors

Removed = `--dsw-alias-state-error-primary`; added = `--dsw-alias-state-success-primary`;
context = `--dsw-alias-label-secondary`; path = `--dsw-alias-label-primary` + `font-weight: 600`;
gap/expand = `--dsw-alias-label-tertiary`. A literal `- ` / `+ ` prefix is drawn before each line
(`DiffBlock.module.css:61-85`).

### 1.5 Selection

No custom rule. Android should use the platform text-selection handles; if a tint is desired, the
closest product color is `--dsw-alias-state-business-tertiary` (`#E4EDFD` / `#34415B`) with
`--dsw-alias-state-business-primary` handles.

---

## 2. Typography

### 2.1 Families (`base.css:6-14`)

```css
--dsw-font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC',
  'Hiragino Sans GB', 'Microsoft YaHei', 'Helvetica Neue', Helvetica, Arial, sans-serif;
--ds-font-family-code: 'SF Mono', 'JetBrains Mono', 'Fira Code', Consolas,
  'Liberation Mono', Menlo, Courier, 'PingFang SC', 'Microsoft YaHei';
```

No webfont is loaded (no `@font-face` outside KaTeX math). So the UI is **system font** on every
platform: on Android that is `Roboto` (and the CJK fallbacks `Noto Sans CJK`/`PingFang` equivalents).
The code family's deliberate omission of a bare `monospace` tail is a Windows-CJK workaround
(`base.css:1-5`) — on Android use `monospace` → `Roboto Mono`/`Droid Sans Mono`.
Some sheets prepend `Inter` before the family (`QueueDock.module.css:87,190`), which never loads
either; ignore it.

### 2.2 Content font-size axis (`gradient-shadow-text.css:54-57`)

```css
--dsh-content-font-delta: calc(var(--dsh-content-font-size, 14px) - 14px);
--dsh-content-font-size-secondary: min(calc(var(--dsh-content-font-size,14px) - 1px),
                                       max(13px, calc(var(--dsh-content-font-size,14px) - 2px)));
--dsh-content-font-delta-secondary: calc(var(--dsh-content-font-size-secondary) - 13px);
```

`--dsh-content-font-size` ∈ {12..17}, default 14. Secondary size = **11,12,13,13,14,15** for
12,13,14,15,16,17. Body line-height = `24px + delta` (so 22,23,24,25,26,27). Secondary line-height =
`20px + delta-secondary` (18,19,20,20,21,22).

### 2.3 Markdown / content ladder (`gradient-shadow-text.css:59-177`)

| Token | Weight | Size (default) | Line-height (default) |
| --- | --- | --- | --- |
| `--dsw-font-markdown-h1` | 700 | 21px + delta | 30px + delta |
| `--dsw-font-markdown-h2` | 700 | 19px + delta | 28px + delta |
| `--dsw-font-markdown-h3` | 700 | 18px + delta | 26px + delta |
| `--dsw-font-markdown-h4` | 600 | 14px (axis) | 24px + delta |
| `--dsw-font-markdown-base` | 400 | 14px (axis) | 24px + delta |
| `--dsw-font-markdown-base-strong` | 600 | 14px (axis) | 24px + delta |
| `--dsw-font-markdown-base-italic` | 400 italic | 14px (axis) | 24px + delta |
| `--dsw-font-markdown-base-strong-italic` | 600 italic | 14px (axis) | 24px + delta |
| `--dsw-font-markdown-table` | 400 | 13px (secondary) | 22px + d2 |
| `--dsw-font-markdown-table-head` | 500 | 13px (secondary) | 22px + d2 |
| `--dsw-font-markdown-small` | 400 | 12px | 20px |
| `--dsw-font-markdown-small-strong` | 600 | 12px | 20px |
| `--dsw-font-markdown-code` (inline) | 400 | 12px | 19px |
| `--dsw-font-markdown-code-block` | 400 | **11px** | 19px |
| `--dsw-font-markdown-code-block-small` | 400 | **11px** | 16px |

Markdown block spacing: `p` margin `16px 0`; h1–h3 margin `32px 0 16px`; h4–h6 `16px 0`
and `8px` against a following list; `ul/ol` `padding-left: 18px`, `li + li` `margin-top: 6px`,
nested list `margin-top: 4px`; `hr` is a `0.5px` line in `border-l2` with `32px 0`;
`blockquote` `border-left: 2px solid label-caption; padding-left: 14px`.
Inline code chip (`MarkdownText.module.css:161-172`): `0.875em` (so ~12px at 14px body),
`background-color: markdown-inline-code`, `border: 0.5px solid border-l1`, `border-radius: 6px`,
`padding: 0 5px`.

### 2.4 UI ladder (`gradient-shadow-text.css:179-268`)

| Token | Weight | Size | Line-height | Typical use |
| --- | --- | --- | --- | --- |
| `--dsw-font-xl-24` | 600 | 24px | 32px | — (settings headings) |
| `--dsw-font-l-20` | 500 | 20px | 28px | — |
| `--dsw-font-m-18` | 500 | **16px** | 28px | modal-ish title (token name is a lie) |
| `--dsw-font-base-16` | 400 | 16px | 24px | — |
| `--dsw-font-base-strong-16` | 500 | 16px | 24px | — |
| `--dsw-font-s-14` | 400 | 14px | 22px | menu items, sidebar rows |
| `--dsw-font-s-strong-14` | 500 | 14px | 22px | selected sidebar item |
| `--dsw-font-xs-13` | 400 | 13px | 20px | chips, tool summaries, timestamps |
| `--dsw-font-xs-strong-13` | 500 | 13px | 20px | model select, composer chips |
| `--dsw-font-xxs-12` | 400 | 12px | 18px | small labels, meta |
| `--dsw-font-xxs-strong-12` | 500 | 12px | 18px | tags, badges |
| `--dsw-font-xxxs-11` | 400 | 11px | 14px | dense code labels |
| `--dsw-font-xxxs-strong-11` | 500 | 11px | 14px | dense labels |

### 2.5 Named element type (measured from the components)

| Element | Spec | Source |
| --- | --- | --- |
| Sidebar brand wordmark | 18px / 600 / 24px, `letter-spacing: 0.04em` | `SidebarRoot.module.css:151-155` |
| Sidebar brand fallback | 17px, `letter-spacing: 0` | `SidebarRoot.module.css:157-161` |
| Local-build 2-line title | 12px / 13px | `SidebarRoot.module.css:174-178` |
| Build version chip | mono 6px / 500 / 10px | `SidebarRoot.module.css:232-246` |
| Sidebar panel row | inherits 14px, `line-height: 22px`; active = `font-weight: 500` | `SidebarRoot.module.css:308-334` |
| New Session button | 14px / 500 / 22px | `SidebarRoot.module.css:250-269` |
| Session list section label | 14px lineage / 20px | `WorkspaceBrowser.module.css:59-74`, `Rows.module.css:170-177` |
| Project row title | 14px / 20px | `Rows.module.css:170-177` |
| Project row meta / time | 12px / 20px, tertiary | `Rows.module.css:195-209` |
| Search result title | 14px / 20px | `Rows.module.css:52-61` |
| Search result meta | 12px / 17px | `Rows.module.css:71-78` |
| Session-header crumb | 14px / 20px, tertiary; current = 500 primary | `ConversationRoot.module.css:94-122` |
| Crumb separator `/` | 14px / 20px, caption | same, `:87-92` |
| View tab | 13px / 16px / 500 | `ConversationRoot.module.css:173-183` |
| Hero headline | 26px / 500 / 32px | `HeroShell.module.css:41-44` |
| Hero preview badge | mono 12px / 500 / 18px | `HeroShell.module.css:62-74` |
| **User message bubble** | `--dsh-content-font-size` (14) / `24px + delta`, primary | `MessageItem.module.css:23-41` |
| **Assistant markdown** | axis size / `24px + delta`, primary; block gap 16px | `AssistantMarkdown.module.css:9-21` |
| Reasoning (Think) summary | secondary (13) / `20px + d2`, tertiary | `ReasoningRow.module.css:77-85` |
| Reasoning body | secondary (13) / `20px + d2`, tertiary, `pre-wrap` | `ReasoningRow.module.css:107-115` |
| Tool row title | secondary (13) / `24px + delta`, secondary | `DisclosureRow.module.css:79-84` |
| Tool row summary | secondary (13) / `24px + delta`, tertiary | `ToolRow.module.css:81-90` |
| Tool IN/OUT card | 11px / 16px mono, secondary | `ToolRow.module.css:212-220`, `--dsw-font-markdown-code-block-small` |
| Code block banner | 11px / 18px UI face; infostring 11px / 18px **mono** | `CodeBlock.module.css:9,45-54` |
| Code block body | 11px / 19px mono | `CodeBlock.module.css:10,78-79` |
| Markdown table cell | 13px / 22px; head 500 | `MarkdownText.module.css:242-260` |
| Message timestamp | secondary (13) / `24px + delta`, tertiary, tabular clock | `MessageIconActions.module.css:15-31` |
| Stats pill | secondary (13) / `20px + d2`, tertiary, tabular-nums | `StatsPills.module.css:5-39` |
| Button (md) | 14px / 22px, h36 | `Button.module.css:4-26` |
| Button (sm) | 12px / 18px, h28 | `Button.module.css:30-36` |
| Pill primitive | 12px / 18px, h24 | `Pill.module.css:1-13` |
| Tag primitive | 11px / 17px / 500 | `Tag.module.css:1-14` |
| Modal title | 16px / 500 / 24px | `Modal.module.css:53-59` |
| Modal description | 14px / 22px | `Modal.module.css:80-87` |
| Menu item | 14px / 22px, min-h 40 | `Menu.module.css:96-113` |
| Menu heading label | 12px / 16px, tertiary | `Menu.module.css:212-217` |
| Tooltip | 13px / 20px, white on `tooltip-bg` | `Tooltip.module.css:9-15` |
| Composer input | axis size / `24px + delta` | `InputBar.module.css:62-63,166-181` |
| Composer placeholder | caption color, single line (hero clamps to 2) | `InputBar.module.css:206-219,236-244` |
| Composer chips (Plan/Read-only) | 13px / 20px / 500 | `InputBar.module.css:322-341` |
| Model trigger | 13px / 20px / 500; effort value in caption | `ModelSelect.module.css:9-63` |
| Todo/Queue/Goal card title | 13px / 24px / 500 | `TodoPanel.module.css:60-66`, `GoalBar.module.css:42-48` |
| Approval headline | 15px / 500 / 24px | `ApprovalPanel.module.css:49-54` |
| Approval command | mono 13px / 20px | `ApprovalPanel.module.css:56-62` |

---

## 3. Spacing, shape, elevation

### 3.1 Border radius inventory (actual values)

| Radius | Used for |
| --- | --- |
| `1px` | 2×2 dot separators' inner radius; context-meter segments |
| `2px` | build-version chip, badge rect, context swatch |
| `3px` | retry summary |
| `4px` | rename input, 16px icon button, thumbnails, retry button |
| `5px` | compact menu item |
| `6px` | tool call row / sub-call row, inline code, reference chip, ContextMeter input, file chip, hidden `ioSection` scrollbar thumb inside override |
| `7px` | compact (dense) menu card |
| `8px` | sidebar panel row, session/project row, search-result row, composer notice, image, tooltip, focus ring, `ContextInjectionRow` body |
| `10px` | menu item, model dropdown option/cell, expanded search field |
| `12px` | code block, terminal card, diff card, IN/OUT card, todo/queue/goal cards, dockkit tab chip, New Session button, stat dialog, `AskQuestionCard` |
| `14px` | "older messages" button, small button |
| `16px` | attachment file card, hero workspace chip, attachment thumbnail |
| `18px` | medium button capsule |
| `20px` | **menu card** (the comment says r12 — code is `20px`), approval card, dockkit floating panel |
| `22px` | user message bubble, composer card, dialog text input, dashed no-workspace ring |
| `24px` | modal dialog, hero preview badge |
| `28px` | message action button, dockkit icon button |
| `999px` / `50%` | pills, tags, circles (attach, send, to-bottom, icon buttons, scrollbar thumb) |

`--dsl-code-block-border-radius: 12px`, `--dsl-terminal-radius: 12px`, `--dsl-diff-radius: 12px`.

### 3.2 Spacing scale actually in use

Observed values form a de-facto 2px grid: `2, 3, 4, 5, 6, 7, 8, 10, 12, 14, 16, 18, 20, 22, 24, 28, 32, 36, 38, 40, 44, 52, 56, 60, 64, 76, 141, 150, 180, 224, 260, 300, 320, 336, 360, 420`.
The recurring rhythm is **4 / 6 / 8 / 12 / 16 / 24 / 32**.

### 3.3 Border widths

- Hairlines are **`0.5px`** for panel/card strokes, dividers, outline buttons, code/terminal/diff borders,
  and the column rules (`border-l1/l2/l3/l4`).
- `1px` for: approval card border (`state-warn-secondary`), retry button, connection indicator.
- `<hr>` in markdown is a `0.5px` height block, not a border.

### 3.4 Elevation / shadow (`gradient-shadow-text.css:5-35`)

```css
--dsw-shadow-lv1:        0 2px 4px 0 rgba(0,0,0,0.05);
--dsw-shadow-lv1-blur:   0 4px 12px 0 rgba(0,0,0,0.02);
--dsw-shadow-lv2:        0 4px 12px 0 rgba(0,0,0,0.02), 0 2px 8px 0 rgba(0,0,0,0.04);
--dsw-shadow-lv3:        0 0 1px 0 rgba(0,0,0,0.2), 0 0 4px 0 rgba(0,0,0,0.02), 0 12px 32px 0 rgba(0,0,0,0.08);

--dsw-elevation-stroke:    0 0 0 0.5px var(--dsw-elevation-stroke-color);
--dsw-elevation-panel:     var(--dsw-elevation-stroke), 0 3px 8px 0 rgba(0,0,0,0.03), 0 0 16px 0 rgba(0,0,0,0.02);
--dsw-elevation-prominent: var(--dsw-elevation-stroke), 0 3px 8px 0 rgba(0,0,0,0.04), 0 0 20px 0 rgba(0,0,0,0.05);
--dsw-elevation-soft:      var(--dsw-elevation-stroke), 0 4px 16px 0 rgba(0,0,0,0.03), 0 0 24px 0 rgba(0,0,0,0.03);

--dsw-mask-blur: blur(2px);
```

`--dsw-elevation-stroke-color` defaults to `--dsw-alias-border-l4`, but each surface rebinds it:

| Surface | Stroke color | Shadow |
| --- | --- | --- |
| Menus, popovers, stat dialogs | `border-l1` | `elevation-prominent` |
| Composer input card | `border-l2` (transparent in the no-workspace trigger state) | `elevation-soft` |
| Back-to-bottom, turn preview | `border-l3` | `elevation-panel` |
| Attachment rail arrows | `border-l2-darkmode-thin` | `elevation-panel` |
| Approval card | — | `shadow-lv2` |
| Toast / tooltip | no shadow | — |

Note the design idiom: **hairline stroke drawn inside `box-shadow`** (so it costs no layout) + one or
two very low-alpha glow layers. The dark theme relies on the stroke, because the glow is nearly invisible.

### 3.5 Motion

```css
--ds-ease-in-out: cubic-bezier(0.4, 0, 0.2, 1);
--ds-transition-duration: 0.2s;
--ds-transition-duration-fast: 0.1s;
--ds-transition-duration-slow: 0.3s;
```

Sidebar/track collapse uses the `slow` duration on the ease-in-out curve. Hover fades are 80–160ms.
The turn-navigator rail and the mark ladder use `cubic-bezier(0.2, 0.8, 0.2, 1)` at 140/220ms.
Every animation is disabled under `prefers-reduced-motion: reduce`.

Recurring key effects:
- **Running tool row / Think row**: a 300px-wide glare band (`transparent → color-mix(bg-base 60%, transparent)
  → transparent`) sweeping left→right over 2.6s, `ease-out infinite`.
- **Turn status text**: a blue shimmer (`deepseek-500 → deepseek-200 → deepseek-500`) clipped to text,
  1.8s linear infinite.
- **Retry text shimmer**: label-tertiary → label-secondary, 1.6s ease-in-out.
- **StateDot "ongoing"**: 5×5 pixel-chase matrix, cells at opacity 1 / 0.6 / 0.35 / 0.15, 1s infinite,
  per-cell delay `index * -125ms`.
- **Composer pending dot**: 8px `state-business-primary` circle pulsing opacity 0.35→1, 1s alternate.

---

## 4. Layout geometry — main chat screen

### 4.1 App frame (`AppFrame.module.css`, `columns.ts`)

- The frame is a CSS grid with a single row of 3 columns: `[sidebar | center | rightbar]`, full viewport,
  background `bg-base`.
- **Sidebar** width: default **280px**, drag clamp **264–420px**, closed rail **56px**.
  Auto-collapses below **1024px** viewport.
- **Center** protected minimum when the right column is open: **400px**.
- **Right panel**: floor **300px**, max **70%** of frame, first-open preference **45%** of frame;
  the track drops entirely when it can't fit.
- Divider drag handles are 8px hit strips (`margin-left: -4px`), no visible pill.
- Sidebar right border: `0.5px solid border-l3`; right panel left border: `0.5px solid border-l4`.
- Fullscreen right panel is `position: fixed; inset: 0; z-index: 40`; normal panel `z-index: 10`;
  floating panels `z-index: 60`; frame overlay layer `z-index: 20`; modals `1000`; menus `1100`.

### 4.2 Sidebar (`SidebarRoot.module.css`)

| Region | Geometry |
| --- | --- |
| Root (expanded) | `padding: 6px 12px`; `--dsh-sidebar-inline-padding: 12px`; `font-size: 14px`; fill `specific-sidebar-fill` |
| Root (rail) | `padding: 18px 10px 6px`; every control 36×36 |
| Logo row | height **60px** (`padding: 8px 0 8px 4px; margin-bottom: 8px`); rail variant 36px, `margin-bottom: 12px` |
| Brand identity | `gap: 8px`, `height: 24px` (mark + name) |
| Panel nav rows | `min-height: 36px`, `padding: 7px 8px`, `gap: 8px`, `border-radius: 8px`; list `gap: 4px`, `margin-bottom: 8px` |
| New Session | `height: 38px`, `padding: 8px 16px`, `margin: 0 2px 8px`, `border: 0.5px solid border-l3`, `border-radius: 12px`, fill `button-elevated-fill`, `gap: 6px` |
| Region/list area | `flex: 1`, left margin `-4px`, right margin `-(12px)`, `padding-left: 4px` |
| Footer | settings + additive actions, `flex-direction: column` |

Icon buttons: 28×28 circle (expanded) / 36×36 (rail), `border-radius: 50%`, `color: label-secondary`
(rail uses `label-primary`).

### 4.3 Session-list screen (`WorkspaceBrowser.module.css`, `Rows.module.css`)

| Element | Geometry |
| --- | --- |
| Browser root | `flex: 1`, `padding-right: 12px` (`--dsh-session-list-edge-inset`), scrollbar width 8px, offset 2px |
| Section header | height **36px**, `padding-left: 4px`, `margin-bottom: 4px`, `border-radius: 12px`, `justify-content: flex-end`, `gap: 4px` |
| Section label | max-width 45%, `line-height: 20px`, tertiary |
| Inline search (collapsed) | 28px circle; expanded: full width, height 30px, `border: 0.5px solid border-l4`, `border-radius: 10px`, `padding: 0 4px 0 0` |
| List rows | `padding-bottom: 16px`; `scrollbar-gutter: stable`; row gap **2px** |
| **Project row** | height **34px**, `padding: 0 8px`, `gap: 6px`, `border-radius: 8px` |
| **Session row** | height **32px**, `gap: 0`, `border-radius: 8px`, `padding: 0 8px`; status slot 16×20px; title `margin: 0 6px 0 4px` |
| Session title | 14px / 20px, primary, ellipsis |
| Session time | 12px / 20px, tertiary; hidden on hover (replaced by row actions) |
| Row hover | `background: interactive-bg-hover`; selected same fill |
| Trailing actions | hidden until hover; 16px glyphs, `gap: 12px`, tertiary |
| Overflow "show more" | height 28px, `padding: 0 12px 0 28px`, `border-radius: 8px`, 12px tertiary |
| Bottom fade | absolute, height 24px, `linear-gradient(to bottom, transparent, specific-sidebar-fill)` |
| Hover card | dark surface both themes: title `#FFFFFF` 14/20, path & time `#CFD3D6` 12/16, status `#ADB2B8` 12/20 |
| Drop indicator | 2px `state-business-primary` rule + chevron, 12px tall, sits ±7–8px from the row edge |

### 4.4 Session header (center column) (`ConversationRoot.module.css`)

| Element | Geometry |
| --- | --- |
| Header | `min-height: 76px`, `padding: 10px 28px 0 20px`, `border-bottom: 0.5px solid border-l3` |
| Title row | `min-height: 30px`, cluster `gap: 10px` |
| Crumb | `padding: 4px 8px`, `border-radius: 12px`, `max-width: 220px`, ellipsis; hover fill `interactive-bg-hover`; current 500 primary |
| Subagent crumb | 12px / 18px |
| Header utilities / actions | `gap: 8px`; utilities `margin-left: 20px`; corner `margin: 0 -16px 0 8px` |
| View tabs strip | `gap: 36px`, `margin-top: 10px`, `padding-left: 8px`; tab `padding: 0 0 9px`, 13px/16/500; active = `state-business-primary` text + a 2px underline bar reaching `bottom: -1px` |

The header is hidden entirely for a blank/hero session (`.headerHidden { display: none }`).

### 4.5 Transcript (`ChatView.module.css`, `ConversationRoot.module.css`)

- The scrollport (`scrollBody`) owns scrolling in the active phase, has `margin-right: 2px` and
  `scrollbar-gutter: stable`; its WebKit track has `margin: 2px`.
- The transcript scroller adds `padding: 16px calc(--dsh-composer-side-clearance + 16px)` = **16px 32px**
  on each side, so the transcript is exactly 32px narrower than the composer card.
- **Message column** `.column`: `max-width: var(--dsh-chat-content-width)`, centered (`margin: 0 auto`).
  ```css
  --dsh-chat-content-width: var(--dsh-chat-user-width,
    clamp(680px, calc(var(--dsh-conversation-column-width) * 0.64), 920px));
  ```
  i.e. **680px floor, 64% of the live column width, 920px cap** (the Figma source was 748px).
- Row rhythm: every non-empty flow item after another gets `margin-top: var(--dsh-chat-flow-gap, 16px)`.
  A *closed* turn-process row followed by its answer tightens to **8px**; expanded process rows use 16px.
- Back-to-bottom: sticky slot, `bottom: 16px` (or `composer height + 16px`), button 34×34 circle,
  `border-radius: 100px`, fill `button-floating-fill`, `elevation-panel` with `border-l3` stroke,
  `margin-top: -34px`, right-aligned to the content column edge, `z-index: 8`.
- **Turn navigator rail**: 28px wide, sticky at the scrollport top, vertically centered in
  `(viewport height − composer height)`, `right: 12px - 32px`, max height `min(natural, band − 64px, 420px)`.
  Marks are 12×2px rounded ticks in `border-l4` (8px wide/60% opacity when unloaded, 18px tertiary on
  preview, 20px primary when active, business blue on focus). Hidden below a 900px container width.
  Preview card: up to 300px wide, `padding: 10px 12px`, `border-radius: 10px`, `bg-layer-1`,
  `elevation-panel`; prompt 13px/500 clamp-1, response 12px clamp-3 caption.

### 4.6 Message rendering rules

- **User messages are bubbles, right-aligned.** `.userRow` is `flex-direction: column; align-items: flex-end; gap: 6px`.
  `.userStack` holds bubble + action row, `gap: 8px`, `max-width: min(contentWidth * 0.702, 82%)`.
  Bubble: `background: specific-bubble` (`#EDF3FE` / `#2C2C2E`), `border-radius: 22px`,
  `padding: 10px 16px`, body size / `24px + delta`, `label-primary`, `white-space: pre-wrap`,
  `word-break: break-word`. At the default 14px this is a 42px single-line bubble (22 line + 2×10).
- **Assistant messages are plain, full-width markdown** — no bubble, no avatar. `.root` is a flex column,
  `.body` blocks separated by `gap: 16px`. The message footer (icon actions) sits at `margin-top: 16px`,
  `margin-left: -6px` (optical alignment with 28px icon targets that pad 6px past the glyph).
- **No avatars or monograms anywhere** in the transcript. Identity is conveyed by alignment (user right)
  and by the flow-row leading icons for process items.
- **Interrupted assistant tail**: a small inline tag, `padding: 0 6px`, `border-radius: 6px`,
  `background: interactive-bg-hover`, `label-tertiary`, 11px/18px.

### 4.7 Tool-call rendering (`ToolRow.module.css`, `ToolCallTree.module.css`, `DisclosureRow.module.css`)

- One collapsed summary row per call: `height: 24px + delta`, layout
  `[16px leading] gap 6 [title 13/24] gap 8 [2×2 separator dot] gap 8 [summary, flex, ellipsis]`.
  The leading glyph is a 14px icon inside a 16px box; on hover the leading icon crossfades to a chevron.
- Row title/summary ride the secondary tier (13px). File paths get a dotted `label-tertiary` underline
  that becomes `currentColor` on hover.
- Expanded bodies are indented `margin: 4px 0 4px 4px`:
  - **IN/OUT card**: `border: 0.5px solid border-l1`, `border-radius: 12px`,
    `background: markdown-code-block`, 11px/16 mono. Two sections in a
    `grid-template-columns: max-content 1fr; column-gap: 14px`, `padding: 12px 16px`,
    each `max-height: 150px` and scrolling independently. A full-width `0.5px border-l2` divider between
    them. Gutter labels `IN`/`OUT` are sticky, `label-caption`.
  - **Bash/terminal card**: 12px radius, same code surface, left gutter 30px holding an 8px state dot;
    banner `padding: 9px 14px 9px 30px`, `max-height: 150px`; `0.5px border-l2` under the banner; output
    `padding: 12px 14px 12px 0`, `max-height: 224px`, `white-space: pre`. Prompt 11px/18 mono,
    `$` cwd in tertiary, command in primary with ellipsis.
  - **Diff card**: 12px radius, body `padding: 12px 14px`, 11px/19 mono; copy button floats at
    `top: 8px; right: 12px`; file path row is 600-weight primary with `padding-right: 56px`; footer
    `padding: 0 14px 12px` in tertiary.
  - **Read / Search / Web / Image cards**: same card family (12px radius, code surface, 0.5px border).
- Sub-call tree: `margin: 4px 0 2px 22px; padding-left: 8px; border-left: 0.5px solid border-l2; gap: 4px`.
- Running state: the 300px glare sweep described in §3.5.
- An "Inspect" pill appears on hover: `padding: 2px 8px`, `border: 0.5px solid border-l3`,
  `border-radius: 999px`, `background: bg-base`, 11px/16, tertiary.
- `cordis_*` tools get the business-blue accent on leading glyph, title (500), and separator dot.

### 4.8 Reasoning ("thinking") rendering (`ReasoningRow.module.css`)

- Collapsed it is exactly one 24px+delta row: `[16px leading: think icon→chevron on hover] gap 6
  [title "Think" 13/24 secondary, weight 400] gap 8 [2×2 dot] gap 8 [summary 13/20 tertiary, nowrap/ellipsis]`.
- The leading glyph swaps to a chevron on hover (disclosure affordance), exactly like tool rows.
- Expanded body: `padding: 4px 0 4px calc(22px + delta)` (indented under the title), secondary tier,
  `white-space: pre-wrap`.
- While `data-state='running'` the row runs the same 300px glare sweep; the header is `position: sticky;
  top: 0` with `background: bg-base` so the collapse toggle stays reachable on a long chain.

### 4.9 Composer / input area (`InputBar.module.css`, `ConversationRoot.module.css`)

- Seat: sticky to the bottom of the transcript scrollport, `z-index: 7`. Its background is a
  **fixed 36px fade**: `linear-gradient(180deg, color-mix(bg-base 0%, transparent) 0px, bg-base 36px)`.
- Vertical stack above the card (`--dsh-composer-stack-gap: 6px`): queue dock → goal bar → todo panel.
- Card: `width: 100%`, `max-width: var(--dsh-composer-card-max-width) = contentWidth + 32px`,
  `border-radius: 22px`, `padding-top: 8px`, `gap: 12px`, fill `specific-input-major`,
  `border: 0`, stroke `border-l2` inside `elevation-soft`.
- Root padding: `0 16px 8px` (`--dsh-composer-side-clearance: 16px`); tightens to `4px` bottom when the
  stats row is mounted.
- Draft scrollport: `max-height: 336px` (14 lines × 24px); right margin 4px; track margin-top 8px.
- Draft surface: `min-height: 36px` docked / **52px** in the hero, `padding: 4px 8px 0 14px`,
  axis size / `24px + delta`, `caret-color: state-business-primary`, `white-space: pre-wrap`.
  Placeholder at `inset: 4px 8px auto 14px`, color `label-caption`, ellipsized to one line
  (two-line clamp in the hero).
- Toolbar row: `padding: 2px 8px 6px`, `gap: 12px`, `justify-content: space-between`, `container-type: inline-size`.
  - Left (`tools`): attach **+** 28×28 circle, `border-radius: 999px`, fill `specific-selector`,
    primary glyph; gap 12 to the mode chips.
  - Modes (`modes`, gap 12): Plan / Read-only native selects — `height: 28px`, `border-radius: 8px`,
    `padding: 0 20px 0 8px`, 13px/20/500 secondary, background chevron SVG 12×12
    (stroke `#81858C`, `1.5` width) at `right 4px center`.
  - Right (`trailing`, gap 12, `margin-left: auto`): context ring (28×28, 2px stroke) + model trigger
    (h28, `border-radius: 24px`, `padding: 0 4px 0 8px`, max-width `min(360px, 45cqw)`, 13/20/500,
    12px caption chevron; icon-only below a 360px container) + **send** 34×34 circle,
    fill `button-info-fill`, `color: #fff`, `transform: translateY(-2px)`, disabled opacity 0.4.
- Dock cards (todo / goal / queue): width = `card max-width − 2×8px` insets, `border: 0.5px solid border-l1`,
  `border-radius: 12px`, fill `specific-tip`. Queue panel attaches to the card top with only its top
  corners rounded (`12px 12px 0 0`) and tucks 3px under it.
- Attachments: 64×64 thumbnails, `border-radius: 16px`, `border: 0.5px solid border-l2-darkmode-thin`;
  6px remove badge in `button-contrast-fill`.
- Stats pills under the composer: centered, `gap: 12px`, `padding: 4px 32px 0`, 13/20 tertiary;
  each pill `padding: 1px 8px`, `border-radius: 24px`, 14px icon, tabular numerals.
- Approval card: max-width = contentWidth, `border: 1px solid state-warn-secondary`,
  `border-radius: 20px`, fill `specific-input-major`, `shadow-lv2`; warning strip
  `padding: 10px 16px`, `state-warn-tertiary` bg, `state-warn-primary` text.

### 4.10 Hero (empty session)

- The composer stack is vertically centered (`justify-content: center`) with `padding-bottom: 32px`,
  `gap: 8px`.
- Headline: `fish + "New Session"`, `gap: 10px`, centered, **26px / 500 / 32px**, primary.
  The fish mark rides `currentColor` and does a small swim animation on hover.
- Optional preview badge: mono 12px/500/18px, `padding: 1px 7px 0`, `border-radius: 24px`,
  `border: 0.5px solid interactive-bg-hover`, `background: state-business-tertiary`,
  color `label-primary-bluish`.
- Workspace row sits 8px above the card (`padding: 0 16px 0 20px` docked, `padding-left: 8px` hero):
  chip `min-height: 28px`, `padding: 0 8px`, `border-radius: 16px`, 13px/20/500, folder glyph +
  label + caption chevron.

### 4.11 Right panel / dockkit (`SidebarRight.module.css`, `dockkit.module.css`)

- Panel is `position: absolute; inset: 0 0 0 auto`, fill `bg-base`, `border-left: 0.5px solid border-l4`,
  slides out with `translateX(100%)` over the slow duration.
- Tab strip: height footprint **38px** (28px content band + 10px top padding), `padding: 10px 6px 0 10px`,
  `gap: 4px`. Tab chip: `min-width: 80px; max-width: 170px; height: 28px; padding: 0 10px;
  border-radius: 12px`, secondary 13px, title fade mask over the last 16px; close button 20×20 at
  `top: 4px; right: 4px`, 14px glyph, tertiary. Add control 28×28.
- Split dividers are 0 (zero layout) with a `0.5px border-l4` hairline centered on the seam and an 8px
  pointer target; hover shows a 1px caption-ink grip fading at both ends.
- Pane body scrolls; floating panels are `border-radius: 20px` with the menu surface family.
- Drop hint: scrim `color-mix(bg-base 72%, transparent)` + `backdrop-filter: blur(6px)`;
  dashed hint cards with a 20px glyph and 13px label.
- The header's collapse/expand control uses the same 28px circle + 15px glyph geometry as message actions.

---

## 5. Component inventory

### 5.1 Main chat screen

**Shell**
1. **AppFrame** — 3-column grid shell: sidebar, center conversation, right panel; draggable 8px column handles.
2. **Sidebar** — brand block (whale + wordmark), panel-navigation toggle, New Session button, panel list,
   region seat (session browser), footer (settings + additive actions). Rail variant at 56px.
3. **Session browser** (`ui-workspace`) — section header (title + inline search + actions), the session
   tree grouped by workspace, workspace rows, session rows with status slot, show-more, bottom fade.
4. **Right panel** (`ui-dockkit` + `ui-sidebar-right`) — tab strip, splittable panes, floating panels,
   document/file/terminal preview tabs.

**Session header**
5. **Breadcrumb trail** — ancestry crumbs separated by `/`; current crumb is 500 primary, ancestors are
   clickable tertiary; subagent crumbs are 12px.
6. **View tabs** — Trajectory/Chat/etc. switch, only shown when >1 view; active tab is blue with a 2px bar.

**Transcript** (node registry in `ui-chat/src/client/chat/register-node-renderers.ts`)
7. **User message bubble** — right-aligned `#EDF3FE`/`#2C2C2E` capsule, r22; optional reference chips
   inline; attachment row (240px file cards or 64px thumbnails) above; its own copy/branch/clock row.
8. **Steering / pending submission bubble** — same bubble chrome for queued mid-turn input.
9. **Assistant markdown block** — full-width prose: headings, paragraphs, lists, tables
   (≥4-column tables break out to the full transcript width), blockquotes, images, inline code, links.
10. **Reasoning ("Think") row** — collapsed 24px disclosure, expandable pre-wrap reasoning.
11. **Tool-call row** — see §4.7; expands into IN/OUT, terminal, diff, read, search, web, or image cards.
12. **Turn process header** — 33px row (`padding: 0 0 8px`, `border-bottom: 0.5px border-l2`) that groups
    a turn's tool/reasoning rows, with a rotating chevron and a 14px/24 label.
13. **Assistant turn tail** — icon actions row (copy, branch, turn-usage pill, clock) + stats pills.
14. **Stats pills** — data/token totals under a turn, 13/20 tertiary, click opens a stat dialog.
15. **Turn-usage pill** — 28px-high pill inside the action row, 15px glyph + tabular label.
16. **Compaction marker** — dim 24px row with a context icon that swaps to a disclosure chevron on
    hover/focus; expands to the checkpoint summary (sticky header, indent 22px).
17. **Retry row** — 13px disclosure with a rotating CSS chevron; active state shimmers.
18. **Turn error row** — 3-column grid `[10px dot][message][code]`, `gap: 8px`; title in
    `state-error-primary` weight 600, message secondary, code 11px mono tertiary.
19. **Max-tokens notice** — same row family, title in `state-warn-primary`.
20. **Context-injection row / system-prompt row** — disclosure rows whose bodies are a capped
    (141px) 8px-radius code-surface panel in 11px/16 mono.
21. **AskQuestion card** — 12px radius card, `padding: 16px 20px`, `gap: 16px`, `max-height: 360px`.
22. **Approval card** — warn-striped card with command preview and action row.
23. **Generic command card / compaction command card** — command rendering in the transcript.
24. **Turn navigator rail** — right-edge tick ladder with hover previews of prompt/response.
25. **Back-to-bottom button** — floating 34px circle.
26. **Older-messages button** — `border-radius: 14px`, `padding: 4px 12px`, 12px secondary on
    `interactive-bg-hover-solid`.

**Composer area**
27. **Composer stack** — vertical card group above the input.
28. **Queue dock** — queued-message panel, 36px header, 36px rows, attached to the card top.
29. **Goal bar** — 36px card: goal glyph, "Goal" label 13/24/500, objective 13/20 dimmed, icon actions.
30. **Todo panel** — 12px card, `padding: 6px 12px`, header 13/24/500 + progress 13/20 tertiary,
    list of 16px state glyphs (done green / pending caption / in-progress blue spinner).
31. **Input card** — 22px surface with draft, placeholder, attach, Plan/Read-only selects, context ring,
    model trigger, send.
32. **Attachment rail** — 64px thumbnails / file cards with hover remove and paging arrows.
33. **Stats pill row** — under the composer, centered.
34. **Connection indicator** — 28px-high pill: warning (amber) or success (green) with a 14px glyph,
    12px/500 text; three-dot animation while connecting.
35. **Toast** — banner surface `toast-bg`.
36. **Composer overlays** — `@`/`/` trigger menus, popup selects, context-meter panel, model dropdown,
    stat dialogs (all menu-surface family: see §5.3).

### 5.2 Session-list screen

37. **Section header** — "Sessions"/list title, inline expanding search, new-session/action icons.
38. **Workspace/group row** — 34px, folder glyph that swaps to a right-pointing triangle on hover,
    title 14px, hover-revealed 16px actions, drop indicators while dragging.
39. **Session row** — 32px: 16px status slot (`StateDot`), title 14px, or time 12px on hover,
    hover actions (rename/menu), mount fade.
40. **Search results** — 48px two-line rows: title 14/20 + workspace 12/17 tertiary + snippet
    (secondary, flex) with a 12px/16px secondary snippet line.
41. **Overflow row** — "show more" 28px row.
42. **Empty state** — `padding: 16px 12px`, 13px tertiary.
43. **Rename/create dialogs** — modal family (r24), input `height: 44px`, r22, `border-l4` hairline,
    `padding: 7px 14px`, 14px/22.
44. **Session hover card** — dark fixed-color preview (title white, path/time `#CFD3D6`, status `#ADB2B8`).

### 5.3 Shared primitives (`ui-primitives`)

45. **Button** — 5 variants (default/primary/ghost/outline/toolbar) × 2 sizes (36px/28px), r18/r14.
46. **Pill** — 24px, r12, `bg-layer-2`, active variant with inset 1px `ghost-active-border` ring.
47. **Tag** — r999, 11/17/500, tones: outline, solid, neutral, quiet, success, info, warning, danger
    (status tones tint their own color at 10%, warning 12%).
48. **Switch**, **Input**, **Menu** (r20, min-w 218, max-w 360, 40px items; compact r7/26px items),
    **HoverCard**, **Modal** (r24, 380px), **OnboardingSurface**, **RiskConfirmation**,
    **ConnectionIndicator**, **Tooltip** (r8, `tooltip-bg`), **Toast**, **JsonTree**, **DisclosureRow**,
    **StateDot** (solid halo dot or 5×5 chase matrix), **FileTypeIcon**, **ReferenceIcon**, **LinkIcon**,
    **CodeBlock**, **JsonBlock**, **MarkdownText**, **TerminalBlock**, **ReadBlock**, **DiffBlock**,
    **SearchBlock**, **WebBlock**.

---

## 6. Iconography

- **Approach: hand-authored inline SVG React components, no icon library.** No `lucide`, no
  `react-icons`, no icon font. 81 exports in `packages/client/ui-primitives/src/icons/index.tsx`
  (1000 lines), named `Icon<Name><Outline|Fill><size>` and documented with their `ic_ds_*` Figma name.
- Every glyph: `viewBox="0 0 16 16"|"0 0 14 14"|"0 0 12 12"`, `fill="none"`, paths use
  `fill="currentColor"` (a few are strokes with `currentColor`). Props are `{ size?, className? }`
  (`icons/props.ts`); default `size` is the drawn size (14 or 16). Color always rides `currentColor`.
- Source: batch A mirrors the DeepSeek DeepSuite icon library (same Figma source); batch B are
  harness-only Figma extracts; the last few are hand-authored.
- On Android, port them as `ImageVector`/vector drawables with `tint = LocalContentColor`.

**Full set (81):**
`IconAgentPresetOutline16`, `IconAlarmClockOutline16`, `IconApiOutline14`, `IconArchiveOutline20`,
`IconBranchOutline16`, `IconBrowseOutline16`, `IconChecklistOutline14`, `IconCheckOutline14`,
`IconCheckOutline16`, `IconChevronDownOutline14`, `IconChevronLeftOutline14`, `IconChevronRightOutline14`,
`IconChevronUpOutline14`, `IconClockOutline16`, `IconCloseFill14`, `IconCloseOutline16`,
`IconCodeOutline16`, `IconCompactOutline16`, `IconContextInjectionOutline16`, `IconCopyOutline16`,
`IconCordisPluginOutline14`, `IconDarkOutline16`, `IconDatabaseOutline16`, `IconDataOutline16`,
`IconDislikeFill16`, `IconDislikeOutline16`, `IconDownloadOutline16`, `IconEditOutline16`,
`IconEllipsisOutline16`, `IconEnhanceOutline16`, `IconFolderClose16`, `IconFolderOpen16`,
`IconFolderOpenOutline16`, `IconFollowsystemOutline16`, `IconFullscreenOutline16`, `IconGaugeOutline16`,
`IconGlobeOutline14`, `IconGoalOutline16`, `IconInspectOutline12`, `IconLightOutline16`,
`IconLikeFill16`, `IconLikeOutline16`, `IconLinkOutline14`, `IconLinkOutline16`, `IconListPenOutline16`,
`IconLoadingOutline16`, `IconNewChatOutline16`, `IconPanelLeftOutline16`, `IconPaperclipOutline16`,
`IconPaperPlaneOutline14`, `IconPauseOutline16`, `IconPersonalizationOutline16`, `IconPlanOutline14`,
`IconPlayOutline16`, `IconPlusOutline16`, `IconProjectAddOutline16`, `IconQuestionOutline14`,
`IconQueueOutline14`, `IconRefreshOutline14`, `IconRefreshOutline16`, `IconRightUpOutline14`,
`IconRightUpOutline16`, `IconSearchOutline16`, `IconSendOutline14`, `IconSettingsOutline14`,
`IconSettingsOutline16`, `IconShareOutline16`, `IconShieldOutline16`, `IconSkillOutline16`,
`IconSparkle16`, `IconStopFill16`, `IconThinkOutline14`, `IconThinkOutline16`, `IconTrashOutline16`,
`IconTreeCorner8x10`, `IconTriangleRightFill14`, `IconUserOutline16`, `IconWarningOutline16`,
`IconWrapLinesOutline16`, plus `SHIELD_OUTLINE_PATH` / `SHIELD_OUTLINE_STROKE` exports.

**Icons on the main chat screen (measured):**

| Where | Icons |
| --- | --- |
| Sidebar shell | `IconPanelLeftOutline16` (panel toggle / rail mark), `IconNewChatOutline16` (new session), `IconSettingsOutline14/16` (footer) |
| Sidebar/session list | `IconFolderOpen16`/`IconFolderClose16`, `IconTriangleRightFill14` (expand arrow), `IconSearchOutline16`, `IconProjectAddOutline16`, `IconEllipsisOutline16`, `IconTrashOutline16`, `IconEditOutline16`, `IconArchiveOutline20`, `IconAlarmClockOutline16` (schedule) |
| Session header | `IconChevronRightOutline14`/`IconChevronLeftOutline14` (breadcrumb/actions), `IconFullscreenOutline16`, `IconShareOutline16`, `IconPanelLeftOutline16` |
| Composer | `IconPlusOutline16 size={14}` (attach), `IconPlanOutline14` (Plan), `IconShieldOutline16` (Read-only), `IconWarningOutline16` (notice), `IconChevronDownOutline14` (selects / back-to-bottom), `IconGaugeOutline16`-style ring (context meter — drawn as a bespoke inline SVG, 28×28, 2px stroke), `IconDataOutline16` (model fallback). **The send/stop button is an inline SVG, not a named icon**: an up-arrow path (`M8.3125 0.98…`), and a 10×10 `rect rx=3` while stopping — both `viewBox="0 0 16 16"`, 16×16, `currentColor` (`InputBar.tsx:461-470`). `IconSendOutline14`/`IconPaperPlaneOutline14` are used by the queue dock and command faces, not the main send button. |
| Message actions | `IconCopyOutline16`, `IconCheckOutline16` (copied swap), `IconBranchOutline16`, `IconLikeOutline16`/`IconLikeFill16`, `IconDislikeOutline16`/`IconDislikeFill16`, `IconGaugeOutline16` (usage) |
| Tool/reasoning rows | Tool leading glyphs come from `VARIANT_ICONS` in `ui-tool/.../GenericToolCard.tsx:16-24`: search → `IconSearchOutline16 size={14}`; read → `IconBrowseOutline16 size={14}`; bash → `IconApiOutline14 size={14}`; write/edit → `IconEditOutline16 size={14}`; code → `IconCodeOutline16 size={14}`; others → `IconSparkle16 size={14}`. Also `IconQuestionOutline14` (ask-question), `IconChecklistOutline14` (todo), `IconGlobeOutline14`/`IconBrowseOutline16` (web_search / web_fetch), `IconFileType*` (file mutation), `IconThinkOutline14/16` (reasoning), `IconInspectOutline12`, `IconChevronDownOutline14` (disclosure + hover swap), `IconTreeCorner8x10` (sub-call tree) |
| Compaction / context | `IconContextInjectionOutline16`, `IconCompactOutline16` |
| Todo / goal / queue | `IconChecklistOutline14`, `IconGoalOutline16`, `IconQueueOutline14`, `IconPlayOutline16`, `IconPauseOutline16`, `IconStopFill16`, `IconLoadingOutline16`, `IconClockOutline16` |
| Misc | `IconCloseOutline16`/`IconCloseFill14`, `IconRefreshOutline14/16` (retry), `IconRightUpOutline16` (open-in-app), `IconWarningOutline16`, `IconCheckOutline14`, `IconEllipsisOutline16` |

---

## 7. Brand mark and logo

### 7.1 The whale ("fish") mark

- File: `packages/client/ui-primitives/src/FishLogo.tsx`.
- Exports `FISH_LOGO_PATH` (single filled path), `FISH_LOGO_VIEWBOX = { width: 23.16, height: 17.04 }`,
  and the `FishLogo` component:
  ```tsx
  <svg width={size} height={size * 17.04 / 23.16}
       viewBox="0 0 23.16 17.04" fill="none" aria-hidden="true">
    <path d={FISH_LOGO_PATH} fill="currentColor" />
  </svg>
  ```
- Default `size = 24` → height ≈ 17.66px. It is a **whale** silhouette (rounded head to the left,
  eye dot, fin, tail sweep to the right) drawn as one `currentColor` fill, no gradients.
- Rendered in the sidebar (`.brandMark`, 24px band) and in the hero headline; it uses the primary ink
  (`color: inherit` → `label-primary`). The sidebar comment notes the main-screen instance is black
  and blue is reserved for brand emphasis only.
- The app favicon `apps/web/public/favicon.svg` is the same whale, 50×50, viewBox `0 0 50 50`,
  fill `#000` with a `prefers-color-scheme: dark` override to `#fff`.

### 7.2 Wordmark

- File: `packages/client/ui-primitives/src/BrandWordmark.tsx` (`BrandWordmark`).
- One SVG viewBox `0 0 182 24` with the mark included, or `26 0 156 24` when `includeMark={false}`;
  height defaults to 24 (width 182 or 156). All paths `fill="currentColor"`.
- Contents: the whale mark at the left (x ≈ 0.14 → 23.16, y 3.52 → 20.56), the lowercase
  "deepseek" word-lettering (letter paths), then a **rounded-rect badge** — `rect x=129.348 y=5.5
  width=52 height=14 rx=2` filled with `currentColor` — whose inner glyph paths are drawn in
  `var(--dsw-alias-label-primary-inverted)`. In light theme that reads as dark-on-white text inside a
  dark pill; in dark theme it inverts automatically.
- Sidebar presentation: mark slot (`FishLogo`) and name slot (`BrandWordmark includeMark={false}`) are
  independent; the shell fixes `height: 24px`, `gap: 8px`, name at 18px/600 with `letter-spacing: 0.04em`
  (`SidebarRoot.module.css:130-155`). The `ui-brand-official` package wires these into the sidebar slots
  (`packages/client/ui-brand-official/src/client/Brand.tsx`).
- Layout note for Android: to reproduce the sidebar lockup, place the whale (24px, primary ink) then the
  182-viewBox wordmark without its mark at the same 24px height — total ≈ 8px gap + wordmark.

### 7.3 Brand usage rules

`BRAND_GUIDELINES.md` asks third parties to use "built on DeepSeek Harness" descriptively and to prefer
the "DSH" abbreviation in project names; do **not** use the full "DeepSeek Harness" trademark as an app
name, and do not imply official endorsement. For a native mirror app, keep the whale as a reference mark
only if you are describing compatibility, and use your own app name/icon.

---

## 8. Gaps, quirks, and traps (things that will bite a port)

1. **`--dsw-alias-separator-primary` is referenced but never defined** (`StatsPills.module.css:66`).
   It resolves to the guaranteed-invalid value, so that separator renders as nothing/inherit.
   Use `label-caption` for separator dots (what every other row does).
2. Other referenced-but-undefined aliases (used with fallbacks, so harmless but worth knowing):
   `--dsw-alias-bg-l1`, `--dsw-alias-bg-l2`, `--dsw-alias-bg-layer-4`, `--dsw-alias-fill-l2`,
   `--dsw-alias-fill-tertiary`, `--dsw-alias-fill-tsp-secondary`, `--dsw-alias-label-error`,
   `--dsw-alias-label-quaternary`.
3. `--dsh-font-mono` is referenced only by `packages/extensions/ui-cordis/.../CordisPanel.module.css:181`
   with a `monospace` fallback; it is not part of the theme. Use `--ds-font-family-code`.
4. `--dsw-font-m-18` is **16px**, not 18 — the token name is stale.
5. `--dsw-font-sm-13` is referenced by `ToolRow.module.css:315` but not defined; that declaration
   falls back to the inherited font.
6. The Menu card comment says "r12" but the code is `border-radius: 20px`; the model dropdown, stat
   dialogs and context-meter panels match at `20px`/`12px` respectively — trust each sheet's code.
7. `--dsw-alias-brand-primary` is ink, not blue (see §1.2). `--dsw-alias-button-primary-fill` is also ink;
   the blue send button is `--dsw-alias-button-info-fill`.
8. The `#EDF3FE` user bubble is **light-theme only**; dark is a neutral `#2C2C2E`. Don't hardcode the blue.
9. Conversation content width is **not fixed** — it is 64% of the live center column, clamped 680–920px,
   unless the user dragged a width. For a phone layout, use the 680px minimum logic as "full width minus
   padding" and keep the 16/32px insets (transcript) vs 16px (composer card).
10. Hover is used heavily (row actions, leading-icon→chevron swap, copy buttons, code banner controls).
    There is no dedicated touch affordance except `@media (pointer: coarse)` on the attachment remove
    badge; on Android you must decide which hover-gated controls become always-visible.

## 9. Quick port checklist for Compose

- Two `ColorScheme` objects from §1.2 (light/dark), driven by `isSystemInDarkTheme()` or a manual toggle.
- One `Typography` object from §2.5; expose the content-size axis as a `CompositionLocal` (12–17, default 14)
  and derive the secondary size with the exact `min(size-1, max(13, size-2))` formula.
- A `Shapes` object from §3.1 (the values that actually matter: 6/8/12/16/18/20/22/24/28/circle).
- A spacing object with the 2px grid from §3.2.
- Elevation: implement the `elevation-stroke` idiom as a 0.5dp border + very low-alpha shadows;
  `elevation-panel/prominent/soft` as three `Modifier.shadow`/border combos.
- Layout: `Row { Sidebar(280dp) ; Conversation(weight 1) ; RightPanel(optional) }`, 0.5dp dividers,
  transcript `Column` centered with `widthIn(max = clamp(680dp, 64%, 920dp))`, 16dp flow gap.
- Transcript rows: user = right-aligned r22 capsule; assistant = plain full-width markdown;
  process rows = 24dp disclosure headers with 16dp leading icons; expanded bodies = r12 code-surface cards.
- Composer: 22dp capsule, soft elevation, 336dp draft cap, 34dp circular blue send, 28dp round auxiliary
  controls, 6dp stack gap, sticky bottom with the fixed 36dp fade above it.
