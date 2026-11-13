// ==UserScript==
// @name         Upgrid Tampermonkey page-world test
// @namespace    https://upgrid.invalid/tests
// @version      1.0.0
// @description  Local test of the early page-world injection used by the Kick script.
// @match        http://127.0.0.1/tampermonkey.html*
// @run-at       document-start
// @sandbox      raw
// @grant        none
// @noframes
// ==/UserScript==

(() => {
    'use strict';
    if (location.port !== '8766') return;
    const original = window.fetch;
    let intercepted = false;
    function fixtureFetch(input, options) {
        const value = input instanceof Request ? input.url : String(input);
        const url = new URL(value, location.href);
        if (url.origin === location.origin && url.pathname === '/tampermonkey-raw-probe') {
            intercepted = true;
            return Promise.resolve(new Response(JSON.stringify({ pageWorldHook: true }), {
                headers: { 'Content-Type': 'application/json' },
            }));
        }
        return Reflect.apply(original, this, [input, options]);
    }
    const state = { readyState: document.readyState, early: !window.upgridPageStarted };
    window.upgridRawInjection = state;
    window.fetch = fixtureFetch;
    document.addEventListener('DOMContentLoaded', () => {
        // Restore only our own wrapper; never replace a later hook.
        if (window.fetch === fixtureFetch) window.fetch = original;
        const panel = document.getElementById('upgrid-tm-raw-results');
        if (panel) panel.textContent = JSON.stringify({ ...state, intercepted }, null, 2);
    }, { once: true });
})();
