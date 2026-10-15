import type { Charge, Drive, HistoryPageMeta } from './types'

export type AnalysisMeta = { availability: string | null; source: string | null; qualityState: string | null; qualityReason: string | null; observedAt: string | null }
export type AnalysisOptions = { isCurrent?: () => boolean }
export type AnalysisHistory<T extends Drive | Charge> = { items: T[]; complete: boolean; pages: number; receivedPages: number; reason: string | null; meta: HistoryPageMeta }
export type BatteryHealth = { maxRangeKm: number | null; currentRangeKm: number | null; maxCapacityKwh: number | null; currentCapacityKwh: number | null; ratedEfficiencyWhKm: number | null; healthPercentage: number | null }
export type SoftwareUpdate = { id: string; version: string | null; startDate: string | null; endDate: string | null }
export type DriveEvidencePoint = { date: string; speedKmh: number | null; powerKw: number | null; batteryLevel: number | null; elevationM: number | null; latitude: number | null; longitude: number | null }
export type DriveEvidence = { drive: Drive | null; points: DriveEvidencePoint[]; source: string | null; qualityState: string | null; pointsComplete: boolean; reason: string | null }
export type ChargeEvidencePoint = { date: string; batteryLevel: number | null; powerKw: number | null; voltageV: number | null; currentA: number | null; temperatureC: number | null }
export type ChargeEvidenceSummary = { chargerPowerKw: number | null; chargerVoltageV: number | null; currentA: number | null; phases: number | null;
  currentRequestA: number | null; currentRequestMaxA: number | null; timeToFullHours: number | null; currentBatteryLevel: number | null;
  outsideTempC: number | null; startRatedRangeKm: number | null; endRatedRangeKm: number | null; odometerKm: number | null; isCharging: boolean | null }
export type ChargeEvidence = { charge: Charge | null; points: ChargeEvidencePoint[]; summary: ChargeEvidenceSummary; source: string | null; qualityState: string | null; pointsComplete: boolean; reason: string | null }
export type ParkedDetail = { olderDriveId: string | null; newerDriveId: string | null; startDate: string | null; endDate: string | null; address: string | null;
  startBatteryLevel: number | null; endBatteryLevel: number | null; batteryDelta: number | null;
  energyKwh: number | null; averagePowerW: number | null; peakPowerW: number | null;
  insideTempC: number | null; outsideTempC: number | null; sampleCount: number | null;
  coverageSeconds: number | null; coverageRatio: number | null; linkedChargeId: string | null; source: string | null; energyReason: string | null }
