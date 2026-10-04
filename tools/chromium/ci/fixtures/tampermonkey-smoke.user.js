// ==UserScript==
// @name         Upgrid Tampermonkey smoke test
// @namespace    https://upgrid.invalid/tests
// @version      1.0.0
// @description  Checks Tampermonkey APIs only on the local Upgrid fixture.
// @match        http://127.0.0.1/tampermonkey.html*
// @run-at       document-start
// @grant        GM_info
// @grant        GM_getValue
// @grant        GM_setValue
// @grant        GM.getValue
// @grant        GM.setValue
// @grant        GM_addStyle
// @grant        GM_xmlhttpRequest
// @grant        GM_registerMenuCommand
// @connect      127.0.0.1
// ==/UserScript==

(async () => {
    'use strict';
    if (location.port !== '8766') return;
    const phase = document.readyState;
    const key = window.top === window.self ? 'main' : 'frame';
    const results = { context: key, readyStateAtInjection: phase };
    if (document.readyState === 'loading') {
        await new Promise(resolve => document.addEventListener('DOMContentLoaded', resolve, { once: true }));
    }
    const panel = document.getElementById('upgrid-tm-results');
    if (!panel) return;
    const render = () => { panel.textContent = JSON.stringify(results, null, 2); };
    panel.style.whiteSpace = 'pre-wrap';
    const check = async (name, action) => {
        try { results[name] = await action(); }
        catch { results[name] = 'ERROR'; }
        render();
    };
    await check('realTampermonkey', () => GM_info.scriptHandler === 'Tampermonkey');
    await check('legacyStorage', () => {
        GM_setValue(`${key}:legacy`, 'upgrid-fixture');
        return GM_getValue(`${key}:legacy`) === 'upgrid-fixture';
    });
    await check('persistentRunCount', async () => {
        const previous = await GM.getValue(`${key}:runs`, 0);
        const next = Number.isSafeInteger(previous) && previous >= 0 ? previous + 1 : 1;
        await GM.setValue(`${key}:runs`, next);
        return await GM.getValue(`${key}:runs`);
    });
    await check('styleInjection', () => {
        GM_addStyle('#upgrid-tm-results { color: rgb(255, 197, 54) !important; }');
        return getComputedStyle(panel).color === 'rgb(255, 197, 54)';
    });
    await check('localGmRequest', () => new Promise(resolve => {
        GM_xmlhttpRequest({
            method: 'GET', url: 'http://127.0.0.1:8766/tampermonkey-probe.json',
            timeout: 5000,
            onload: response => {
                try {
                    resolve(response.status === 200 &&
                        JSON.parse(response.responseText).fixture === 'upgrid-tampermonkey-smoke-v1');
                } catch { resolve(false); }
            },
            onerror: () => resolve(false), ontimeout: () => resolve(false),
        });
    }));
    await check('menuRegistration', () => {
        GM_registerMenuCommand('Upgrid: проверить меню', () => {
            results.menuInvoked = true;
            render();
        });
        return true;
    });
})();
