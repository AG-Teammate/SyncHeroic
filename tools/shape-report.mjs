#!/usr/bin/env node

import { readFile } from "node:fs/promises";

const input = process.argv[2];
if (!input) {
  process.stderr.write("Usage: node tools/shape-report.mjs <private-export.json>\n");
  process.exitCode = 2;
} else {
  const value = JSON.parse(await readFile(input, "utf8"));
  const shapes = new Map();

  function kind(item) {
    if (item === null) return "null";
    if (Array.isArray(item)) return "array";
    return typeof item;
  }

  function visit(item, path) {
    const key = path || "(root)";
    const row = shapes.get(key) ?? { count: 0, types: new Set() };
    row.count += 1;
    row.types.add(kind(item));
    shapes.set(key, row);
    if (Array.isArray(item)) {
      for (const child of item) visit(child, `${key === "(root)" ? "" : key}[]`);
    } else if (item !== null && typeof item === "object") {
      for (const [name, child] of Object.entries(item)) visit(child, path ? `${path}.${name}` : name);
    }
  }

  visit(value, "");
  for (const [path, row] of [...shapes].sort(([a], [b]) => a.localeCompare(b))) {
    process.stdout.write(`${path} : ${[...row.types].sort().join("|")}, n=${row.count}\n`);
  }
}

