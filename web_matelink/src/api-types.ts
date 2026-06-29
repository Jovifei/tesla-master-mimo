// MateLink API Types
// Based on teslamateapi v1.21 source code

export interface Car {
  car_id: number;
  name: string;
  car_details: {
    eid: number;
    vid: number;
    vin: string;
    model: string;
    trim_badging: string;
    efficiency: number;
  };
  car_exterior: {
    exterior_color: string;
    spoiler_type: string;
    wheel_type: string;
  };
  car_settings: {
    suspend_min: number;
    suspend_after_idle_min: number;
    req_not_unlocked: boolean;
    free_supercharging: boolean;
    use_streaming_api: boolean;
  };
  teslamate_details: {
    inserted_at: string;
    updated_at: string;
  };
  teslamate_stats: {
    total_charges: number;
    total_drives: number;
    total_updates: number;
  };
}

export type CarState = 'online' | 'offline' | 'asleep' | 'charging' | 'driving';

export interface CarStatus {
  car_id: number;
  state: CarState;
  since: string;
  healthy: boolean;
  odometer: number;
  battery_level: number;
  usable_battery_level: number;
  usable_battery_range_km: number;
  ideal_battery_range_km: number;
  charge_limit_soc: number;
  charger_power: number;
  charge_energy_added: number;
  charger_voltage: number;
  charger_actual_current: number;
  time_to_full_charge: number;
  inside_temp: number;
  outside_temp: number;
  is_climate_on: boolean;
  locked: boolean;
  sentry_mode: boolean;
  plugged_in: boolean;
  charge_port_door_open: boolean;
  tire_pressure_front_left: number;
  tire_pressure_front_right: number;
  tire_pressure_rear_left: number;
  tire_pressure_rear_right: number;
  latitude: number;
  longitude: number;
  elevation: number;
  speed: number;
  power: number;
  heading: number;
  shift_state: string | null;
}

export interface Charge {
  id: number;
  start_date: string;
  end_date: string;
  charge_energy_added: number;
  start_battery_level: number;
  end_battery_level: number;
  start_ideal_range_km: number;
  end_ideal_range_km: number;
  start_rated_range_km: number;
  end_rated_range_km: number;
  duration_min: number;
  cost: number | null;
  address: string;
  latitude: number;
  longitude: number;
  charging_type: 'AC' | 'DC';
  power_max: number;
  power_min: number;
  outside_temp_avg: number;
}

export interface Drive {
  id: number;
  start_date: string;
  end_date: string;
  distance_km: number;
  duration_min: number;
  start_address: string;
  end_address: string;
  start_latitude: number;
  start_longitude: number;
  end_latitude: number;
  end_longitude: number;
  start_battery_level: number;
  end_battery_level: number;
  start_ideal_range_km: number;
  end_ideal_range_km: number;
  outside_temp_avg: number;
  speed_max: number;
  power_max: number;
  power_min: number;
  efficiency: number;
  elevation_gain: number;
  elevation_loss: number;
}

export interface DriveDetail extends Drive {
  positions: Position[];
}

export interface Position {
  date: string;
  latitude: number;
  longitude: number;
  speed: number;
  power: number;
  odometer: number;
  elevation: number;
  battery_level: number;
  inside_temp: number;
  outside_temp: number;
  tire_pressure_front_left: number;
  tire_pressure_front_right: number;
  tire_pressure_rear_left: number;
  tire_pressure_rear_right: number;
}

export interface BatteryHealth {
  car_id: number;
  date: string;
  battery_level: number;
  rated_range_km: number;
  ideal_range_km: number;
  odometer: number;
  outside_temp: number;
  usable_battery_level: number;
}

export interface SoftwareUpdate {
  id: number;
  version: string;
  date: string;
  download_date: string | null;
  install_date: string | null;
}

export interface SentryEvent {
  id: number;
  start_date: string;
  end_date: string;
  latitude: number;
  longitude: number;
  address: string;
}

export interface Trip {
  id: number;
  name: string;
  start_date: string;
  end_date: string;
  distance_km: number;
  duration_min: number;
  energy_used: number;
  efficiency: number;
  start_address: string;
  end_address: number;
  drives: number[];
  charges: number[];
}

export interface VisitedRegion {
  country: string;
  country_code: string;
  region: string;
  distance_km: number;
  energy_used: number;
  drive_count: number;
  charge_count: number;
  last_visit: string;
}

// API Response wrapper
export interface ApiResponse<T> {
  data: T;
}

// Pagination
export interface PaginatedResponse<T> {
  data: T[];
  page: number;
  limit: number;
  total: number;
}
