import { Image } from '@tarojs/components'
import battery_primary from './icons/battery-primary.png'
import battery_muted from './icons/battery-muted.png'
import battery_warning from './icons/battery-warning.png'
import bolt_primary from './icons/bolt-primary.png'
import bolt_muted from './icons/bolt-muted.png'
import bolt_warning from './icons/bolt-warning.png'
import camera_primary from './icons/camera-primary.png'
import camera_muted from './icons/camera-muted.png'
import camera_warning from './icons/camera-warning.png'
import car_primary from './icons/car-primary.png'
import car_muted from './icons/car-muted.png'
import car_warning from './icons/car-warning.png'
import climate_primary from './icons/climate-primary.png'
import climate_muted from './icons/climate-muted.png'
import climate_warning from './icons/climate-warning.png'
import location_primary from './icons/location-primary.png'
import location_muted from './icons/location-muted.png'
import location_warning from './icons/location-warning.png'
import lock_primary from './icons/lock-primary.png'
import lock_muted from './icons/lock-muted.png'
import lock_warning from './icons/lock-warning.png'
import refresh_primary from './icons/refresh-primary.png'
import refresh_muted from './icons/refresh-muted.png'
import refresh_warning from './icons/refresh-warning.png'
import security_primary from './icons/security-primary.png'
import security_muted from './icons/security-muted.png'
import security_warning from './icons/security-warning.png'
import settings_primary from './icons/settings-primary.png'
import settings_muted from './icons/settings-muted.png'
import settings_warning from './icons/settings-warning.png'
import speed_primary from './icons/speed-primary.png'
import speed_muted from './icons/speed-muted.png'
import speed_warning from './icons/speed-warning.png'
import thermostat_primary from './icons/thermostat-primary.png'
import thermostat_muted from './icons/thermostat-muted.png'
import thermostat_warning from './icons/thermostat-warning.png'
import unlock_primary from './icons/unlock-primary.png'
import unlock_muted from './icons/unlock-muted.png'
import unlock_warning from './icons/unlock-warning.png'
import warning_primary from './icons/warning-primary.png'
import warning_muted from './icons/warning-muted.png'
import warning_warning from './icons/warning-warning.png'
import battery_primary_dark from './icons/battery-primary-dark.png'
import battery_muted_dark from './icons/battery-muted-dark.png'
import bolt_primary_dark from './icons/bolt-primary-dark.png'
import bolt_muted_dark from './icons/bolt-muted-dark.png'
import camera_primary_dark from './icons/camera-primary-dark.png'
import camera_muted_dark from './icons/camera-muted-dark.png'
import car_primary_dark from './icons/car-primary-dark.png'
import car_muted_dark from './icons/car-muted-dark.png'
import climate_primary_dark from './icons/climate-primary-dark.png'
import climate_muted_dark from './icons/climate-muted-dark.png'
import location_primary_dark from './icons/location-primary-dark.png'
import location_muted_dark from './icons/location-muted-dark.png'
import lock_primary_dark from './icons/lock-primary-dark.png'
import lock_muted_dark from './icons/lock-muted-dark.png'
import refresh_primary_dark from './icons/refresh-primary-dark.png'
import refresh_muted_dark from './icons/refresh-muted-dark.png'
import security_primary_dark from './icons/security-primary-dark.png'
import security_muted_dark from './icons/security-muted-dark.png'
import settings_primary_dark from './icons/settings-primary-dark.png'
import settings_muted_dark from './icons/settings-muted-dark.png'
import speed_primary_dark from './icons/speed-primary-dark.png'
import speed_muted_dark from './icons/speed-muted-dark.png'
import thermostat_primary_dark from './icons/thermostat-primary-dark.png'
import thermostat_muted_dark from './icons/thermostat-muted-dark.png'
import unlock_primary_dark from './icons/unlock-primary-dark.png'
import unlock_muted_dark from './icons/unlock-muted-dark.png'
import warning_primary_dark from './icons/warning-primary-dark.png'
import warning_muted_dark from './icons/warning-muted-dark.png'
const icons = {
  battery: { primary: battery_primary, muted: battery_muted, warning: battery_warning },
  bolt: { primary: bolt_primary, muted: bolt_muted, warning: bolt_warning },
  camera: { primary: camera_primary, muted: camera_muted, warning: camera_warning },
  car: { primary: car_primary, muted: car_muted, warning: car_warning },
  climate: { primary: climate_primary, muted: climate_muted, warning: climate_warning },
  location: { primary: location_primary, muted: location_muted, warning: location_warning },
  lock: { primary: lock_primary, muted: lock_muted, warning: lock_warning },
  refresh: { primary: refresh_primary, muted: refresh_muted, warning: refresh_warning },
  security: { primary: security_primary, muted: security_muted, warning: security_warning },
  settings: { primary: settings_primary, muted: settings_muted, warning: settings_warning },
  speed: { primary: speed_primary, muted: speed_muted, warning: speed_warning },
  thermostat: { primary: thermostat_primary, muted: thermostat_muted, warning: thermostat_warning },
  unlock: { primary: unlock_primary, muted: unlock_muted, warning: unlock_warning },
  warning: { primary: warning_primary, muted: warning_muted, warning: warning_warning },
}
const darkIcons = {
  battery: { primary: battery_primary_dark, muted: battery_muted_dark, warning: battery_warning },
  bolt: { primary: bolt_primary_dark, muted: bolt_muted_dark, warning: bolt_warning },
  camera: { primary: camera_primary_dark, muted: camera_muted_dark, warning: camera_warning },
  car: { primary: car_primary_dark, muted: car_muted_dark, warning: car_warning },
  climate: { primary: climate_primary_dark, muted: climate_muted_dark, warning: climate_warning },
  location: { primary: location_primary_dark, muted: location_muted_dark, warning: location_warning },
  lock: { primary: lock_primary_dark, muted: lock_muted_dark, warning: lock_warning },
  refresh: { primary: refresh_primary_dark, muted: refresh_muted_dark, warning: refresh_warning },
  security: { primary: security_primary_dark, muted: security_muted_dark, warning: security_warning },
  settings: { primary: settings_primary_dark, muted: settings_muted_dark, warning: settings_warning },
  speed: { primary: speed_primary_dark, muted: speed_muted_dark, warning: speed_warning },
  thermostat: { primary: thermostat_primary_dark, muted: thermostat_muted_dark, warning: thermostat_warning },
  unlock: { primary: unlock_primary_dark, muted: unlock_muted_dark, warning: unlock_warning },
  warning: { primary: warning_primary_dark, muted: warning_muted_dark, warning: warning_warning },
}
export type DashboardIconName = keyof typeof icons
export default function DashboardIcon({ name, tone = 'primary' }: { name: DashboardIconName; tone?: 'primary' | 'muted' | 'warning' }) {
  return <><Image className={`dashboard-icon dashboard-icon-light dashboard-icon-${name}`} src={icons[name][tone]} mode="aspectFit" /><Image className={`dashboard-icon dashboard-icon-dark dashboard-icon-${name}`} src={darkIcons[name][tone]} mode="aspectFit" /></>
}
