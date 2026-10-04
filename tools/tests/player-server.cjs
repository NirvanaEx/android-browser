const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const files = Object.fromEntries(['lab.html', 'player.html', 'iframe.html', 'multiple.html', 'controls.html', 'media-scroll.html', 'native.html', 'engine.html', 'aspect.html', 'tabs.html', 'translation.html', 'player-workbench.html'].map(name =>
    ['/' + name, path.join(__dirname, 'fixtures', name)]));
files['/sample.mp4'] = path.resolve(__dirname, '../../build/fenix/sample.mp4');
files['/sample-poster.png'] = path.join(__dirname, 'fixtures/video-poster.png');
files['/sample-fragmented.mp4'] = path.resolve(__dirname, '../../build/fenix/sample-fragmented.mp4');
files['/portrait.mp4'] = path.resolve(__dirname, '../../build/fenix/portrait.mp4');
files['/square.mp4'] = path.resolve(__dirname, '../../build/fenix/square.mp4');
files['/failure.mp4'] = files['/sample.mp4'];
let denyNative = false;
const probeEvents = [];
const tabEvents = [];
const translationEvents = [];
http.createServer((req, res) => {
    const pathname = new URL(req.url, 'http://localhost').pathname;
    if (pathname === '/translation-events' && req.method === 'GET') {
        res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
        res.end(JSON.stringify(translationEvents)); return;
    }
    if (pathname === '/translation-events' && req.method === 'POST') {
        let body = '';
        req.on('data', chunk => { body += chunk; if (body.length > 2048) req.destroy(); });
        req.on('end', () => {
            try {
                const event = JSON.parse(body);
                translationEvents.push({ at: Date.now(), run: String(event.run || '').slice(0, 40), translated: Number(event.translated),
                    total: Number(event.total), sinceLoad: Number(event.sinceLoad), sinceFirst: Number(event.sinceFirst),
                    excludedIntact: event.excludedIntact === true, inputIntact: event.inputIntact === true });
                if (translationEvents.length > 1000) translationEvents.shift();
                res.writeHead(204); res.end();
            } catch { res.writeHead(400); res.end(); }
        });
        return;
    }
    if (pathname === '/tab-events' && req.method === 'GET') {
        res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
        res.end(JSON.stringify(tabEvents)); return;
    }
    if (pathname === '/tab-events' && req.method === 'POST') {
        let body = '';
        req.on('data', chunk => { body += chunk; if (body.length > 2048) req.destroy(); });
        req.on('end', () => {
            try {
                const event = JSON.parse(body);
                tabEvents.push({ at: Date.now(), id: Number(event.id), step: Number(event.step),
                    loads: Number(event.loads), scroll: Number(event.scroll), draftLength: Number(event.draftLength),
                    kind: String(event.kind).slice(0, 32) });
                if (tabEvents.length > 1000) tabEvents.shift();
                res.writeHead(204); res.end();
            } catch { res.writeHead(400); res.end(); }
        });
        return;
    }
    // Local Android fixture observations only; never used by the browser product.
    if (pathname === '/probe-events' && req.method === 'GET') {
        res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
        res.end(JSON.stringify(probeEvents)); return;
    }
    if (pathname === '/probe-events' && req.method === 'POST') {
        let body = '';
        req.on('data', chunk => { body += chunk; if (body.length > 2048) req.destroy(); });
        req.on('end', () => {
            try {
                const event = JSON.parse(body);
                if (typeof event.kind !== 'string' || typeof event.time !== 'number') throw new Error('Invalid event');
                probeEvents.push({ at: Date.now(), kind: event.kind.slice(0, 32), time: event.time,
                    paused: event.paused === true, fullscreen: event.fullscreen === true,
                    pauses: Number(event.pauses), loads: Number(event.loads),
                    scale: String(event.scale || '').slice(0,32), fit: String(event.fit || '').slice(0,16),
                    videoWidth: Number(event.videoWidth) || 0, videoHeight: Number(event.videoHeight) || 0 });
                if (probeEvents.length > 1000) probeEvents.shift();
                res.writeHead(204); res.end();
            } catch { res.writeHead(400); res.end(); }
        });
        return;
    }
    if (pathname === '/deny-native') { denyNative = true; res.writeHead(200); res.end('denied'); return; }
    if (pathname === '/reset-failure') { denyNative = false; res.writeHead(200); res.end('reset'); return; }
    if (pathname === '/failure.mp4' && denyNative) { res.writeHead(403); res.end('controlled test refusal'); return; }
    const file = files[pathname];
    if (!file || !fs.existsSync(file)) { res.writeHead(404); res.end(); return; }
    const size = fs.statSync(file).size;
    const type = file.endsWith('.mp4') ? 'video/mp4' : file.endsWith('.png') ? 'image/png' : 'text/html; charset=utf-8';
    const range = req.headers.range?.match(/^bytes=(\d+)-(\d*)$/);
    const start = range ? Number(range[1]) : 0;
    const end = range && range[2] ? Math.min(Number(range[2]), size - 1) : size - 1;
    if (start > end || start >= size) { res.writeHead(416, { 'Content-Range': `bytes */${size}` }); res.end(); return; }
    res.writeHead(range ? 206 : 200, { 'Content-Type': type, 'Content-Length': end - start + 1,
        'Accept-Ranges': 'bytes', 'Cache-Control': 'no-store',
        ...(type.startsWith('text/html') && new URL(req.url, 'http://localhost').searchParams.has('nofs') ?
            { 'Permissions-Policy': 'fullscreen=()', 'Feature-Policy': "fullscreen 'none'" } : {}),
        ...(range ? { 'Content-Range': `bytes ${start}-${end}/${size}` } : {}) });
    fs.createReadStream(file, { start, end }).pipe(res);
}).listen(Number(process.env.UPGRID_FIXTURE_PORT || 8766), '127.0.0.1', () => console.log('Player fixture server ready'));
