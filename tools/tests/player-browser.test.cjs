const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const { test } = require('node:test');
const { firefox } = require(process.env.UPGRID_PLAYWRIGHT_MODULE || 'playwright');
const launchOptions = { headless: true, ...(process.env.UPGRID_FIREFOX_BINARY ? { executablePath: process.env.UPGRID_FIREFOX_BINARY } : {}) };
const source = readFileSync(resolve(__dirname, process.env.UPGRID_PLAYER_SOURCE || '../../app/src/main/assets/extensions/upgrid_fullscreen/player.js'), 'utf8');
const lifecycle = readFileSync(resolve(__dirname, '../../app/src/main/assets/extensions/upgrid_fullscreen/lifecycle.js'), 'utf8');
const preload = readFileSync(resolve(__dirname, '../../app/src/main/assets/extensions/upgrid_fullscreen/preload.js'), 'utf8');

test('Firefox: cached fullscreen avoids DOM enumeration and all scale modes preserve playback', async () => {
    const browser = await firefox.launch(launchOptions);
    try {
        const page = await browser.newPage({viewport:{width:576,height:1000}});
        await page.goto('http://127.0.0.1:8766/aspect.html');
        await page.addScriptTag({content:`window.handlers=[];window.playerEvents=[];window.browser={runtime:{
            sendMessage:msg=>{playerEvents.push(msg);return Promise.resolve();},
            onMessage:{addListener:fn=>handlers.push(fn)}}};
            window.command=msg=>{for(const fn of handlers){const value=fn(msg);if(value!==undefined)return value;}};` + source + preload});
        await page.getByRole('button',{name:'Play',exact:true}).click();
        await page.waitForFunction(() => playerEvents.some(event => event.t === 'video_hint' && event.playing));
        await page.evaluate(() => {
            window.beforeSource=video.currentSrc;window.beforeTime=video.currentTime;window.pauses=0;window.loads=0;
            video.addEventListener('pause',()=>window.pauses++);video.addEventListener('loadstart',()=>window.loads++);
            window.originalQuery=document.querySelectorAll;
            document.querySelectorAll=()=>{throw Error('Unexpected full DOM search');};
            const button=document.createElement('button');button.id='cached';button.textContent='Cached player';
            button.onclick=()=>{const hint=playerEvents.filter(event=>event.t==='video_hint').at(-1);
                window.openResult=command({cmd:'fast_takeover',key:hint.key,requestId:5,token:'cached'});};
            document.body.prepend(button);
        });
        await page.getByRole('button',{name:'Cached player'}).click();
        await page.waitForFunction(() => document.fullscreenElement === video);
        await page.evaluate(() => { document.querySelectorAll=originalQuery; });
        assert.equal(await page.evaluate(() => openResult), 'ok');
        for (const mode of ['cover','fill','contain']) {
            await page.evaluate(mode => command({cmd:'scale',requestId:5,mode}), mode);
            await page.waitForFunction(mode => getComputedStyle(video).objectFit === mode, mode);
            await page.evaluate(() => video.style.setProperty('object-fit','none','important'));
            await page.waitForFunction(mode => getComputedStyle(video).objectFit === mode, mode);
        }
        for (const [horizontal, vertical, scale] of [[true,false,'-1 1'],
            [false,true,'1 -1'],[true,true,'-1']]) {
            await page.evaluate(([horizontal,vertical])=>command({cmd:'mirror',requestId:5,horizontal,vertical}),[horizontal,vertical]);
            await page.waitForFunction(scale=>getComputedStyle(video).scale===scale,scale);
            await page.evaluate(()=>video.style.setProperty('scale','3','important'));
            await page.waitForFunction(scale=>getComputedStyle(video).scale===scale,scale);
            assert.deepEqual(await page.evaluate(()=>{
                const state=playerEvents.filter(e=>e.t==='state').at(-1);
                return [state.mirrorX,state.mirrorY,state.videoWidth,state.videoHeight];
            }),[horizontal,vertical,640,360]);
        }
        await page.evaluate(()=>command({cmd:'mirror',requestId:5,horizontal:false,vertical:false}));
        await page.waitForFunction(()=>getComputedStyle(video).scale==='none');
        await page.waitForFunction(() => video.currentTime > beforeTime + 0.5);
        assert.deepEqual(await page.evaluate(() => ({pauses:window.pauses,loads:window.loads,
            same:video.currentSrc===beforeSource,paused:video.paused})), {pauses:0,loads:0,same:true,paused:false});
        await page.evaluate(() => command({cmd:'release',requestId:5,resume:false}));
        await page.waitForFunction(() => !document.fullscreenElement && video.paused);
        assert.equal(await page.evaluate(() => getComputedStyle(video).objectFit), 'fill');
        assert.equal(await page.evaluate(() => getComputedStyle(video).scale), 'none');
    } finally { await browser.close(); }
});

test('Firefox: real blob and MSE fullscreen use the video top layer and restore paused, unchanged DOM', async () => {
    const browser = await firefox.launch(launchOptions);
    try {
        for (const [kind, viewport] of [['blob', {width:576,height:1000}], ['mse', {width:1000,height:576}]]) {
            const page = await browser.newPage({ viewport });
            await page.goto('http://127.0.0.1:8766/engine.html');
            await page.addScriptTag({ content: `window.browser = {runtime:{
                sendMessage: msg => {window.playerEvents.push(msg);return Promise.resolve({frameId:0,playing:true,area:100});},
                onMessage:{addListener:fn=>{window.playerCommand=fn;}}
            }};window.playerEvents=[];` + source });
            await page.click('#' + kind);
            await page.waitForFunction(() => video.readyState >= 2 && !video.paused && video.currentSrc.startsWith('blob:'));
            await page.evaluate(async () => {
                video.pause(); video.currentTime = 10;
                video.style.setProperty('object-fit', 'cover', 'important');
                video.style.setProperty('object-position', '0% 0%', 'important');
                window.originalParent = video.parentElement;
                window.originalStyle = video.getAttribute('style');
                await upgridPlayerMain(1, 'fullscreen-test');
            });
            await page.click('#fullscreen');
            await page.waitForFunction(() => playerEvents.some(event => event.ok && event.mode === 'engine'));
            assert.deepEqual(await page.evaluate(() => ({
                fullscreen: document.fullscreenElement === video,
                same: video.parentElement === originalParent,
                fit: getComputedStyle(video).objectFit, position: getComputedStyle(video).objectPosition,
                controls: video.controls, paused: video.paused,
                fillsScreen: Math.round(video.getBoundingClientRect().width) === innerWidth && Math.round(video.getBoundingClientRect().height) === innerHeight,
            })), { fullscreen:true, same:true, fit:'contain', position:'50% 50%', controls:false, paused:true, fillsScreen:true });
            await page.evaluate(() => {
                video.style.setProperty('object-fit', 'fill', 'important');
                video.style.setProperty('transform', 'scaleX(2)', 'important');
                video.controls = true;
            });
            await page.waitForFunction(() => getComputedStyle(video).objectFit === 'contain' &&
                getComputedStyle(video).transform === 'none' && !video.controls);
            await page.evaluate(() => playerCommand({cmd:'seekBy',delta:5,requestId:1}));
            assert.equal(await page.evaluate(() => video.currentTime), 15);
            await page.evaluate(() => playerCommand({cmd:'release',resume:false,requestId:1}));
            await page.waitForFunction(() => !document.fullscreenElement);
            assert.deepEqual(await page.evaluate(() => ({paused:video.paused,controls:video.controls,
                sameParent:video.parentElement===originalParent,style:video.getAttribute('style'),originalStyle})),
                await page.evaluate(()=>({paused:true,controls:true,sameParent:true,style:originalStyle,originalStyle})));
            await page.close();
        }
    } finally { await browser.close(); }
});

test('Firefox: engine entry and return keep decoded playback without new media requests or pause', async () => {
    const browser = await firefox.launch(launchOptions);
    try {
        const page = await browser.newPage();
        let requests = 0;
        page.on('request', request => { if (request.url().includes('sample.mp4')) requests++; });
        await page.goto('http://127.0.0.1:8766/engine.html');
        await page.addScriptTag({content:`window.browser={runtime:{
            sendMessage:()=>Promise.resolve({frameId:0,playing:true,area:100}),
            onMessage:{addListener:fn=>{window.playerCommand=fn;}}
        }};` + source});
        await page.click('#blob');
        await page.waitForFunction(() => !video.paused && video.currentTime > 0.3);
        await page.evaluate(async () => {
            window.pauseEvents = 0; window.loadEvents = 0;
            video.addEventListener('pause', () => pauseEvents++);
            video.addEventListener('loadstart', () => loadEvents++);
            window.beforeSource = video.currentSrc; window.beforeTime = video.currentTime;
            await upgridPlayerMain(1, 'continuity');
        });
        const requestsBefore = requests;
        await page.click('#fullscreen');
        await page.waitForFunction(() => document.fullscreenElement === video && video.currentTime > beforeTime + 0.3);
        assert.deepEqual(await page.evaluate(() => ({paused:video.paused, pauses:pauseEvents,
            loads:loadEvents, sameSource:video.currentSrc === beforeSource})),
            {paused:false,pauses:0,loads:0,sameSource:true});
        assert.equal(requests, requestsBefore);
        await page.evaluate(() => {
            window.exitTime = video.currentTime;
            return playerCommand({cmd:'release',resume:true,requestId:1});
        });
        await page.waitForFunction(() => !document.fullscreenElement && video.currentTime > exitTime + 0.3);
        assert.deepEqual(await page.evaluate(() => ({paused:video.paused, pauses:pauseEvents,
            loads:loadEvents, sameSource:video.currentSrc === beforeSource})),
            {paused:false,pauses:0,loads:0,sameSource:true});
        assert.equal(requests, requestsBefore);
    } finally { await browser.close(); }
});

test('Firefox: leaving a page blocks script autoplay on return until manual Play', async () => {
    const browser = await firefox.launch(launchOptions);
    try {
        const page = await browser.newPage();
        await page.goto('http://127.0.0.1:8766/engine.html');
        await page.addScriptTag({content:`window.browser={runtime:{onMessage:{addListener:fn=>{window.lifecycleCommand=fn;}}}};` + lifecycle});
        await page.click('#play');
        await page.waitForFunction(() => !video.paused);
        await page.evaluate(() => lifecycleCommand({cmd:'suspend_page'}));
        assert.equal(await page.evaluate(() => video.paused), true);
        await page.evaluate(() => video.play().catch(()=>{}));
        await page.waitForTimeout(500);
        assert.equal(await page.evaluate(() => video.paused), true);
        await page.evaluate(() => document.querySelector('#play').click());
        await page.waitForTimeout(500);
        assert.equal(await page.evaluate(() => video.paused), true, 'synthetic website click is not manual resume');
        await page.click('#play');
        await page.waitForFunction(() => !video.paused);
    } finally { await browser.close(); }
});

test('Firefox: fullscreen activation prompt needs a real click and cleans up on exit', async () => {
    const browser = await firefox.launch(launchOptions);
    try {
        const page = await browser.newPage();
        await page.goto('http://127.0.0.1:8766/engine.html');
        await page.addScriptTag({content:`window.browser={runtime:{
            sendMessage:msg=>{window.events.push(msg);return Promise.resolve({frameId:0,playing:true,area:100});},
            onMessage:{addListener:fn=>{window.playerCommand=fn;}}
        }};window.events=[];` + source});
        await page.click('#blob');
        await page.waitForFunction(() => !video.paused && video.readyState >= 2);
        const status = await page.evaluate(async () => {
            video.pause(); await upgridPlayerMain(1, 'gesture');
            const original = video.requestFullscreen.bind(video); let first = true;
            video.requestFullscreen = () => {
                if (first) { first=false; return Promise.reject(new Error('Activation expired')); }
                return original();
            };
            return playerCommand({cmd:'engine_takeover',requestId:1});
        });
        assert.equal(status, 'awaiting_gesture');
        await page.getByRole('button', {name:'Открыть плеер', exact:true}).evaluate(button => button.click());
        assert.equal(await page.evaluate(() => !!document.fullscreenElement), false);
        await page.getByRole('button', {name:'Открыть плеер', exact:true}).click();
        await page.waitForFunction(() => document.fullscreenElement === video);
        assert.equal(await page.getByRole('button', {name:'Открыть плеер', exact:true}).count(), 0);
        await page.evaluate(() => playerCommand({cmd:'release',requestId:1,resume:false}));
        await page.waitForFunction(() => !document.fullscreenElement);
        assert.equal(await page.evaluate(() => video.paused && video.getAttribute('style') === null), true);
    } finally { await browser.close(); }
});

for (const shadow of [false, true]) test(`Firefox: site controls stay hidden and restore (${shadow ? 'shadow DOM' : 'DOM'})`, async () => {
    const browser = await firefox.launch(launchOptions);
    try {
        const page = await browser.newPage({ viewport: { width: 576, height: 1000 } });
        await page.goto('http://127.0.0.1:8766/controls.html' + (shadow ? '?shadow=1' : ''));
        await page.addScriptTag({ content: `window.browser = { runtime: {
            sendMessage: msg => { window.playerEvents.push(msg); return Promise.resolve({ frameId: 0, playing: true, area: 100 }); },
            onMessage: { addListener: fn => { window.playerCommand = fn; } }
        }}; window.playerEvents = [];` + source });
        await page.evaluate(async () => {
            await originalVideo.play(); originalVideo.currentTime = 5;
            window.savedVideoStyle = originalVideo.getAttribute('style');
            await upgridPlayerMain(1, 'regression');
            await playerCommand({ cmd: 'takeover', requestId: 1 });
        });
        await page.waitForFunction(() => playerEvents.some(event => event.t === 'takeover' && event.ok));
        assert.equal(await page.evaluate(() => getComputedStyle(stageRoot.querySelector('#panel')).opacity), '0',
            'site controls must not leak through a visibility:hidden ancestor');
        await page.evaluate(() => {
            window.styleWrites = 0;
            new MutationObserver(records => { styleWrites += records.length; }).observe(originalVideo, { attributes: true });
            addSitePanel();
            stageRoot.querySelector('#panel').style.cssText = 'visibility:visible!important;opacity:1!important';
            originalVideo.style.cssText = 'width:12px;height:12px;';
            originalVideo.controls = true;
        });
        await page.waitForFunction(() =>
            getComputedStyle(stageRoot.querySelector('#late')).opacity === '0' &&
            originalVideo.getBoundingClientRect().width === innerWidth && !originalVideo.controls);
        const during = await page.evaluate(() => ({
            opacity: getComputedStyle(stageRoot.querySelector('#panel')).opacity,
            childVisibility: getComputedStyle(stageRoot.querySelector('#site-cc')).visibility,
            controls: originalVideo.controls, position: originalVideo.currentTime,
            rect: { width: originalVideo.getBoundingClientRect().width, height: originalVideo.getBoundingClientRect().height },
            sameVideo: stageRoot.querySelector('video') === originalVideo,
        }));
        assert.equal(during.opacity, '0');
        assert.equal(during.childVisibility, 'visible', 'explicit child visibility reproduces the original bug');
        assert.equal(during.controls, false);
        assert.equal(during.sameVideo, true);
        assert.ok(during.position >= 5);
        assert.deepEqual(during.rect, { width: 576, height: 1000 });
        await page.evaluate(() => { styleWrites = 0; });
        await page.waitForTimeout(1200);
        assert.ok(await page.evaluate(() => styleWrites) < 20, 'observer must not feed back into itself');
        if (!shadow) await page.screenshot({ path: resolve(__dirname, '../../screenshots/controls-fixed-firefox.png') });
        await page.evaluate(() => playerCommand({ cmd: 'release', requestId: 1 }));
        const restored = await page.evaluate(() => ({
            opacity: getComputedStyle(stageRoot.querySelector('#panel')).opacity,
            lateOpacity: getComputedStyle(stageRoot.querySelector('#late')).opacity,
            controls: originalVideo.controls,
            sameVideo: stageRoot.querySelector('video') === originalVideo,
            style: originalVideo.getAttribute('style'), originalStyle: savedVideoStyle,
            width: originalVideo.getBoundingClientRect().width,
        }));
        assert.equal(restored.opacity, '1'); assert.equal(restored.lateOpacity, '1');
        assert.equal(restored.controls, true); assert.equal(restored.sameVideo, true);
        assert.equal(restored.style, restored.originalStyle); assert.ok(restored.width < 576);
        await page.evaluate(() => { originalVideo.pause(); originalVideo.style.width = '240px'; });
        await page.waitForTimeout(100);
        assert.equal(await page.evaluate(() => originalVideo.style.width), '240px', 'observer must be disconnected after Back');
    } finally { await browser.close(); }
});

for (const shadow of [false, true]) test(`Firefox native source handoff leaves DOM unchanged (${shadow ? 'shadow' : 'ordinary'})`, async () => {
    const browser = await firefox.launch(launchOptions);
    try {
        const page = await browser.newPage({ viewport: { width: 800, height: 1200 } });
        await page.goto('http://127.0.0.1:8766/controls.html' + (shadow ? '?shadow=1' : ''));
        await page.addScriptTag({ content: `window.browser = { runtime: {
            sendMessage: () => Promise.resolve({ frameId: 0, playing: true, area: 100 }),
            onMessage: { addListener: fn => { window.playerCommand = fn; } }
        }};` + source });
        const result = await page.evaluate(async () => {
            await originalVideo.play(); originalVideo.currentTime = 5;
            const before = originalVideo.getBoundingClientRect().toJSON();
            const css = originalVideo.getAttribute('style');
            await upgridPlayerMain(1, 'native-real-browser');
            const stream = await playerCommand({ cmd: 'describe_stream', requestId: 1 });
            return { stream, paused: originalVideo.paused, before, after: originalVideo.getBoundingClientRect().toJSON(),
                css, afterCss: originalVideo.getAttribute('style'), controls: originalVideo.controls };
        });
        assert.match(result.stream.url, /\/sample.mp4$/);
        assert.ok(result.stream.pos >= 5); assert.equal(result.stream.paused, false);
        assert.equal(result.paused, true); assert.equal(result.controls, true);
        assert.deepEqual(result.after, result.before); assert.equal(result.css, result.afterCss);
        await page.evaluate(() => originalVideo.play().catch(() => {}));
        assert.equal(await page.evaluate(() => originalVideo.paused), true, 'site script cannot restart audio behind the native player');
        await page.evaluate(() => playerCommand({ cmd: 'return_stream', requestId: 1, pos: 15, paused: false, resume: true }));
        await page.waitForFunction(() => !originalVideo.paused && originalVideo.currentTime >= 15);
        await page.evaluate(async () => {
            originalVideo.src = URL.createObjectURL(await (await fetch('/sample.mp4')).blob());
            await originalVideo.play(); await upgridPlayerMain(2, 'blob');
        });
        const rejected = await page.evaluate(() => playerCommand({ cmd: 'describe_stream', requestId: 2 }));
        assert.equal(rejected.error, 'embedded_stream');
        assert.equal(await page.evaluate(() => originalVideo.paused), false);
    } finally { await browser.close(); }
});
