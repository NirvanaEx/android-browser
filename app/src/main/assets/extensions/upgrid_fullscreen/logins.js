// logins.js — password capture and fill, in the page.
//
// Two halves, and both live here because both need the DOM:
//
//   1. Capture. When a form carrying a password field is submitted, report the
//      pair to the native side, which offers to save it. `submit` in the
//      capture phase, plus a fallback for the very common single-page-app case
//      where the login button is a <button type=button> and no submit event
//      ever fires.
//   2. Fill. On load, if the page has a password field, say so; the native side
//      answers with a saved login for this host, if it has one.
//
// Nothing is stored here and nothing is remembered between documents: the
// script holds a password only for the moment it takes to hand it over.
//
// Values are written through the prototype's own setter rather than by
// assigning `.value`, because React (and every framework that copies it)
// installs its own value setter and ignores plain assignments — the field would
// look filled and submit empty.

(function () {
    if (window.__upgridLogins) return;
    window.__upgridLogins = true;

    var USERNAME_TYPES = { text: 1, email: 1, tel: 1 };

    function isVisible(el) {
        if (!el) return false;
        if (el.disabled || el.readOnly) return false;
        if (el.type === "hidden") return false;
        try {
            if (el.checkVisibility && !el.checkVisibility()) return false;
        } catch (e) {}
        return el.offsetWidth > 0 || el.offsetHeight > 0;
    }

    /**
     * The username field for a password field.
     *
     * Searched backwards from the password within the same form, because the
     * username is above it on every login form ever built, and because a
     * "confirm email" field below it would otherwise win.
     */
    function usernameFor(passwordField) {
        var scope = passwordField.form || document;
        var fields = Array.prototype.slice.call(scope.querySelectorAll("input"));
        var at = fields.indexOf(passwordField);
        if (at < 0) at = fields.length;
        for (var i = at - 1; i >= 0; i--) {
            var candidate = fields[i];
            var type = (candidate.type || "text").toLowerCase();
            if (USERNAME_TYPES[type] && isVisible(candidate)) return candidate;
        }
        return null;
    }

    function passwordFields() {
        return Array.prototype.slice
            .call(document.querySelectorAll('input[type="password"]'))
            .filter(isVisible);
    }

    function hostOf() {
        try {
            return location.hostname || "";
        } catch (e) {
            return "";
        }
    }

    function report(message) {
        try {
            message.t = "login";
            message.host = hostOf();
            browser.runtime.sendMessage(message);
        } catch (e) {}
    }

    /**
     * A sign-in attempt worth offering to save.
     *
     * Two password fields mean a registration or a change-password form, where
     * the second one is the confirmation — we take the first and let the native
     * side decide; it stores one row per (host, username) either way.
     */
    function offer(passwordField) {
        if (!passwordField || !passwordField.value) return;
        var username = usernameFor(passwordField);
        report({
            action: "offer",
            username: username ? username.value : "",
            password: passwordField.value,
        });
    }

    document.addEventListener(
        "submit",
        function (event) {
            var form = event.target;
            if (!form || !form.querySelectorAll) return;
            var field = Array.prototype.slice
                .call(form.querySelectorAll('input[type="password"]'))
                .filter(isVisible)[0];
            offer(field);
        },
        true,
    );

    // Frameworks that never submit: the button is a plain button and the page
    // navigates by script. A filled password field that the user has just
    // clicked away from is the last moment we can see the value at all.
    document.addEventListener(
        "click",
        function (event) {
            var target = event.target;
            if (!target || !target.closest) return;
            var button = target.closest(
                'button, [role="button"], input[type="submit"], input[type="button"]',
            );
            if (!button) return;
            var fields = passwordFields();
            if (!fields.length) return;
            // Only when the button is part of the same form — a "show password"
            // eye icon is a button too, but so is half the page furniture.
            var form = fields[0].form;
            if (form && !form.contains(button)) return;
            offer(fields[0]);
        },
        true,
    );

    function setValue(field, value) {
        var proto = Object.getPrototypeOf(field);
        var descriptor = Object.getOwnPropertyDescriptor(proto, "value");
        if (descriptor && descriptor.set) descriptor.set.call(field, value);
        else field.value = value;
        field.dispatchEvent(new Event("input", { bubbles: true }));
        field.dispatchEvent(new Event("change", { bubbles: true }));
    }

    function fill(username, password) {
        var fields = passwordFields();
        if (!fields.length) return;
        var passwordField = fields[0];
        var usernameField = usernameFor(passwordField);
        if (usernameField && username) setValue(usernameField, username);
        setValue(passwordField, password);
    }

    browser.runtime.onMessage.addListener(function (msg) {
        // Shared channel: player, find and translate have their own verbs.
        if (!msg || !msg.cmd) return;
        if (msg.cmd === "loginFill") fill(msg.username || "", msg.password || "");
    });

    // Announce once the document has a password field to fill. A page without
    // one never asks, so the native side never has to reason about which pages
    // are login pages.
    function announce() {
        if (!passwordFields().length) return;
        report({ action: "ready" });
    }

    if (document.readyState === "complete") announce();
    else window.addEventListener("load", announce, { once: true });
})();
