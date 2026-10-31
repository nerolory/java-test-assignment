const sass = require('sass');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const source = path.join(root, 'resources/assets/scss/app.scss');
const target = path.join(root, 'resources/assets/css/app.css');
const result = sass.compile(source, { style: 'compressed', sourceMap: false });

// JavaFX's font-face parser requires a trailing declaration semicolon.
// Sass removes it in compressed output. Restore it only in font-face rules.
const css = result.css.replace(/(@font-face\{[^{}]*?)(;?)\}/g, '$1;}');
fs.mkdirSync(path.dirname(target), { recursive: true });
fs.writeFileSync(target, css + '\n');
