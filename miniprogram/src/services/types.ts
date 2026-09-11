export type ApiEnvelope<T> = {
  data?: T
  meta?: {
    page?: number
    show?: number
    total?: number
    total_pages?: number
  }
}

export type Car = {
  id: number
  name: string | null
  model: string | null
  displayName: string | null
}

export type RawCar = {
  car_id?: number
  id?: number
  name?: string | null
  model?: string | null
  car_details?: { model?: string | null }
}

export type CarStatus = {
  state: string | null
  batteryLevel: number | null
  ratedRange: number | null
  odometer: number | null
  location: { latitude: number; longitude: number } | null
  observedAt: string | null
  source: string | null
}

export type RawCarStatus = {
  state?: string | null
  odometer?: number | null
  battery_details?: {
    battery_level?: number | null
    rated_battery_range?: number | null
  }
  car_geodata?: {
    latitude?: number | null
    longitude?: number | null
  }
}

export type ReadinessItem = {
  key: string
  status: string | null
  message: string | null
  action: string | null
  message_key?: string | null
}

export type DataReadiness = {
  items: ReadinessItem[]
}

export type TelemetryPairing = {
  status: string | null
  virtual_key_url: string | null
  config_synced: boolean | null
  updated_at: string | null
  error_class: string | null
}
