#!/usr/bin/env node
/**
 * Standalone WASI filesystem probe for km-io-core tests.
 *
 * Mirrors the Gradle wasmWasiNodeTest setup (two preopened temp dirs mapped to
 * /tmp and /var/log), wraps every wasi_snapshot_preview1 import with a call
 * counter, and decodes path arguments from WASM linear memory so you can see
 * exactly which files the tests open, create, rename, and delete.
 *
 * Usage:
 *   node scripts/wasm-fs-probe.mjs [--module core|root]
 */

import { WASI } from 'wasi';
import { argv, env } from 'node:process';
import { readFileSync, mkdirSync, readdirSync, rmSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

// ---------------------------------------------------------------------------
// Config
// ---------------------------------------------------------------------------

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const MODULE = argv.includes('--module') ? argv[argv.indexOf('--module') + 1] : 'core';

const WASM_PATHS = {
  core: 'core/build/compileSync/wasmWasi/test/testDevelopmentExecutable/kotlin/km-io-km-io-core-test.wasm',
  root: 'build/compileSync/wasmWasi/test/testDevelopmentExecutable/kotlin/km-io-test.wasm',
};

const wasmPath = join(ROOT, WASM_PATHS[MODULE] ?? WASM_PATHS.core);

// ---------------------------------------------------------------------------
// Temp dirs (mirror what Gradle's doFirst creates)
// ---------------------------------------------------------------------------

const tmpBase = tmpdir();
const tmp1 = join(tmpBase, `km-io-probe-tmp-${process.pid}`);
const tmp2 = join(tmpBase, `km-io-probe-var-log-${process.pid}`);
mkdirSync(tmp1, { recursive: true });
mkdirSync(tmp2, { recursive: true });

// ---------------------------------------------------------------------------
// WASI instance with two preopens: /tmp → tmp1, /var/log → tmp2
// ---------------------------------------------------------------------------

const wasi = new WASI({
  version: 'preview1',
  args: argv,
  env,
  preopens: {
    '/tmp': tmp1,
    '/var/log': tmp2,
  },
});

// ---------------------------------------------------------------------------
// Intercept layer
// ---------------------------------------------------------------------------

// Lazy reference to WASM instance — populated after instantiation.
// Read memory.buffer dynamically on every access because WASM memory.grow
// replaces the underlying ArrayBuffer, invalidating any cached reference.
let wasmExports = null;

const callCounts = {};
const pathLog = []; // { op, path, fd, result }

/** Read a UTF-8 string from WASM linear memory. */
function readStr(ptr, len) {
  if (!wasmExports || ptr === 0 || len === 0) return '<unknown>';
  try {
    return Buffer.from(wasmExports.memory.buffer, ptr, len).toString('utf8');
  } catch {
    return '<unreadable>';
  }
}

// WASI path-based syscall arities (how to extract path ptr/len from args):
// path_open(fd, dirflags, path_ptr, path_len, ...)
// path_create_directory(fd, path_ptr, path_len)
// path_unlink_file(fd, path_ptr, path_len)
// path_remove_directory(fd, path_ptr, path_len)
// path_rename(fd, old_ptr, old_len, new_fd, new_ptr, new_len)
// path_symlink(old_ptr, old_len, fd, new_ptr, new_len)
// path_readlink(fd, path_ptr, path_len, buf, buf_len, nread_ptr)
// path_filestat_get(fd, flags, path_ptr, path_len, ...)
const PATH_OPS = {
  path_open:              (args) => [readStr(args[2], args[3])],
  path_create_directory:  (args) => [readStr(args[1], args[2])],
  path_unlink_file:       (args) => [readStr(args[1], args[2])],
  path_remove_directory:  (args) => [readStr(args[1], args[2])],
  path_rename:            (args) => [readStr(args[1], args[2]), '→', readStr(args[4], args[5])],
  path_symlink:           (args) => [readStr(args[0], args[1]), '→', readStr(args[3], args[4])],
  path_readlink:          (args) => [readStr(args[1], args[2])],
  path_filestat_get:      (args) => [readStr(args[2], args[3])],
};

const rawImports = wasi.getImportObject();
const wrapped = { wasi_snapshot_preview1: {} };

for (const [name, fn] of Object.entries(rawImports.wasi_snapshot_preview1)) {
  callCounts[name] = 0;
  const pathDecoder = PATH_OPS[name];

  wrapped.wasi_snapshot_preview1[name] = (...args) => {
    callCounts[name]++;
    const result = fn(...args);

    if (pathDecoder) {
      const parts = pathDecoder(args);
      pathLog.push({ op: name, parts, result });
    }

    return result;
  };
}

// ---------------------------------------------------------------------------
// Load + instantiate
// ---------------------------------------------------------------------------

console.error(`[probe] loading ${wasmPath}`);
const wasmBuffer = readFileSync(wasmPath);
const wasmModule = new WebAssembly.Module(wasmBuffer);
const wasmInstance = new WebAssembly.Instance(wasmModule, wrapped);

wasi.finalizeBindings(wasmInstance);

// Hook exports reference now that the instance is live.
wasmExports = wasmInstance.exports;

// ---------------------------------------------------------------------------
// Run tests
// ---------------------------------------------------------------------------

console.error(`[probe] running startUnitTests — test output below\n${'─'.repeat(72)}`);
try {
  wasmInstance.exports.startUnitTests();
} catch (e) {
  console.error(`[probe] startUnitTests threw: ${e}`);
}
console.error('─'.repeat(72));

// ---------------------------------------------------------------------------
// Report
// ---------------------------------------------------------------------------

console.error('\n=== WASI SYSCALL COUNTS ===');
const sorted = Object.entries(callCounts)
  .filter(([, n]) => n > 0)
  .sort(([, a], [, b]) => b - a);
for (const [name, count] of sorted) {
  console.error(`  ${name.padEnd(32)} ${count}`);
}

console.error('\n=== PATH OPERATIONS (in order) ===');
if (pathLog.length === 0) {
  console.error('  (none)');
} else {
  for (const { op, parts, result } of pathLog) {
    const status = result === 0 ? 'ok' : `errno=${result}`;
    console.error(`  ${op.padEnd(28)} ${parts.join(' ')}  [${status}]`);
  }
}

// Walk the temp dirs to show what actually persisted.
function walk(dir, base) {
  const entries = [];
  try {
    for (const name of readdirSync(dir)) {
      const full = join(dir, name);
      const rel = join(base, name);
      const st = statSync(full, { throwIfNoEntry: false });
      if (!st) continue;
      if (st.isDirectory()) {
        entries.push(`  [dir]  ${rel}`);
        entries.push(...walk(full, rel));
      } else {
        entries.push(`  [file] ${rel}  (${st.size}B)`);
      }
    }
  } catch { /* ignore */ }
  return entries;
}

console.error('\n=== FILES IN /tmp preopen AFTER TESTS ===');
const f1 = walk(tmp1, '/tmp');
f1.length ? f1.forEach(l => console.error(l)) : console.error('  (empty — tests cleaned up)');

console.error('\n=== FILES IN /var/log preopen AFTER TESTS ===');
const f2 = walk(tmp2, '/var/log');
f2.length ? f2.forEach(l => console.error(l)) : console.error('  (empty — tests cleaned up)');

// Cleanup
rmSync(tmp1, { recursive: true, force: true });
rmSync(tmp2, { recursive: true, force: true });
