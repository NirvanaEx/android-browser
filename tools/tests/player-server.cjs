const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const files = Object.fromEntries(['player.html', 'iframe.html', 'multiple.html', 'controls.html', 'media-scroll.html', 'native.html', 'engine.html'].map(name =>
    ['/' + name, path.join(__dirname, 'fixtures', name)]));
files['/sample.mp4'] = path.resolve(__dirname, '../../build/fenix/sample.mp4');
files['/sample-fragmented.mp4'] = path.resolve(__dirname, '../../build/fenix/sample-fragmented.mp4');
files['/failure.mp4'] = files['/sample.mp4'];
let denyNative = false;
http.createServer((req, res) => {
    const pathname = new URL(req.url, 'http://localhost').pathname;
    if (pathname === '/deny-native') { denyNative = true; res.writeHead(200); res.end('denied'); return; }
    if (pathname === '/reset-failure') { denyNative = false; res.writeHead(200); res.end('reset'); return; }
    if (pathname === '/failure.mp4' && denyNative) { res.writeHead(403); res.end('controlled test refusal'); return; }
    const file = files[pathname];
    if (!file || !fs.existsSync(file)) { res.writeHead(404); res.end(); return; }
    const size = fs.statSync(file).size;
    const type = file.endsWith('.mp4') ? 'video/mp4' : 'text/html; charset=utf-8';
    const range = req.headers.range?.match(/^bytes=(\d+)-(\d*)$/);
    const start = range ? Number(range[1]) : 0;
    const end = range && range[2] ? Math.min(Number(range[2]), size - 1) : size - 1;
    if (start > end || start >= size) { res.writeHead(416, { 'Content-Range': `bytes */${size}` }); res.end(); return; }
    res.writeHead(range ? 206 : 200, { 'Content-Type': type, 'Content-Length': end - start + 1,
        'Accept-Ranges': 'bytes', 'Cache-Control': 'no-store',
        ...(range ? { 'Content-Range': `bytes ${start}-${end}/${size}` } : {}) });
    fs.createReadStream(file, { start, end }).pipe(res);
}).listen(8766, '127.0.0.1', () => console.log('Player fixtures on http://127.0.0.1:8766/player.html'));
