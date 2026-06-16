// Dropbox OAuth popup callback. Kept as an external script (NOT inline in oauth.html) so the
// enforcing CSP can use script-src 'self' with no 'unsafe-inline'. Reads the code/state/error from
// the redirect query, posts them back to the opener, and closes the popup.
(function () {
  var params = new URLSearchParams(window.location.search);
  var code = params.get("code");
  var state = params.get("state");
  var error = params.get("error");
  if (window.opener) {
    window.opener.postMessage(
      { source: "tdz-dropbox-oauth", code: code, state: state, error: error },
      window.location.origin
    );
  }
  window.close();
})();
