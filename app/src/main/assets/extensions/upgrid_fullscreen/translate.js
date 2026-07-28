// translate.js — translate the page where it stands.
//
// The previous implementation navigated to `*.translate.goog`, Google's
// proxy. It worked, and it brought two things the user didn't ask for: a
// captcha (the proxy rate-limits by IP and a phone on mobile data shares one
// with a lot of people) and Google's own "translated from X to Y" banner
// pinned over the top of the page, in Google's colours, with no way to restyle
// it.
//
// So: no proxy and no navigation. Collect the page's text, send it to the same
// translation endpoint Chrome's own translate uses, and write the results back
// into the text nodes they came from. The URL never changes, nothing reloads,
// the page keeps its login and its scroll position — and "show original" is
// instant, because the originals never left this script.
//
// The network call is made by background.js: a content script's fetch is
// subject to the page's CORS rules, the background page's is not.

(function () {
    if (window.__upgridTranslate) return;
    window.__upgridTranslate = true;

    var SKIP = {
        SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, TEXTAREA: 1, INPUT: 1,
        SELECT: 1, OPTION: 1, IFRAME: 1, CANVAS: 1, SVG: 1, HEAD: 1,
        // Code is text that stops working when translated.
        CODE: 1, PRE: 1, KBD: 1, SAMP: 1, VAR: 1,
    };

    // Ceilings, not targets. A page with more text than this is a document
    // dump, and translating it would be a few hundred requests.
    var MAX_NODES = 2500;
    var MAX_CHARS = 60000;

    // One request per this many characters. Small enough to stay well inside
    // the endpoint's limits, large enough that an article is a handful of
    // round-trips rather than one per paragraph.
    var CHUNK_CHARS = 1400;
    var CHUNK_ITEMS = 40;

    var originals = [];
    var translated = false;
    var busy = false;
    var sourceLanguage = "";

    function collect() {
        var out = [];
        if (!document.body) return out;
        var walker = document.createTreeWalker(
            document.body,
            NodeFilter.SHOW_TEXT,
            {
                acceptNode: function (node) {
                    var value = node.nodeValue;
                    if (!value || !value.trim()) return NodeFilter.FILTER_REJECT;
                    // Punctuation, digits and separators translate to
                    // themselves; sending them is a wasted round-trip.
                    if (!/[\p{L}]/u.test(value)) return NodeFilter.FILTER_REJECT;
                    var el = node.parentElement;
                    if (!el || SKIP[el.tagName]) return NodeFilter.FILTER_REJECT;
                    if (el.classList && el.classList.contains("upgrid-find")) {
                        return NodeFilter.FILTER_REJECT;
                    }
                    if (el.checkVisibility && !el.checkVisibility()) {
                        return NodeFilter.FILTER_REJECT;
                    }
                    return NodeFilter.FILTER_ACCEPT;
                },
            },
        );
        var node;
        var chars = 0;
        while ((node = walker.nextNode())) {
            out.push(node);
            chars += node.nodeValue.length;
            if (out.length >= MAX_NODES || chars >= MAX_CHARS) break;
        }
        return out;
    }

    /** Split into request-sized groups of indices into [items]. */
    function batches(items) {
        var out = [];
        var group = [];
        var chars = 0;
        for (var i = 0; i < items.length; i++) {
            group.push(i);
            chars += items[i].length;
            if (chars >= CHUNK_CHARS || group.length >= CHUNK_ITEMS) {
                out.push(group);
                group = [];
                chars = 0;
            }
        }
        if (group.length) out.push(group);
        return out;
    }

    function report(state) {
        try {
            browser.runtime.sendMessage({
                t: "translate",
                state: state,
                source: sourceLanguage,
                translated: translated,
            });
        } catch (e) {}
    }

    function apply(target) {
        if (busy) return;
        if (translated) { report("done"); return; }
        busy = true;
        report("working");

        var nodes = collect();
        if (!nodes.length) {
            busy = false;
            report("empty");
            return;
        }

        // The whitespace around a text node is layout, not language: it has to
        // survive the round-trip or words weld themselves to the punctuation
        // next to them.
        originals = [];
        var payload = [];
        for (var i = 0; i < nodes.length; i++) {
            var raw = nodes[i].nodeValue;
            var parts = /^(\s*)([\s\S]*?)(\s*)$/.exec(raw);
            originals.push({
                node: nodes[i],
                raw: raw,
                lead: parts[1],
                tail: parts[3],
            });
            payload.push(parts[2]);
        }

        var groups = batches(payload);
        var chain = Promise.resolve();
        var failed = false;

        groups.forEach(function (group) {
            chain = chain.then(function () {
                if (failed) return null;
                var texts = group.map(function (index) { return payload[index]; });
                return browser.runtime
                    .sendMessage({ t: "translateFetch", texts: texts, to: target })
                    .then(function (answer) {
                        if (!answer || !answer.ok || !answer.data) {
                            failed = true;
                            return null;
                        }
                        var rows = answer.data;
                        for (var k = 0; k < group.length && k < rows.length; k++) {
                            var row = rows[k];
                            var text = Array.isArray(row) ? row[0] : row;
                            if (!sourceLanguage && Array.isArray(row) && row[1]) {
                                sourceLanguage = String(row[1]);
                            }
                            if (typeof text !== "string" || !text) continue;
                            var slot = originals[group[k]];
                            if (!slot.node.parentNode) continue;
                            slot.node.nodeValue = slot.lead + text + slot.tail;
                        }
                        return null;
                    })
                    .catch(function () {
                        failed = true;
                        return null;
                    });
            });
        });

        chain.then(function () {
            busy = false;
            translated = !failed;
            report(failed ? "error" : "done");
        });
    }

    function restore() {
        for (var i = 0; i < originals.length; i++) {
            var slot = originals[i];
            if (slot.node.parentNode) slot.node.nodeValue = slot.raw;
        }
        originals = [];
        translated = false;
        report("idle");
    }

    browser.runtime.onMessage.addListener(function (msg) {
        if (!msg || !msg.cmd) return;
        if (msg.cmd === "translate") apply(String(msg.to || "en"));
        else if (msg.cmd === "untranslate") restore();
        else if (msg.cmd === "translateState") report(busy ? "working" : (translated ? "done" : "idle"));
    });
})();
