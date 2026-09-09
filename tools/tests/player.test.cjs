const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const { test } = require('node:test');
const vm = require('node:vm');
const source = readFileSync(resolve(__dirname, '../../app/src/main/assets/extensions/upgrid_fullscreen/player.js'), 'utf8');
const flush = () => new Promise(resolve => setImmediate(resolve));

function facebookHarness(options) {
    const h = harness(options);
    h.document.URL = 'https://www.facebook.com/watch/?v=999999';
    h.video.currentSrc = 'blob:https://www.facebook.com/local-media-session';
    const metadata = { videoFBID: '123456', graphQLVideoDRMInfo: null, isLiveStreaming: false };
    h.video.wrappedJSObject = { '__reactFiber$test': {
        memoizedProps: {}, return: { memoizedProps: { coreVideoPlayerMetaData: metadata } },
    } };
    const card = { __typename: 'Video', id: '123456', playable_duration_in_ms: 100000,
        videoDeliveryLegacyFields: { browser_native_hd_url: 'https://video.example.fbcdn.net/selected.mp4' } };
    const unrelated = { ...card, id: '999999', videoDeliveryLegacyFields: {
        browser_native_hd_url: 'https://video.example.fbcdn.net/wrong.mp4' } };
    const query = h.document.querySelectorAll;
    h.document.querySelectorAll = selector => selector.startsWith('script[') ?
        [{ textContent: JSON.stringify({ videos: [card, unrelated, { __typename: 'Video', id: '123456' }] }) }] : query(selector);
    return { h, metadata, card };
}

test('Facebook slow scan yields and can still resolve beyond the old 120 ms deadline', async () => {
    let elapsed = 0;
    const { h } = facebookHarness({ now: () => (elapsed += 5) });
    await h.probe();
    let result;
    const scan = h.command({ cmd: 'describe_stream' }).then(value => { result = value; });
    assert.ok(h.timers.size, 'slow parsing must yield');
    for (let step = 0; !result && step < 100; step++) {
        for (const [id, fn] of [...h.timers]) { h.timers.delete(id); fn(); }
        await flush();
    }
    await scan;
    assert.ok(elapsed > 120);
    assert.equal(result.url, 'https://video.example.fbcdn.net/selected.mp4');
});

for (const change of ['cancel', 'replace']) test(`Facebook ${change} during a scan never pauses or transfers a new video`, async () => {
    let elapsed = 0;
    const { h, metadata } = facebookHarness({ now: () => (elapsed += 5) });
    await h.probe();
    let result;
    const scan = h.command({ cmd: 'describe_stream' }).then(value => { result = value; });
    assert.ok(h.timers.size);
    if (change === 'cancel') await h.command({ cmd: 'release' });
    else metadata.videoFBID = '654321';
    for (let step = 0; !result && step < 100; step++) {
        for (const [id, fn] of [...h.timers]) { h.timers.delete(id); fn(); }
        await flush();
    }
    await scan;
    assert.equal(result.error, 'no_video');
    assert.equal(h.video.paused, false);
    assert.equal(h.video.currentTime, 8);
});

test('Facebook resolves only the selected element identity, without page mutation or credential access', async () => {
    const { h, metadata } = facebookHarness();
    Object.defineProperty(metadata, 'accessToken', { get() { throw new Error('Credential must not be read'); } });
    await h.probe();
    const stream = await h.command({ cmd: 'describe_stream' });
    assert.equal(stream.url, 'https://video.example.fbcdn.net/selected.mp4');
    assert.equal(stream.referrer, 'https://www.facebook.com/');
    assert.equal(h.video.paused, true);
    assert.equal(h.video.style.cssText, 'object-fit:cover;');
    assert.equal(h.parent.style.cssText, 'transform:translateZ(0);overflow:hidden;');
    await h.command({ cmd: 'return_stream', pos: 23, paused: true, resume: true });
    assert.equal(h.video.currentTime, 23);
    assert.equal(h.video.paused, true);
});

test('Facebook will not seek a new video that reuses the same blob element', async () => {
    const { h, metadata } = facebookHarness();
    await h.probe(); await h.command({ cmd: 'describe_stream' });
    metadata.videoFBID = '999999';
    await h.command({ cmd: 'return_stream', pos: 70, paused: false, resume: true });
    assert.equal(h.video.currentTime, 8);
    assert.equal(h.video.paused, true);
});

test('Facebook progressive response format is supported when legacy URLs are absent', async () => {
    const { h, card } = facebookHarness();
    delete card.videoDeliveryLegacyFields;
    card.videoDeliveryResponseFragment = { videoDeliveryResponseResult: { progressive_urls: [
        { progressive_url: 'https://video.example.fbcdn.net/progressive.mp4' },
    ] } };
    await h.probe();
    assert.equal((await h.command({ cmd: 'describe_stream' })).url, 'https://video.example.fbcdn.net/progressive.mp4');
});

test('Facebook supplies distinct HD/SD alternatives for the same selected video', async () => {
    const { h, card } = facebookHarness();
    card.videoDeliveryLegacyFields.browser_native_sd_url = 'https://video.example.fbcdn.net/selected-sd.mp4';
    card.videoDeliveryLegacyFields.playable_url_quality_hd = card.videoDeliveryLegacyFields.browser_native_hd_url;
    await h.probe(); const stream = await h.command({cmd:'describe_stream'});
    assert.equal(stream.sources.length, 2);
    assert.equal(stream.sources[1].url, 'https://video.example.fbcdn.net/selected-sd.mp4');
});

test('Facebook global DRM certificate is not mistaken for an encrypted video', async () => {
    const { h, metadata, card } = facebookHarness();
    const config = { video_license_uri_map: {}, graph_api_video_license_uri: null, widevine_cert: 'public certificate' };
    metadata.graphQLVideoDRMInfo = config;
    card.drm_info = JSON.stringify(config);
    await h.probe();
    assert.ok((await h.command({ cmd: 'describe_stream' })).url);
});

test('Facebook refuses unknown identity, foreign hosts, mismatched duration, DRM and live streams', async () => {
    for (const change of [
        x => { x.h.document.URL = 'https://facebook.com.example.com/'; },
        x => { x.metadata.videoFBID = '666666'; },
        x => { x.card.playable_duration_in_ms = 200000; },
        x => { x.card.videoDeliveryLegacyFields.browser_native_hd_url = 'https://fbcdn.net.example.com/video.mp4'; },
        x => { x.card.videoDeliveryLegacyFields.browser_native_hd_url = 'https://user:pass@video.example.fbcdn.net/video.mp4'; },
        x => { x.metadata.graphQLVideoDRMInfo = {}; },
        x => { x.card.drm_info = {}; },
        x => { x.metadata.graphQLVideoDRMInfo = { video_license_uri_map: { widevine: 'https://license.example/' } }; },
        x => { x.metadata.isLiveStreaming = true; },
    ]) {
        const setup = facebookHarness(); change(setup);
        await setup.h.probe();
        assert.ok((await setup.h.command({ cmd: 'describe_stream' })).error);
        assert.equal(setup.h.video.paused, false);
    }
});

test('Facebook does not invoke getters masquerading as player metadata', async () => {
    const { h, metadata } = facebookHarness();
    let reads = 0;
    Object.defineProperty(metadata, 'videoFBID', { get() { reads++; return '123456'; } });
    await h.probe();
    assert.equal((await h.command({ cmd: 'describe_stream' })).error, 'embedded_stream');
    assert.equal(reads, 0);
});

function element(css = '') {
    const style = { cssText: css,
        get length() { return this.declarations().size; },
        item(index) { return [...this.declarations().keys()][index]; },
        declarations() { return new Map(this.cssText.split(';').filter(Boolean).map(s => {
            const colon = s.indexOf(':'); return [s.slice(0, colon), s.slice(colon + 1)];
        })); },
        getPropertyValue(name) { return (this.declarations().get(name) || '').replace(/!important$/, ''); },
        getPropertyPriority(name) { return (this.declarations().get(name) || '').endsWith('!important') ? 'important' : ''; },
        setProperty(name, value, priority) {
            const values = this.declarations(); values.set(name, value + (priority ? '!important' : ''));
            this.cssText = [...values].map(([key, val]) => key + ':' + val + ';').join('');
        },
    };
    return { style, children: [], parentElement: null, isConnected: true,
        getAttribute: function () { return this.style.cssText || null; },
        setAttribute: function (_, value) { this.style.cssText = value; },
        removeAttribute: function () { this.style.cssText = ''; }, contains: () => false };
}
function harness({ nested = false, width = 640, shadow = false, now = () => 0 } = {}) {
    const messages = [], timers = new Map(), intervals = new Map(), listeners = {}, observers = [];
    let timerId = 0, command, fullscreenRequests = 0;
    const root = element('background:white;'), parent = element('transform:translateZ(0);overflow:hidden;');
    const sibling = element('color:red;');
    const video = Object.assign(element('object-fit:cover;'), {
        controls: true, isConnected: true, paused: false, ended: false, readyState: 4,
        currentTime: 8, duration: 100, parentElement: parent, currentSrc: 'https://cdn.example/video.mp4',
        getBoundingClientRect: () => ({ width, height: 360 }),
        addEventListener() {}, removeEventListener() {},
        play() { this.paused = false; return Promise.resolve(); }, pause() { this.paused = true; },
        requestFullscreen() { fullscreenRequests++; return Promise.reject(new Error('NotAllowedError')); },
    });
    parent.children = [video, sibling]; parent.parentElement = root; root.children = [parent];
    const shadowRoot = { querySelectorAll: s => s === 'video' ? [video] : [] };
    const document = { URL: 'https://example.com/watch?id=private', baseURI: 'https://example.com/', title: 'Video',
        documentElement: root, fullscreenElement: null, fullscreenEnabled: false, createElement: () => element(),
        querySelectorAll: s => s === 'video' ? (shadow ? [] : [video]) : (shadow ? [{ shadowRoot }] : [video]),
    };
    const window = { getComputedStyle: () => ({ visibility: 'visible', display: 'block' }),
        addEventListener: (name, fn) => { listeners[name] = fn; } };
    const posted = [];
    window.top = nested ? {} : window;
    window.parent = nested ? { postMessage: msg => posted.push(msg) } : window;
    const context = vm.createContext({ document, window, Promise, Map, isFinite, URL, Date: { now }, navigator: { userAgent: 'Test' },
        MutationObserver: class {
            constructor(callback) { this.callback = callback; this.nodes = new Set(); observers.push(this); }
            observe(node) { this.nodes.add(node); }
            disconnect() { this.nodes.clear(); }
        },
        setTimeout: fn => { timers.set(++timerId, fn); return timerId; }, clearTimeout: id => timers.delete(id),
        setInterval: fn => { intervals.set(++timerId, fn); return timerId; }, clearInterval: id => intervals.delete(id),
        browser: { runtime: {
            sendMessage: msg => { messages.push(msg); return Promise.resolve(msg.t === 'candidate' ? { frameId: 2, playing: msg.playing, area: msg.area } : null); },
            onMessage: { addListener: fn => { command = fn; } },
        } },
    });
    vm.runInContext(source, context);
    return { video, parent, sibling, root, window, document, timers, intervals, messages, posted, observers,
        mutate: () => observers.filter(observer => observer.nodes.size).forEach(observer => observer.callback()),
        probe: (id = 1) => context.upgridPlayerMain(id, 'test-token'),
        command: msg => command({ requestId: 1, ...msg }),
        confirmParent: (extra = {}) => listeners.message({ source: window.parent,
            data: { upgrid: 'expanded', requestId: 1, token: 'test-token', ok: true, ...extra } }),
        event: name => listeners[name](),
        get fullscreenRequests() { return fullscreenRequests; },
    };
}

test('discovery does not modify or fullscreen the video', async () => {
    const h = harness();
    assert.equal((await h.probe()).frameId, 2);
    assert.equal(h.video.style.cssText, 'object-fit:cover;');
    assert.equal(h.video.controls, true);
    assert.equal(h.fullscreenRequests, 0);
});
test('a decoder failure cannot open a black player or leave an active capture behind', async () => {
    const h = harness();
    h.video.error = { code: 3 };
    assert.equal(await h.probe(), null);
    assert.equal(await h.command({ cmd: 'takeover' }), 'none');
    assert.equal(h.messages.some(m => m.ok), false);
    h.video.error = null;
    await h.probe(); await h.command({ cmd: 'takeover' });
    h.video.error = { code: 3 };
    for (const tick of [...h.intervals.values()]) tick();
    assert.equal(h.messages.at(-1).t, 'released');
    assert.equal(h.video.controls, true);
    assert.equal(h.intervals.size, 0);
});
test('opens a player page without requesting fullscreen or resetting playback', async () => {
    const h = harness(); await h.probe();
    assert.equal(await h.command({ cmd: 'takeover' }), 'ok');
    assert.equal(h.fullscreenRequests, 0);
    assert.equal(h.messages.at(-1).fs, false);
    assert.equal(h.messages.at(-1).ok, true);
    assert.equal(h.video.currentTime, 8);
    assert.equal(h.video.parentElement, h.parent);
    assert.match(h.parent.style.cssText, /transform:none!important/);
    assert.match(h.video.style.cssText, /width:100vw/);
});
test('Back restores the original controls, ancestor clipping and sibling styles', async () => {
    const h = harness(); await h.probe(); await h.command({ cmd: 'takeover' });
    h.command({ cmd: 'release' });
    assert.equal(h.video.controls, true);
    assert.equal(h.video.style.cssText, 'object-fit:cover;');
    assert.equal(h.parent.style.cssText, 'transform:translateZ(0);overflow:hidden;');
    assert.equal(h.sibling.style.cssText, 'color:red;');
    assert.equal(h.root.style.cssText, 'background:white;');
    assert.equal(h.intervals.size, 0);
    assert.equal(h.observers.every(observer => !observer.nodes.size), true);
});
test('panels added or restyled during playback stay hidden and are restored on release', async () => {
    const h = harness(); await h.probe(); await h.command({ cmd: 'takeover' });
    const late = element('visibility:visible!important;');
    h.parent.children.push(late);
    h.sibling.style.cssText = 'opacity:1!important;visibility:visible!important;';
    h.video.controls = true;
    h.mutate();
    assert.equal(late.style.getPropertyValue('opacity'), '0');
    assert.equal(h.sibling.style.getPropertyValue('opacity'), '0');
    assert.equal(h.video.controls, false);
    const styles = h.video.style.cssText;
    h.mutate(); h.mutate();
    assert.equal(h.video.style.cssText, styles, 'reconciliation must not append duplicate declarations');
    h.command({ cmd: 'release' });
    assert.equal(late.style.cssText, 'visibility:visible!important;');
    assert.equal(h.sibling.style.cssText, 'color:red;');
    assert.equal(h.observers.every(observer => !observer.nodes.size), true);
});
test('nested frame waits for the parent to expand before reporting success', async () => {
    const h = harness({ nested: true }); await h.probe();
    const pending = h.command({ cmd: 'takeover' });
    assert.equal(h.messages.some(m => m.ok), false);
    h.confirmParent();
    assert.equal(await pending, 'ok');
    assert.equal(h.timers.size, 0);
});
test('missing iframe acknowledgement restores the page after timeout', async () => {
    const h = harness({ nested: true }); await h.probe();
    const pending = h.command({ cmd: 'takeover' });
    for (const timeout of [...h.timers.values()]) timeout();
    assert.equal(await pending, 'failed');
    assert.equal(h.video.controls, true);
    assert.equal(h.intervals.size, 0);
});
test('cancellation while expanding a frame prevents a late player event', async () => {
    const h = harness({ nested: true }); await h.probe();
    const pending = h.command({ cmd: 'takeover' });
    h.command({ cmd: 'release' }); h.confirmParent();
    assert.equal(await pending, 'cancelled');
    assert.equal(h.messages.some(m => m.ok), false);
});
test('a parent acknowledgement with a different token cannot activate capture', async () => {
    const h = harness({ nested: true }); await h.probe();
    const pending = h.command({ cmd: 'takeover' });
    h.confirmParent({ token: 'other' }); await flush();
    assert.equal(h.messages.some(m => m.ok), false);
    h.confirmParent(); assert.equal(await pending, 'ok');
});
test('open shadow roots are searched and zero-size videos are ignored', async () => {
    assert.equal((await harness({ shadow: true }).probe()).frameId, 2);
    assert.equal(await harness({ width: 0 }).probe(), null);
});
test('stale commands cannot change a new capture', async () => {
    const h = harness(); await h.probe(); await h.command({ cmd: 'takeover' });
    h.command({ cmd: 'release' }); await h.probe(2); await h.command({ cmd: 'takeover', requestId: 2 });
    h.command({ cmd: 'release' }); h.command({ cmd: 'toggle' });
    assert.equal(h.video.controls, false); assert.equal(h.video.paused, false);
});
test('removed video and pagehide stop monitoring and release the page', async () => {
    const h = harness(); await h.probe(); await h.command({ cmd: 'takeover' });
    h.video.isConnected = false;
    for (const tick of [...h.intervals.values()]) tick();
    assert.equal(h.intervals.size, 0); assert.equal(h.messages.at(-1).t, 'released');
});

function relayHarness(native = false, streamResponse = { url: 'https://cdn.example/video.mp4', pos: 8 }, engineResponse = 'failed', returnResponse = 'ok') {
    const hooks = {}, events = [], commands = [], executions = [], timers = new Map();
    let timerId = 0;
    const hook = name => ({ addListener: fn => { hooks[name] = fn; } });
    const context = vm.createContext({
        setTimeout: fn => { timers.set(++timerId, fn); return timerId; }, clearTimeout: id => timers.delete(id),
        browser: { runtime: { onMessage: hook('message'), connectNative: () => ({
            onMessage: hook('command'), onDisconnect: hook('disconnect'), postMessage: msg => events.push(msg),
        }) }, browserAction: { onClicked: hook('click') }, tabs: {
            onRemoved: hook('remove'), onUpdated: hook('update'), onActivated: hook('activate'),
            sendMessage: (tab, msg, options) => { commands.push({ tab, msg, options });
                return Promise.resolve(msg.cmd === 'describe_stream' ? streamResponse : msg.cmd === 'engine_takeover' ? engineResponse : msg.cmd === 'return_stream' ? returnResponse : 'ok'); },
            executeScript: (tab, options) => new Promise((resolve, reject) => { executions.push({ tab, options, resolve, reject }); }),
        } },
    });
    vm.runInContext(source, context);
    vm.runInContext(readFileSync(resolve(__dirname, '../../app/src/main/assets/extensions/upgrid_fullscreen/background.js'), 'utf8'), context);
    context.nativePlayerEnabled = native;
    return { hooks, events, commands, executions, timers,
        message: (msg, tab = 7, frame = 2) => hooks.message({ requestId: context.attempt, ...msg }, { tab: { id: tab }, frameId: frame }),
        get requestId() { return context.attempt; }, click: (tab = 7) => hooks.click({ id: tab }),
        async select(candidates = [{ frameId: 2, playing: true, area: 100 }]) { executions.at(-1).resolve(candidates); await flush(); },
    };
}
test('relay coalesces taps and selects the playing video across all frames', async () => {
    const h = relayHarness(); h.click(); h.click();
    assert.equal(h.executions.length, 1);
    await h.select([{ frameId: 0, playing: false, area: 90000 }, { frameId: 2, playing: true, area: 100 }]);
    assert.equal(h.commands.at(-1).options.frameId, 2);
    assert.equal(h.commands.at(-1).msg.cmd, 'takeover');
});

test('lifecycle cancellation waits for the final native position acknowledgement', async () => {
    let acknowledge;
    const h = relayHarness(true, undefined, 'failed', new Promise(resolve => { acknowledge=resolve; }));
    h.click(); await h.select();
    const requestId=h.requestId;
    h.hooks.command({cmd:'return_stream',requestId,pos:20,paused:true,resume:false});
    h.hooks.command({cmd:'suspend'});
    h.hooks.command({cmd:'release'});
    await flush();
    assert.equal(h.commands.some(c=>c.msg.cmd==='release'),false,'must not destroy the page session before applying position');
    assert.equal(h.commands.some(c=>c.msg.cmd==='suspend_page'),true,'background pause still happens immediately');
    assert.equal(h.commands.find(c=>c.msg.cmd==='return_stream').msg.pos,20);
    acknowledge('ok'); await flush();
    assert.equal(h.commands.filter(c=>c.msg.cmd==='release').length,1);
    assert.equal(h.events.at(-1).t,'released');
});
test('only the selected frame may confirm capture or send player state', async () => {
    const h = relayHarness(); h.click(); await h.select();
    h.message({ t: 'takeover', ok: true }, 7, 3);
    assert.equal(h.events.some(m => m.ok), false);
    h.message({ t: 'takeover', ok: true });
    h.message({ t: 'state', pos: 99 }, 7, 3);
    assert.equal(h.events.length, 1);
    h.hooks.command({ cmd: 'toggle' }); assert.equal(h.commands.at(-1).options.frameId, 2);
});
test('cancellation before discovery completes is broadcast and suppresses stale selection', async () => {
    const h = relayHarness(); h.click(); h.hooks.command({ cmd: 'release' }); await h.select();
    assert.equal(h.commands[0].options.frameId, undefined);
    assert.equal(h.commands.some(c => c.msg.cmd === 'takeover'), false);
});
test('old success, state and rejection cannot cancel a retry in the same tab', async () => {
    const h = relayHarness(); h.click(); const old = h.requestId;
    h.hooks.command({ cmd: 'release' }); h.click(); await h.select();
    h.executions[0].reject(new Error('old request')); await flush();
    h.message({ t: 'takeover', ok: true, requestId: old });
    assert.equal(h.commands.at(-1).msg.requestId, old);
    h.message({ t: 'takeover', ok: true }); const count = h.events.length;
    h.message({ t: 'released', requestId: old }); assert.equal(h.events.length, count);
});
test('navigation and missing acknowledgement release capture', async () => {
    const h = relayHarness(); h.click(); await h.select();
    for (const timeout of [...h.timers.values()]) timeout();
    assert.equal(h.events.at(-1).reason, 'player_failed');
    h.click(); h.hooks.update(7, { status: 'loading' });
    assert.equal(h.events.at(-1).t, 'released');
});
test('injected function is self-contained and receives the request token', () => {
    const h = relayHarness(); h.click(); let received;
    vm.runInNewContext(h.executions[0].options.code, {
        window: { __upgridPagePlayer: (...args) => { received = args; } },
    });
    assert.equal(received[0], h.requestId); assert.equal(typeof received[1], 'string');
});

test('native handoff pauses a real source without changing page or iframe styles', async () => {
    const h = harness({ nested: true }); await h.probe();
    const stream = await h.command({ cmd: 'describe_stream' });
    assert.equal(stream.url, h.video.currentSrc);
    assert.equal(stream.pos, 8); assert.equal(stream.paused, false);
    assert.equal(stream.referrer, 'https://example.com/');
    assert.equal(h.video.paused, true);
    assert.equal(h.video.style.cssText, 'object-fit:cover;');
    assert.equal(h.parent.style.cssText, 'transform:translateZ(0);overflow:hidden;');
    assert.equal(h.video.controls, true);
    assert.equal(h.observers.length, 0); assert.equal(h.posted.length, 0);
    await h.command({ cmd: 'return_stream', pos: 24, resume: true, paused: false });
    assert.equal(h.video.currentTime, 24); assert.equal(h.video.paused, false);
});

test('native return preserves pause/background silence and never seeks a replacement source', async () => {
    for (const [resume, paused] of [[true, true], [false, false]]) {
        const h = harness(); await h.probe(); await h.command({ cmd: 'describe_stream' });
        await h.command({ cmd: 'return_stream', pos: 21, resume, paused });
        await h.command({ cmd: 'release' });
        assert.equal(h.video.currentTime, 21); assert.equal(h.video.paused, true);
    }
    const h = harness(); await h.probe(); await h.command({ cmd: 'describe_stream' });
    h.video.currentSrc = 'https://cdn.example/next.mp4';
    await h.command({ cmd: 'return_stream', pos: 55, resume: true, paused: false });
    assert.equal(h.video.currentTime, 8); assert.equal(h.video.paused, true);
});

test('blob, protected and non-network sources fail explicitly and leave playback untouched', async () => {
    for (const src of ['blob:https://example.com/abc', 'file:///private', 'data:video/mp4,ABC']) {
        const h = harness(); h.video.currentSrc = src; await h.probe();
        assert.equal((await h.command({ cmd: 'describe_stream' })).error, 'embedded_stream');
        assert.equal(h.video.paused, false); assert.equal(h.video.controls, true);
    }
    const h = harness(); h.video.mediaKeys = {}; await h.probe();
    assert.equal((await h.command({ cmd: 'describe_stream' })).error, 'protected_stream');
    assert.equal(h.video.paused, false);
});

test('native relay transfers only the winning frame and never invokes CSS takeover', async () => {
    const h = relayHarness(true); h.click(); h.click(); await h.select();
    assert.equal(h.executions.length, 1);
    assert.equal(h.commands[0].msg.cmd, 'describe_stream');
    assert.equal(h.commands[0].options.frameId, 2);
    assert.equal(h.events.at(-1).t, 'stream'); assert.equal(h.timers.size, 0);
    h.hooks.command({ cmd: 'return_stream', requestId: h.requestId - 1, pos: 99 });
    assert.equal(h.commands.length, 1);
    h.hooks.command({ cmd: 'return_stream', requestId: h.requestId, pos: 22, resume: true });
    await flush();
    assert.equal(h.commands[1].msg.pos, 22); assert.equal(h.events.at(-1).t, 'released');
    assert.equal(h.commands.some(c => c.msg.cmd === 'takeover'), false);
});

test('unsupported native source never falsely reports a successful player', async () => {
    const h = relayHarness(true, { error: 'embedded_stream' }); h.click(); await h.select();
    assert.equal(h.events.at(-1).reason, 'embedded_stream');
    assert.equal(h.events.some(e => e.t === 'stream' || e.ok), false);
    assert.equal(h.commands.some(c => c.msg.cmd === 'takeover'), false);
});

test('opening an ended video prepares a paused frame at the beginning', async () => {
    const h = harness(); h.video.ended = true; h.video.paused = true; h.video.currentTime = 100;
    await h.probe(); const stream = await h.command({ cmd: 'describe_stream' });
    assert.equal(stream.pos, 0); assert.equal(stream.paused, true);
});

test('MSE adapters use the instance bound to the selected video, never another player', async () => {
    for (const method of ['hls', 'dash', 'shaka', 'videojs', 'jw']) {
        const h = harness(); h.video.currentSrc = 'blob:https://example.com/mse';
        if (method === 'hls') h.window.hls = { media: h.video, url: 'https://cdn.example/manifest' };
        if (method === 'dash') h.window.dash = { getVideoElement: () => h.video, getSource: () => 'https://cdn.example/manifest' };
        if (method === 'shaka') h.window.player = { getMediaElement: () => h.video, getAssetUri: () => 'https://cdn.example/manifest' };
        if (method === 'videojs') h.window.videojs = { getPlayers: () => ({
            wrong: { tech: () => ({ el: () => ({}) }), currentSources: () => [{ src: 'https://cdn.example/wrong.mp4' }] },
            selected: { tech: () => ({ el: () => h.video }), currentSources: () => [{ src: 'https://cdn.example/manifest', type: 'application/x-mpegURL' }] },
        }) };
        if (method === 'jw') h.window.jwplayer = index => ({
            getContainer: () => index ? null : { contains: node => node === h.video, querySelectorAll: () => [h.video] },
            getPlaylistItem: () => ({ duration: 100, sources: [{ file: 'https://cdn.example/manifest' }] }),
        });
        await h.probe();
        const stream = await h.command({ cmd: 'describe_stream' });
        assert.equal(stream.url, 'https://cdn.example/manifest', method);
        assert.equal(stream.sources.length, 1);
    }
});

test('unbound MSE objects and credential-bearing alternatives cannot supply a video', async () => {
    const h = harness(); h.video.currentSrc = 'blob:https://example.com/mse';
    h.window.hls = { media: {}, url: 'https://cdn.example/wrong.m3u8' };
    h.window.player = { getMediaElement: () => h.video, getAssetUri: () => 'https://user:pass@cdn.example/private.mpd' };
    await h.probe();
    assert.equal((await h.command({ cmd: 'describe_stream' })).error, 'embedded_stream');
    assert.equal(h.video.paused, false);
});

test('an MSE player that reuses its element cannot receive the previous clip position', async () => {
    const h = harness(); h.video.currentSrc = 'blob:https://example.com/mse';
    h.window.hls = {media:h.video,url:'https://cdn.example/first.m3u8'};
    await h.probe(); await h.command({cmd:'describe_stream'});
    h.window.hls.url = 'https://cdn.example/second.m3u8';
    assert.equal(await h.command({cmd:'return_stream',pos:77,paused:false,resume:true}), 'stale');
    assert.equal(h.video.currentTime, 8);
    assert.equal(h.video.paused, true);
});

test('browser-engine fallback fullscreens only the video without changing page CSS', async () => {
    const h = harness(); h.video.currentSrc = 'blob:https://example.com/mse';
    h.video.requestFullscreen = async () => { h.document.fullscreenElement = h.video; };
    h.document.exitFullscreen = async () => { h.document.fullscreenElement = null; };
    await h.probe();
    assert.equal(await h.command({ cmd: 'engine_takeover' }), 'ok');
    assert.equal(h.messages.at(-1).mode, 'engine');
    assert.equal(h.video.controls, false);
    assert.equal(h.video.paused, false);
    assert.equal(h.video.style.cssText, 'object-fit:cover;');
    assert.equal(h.parent.style.cssText, 'transform:translateZ(0);overflow:hidden;');
    assert.equal(h.observers.length, 0);
    await h.command({ cmd: 'pause' });
    assert.equal(h.video.paused, true);
    await h.command({ cmd: 'release', resume: false });
    assert.equal(h.document.fullscreenElement, null);
    assert.equal(h.video.controls, true);
    assert.equal(h.video.paused, true);
});

test('fullscreen refusal and cancellation never leave a partial player or modify CSS', async () => {
    const refused = harness(); await refused.probe();
    assert.equal(await refused.command({ cmd: 'engine_takeover' }), 'failed');
    assert.equal(refused.video.controls, true);
    assert.equal(refused.video.paused, false);
    const h = harness(); let finish;
    h.video.requestFullscreen = () => new Promise(resolve => { finish = () => { h.document.fullscreenElement = h.video; resolve(); }; });
    h.document.exitFullscreen = async () => { h.document.fullscreenElement = null; };
    await h.probe(); const enter = h.command({ cmd: 'engine_takeover' });
    await h.command({ cmd: 'release' }); finish();
    assert.equal(await enter, 'cancelled');
    assert.equal(h.document.fullscreenElement, null);
    assert.equal(h.messages.some(item => item.ok), false);
});

test('native failure starts engine fallback only for the same capture and preserves pause/position', async () => {
    const h = relayHarness(true, undefined, 'ok'); h.click(); await h.select();
    h.hooks.command({ cmd: 'engine_fallback', requestId: h.requestId - 1, pos: 22, paused: true });
    assert.equal(h.commands.length, 1);
    h.hooks.command({ cmd: 'engine_fallback', requestId: h.requestId, pos: 22, paused: true });
    await flush();
    assert.equal(h.commands[1].msg.cmd, 'return_stream');
    assert.equal(h.commands[1].msg.pos, 22);
    assert.equal(h.commands[1].msg.resume, false);
    assert.equal(h.commands[2].msg.cmd, 'engine_takeover');
    assert.equal(h.commands[2].msg.paused, true);
    assert.equal(h.commands[2].options.frameId, 2);
});
