// Renders assets/icon.svg to the PNGs the plugin ships.
// Usage: node assets/render-icon.mjs   (needs @resvg/resvg-js installed where Node can find it)
import { Resvg } from '@resvg/resvg-js';
import { readFileSync, writeFileSync } from 'node:fs';

const svg = readFileSync(new URL('./icon.svg', import.meta.url));
const targets = [
  ['../icon.png', 48],
  ['../src/main/resources/icon.png', 24],
];
for (const [path, size] of targets) {
  const png = new Resvg(svg, { fitTo: { mode: 'width', value: size } }).render().asPng();
  writeFileSync(new URL(path, import.meta.url), png);
}
