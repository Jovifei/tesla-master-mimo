package main

import "math"

// These are aggregates of observed samples, never a continuous vehicle peak or
// a time-weighted trip mean. Missing observations remain nil, including zero.
type historySampleMetrics struct {
	SpeedMax, SpeedAvg            *float64
	InsideTempAvg, OutsideTempAvg *float64
	SpeedCount                    int
}

func addObservedClimate(item map[string]any, inside, outside *float64) {
	inside, outside = finiteHistorySample(inside), finiteHistorySample(outside)
	if inside != nil || outside != nil {
		item["climate_info"] = map[string]any{"inside_temp": nullableFloat(inside), "outside_temp": nullableFloat(outside)}
	}
}

func importedObservedClimate(point historyImportRoutePoint) (*float64, *float64) {
	inside, outside := point.InsideTemp, point.OutsideTemp
	if point.ClimateInfo != nil {
		if point.ClimateInfo.InsideTemp != nil {
			inside = point.ClimateInfo.InsideTemp
		}
		if point.ClimateInfo.OutsideTemp != nil {
			outside = point.ClimateInfo.OutsideTemp
		}
	}
	return finiteHistorySample(inside), finiteHistorySample(outside)
}

func finiteHistorySample(value *float64) *float64 {
	if value == nil || math.IsNaN(*value) || math.IsInf(*value, 0) {
		return nil
	}
	return value
}

func sessionSampleMetrics(session telemetrySession, kind string) historySampleMetrics {
	var result historySampleMetrics
	var speedSum, insideSum, outsideSum float64
	insideCount, outsideCount := 0, 0
	add := func(speed, inside, outside *float64) {
		if kind == "drive" {
			if speed = finiteHistorySample(speed); speed != nil && *speed >= 0 && *speed <= math.MaxInt32 {
				if result.SpeedMax == nil || *speed > *result.SpeedMax {
					result.SpeedMax = cloneFloat(speed)
				}
				speedSum += *speed
				result.SpeedCount++
			}
			if inside = finiteHistorySample(inside); inside != nil {
				insideSum += *inside
				insideCount++
			}
		}
		if outside = finiteHistorySample(outside); outside != nil {
			outsideSum += *outside
			outsideCount++
		}
	}
	if kind == "charge" {
		for _, point := range session.ChargePoints {
			add(nil, nil, point.OutsideTemp)
		}
	} else if session.Source == "teslamate_archive" && session.ArchiveRoute != nil {
		for _, point := range session.ArchiveRoute {
			inside, outside := importedObservedClimate(point)
			add(point.Speed, inside, outside)
		}
	} else {
		for _, point := range session.Route {
			add(point.Speed, point.InsideTemp, point.OutsideTemp)
		}
	}
	if result.SpeedCount > 0 {
		mean := speedSum / float64(result.SpeedCount)
		result.SpeedAvg = finiteHistorySample(&mean)
	}
	if insideCount > 0 {
		mean := insideSum / float64(insideCount)
		result.InsideTempAvg = finiteHistorySample(&mean)
	}
	if outsideCount > 0 {
		mean := outsideSum / float64(outsideCount)
		result.OutsideTempAvg = finiteHistorySample(&mean)
	}
	return result
}

func applyHistorySampleMetrics(item map[string]any, metrics historySampleMetrics, kind string) {
	item["outside_temp_avg"] = nullableFloat(metrics.OutsideTempAvg)
	if kind != "drive" {
		return
	}
	item["speed_max"] = nil
	if metrics.SpeedMax != nil {
		item["speed_max"] = int(math.Round(*metrics.SpeedMax))
	}
	item["speed_avg"] = nullableFloat(metrics.SpeedAvg)
	item["inside_temp_avg"] = nullableFloat(metrics.InsideTempAvg)
	item["speed_sample_count"] = metrics.SpeedCount
	item["speed_metrics_source"] = nil
	if metrics.SpeedCount > 0 {
		item["speed_metrics_source"] = "observed_route_samples"
	}
}
