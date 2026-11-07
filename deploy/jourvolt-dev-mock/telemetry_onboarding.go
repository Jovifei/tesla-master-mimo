package main

// onboardingPhase grades the raw telemetry pairing state into the
// small set of phases clients present during onboarding (design
// 2026-11-05 §8): the user only ever sees one graded reason —
// pairing_required （未配对）, configuring （配置未同步）,
// awaiting_first_event （等待首事件）, ready, or a terminal
// permission/blocked/error state — instead of every client
// re-deriving it from status + config_synced + data presence.
//
// Terminal states win over data presence: a stream in telemetry_error
// must not present as ready just because older data exists.
func onboardingPhase(status string, configSynced *bool, hasData bool) string {
	switch status {
	case "pairing_required":
		return "pairing_required"
	case "permission_required":
		return "permission_required"
	case "billing_blocked":
		return "blocked"
	case "telemetry_error":
		return "error"
	}
	if hasData || status == "collecting" || status == "available" {
		return "ready"
	}
	if configSynced != nil && *configSynced {
		return "awaiting_first_event"
	}
	return "configuring"
}

// onboardingPhaseNeedsData reports whether the phase for this pairing
// state depends on whether any telemetry has arrived yet, so pairing()
// only pays the latest-snapshot lookup when it can change the answer.
func onboardingPhaseNeedsData(status string) bool {
	switch status {
	case "pairing_required", "permission_required", "billing_blocked", "telemetry_error":
		return false
	}
	return true
}
