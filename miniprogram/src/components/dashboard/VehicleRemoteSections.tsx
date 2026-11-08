import { Button, Input, Slider, Text, View } from '@tarojs/components'
import { useState } from 'react'
import type { CarStatus, ChargingSite, VehiclePlace } from '../../services/types'
import {
  commandErrorMessage,
  commandLabel,
  needsCommandReauthorization,
  offeredCommands,
} from '../../domain/vehicle_commands'
import { isSuperchargerSite, siteDisplayParking, siteDisplayPrice, siteStallsText } from '../../domain/nearby'
import DashboardIcon from './DashboardIcon'
import VisualIcon from '../VisualIcon'
import LoadingIndicator from '../LoadingIndicator'

export type CommandOutcome = {
  status: 'accepted' | 'failed'
  command: string
  verified?: boolean
  errorClass?: string
}

export type VehicleRemoteSectionsProps = {
  carId: number
  status: CarStatus
  onReauthorize?: () => void
  onExecuteCommand: (command: string, params?: Record<string, unknown>, confirm?: boolean) => Promise<void>
  busyCommand: string | null
  commandOutcome: CommandOutcome | null
  onDismissOutcome: () => void

  nearbySites: ChargingSite[] | null
  nearbySource: string | null
  nearbyLoading: boolean
  nearbyError: boolean
  onLoadNearby: () => void
  onNavigateSite?: (site: ChargingSite) => void

  places: VehiclePlace[] | null
  placesLoading: boolean
  placesError: boolean
  onLoadPlaces: () => void
  onCreatePlace: (name: string, radiusM: number) => Promise<void>
  onDeletePlace: (id: number) => Promise<void>
}

export default function VehicleRemoteSections({
  carId,
  status,
  onReauthorize,
  onExecuteCommand,
  busyCommand,
  commandOutcome,
  onDismissOutcome,
  nearbySites,
  nearbySource,
  nearbyLoading,
  nearbyError,
  onLoadNearby,
  onNavigateSite,
  places,
  placesLoading,
  placesError,
  onLoadPlaces,
  onCreatePlace,
  onDeletePlace,
}: VehicleRemoteSectionsProps) {
  const [unlockConfirmOpen, setUnlockConfirmOpen] = useState(false)
  const [climateModalOpen, setClimateModalOpen] = useState(false)
  const [chargeLimitModalOpen, setChargeLimitModalOpen] = useState(false)
  const [addPlaceModalOpen, setAddPlaceModalOpen] = useState(false)
  const [placePendingDelete, setPlacePendingDelete] = useState<VehiclePlace | null>(null)

  const [driverTemp, setDriverTemp] = useState<number>(() => status.driverTempSetting ?? 21.0)
  const [passengerTemp, setPassengerTemp] = useState<number>(() => status.passengerTempSetting ?? 21.0)
  const [chargeLimit, setChargeLimit] = useState<number>(() => status.chargeLimitSoc ?? 80)
  const [newPlaceName, setNewPlaceName] = useState('')
  const [newPlaceRadius, setNewPlaceRadius] = useState(200)

  const reauthNeeded = needsCommandReauthorization(status)
  const commands = offeredCommands(status)
  const isBusy = busyCommand !== null

  const isLocked = status.locked ?? true
  const isClimateOn = status.isClimateOn ?? false
  const isPortOpen = status.chargePortDoorOpen ?? false
  const isCharging = status.chargingState?.toLowerCase() === 'charging'
  const isSentryOn = status.sentryMode ?? false
  const isWindowsOpen = status.windowsOpen ?? false
  const climateTarget = status.driverTempSetting ?? 21.0

  const handleAdjustTemp = (delta: number) => {
    const next = Math.max(15, Math.min(28, Math.round((climateTarget + delta) * 2) / 2))
    void onExecuteCommand('set_climate_temp', { driver_temp: next, passenger_temp: next })
  }

  return (
    <View className="vehicle-remote-sections">
      {/* 1. Vehicle Controls Section */}
      {reauthNeeded ? (
        <View className="card vehicle-reauth-panel">
          <View className="reauth-heading">
            <DashboardIcon name="lock" tone="warning" />
            <Text className="reauth-title">车辆控制需重新授权</Text>
          </View>
          <Text className="reauth-body">
            由于控制安全权限升级，需要您为该车辆补充授予控制权限范围。
          </Text>
          {onReauthorize ? (
            <Button className="button" onClick={onReauthorize}>
              重新授权
            </Button>
          ) : null}
        </View>
      ) : commands.length > 0 ? (
        <View className="card vehicle-remote-card">
          <View className="remote-card-header">
            <View className="remote-card-title">
              <DashboardIcon name="car" tone="primary" />
              <Text className="section-title">车辆控制</Text>
            </View>
            {isBusy ? (
              <View className="remote-busy-indicator">
                <Text className="remote-busy-text">执行中…</Text>
              </View>
            ) : null}
          </View>

          {/* Inline Feedback Pill */}
          {commandOutcome ? (
            <View className={`command-outcome-pill outcome-${commandOutcome.status}`}>
              <Text className="outcome-text">
                {commandOutcome.status === 'accepted'
                  ? `${commandLabel(commandOutcome.command)}：${
                      commandOutcome.verified
                        ? '已执行并确认。'
                        : '车辆已接受指令，但状态回读未确认，请以车辆实际状态为准。'
                    }`
                  : `${commandLabel(commandOutcome.command)}：${commandErrorMessage(commandOutcome.errorClass)}`}
              </Text>
              <Button className="outcome-dismiss" onClick={onDismissOutcome}>
                ✕
              </Button>
            </View>
          ) : null}

          {/* Group 1: 门锁与寻车 */}
          <View className="command-group">
            <Text className="command-group-title">门锁与寻车</Text>
            <View className="command-actions-row">
              {isLocked ? (
                <Button
                  className="remote-action-btn"
                  disabled={isBusy}
                  onClick={() => setUnlockConfirmOpen(true)}
                >
                  <DashboardIcon name="unlock" tone="muted" />
                  <Text className="btn-label">解锁</Text>
                </Button>
              ) : (
                <Button
                  className="remote-action-btn active"
                  disabled={isBusy}
                  onClick={() => void onExecuteCommand('lock')}
                >
                  <DashboardIcon name="lock" tone="primary" />
                  <Text className="btn-label">上锁</Text>
                </Button>
              )}
              <Button
                className="remote-action-btn"
                disabled={isBusy}
                onClick={() => void onExecuteCommand('flash')}
              >
                <VisualIcon name="energy" />
                <Text className="btn-label">闪灯</Text>
              </Button>
              <Button
                className="remote-action-btn"
                disabled={isBusy}
                onClick={() => void onExecuteCommand('honk')}
              >
                <DashboardIcon name="speed" tone="muted" />
                <Text className="btn-label">鸣笛</Text>
              </Button>
            </View>
          </View>

          <View className="remote-group-divider" />

          {/* Group 2: 空调温控 */}
          <View className="command-group">
            <Text className="command-group-title">空调温控</Text>
            <View className="command-actions-row">
              {isClimateOn ? (
                <Button
                  className="remote-action-btn active flex-1"
                  disabled={isBusy}
                  onClick={() => void onExecuteCommand('climate_off')}
                >
                  <DashboardIcon name="climate" tone="primary" />
                  <Text className="btn-label">关闭空调</Text>
                </Button>
              ) : (
                <Button
                  className="remote-action-btn flex-1"
                  disabled={isBusy}
                  onClick={() => void onExecuteCommand('climate_on')}
                >
                  <DashboardIcon name="climate" tone="muted" />
                  <Text className="btn-label">打开空调</Text>
                </Button>
              )}
              <View className="temperature-stepper flex-1">
                <Button
                  className="stepper-btn"
                  disabled={isBusy}
                  onClick={() => handleAdjustTemp(-0.5)}
                >
                  −
                </Button>
                <View
                  className="stepper-display"
                  onClick={() => {
                    setDriverTemp(status.driverTempSetting ?? 21.0)
                    setPassengerTemp(status.passengerTempSetting ?? 21.0)
                    setClimateModalOpen(true)
                  }}
                >
                  <DashboardIcon name="thermostat" tone="primary" />
                  <Text className="temp-val">{climateTarget.toFixed(1)}°C</Text>
                </View>
                <Button
                  className="stepper-btn"
                  disabled={isBusy}
                  onClick={() => handleAdjustTemp(0.5)}
                >
                  +
                </Button>
              </View>
            </View>
            <View className="command-actions-row">
              <Button
                className={`remote-action-btn flex-1${isWindowsOpen ? ' active' : ''}`}
                disabled={isBusy}
                onClick={() => void onExecuteCommand('windows_vent')}
              >
                <Text className="btn-label">车窗通风</Text>
              </Button>
              <Button
                className="remote-action-btn flex-1"
                disabled={isBusy}
                onClick={() => void onExecuteCommand('windows_close')}
              >
                <Text className="btn-label">关闭车窗</Text>
              </Button>
            </View>
          </View>

          <View className="remote-group-divider" />

          {/* Group 3: 充电管理 */}
          <View className="command-group">
            <Text className="command-group-title">充电管理</Text>
            <View className="command-actions-row">
              {isPortOpen ? (
                <Button
                  className="remote-action-btn active flex-1"
                  disabled={isBusy}
                  onClick={() => void onExecuteCommand('charge_port_close')}
                >
                  <DashboardIcon name="bolt" tone="primary" />
                  <Text className="btn-label">关闭充电口</Text>
                </Button>
              ) : (
                <Button
                  className="remote-action-btn flex-1"
                  disabled={isBusy}
                  onClick={() => void onExecuteCommand('charge_port_open')}
                >
                  <DashboardIcon name="bolt" tone="muted" />
                  <Text className="btn-label">打开充电口</Text>
                </Button>
              )}
              {isCharging ? (
                <Button
                  className="remote-action-btn active warning flex-1"
                  disabled={isBusy}
                  onClick={() => void onExecuteCommand('charge_stop')}
                >
                  <Text className="btn-label">停止充电</Text>
                </Button>
              ) : (
                <Button
                  className="remote-action-btn flex-1"
                  disabled={isBusy || status.pluggedIn !== true}
                  onClick={() => void onExecuteCommand('charge_start')}
                >
                  <Text className="btn-label">开始充电</Text>
                </Button>
              )}
              <Button
                className="remote-action-btn flex-1"
                disabled={isBusy}
                onClick={() => {
                  setChargeLimit(status.chargeLimitSoc ?? 80)
                  setChargeLimitModalOpen(true)
                }}
              >
                <Text className="btn-label">上限 {status.chargeLimitSoc ?? 80}%</Text>
              </Button>
            </View>
          </View>

          <View className="remote-group-divider" />

          {/* Group 4: 安全防护 */}
          <View className="command-group">
            <Text className="command-group-title">安全防护</Text>
            <View className="command-actions-row">
              {isSentryOn ? (
                <Button
                  className="remote-action-btn active flex-1"
                  disabled={isBusy}
                  onClick={() => void onExecuteCommand('sentry_off')}
                >
                  <DashboardIcon name="security" tone="primary" />
                  <Text className="btn-label">关闭哨兵模式</Text>
                </Button>
              ) : (
                <Button
                  className="remote-action-btn flex-1"
                  disabled={isBusy}
                  onClick={() => void onExecuteCommand('sentry_on')}
                >
                  <DashboardIcon name="security" tone="muted" />
                  <Text className="btn-label">打开哨兵模式</Text>
                </Button>
              )}
            </View>
          </View>
        </View>
      ) : null}

      {/* 2. Nearby Charging Section */}
      <View className="card nearby-charging-card">
        <View className="nearby-card-header">
          <View className="nearby-card-title">
            <DashboardIcon name="bolt" tone="primary" />
            <Text className="section-title">附近充电</Text>
          </View>
          {nearbySites !== null ? (
            <Button className="dashboard-icon-button" onClick={onLoadNearby} loading={nearbyLoading}>
              <DashboardIcon name="refresh" tone="muted" />
            </Button>
          ) : null}
        </View>

        {nearbySites === null && !nearbyLoading ? (
          <View className="nearby-unloaded">
            <Text className="nearby-hint">车辆附近的超级充电站与目的地充电，按需加载。</Text>
            <Button className="button" onClick={onLoadNearby}>
              加载附近充电站
            </Button>
          </View>
        ) : nearbyLoading ? (
          <LoadingIndicator label="正在搜索附近充电站…" compact />
        ) : nearbyError ? (
          <View className="nearby-error">
            <Text className="muted">附近充电站加载失败，请重试。</Text>
            <Button className="button button-secondary" onClick={onLoadNearby}>
              重试
            </Button>
          </View>
        ) : (
          <View className="nearby-list">
            {nearbySites && nearbySites.length > 0 ? (
              nearbySites.slice(0, 5).map(site => (
                <View
                  key={`${site.latitude}-${site.longitude}-${site.name}`}
                  className="nearby-site-row"
                  onClick={onNavigateSite ? () => onNavigateSite(site) : undefined}
                >
                  <View className="site-main-row">
                    <View className={`site-type-badge ${isSuperchargerSite(site) ? 'super' : 'dest'}`}>
                      <DashboardIcon name="bolt" tone={isSuperchargerSite(site) ? 'warning' : 'primary'} />
                    </View>
                    <View className="site-info">
                      <Text className="site-name">{site.name || '充电站'}</Text>
                      <Text className="site-desc">
                        {isSuperchargerSite(site) ? '超级充电站' : '目的地充电'}
                        {siteStallsText(site) ? ` · 空闲 ${siteStallsText(site)}` : ''}
                      </Text>
                    </View>
                    {site.site_closed ? (
                      <Text className="site-closed-tag">已关闭</Text>
                    ) : site.distance_km != null ? (
                      <Text className="site-distance">{site.distance_km.toFixed(1)} km</Text>
                    ) : null}
                  </View>
                  <View className="site-price-row">
                    <Text className="site-price">{siteDisplayPrice(site)}</Text>
                    <Text className="site-parking">🅿️ {siteDisplayParking(site)}</Text>
                  </View>
                </View>
              ))
            ) : (
              <Text className="muted">车辆附近暂无可显示的充电站。</Text>
            )}
            {nearbySource === 'cache' ? (
              <Text className="muted nearby-cached-hint">数据来自缓存</Text>
            ) : null}
          </View>
        )}
      </View>

      {/* 3. Vehicle Places Section */}
      <View className="card vehicle-places-card">
        <View className="places-card-header">
          <View className="places-card-title">
            <DashboardIcon name="location" tone="primary" />
            <Text className="section-title">我的地点</Text>
          </View>
          <Button className="dashboard-icon-button" onClick={onLoadPlaces} loading={placesLoading}>
            <DashboardIcon name="refresh" tone="muted" />
          </Button>
        </View>

        {placesLoading ? (
          <LoadingIndicator label="正在读取我的地点…" compact />
        ) : placesError ? (
          <View className="places-error">
            <Text className="muted">地点加载失败，请重试。</Text>
            <Button className="button button-secondary" onClick={onLoadPlaces}>
              重试
            </Button>
          </View>
        ) : (
          <View className="places-list">
            {places && places.length > 0 ? (
              places.map(place => (
                <View key={place.id ?? `${place.latitude}-${place.longitude}-${place.name}`} className="place-row">
                  <DashboardIcon name="location" tone="primary" />
                  <View className="place-info">
                    <Text className="place-name">{place.name}</Text>
                    <Text className="place-radius">半径 {place.radius_m || 200} 米</Text>
                  </View>
                  {place.id != null ? (
                    <Button
                      className="place-delete-btn"
                      onClick={() => setPlacePendingDelete(place)}
                    >
                      删除
                    </Button>
                  ) : null}
                </View>
              ))
            ) : (
              <Text className="muted">还没有保存的地点。把车辆当前位置存下来即可命名。</Text>
            )}

            {status.location ? (
              <Button
                className="button button-secondary add-place-trigger"
                onClick={() => {
                  setNewPlaceName('')
                  setNewPlaceRadius(200)
                  setAddPlaceModalOpen(true)
                }}
              >
                把当前位置存为地点
              </Button>
            ) : (
              <Text className="muted place-no-pos">暂无车辆位置，暂时无法添加地点。</Text>
            )}
          </View>
        )}
      </View>

      {/* Place Delete Confirmation Modal (mirrors the App's pendingDelete dialog) */}
      {placePendingDelete ? (
        <View className="remote-modal-overlay" onClick={() => setPlacePendingDelete(null)}>
          <View className="remote-modal-dialog" onClick={e => e.stopPropagation()}>
            <Text className="modal-title">删除地点「{placePendingDelete.name}」？</Text>
            <View className="modal-actions">
              <Button
                className="button place-delete-confirm-btn"
                onClick={() => {
                  const id = placePendingDelete.id
                  setPlacePendingDelete(null)
                  if (id != null) void onDeletePlace(id)
                }}
              >
                确认执行
              </Button>
              <Button className="button button-secondary" onClick={() => setPlacePendingDelete(null)}>
                取消
              </Button>
            </View>
          </View>
        </View>
      ) : null}

      {/* Unlock Confirmation Modal */}
      {unlockConfirmOpen ? (
        <View className="remote-modal-overlay" onClick={() => setUnlockConfirmOpen(false)}>
          <View className="remote-modal-dialog" onClick={e => e.stopPropagation()}>
            <Text className="modal-title">确认解锁车辆？</Text>
            <Text className="modal-body">解锁是高敏感操作。请确认车辆处于安全环境后再执行。</Text>
            <View className="modal-actions">
              <Button
                className="button"
                onClick={() => {
                  setUnlockConfirmOpen(false)
                  void onExecuteCommand('unlock', undefined, true)
                }}
              >
                确认执行
              </Button>
              <Button className="button button-secondary" onClick={() => setUnlockConfirmOpen(false)}>
                取消
              </Button>
            </View>
          </View>
        </View>
      ) : null}

      {/* Climate Temperature Modal */}
      {climateModalOpen ? (
        <View className="remote-modal-overlay" onClick={() => setClimateModalOpen(false)}>
          <View className="remote-modal-dialog" onClick={e => e.stopPropagation()}>
            <Text className="modal-title">设置空调温度</Text>
            <View className="slider-field">
              <Text className="field-label">驾驶位：{driverTemp.toFixed(1)}°C</Text>
              <Slider
                min={15}
                max={28}
                step={0.5}
                value={driverTemp}
                activeColor="var(--dashboard-primary)"
                onChanging={e => setDriverTemp(Number(e.detail.value))}
                onChange={e => setDriverTemp(Number(e.detail.value))}
              />
            </View>
            <View className="slider-field">
              <Text className="field-label">副驾驶：{passengerTemp.toFixed(1)}°C</Text>
              <Slider
                min={15}
                max={28}
                step={0.5}
                value={passengerTemp}
                activeColor="var(--dashboard-primary)"
                onChanging={e => setPassengerTemp(Number(e.detail.value))}
                onChange={e => setPassengerTemp(Number(e.detail.value))}
              />
            </View>
            <View className="modal-actions">
              <Button
                className="button"
                onClick={() => {
                  setClimateModalOpen(false)
                  void onExecuteCommand('set_climate_temp', {
                    driver_temp: driverTemp,
                    passenger_temp: passengerTemp,
                  })
                }}
              >
                确认设置
              </Button>
              <Button className="button button-secondary" onClick={() => setClimateModalOpen(false)}>
                取消
              </Button>
            </View>
          </View>
        </View>
      ) : null}

      {/* Charge Limit Modal */}
      {chargeLimitModalOpen ? (
        <View className="remote-modal-overlay" onClick={() => setChargeLimitModalOpen(false)}>
          <View className="remote-modal-dialog" onClick={e => e.stopPropagation()}>
            <Text className="modal-title">设置充电上限</Text>
            <Text className="modal-lead-value">{chargeLimit}%</Text>
            <View className="slider-field">
              <Slider
                min={50}
                max={100}
                step={1}
                value={chargeLimit}
                activeColor="var(--dashboard-primary)"
                onChanging={e => setChargeLimit(Number(e.detail.value))}
                onChange={e => setChargeLimit(Number(e.detail.value))}
              />
            </View>
            <View className="modal-actions">
              <Button
                className="button"
                onClick={() => {
                  setChargeLimitModalOpen(false)
                  void onExecuteCommand('set_charge_limit', { percent: chargeLimit })
                }}
              >
                确认设置
              </Button>
              <Button className="button button-secondary" onClick={() => setChargeLimitModalOpen(false)}>
                取消
              </Button>
            </View>
          </View>
        </View>
      ) : null}

      {/* Add Place Modal */}
      {addPlaceModalOpen ? (
        <View className="remote-modal-overlay" onClick={() => setAddPlaceModalOpen(false)}>
          <View className="remote-modal-dialog" onClick={e => e.stopPropagation()}>
            <Text className="modal-title">添加我的地点</Text>
            <View className="input-field">
              <Text className="field-label">地点名称</Text>
              <Input
                className="place-name-input"
                placeholder="例如：家、公司"
                value={newPlaceName}
                onInput={e => setNewPlaceName(e.detail.value)}
              />
            </View>
            <View className="slider-field">
              <Text className="field-label">有效范围半径：{newPlaceRadius} 米</Text>
              <Slider
                min={50}
                max={2000}
                step={50}
                value={newPlaceRadius}
                activeColor="var(--dashboard-primary)"
                onChanging={e => setNewPlaceRadius(Number(e.detail.value))}
                onChange={e => setNewPlaceRadius(Number(e.detail.value))}
              />
            </View>
            <View className="modal-actions">
              <Button
                className="button"
                disabled={!newPlaceName.trim()}
                onClick={() => {
                  setAddPlaceModalOpen(false)
                  void onCreatePlace(newPlaceName.trim(), newPlaceRadius)
                }}
              >
                保存
              </Button>
              <Button className="button button-secondary" onClick={() => setAddPlaceModalOpen(false)}>
                取消
              </Button>
            </View>
          </View>
        </View>
      ) : null}
    </View>
  )
}
