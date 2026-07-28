// find.js — find in page, done in the page.
//
// Gecko has its own finder and we used to drive it. Two things it cannot do,
// and both were asked for by name:
//
//   1. Colour. The engine paints matches with an internal selection colour
//      that no page or embedder CSS can reach, so "orange like Chrome" is not
//      a setting — it's a different implementation.
//   2. Find text the page hasn't laid out yet. Feeds (Reddit, Habr) mark
//      off-screen items `content-visibility: auto`, and the engine's finder
//      skips subtrees that were never rendered. That is why a word plainly
//      visible further down the page came back as "not found".
//
// Walking the DOM ourselves answers both: the tree is there whether or not it
// has been painted, and a <span> we created is ours to style.
//
// What this gives up, honestly: a match split across element boundaries
// ("wor<b>ld</b>") isn't found, and neither is text inside an iframe — this
// script runs in the top document only, because a per-frame match counter that
// still counts "3 of 12" in document order is a much bigger machine than the
// feature is worth.

(function () {
    if (window.__upgridFind) return;
    window.__upgridFind = true;

    var CLASS = "upgrid-find";
    var CLASS_CURRENT = "upgrid-find-current";

    // Elements whose text is not page text: code the browser runs, controls the
    // user types into, or content with its own rendering.
    var SKIP = {
        SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, TEXTAREA: 1, INPUT: 1,
        SELECT: 1, OPTION: 1, IFRAME: 1, CANVAS: 1, SVG: 1, HEAD: 1, TITLE: 1,
    };

    // A page that legitimately contains ten thousand of one word is a page
    // where the counter has stopped being useful anyway, and wrapping them all
    // is the part that would take seconds.
    var MAX_MATCHES = 2000;

    var marks = [];
    var current = -1;
    var query = "";

    function clear() {
        var touched = [];
        for (var i = 0; i < marks.length; i++) {
            var mark = marks[i];
            var parent = mark.parentNode;
            if (!parent) continue;
            parent.replaceChild(document.createTextNode(mark.textContent), mark);
            if (touched.indexOf(parent) === -1) touched.push(parent);
        }
        // Undoing the wrap leaves the text in three adjacent nodes where there
        // was one. Merging them back matters: without it, searching twice for
        // overlapping words stops finding anything, because the second search
        // sees fragments too short to contain the term.
        for (var j = 0; j < touched.length; j++) {
            try { touched[j].normalize(); } catch (e) {}
        }
        marks = [];
        current = -1;
        query = "";
    }

    function candidates() {
        var out = [];
        if (!document.body) return out;
        var walker = document.createTreeWalker(
            document.body,
            NodeFilter.SHOW_TEXT,
            {
                acceptNode: function (node) {
                    if (!node.nodeValue || !node.nodeValue.trim()) {
                        return NodeFilter.FILTER_REJECT;
                    }
                    var el = node.parentElement;
                    if (!el || SKIP[el.tagName]) return NodeFilter.FILTER_REJECT;
                    // checkVisibility's defaults ignore content-visibility:auto,
                    // which is exactly what we want: that content is real, it
                    // just hasn't been painted yet. display:none is excluded.
                    if (el.checkVisibility && !el.checkVisibility()) {
                        return NodeFilter.FILTER_REJECT;
                    }
                    return NodeFilter.FILTER_ACCEPT;
                },
            },
        );
        var n;
        while ((n = walker.nextNode())) out.push(n);
        return out;
    }

    function wrap(node, needle) {
        // The list was collected before the first replacement; a script on the
        // page can detach a node between then and now.
        if (!node.parentNode) return null;
        var text = node.nodeValue;
        var lower = text.toLowerCase();
        var at = lower.indexOf(needle);
        if (at < 0) return null;

        var fragment = document.createDocumentFragment();
        var made = [];
        var offset = 0;
        while (at >= 0 && marks.length + made.length < MAX_MATCHES) {
            if (at > offset) {
                fragment.appendChild(document.createTextNode(text.slice(offset, at)));
            }
            var span = document.createElement("span");
            span.className = CLASS;
            span.textContent = text.slice(at, at + needle.length);
            fragment.appendChild(span);
            made.push(span);
            offset = at + needle.length;
            at = lower.indexOf(needle, offset);
        }
        if (offset < text.length) {
            fragment.appendChild(document.createTextNode(text.slice(offset)));
        }
        node.parentNode.replaceChild(fragment, node);
        return made;
    }

    function run(text) {
        clear();
        query = text;
        var needle = text.toLowerCase();
        if (!needle) {
            report();
            return;
        }

        var nodes = candidates();
        for (var i = 0; i < nodes.length && marks.length < MAX_MATCHES; i++) {
            var made = wrap(nodes[i], needle);
            if (made && made.length) marks = marks.concat(made);
        }

        if (marks.length) activate(0);
        report();
    }

    function activate(index) {
        if (!marks.length) return;
        if (current >= 0 && marks[current]) {
            marks[current].classList.remove(CLASS_CURRENT);
        }
        var count = marks.length;
        current = ((index % count) + count) % count;
        var el = marks[current];
        el.classList.add(CLASS_CURRENT);
        // scrollIntoView rather than a window.scrollTo computed from a
        // bounding rect: matches live inside nested scrollers as often as not,
        // and only the browser knows which ancestors have to move. `center`
        // also keeps the match clear of our own top bar.
        try {
            el.scrollIntoView({ block: "center", inline: "nearest" });
        } catch (e) {
            try { el.scrollIntoView(); } catch (e2) {}
        }
    }

    function step(delta) {
        if (!marks.length) return;
        activate(current + delta);
        report();
    }

    function report() {
        try {
            browser.runtime.sendMessage({
                t: "find",
                query: query,
                total: marks.length,
                index: marks.length ? current + 1 : 0,
            });
        } catch (e) {}
    }

    browser.runtime.onMessage.addListener(function (msg) {
        // Shared channel: observer.js and translate.js have their own verbs.
        if (!msg || !msg.cmd) return;
        if (msg.cmd === "find") run(String(msg.query || ""));
        else if (msg.cmd === "findNext") step(1);
        else if (msg.cmd === "findPrev") step(-1);
        else if (msg.cmd === "findClear") { clear(); report(); }
    });
})();
