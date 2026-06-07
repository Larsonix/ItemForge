import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'
import { readdirSync, existsSync } from 'fs'

/**
 * Vuetale UI build configuration for ItemForge.
 *
 * Compiles Vue SFCs to `.vue.js` files that Vuetale's V8 engine loads at runtime.
 * Each page is a separate ES module entry point. Cross-module imports (@core/*)
 * are externalized — Vuetale's ModuleRegistry resolves them at runtime.
 *
 * Output: src/main/resources/vuetale/itemforge/
 * This is picked up by Gradle's processResources and bundled into the JAR.
 *
 * ## Why no Tailwind CSS
 *
 * ARCHITECTURE.md §13.2 lists Tailwind as a devDependency, but Hytale's native UI
 * elements (Group, Label, NumberField, CheckBox, etc.) use a property-based styling
 * system — not CSS. Styling is done via Hytale-specific props: `el-style` (LabelStyle
 * with FontSize/TextColor), `background` (PatchStyle), `anchor` (positioning).
 * Tailwind utility classes have no effect on these native elements.
 *
 * Vuetale's CssBuildPlugin can process CSS class selectors at the virtual DOM level,
 * but the actual element appearance is controlled entirely by Hytale's property system.
 * Tailwind is therefore not used in ItemForge — this is a settled decision, not pending.
 */

const OUTPUT_DIR = resolve(__dirname, '../../src/main/resources/vuetale/itemforge')

// Discover page entry points from lib/pages/*.vue
function discoverEntries(dir: string, prefix: string): Record<string, string> {
  const entries: Record<string, string> = {}
  if (!existsSync(dir)) return entries

  for (const file of readdirSync(dir)) {
    if (file.endsWith('.vue')) {
      // "Editor.vue" → entry key "pages/Editor" → output "pages/Editor.js"
      // Vuetale's module resolver appends ".js" to import paths:
      //   @itemforge/pages/Editor → vuetale/itemforge/pages/Editor.js
      // TrailOfOrbis uses the same .js naming (not .vue.js).
      const name = file.replace('.vue', '')
      entries[`${prefix}/${name}`] = resolve(dir, file)
    }
  }
  return entries
}

const pageEntries = discoverEntries(resolve(__dirname, 'lib/pages'), 'pages')

/**
 * Native Hytale UI element names that Vuetale maps to real game elements.
 * These must be treated as custom elements (not Vue components) so the SFC
 * compiler emits createElementVNode() instead of resolveComponent().
 *
 * Source: Vuetale Elements.kt — 50+ registered element types.
 * Listed here are the ones ItemForge uses or may use.
 */
const HYTALE_ELEMENTS = new Set([
  // Containers
  'Group', 'Panel', 'DynamicPane', 'Prefab', 'Root',
  // Text
  'Label', 'TimerLabel',
  // Buttons
  'Button', 'ActionButton', 'BackButton', 'CustomButton', 'TextButton',
  // Inputs
  'TextField', 'MultilineTextField', 'CompactTextField',
  'NumberField', 'FloatNumberField',
  'CheckBox', 'LabeledCheckBox',
  'Slider', 'FloatSlider',
  'SliderNumberField', 'FloatSliderNumberField',
  'DropdownBox', 'DropdownEntry',
  'ColorPicker', 'CodeEditor', 'BlockSelector',
  // Items
  'ItemSlot', 'ItemGrid', 'ItemIcon', 'ItemSlotButton',
  // Navigation
  'TabNavigation', 'NativeTabNavigation', 'NativeTabButton',
  // Layout
  'ReorderableList', 'ProgressBar',
  // Images
  'Image', 'DynamicImage', 'Sprite', 'HyvatarImage',
])

export default defineConfig({
  plugins: [
    vue({
      template: {
        compilerOptions: {
          // Tell Vue these are native Hytale elements, not Vue components.
          // Without this, the compiler emits resolveComponent() calls which
          // Vuetale's custom renderer doesn't handle the same way.
          isCustomElement: (tag) => HYTALE_ELEMENTS.has(tag),
        },
      },
    }),
  ],

  build: {
    outDir: OUTPUT_DIR,
    emptyOutDir: false,

    lib: {
      entry: pageEntries,
      formats: ['es'],
      // Output "pages/Editor" entry → "pages/Editor.js"
      fileName: (_format, entryName) => `${entryName}.js`,
    },

    rollupOptions: {
      // Externalize Vue runtime and Vuetale core — resolved by Vuetale's module system
      external: [
        'vue',
        /^@core\//,
        /^@itemforge\//,
      ],
    },

    // Keep readable for development and debugging
    minify: false,
    sourcemap: true,
  },
})
