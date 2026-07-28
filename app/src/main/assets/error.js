/*
 * Fills in the error page from its own query string.
 *
 * A separate file, not a <script> block: this document is system-privileged and
 * Gecko's CSP for those has no 'unsafe-inline', so an inline script never runs
 * at all — which looked exactly like a page with nothing on it.
 *
 * And it reads document.documentURI rather than location.search, because Gecko
 * loads an error page with the FAILED address still in location (that is what
 * keeps the address bar honest). location.search here belongs to the site that
 * didn't answer.
 */
(function () {
  function query() {
    var uri = document.documentURI || "";
    var mark = uri.indexOf("?");
    if (mark >= 0) return uri.slice(mark + 1);
    // Nothing to go on. location.search is not where our parameters are, but
    // an empty string is the honest answer and every getter below copes.
    return "";
  }

  var q = new URLSearchParams(query());

  function put(id, value) {
    document.getElementById(id).textContent = value || "";
  }

  var title = q.get("t") || "";
  document.title = title;
  put("title", title);
  put("body", q.get("m"));

  var url = q.get("u") || "";
  put("url", url);

  // Retry is a fresh navigation to the address that failed, never
  // location.reload() — reloading would reload *this* page, which is the one
  // thing certain to work. With no address to go back to (which should not
  // happen: the interceptor always passes one) a reload is the honest
  // fallback, since location is still the failed page.
  var retry = document.getElementById("retry");
  retry.textContent = q.get("r") || "↻";
  retry.addEventListener("click", function () {
    if (url) {
      location.href = url;
    } else {
      location.reload();
    }
  });

  var back = document.getElementById("back");
  back.textContent = q.get("b") || "";
  if (history.length > 1) {
    back.addEventListener("click", function () { history.back(); });
  } else {
    back.style.display = "none";
  }
})();
