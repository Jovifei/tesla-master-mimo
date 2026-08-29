package main

import (
	"html/template"
	"net/http"
	"net/url"
	"strings"
)

// applinkPackage is the Android applicationId of the MateLink app that the
// fallback page tries to launch via an intent:// URL. App Link verification
// relies on Google Play Services, which is unreachable in mainland China, so
// the browser lands on this page instead of opening the app directly.
const applinkPackage = "com.matelink"

type applinkView struct {
	// Status is the user-facing Chinese message describing the outcome.
	Status string
	// Detail explains what the user should do next.
	Detail string
	// IntentURL is the intent:// deep link that relaunches MateLink.
	IntentURL template.URL
	// ShowManual becomes true after the automatic attempt times out.
	ShowManual bool
}

// applinkFallbackPage serves the second-hop landing page for the OAuth App
// Link (JOURVOLT_APP_LINK_URI). When Android App Link verification has not
// completed (typical in mainland China), the browser lands here; the page
// immediately retries the launch through an intent:// URL which Chrome
// resolves without GMS verification.
func (a *app) applinkFallbackPage(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		a.json(w, http.StatusMethodNotAllowed, map[string]string{"error": "method_not_allowed"})
		return
	}
	ticket := r.URL.Query().Get("ticket")
	errorCode := r.URL.Query().Get("error")

	host := strings.TrimSuffix(a.appLinkHost(), "/")
	if host == "" {
		host = r.Host
	}

	status, detail := applinkCopy(ticket, errorCode)

	// Keep the original query (ticket or error) so the app receives the same
	// parameters whether it is opened by App Link or by the intent fallback.
	query := r.URL.Query()
	rawQuery := query.Encode()
	landing := "https://" + host + "/oauth/callback"
	if rawQuery != "" {
		landing += "?" + rawQuery
	}

	// Build the intent URL manually: url.URL.Fragment would percent-escape the
	// already-escaped browser_fallback_url a second time (%3A -> %253A).
	intent := "intent://" + host + "/oauth/callback"
	if rawQuery != "" {
		intent += "?" + rawQuery
	}
	intent += "#Intent;scheme=https;package=" + applinkPackage +
		";S.browser_fallback_url=" + url.QueryEscape(landing) + ";end"

	page := applinkView{
		Status:      status,
		Detail:      detail,
		IntentURL:   template.URL(intent),
		ShowManual:  ticket != "" || errorCode != "",
	}

	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	if err := applinkFallbackTemplate.Execute(w, page); err != nil {
		// Headers are already sent; nothing useful left to do.
		return
	}
}

// appLinkHost extracts the host from the configured App Link URI, falling
// back to empty when the config is missing so callers can use the request
// Host instead.
func (a *app) appLinkHost() string {
	if a.oauth != nil && a.oauth.config != nil && a.oauth.config.AppLinkURI != "" {
		if parsed, err := url.Parse(a.oauth.config.AppLinkURI); err == nil && parsed.Host != "" {
			return parsed.Host
		}
	}
	return ""
}

func applinkCopy(ticket, errorCode string) (status, detail string) {
	switch {
	case errorCode != "":
		return "授权失败", "授权失败：" + errorCode + "，请回到 MateLink 重新登录。"
	case ticket != "":
		return "授权成功", "授权成功，正在返回 MateLink…"
	default:
		return "链接无效", "链接无效，请从 MateLink 重新发起登录。"
	}
}

var applinkFallbackTemplate = template.Must(template.New("applink").Parse(`<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>正在返回 MateLink…</title>
<style>
  body { font-family: -apple-system, "Segoe UI", Roboto, "PingFang SC", "Microsoft YaHei", sans-serif;
         background: #f5f6f8; color: #1c1e21; display: flex; align-items: center; justify-content: center;
         min-height: 100vh; margin: 0; }
  .card { background: #fff; border-radius: 12px; box-shadow: 0 1px 4px rgba(0,0,0,.08);
          padding: 32px 24px; max-width: 360px; width: calc(100% - 48px); text-align: center; }
  h1 { font-size: 20px; margin: 0 0 12px; }
  p { font-size: 14px; color: #5f6672; margin: 0 0 20px; line-height: 1.6; }
  a.button { display: inline-block; background: #1f6f8b; color: #fff; text-decoration: none;
             padding: 12px 24px; border-radius: 24px; font-size: 15px; }
  .hidden { display: none; }
</style>
</head>
<body>
<div class="card">
  <h1>{{.Status}}</h1>
  <p>{{.Detail}}</p>
  <a id="manual" class="button hidden" href="{{.IntentURL}}">手动返回 MateLink</a>
</div>
<script>
(function () {
  // Chrome may bounce back to browser_fallback_url (this very page) when the
  // app is missing or the launch is cancelled; only auto-launch once per
  // session and then leave the manual button to the user.
  var attempted = false;
  try { attempted = sessionStorage.getItem("matelink_applink_attempted") === "1"; } catch (e) {}
  if (!attempted) {
    try { sessionStorage.setItem("matelink_applink_attempted", "1"); } catch (e) {}
    window.location.replace({{.IntentURL}});
  }
  setTimeout(function () {
    document.getElementById("manual").classList.remove("hidden");
  }, 1500);
})();
</script>
</body>
</html>
`))
