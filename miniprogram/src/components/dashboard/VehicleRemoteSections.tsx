import { Button, Input, Slider, Text, View } from '@tarojs/components'
import { useEffect, useState } from 'react'
import type { ChargingSite, VehiclePlace } from '../../services/types'
import { isSuperchargerSite, siteDisplayParking, siteDisplayPrice, siteStallsText } from '../../domain/nearby'
import DashboardIcon from './DashboardIcon'
import LoadingIndicator from '../LoadingIndicator'

/**
 * Dashboard fleet-expansion sections, mirroring the App's
 * VehicleRemoteSections.kt as rendered by DashboardScreen v2.1.87:
 * NearbyChargingSection + VehiclePlacesSection only. The App drives all
 * vehicle commands from the four dashboard status chips (confirm dialogs
 * on the page), so this component exposes no command UI. Both sections
 * auto-load on mount (App LaunchedEffect) and show a bare spinner while
 * loading — never an invented hint line or a manual load button.
 */

export type VehicleRemoteSectionsProps = {
  carId: number
  hasLocation: boolean

  nearbySites: ChargingSite[] | null
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
  hasLocation,
  nearbySites,
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
  const [addPlaceModalOpen, setAddPlaceModalOpen] = useState(false)
  const [placePendingDelete, setPlacePendingDelete] = useState<VehiclePlace | null>(null)
  const [newPlaceName, setNewPlaceName] = useState('')
  const [newPlaceRadius, setNewPlaceRadius] = useState(200)

  // App NearbyChargingSection: LaunchedEffect(carId, lat, lon) { loadNearby }.
  useEffect(() => { onLoadNearby() }, [carId, hasLocation])
  // App VehiclePlacesSection: LaunchedEffect(carId) { loadPlaces }.
  useEffect(() => { onLoadPlaces() }, [carId])

  return (
    <View className="vehicle-remote-sections">
      {/* 1. Nearby Charging Section */}
      <View className="card nearby-charging-card">
        <View className="nearby-card-header">
          <View className="nearby-card-title">
            <DashboardIcon name="bolt" tone="primary" />
            <Text className="section-title">附近充电</Text>
          </View>
          <Button className="dashboard-icon-button" onClick={onLoadNearby} loading={nearbyLoading}>
            <DashboardIcon name="refresh" tone="primary" />
          </Button>
        </View>

        {nearbySites === null && nearbyLoading ? (
          <LoadingIndicator compact />
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
              // App NearbyChargingSection renders at most five sites.
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
                      <Text className="site-distance">{site.distance_km.toFixed(1)} 公里</Text>
                    ) : null}
                  </View>
                  <View className="site-price-row">
                    <Text className="site-price">{siteDisplayPrice(site)}</Text>
                    <Text className="site-parking">{siteDisplayParking(site)}</Text>
                  </View>
                </View>
              ))
            ) : (
              <Text className="muted">车辆附近暂无可显示的充电站。</Text>
            )}
          </View>
        )}
      </View>

      {/* 2. Vehicle Places Section */}
      <View className="card vehicle-places-card">
        <View className="places-card-header">
          <View className="places-card-title">
            <DashboardIcon name="location" tone="primary" />
            <Text className="section-title">我的地点</Text>
          </View>
          <Button className="dashboard-icon-button" onClick={onLoadPlaces} loading={placesLoading}>
            <DashboardIcon name="refresh" tone="primary" />
          </Button>
        </View>

        {places === null && placesLoading ? (
          <LoadingIndicator compact />
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
                    {place.radius_m != null ? <Text className="place-radius">半径 {place.radius_m} 米</Text> : null}
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

            {hasLocation ? (
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

      {/* Add Place Modal — App: title 把当前位置存为地点, fields 地点名称 +
          半径 X 米 slider, actions 保存 / 取消. */}
      {addPlaceModalOpen ? (
        <View className="remote-modal-overlay" onClick={() => setAddPlaceModalOpen(false)}>
          <View className="remote-modal-dialog" onClick={e => e.stopPropagation()}>
            <Text className="modal-title">把当前位置存为地点</Text>
            <View className="input-field">
              <Text className="field-label">地点名称</Text>
              <Input
                className="place-name-input"
                value={newPlaceName}
                onInput={e => setNewPlaceName(e.detail.value)}
              />
            </View>
            <View className="slider-field">
              <Text className="field-label">半径 {newPlaceRadius} 米</Text>
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
