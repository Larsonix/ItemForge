#!/usr/bin/env node
/**
 * Build-time validator for compiled Vuetale JS output.
 *
 * Scans the compiled Editor.js for patterns known to cause server-freezing
 * structural changes in Vuetale's VueBridge. Fails the build with a clear
 * error message if any pattern is detected.
 *
 * Known freeze-causing patterns:
 * 1. _cache[N] inside renderList → hoisted static VNode shared across v-for
 *    iterations → VueBridge moves element between parents → structural change
 *    → routing key regeneration → stale keys → button clicks dropped
 *
 * 2. flushUpdates calls → forces onDirty during V8 callback while world thread
 *    is blocked → sendUpdate contends for player/page locks → deadlock risk
 *
 * Limitations (honest):
 * - Only catches SYNTAX patterns, not logic bugs
 * - Cannot detect computed properties that return different element lists
 * - Cannot detect infinite loops in Vue reactivity
 * - The V8Watchdog (runtime) catches what this validator cannot
 *
 * Run: node scripts/validate-compiled-js.js
 * Exit code: 0 = pass, 1 = violations found
 */

const fs = require('fs');
const path = require('path');

const PAGES_DIR = path.resolve(
  __dirname,
  '../src/main/resources/vuetale/itemforge/pages'
);

// All compiled page files to validate
const PAGE_FILES = ['Editor.js', 'Dashboard.js'];

// ── Validators ───────────────────────────────────────────────────────────

const violations = [];

function check(name, description, test) {
  const result = test();
  if (result) {
    violations.push({ name, description, detail: result });
  }
}

// ── Read Files ───────────────────────────────────────────────────────────

const allSources = [];
for (const file of PAGE_FILES) {
  const filePath = path.resolve(PAGES_DIR, file);
  if (!fs.existsSync(filePath)) {
    console.error(`[validate-compiled-js] File not found: ${filePath}`);
    console.error('Run "npm run build" in src/ui first.');
    process.exit(1);
  }
  allSources.push({ file, source: fs.readFileSync(filePath, 'utf-8') });
}

// Concatenate for backward-compatible single-pass checks
const source = allSources.map(s => s.source).join('\n');
const lines = source.split('\n');

// ── Check 1: _cache inside renderList ────────────────────────────────────
// Vue's compiler hoists static elements to _cache[N]. Inside a v-for (compiled
// as renderList), the same cached VNode is shared across iterations. In Vuetale's
// custom renderer, this causes the same Element to be moved between parents,
// triggering hasStructuralChanges on every render.
//
// Fix: make the element non-static (add a dynamic prop like :visible or :padding)
// or remove it entirely (use padding on adjacent elements).

check(
  'cache-in-renderlist',
  'Static _cache[N] element found inside renderList callback — ' +
  'causes VueBridge structural change (shared VNode across v-for iterations). ' +
  'Fix: remove static element from v-for, use padding on adjacent elements instead.',
  () => {
    // Find all renderList regions and check for _cache usage inside them
    const hits = [];
    let renderListDepth = 0;
    let braceDepth = 0;
    let renderListStartLine = -1;

    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];

      // Track renderList boundaries by counting braces after renderList(
      if (line.includes('renderList(')) {
        if (renderListDepth === 0) renderListStartLine = i;
        renderListDepth++;
        // Count opening braces on this line
        for (const ch of line) {
          if (ch === '{' || ch === '[' || ch === '(') braceDepth++;
          if (ch === '}' || ch === ']' || ch === ')') braceDepth--;
        }
        continue;
      }

      if (renderListDepth > 0) {
        for (const ch of line) {
          if (ch === '{' || ch === '[' || ch === '(') braceDepth++;
          if (ch === '}' || ch === ']' || ch === ')') {
            braceDepth--;
            if (braceDepth <= 0) {
              renderListDepth--;
              braceDepth = 0;
              if (renderListDepth === 0) break;
            }
          }
        }

        // Check for _cache inside the renderList body
        const cacheMatch = line.match(/_cache\[(\d+)\]/);
        if (cacheMatch) {
          hits.push(`Line ${i + 1}: _cache[${cacheMatch[1]}] inside renderList (started at line ${renderListStartLine + 1})`);
        }
      }
    }

    return hits.length > 0 ? hits.join('\n  ') : null;
  }
);

// ── Check 2: flushUpdates calls ──────────────────────────────────────────
// flushUpdates() forces onDirty → sendUpdateAsync during a V8 callback while
// the world thread is blocked on runOnV8Thread().get(). The sendUpdate on
// ForkJoinPool contends for player/page locks → potential deadlock.

check(
  'flush-updates',
  'flushUpdates() call found — causes deadlock risk when called during V8 callback ' +
  '(world thread blocked on runOnV8Thread().get()). ' +
  'Fix: remove the call. Vue flushes naturally on the next V8 tick.',
  () => {
    const hits = [];
    for (let i = 0; i < lines.length; i++) {
      if (lines[i].includes('flushUpdates')) {
        hits.push(`Line ${i + 1}: ${lines[i].trim()}`);
      }
    }
    return hits.length > 0 ? hits.join('\n  ') : null;
  }
);

// ── Results ──────────────────────────────────────────────────────────────

if (violations.length === 0) {
  console.log('[validate-compiled-js] PASS — no freeze-causing patterns detected');
  process.exit(0);
} else {
  console.error('[validate-compiled-js] FAIL — freeze-causing patterns detected:\n');
  for (const v of violations) {
    console.error(`  [${v.name}] ${v.description}`);
    console.error(`  Detail: ${v.detail}\n`);
  }
  console.error(
    'These patterns cause Vuetale structural changes that invalidate routing keys,\n' +
    'leading to server freezes. Fix the Vue source and rebuild.\n' +
    'See docs/reference/confirmed-dead-approaches.md for context.'
  );
  process.exit(1);
}
