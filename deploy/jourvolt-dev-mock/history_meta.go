package main

// historyMeta is deliberately optional in the Android contract. Legacy
// TeslaMate deployments omit it; JourVolt uses it to distinguish an empty
// response from history that has not started collecting yet.
func (a *app) historyMeta(userID string) map[string]any {
	source := "fleet_api"
	if a.mockEnabled && userID == "mock-user" {
		source = "mock_fixture"
	}
	if a.hasMockHistory(userID) {
		coverage := 100.0
		return map[string]any{
			"availability":     "available",
			"source":           source,
			"coverage_percent": coverage,
		}
	}
	return map[string]any{
		"availability": "collecting",
		"source":       source,
	}
}

func (a *app) unsupportedMeta(userID string) map[string]any {
	meta := a.historyMeta(userID)
	meta["availability"] = "unsupported"
	return meta
}
