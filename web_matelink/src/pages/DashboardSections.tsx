import { useCallback, useEffect, useState } from 'react'
import { createVehiclePlace, deleteVehiclePlace, getNearbyChargingSites, getVehiclePlaces } from '../api/matelink'
import type { WebCarStatus, WebChargingSite, WebPlace } from '../api/matelink-types'
import { amapCoordinateUrl, amapMarkerUrl, siteDisplayParking, siteDisplayPrice, siteStallsText } from '../api/nearby'
import { Icon, Skeleton, TONES } from '../components/viz'

/** Nearby charging + my places, the W3 sections of the dashboard (vehicle home parity with App/mini). */

function SectionTitle({ icon, children }: { icon: 'zap' | 'pin'; children: string }) {
  return (
    <h2 style={{ fontSize: 16, fontWeight: 600, marginBottom: 12, display: 'flex', alignItems: 'center', gap: 8 }}>
      <span style={{ color: 'var(--apple-teal)', display: 'inline-flex' }}><Icon name={icon} size={18} /></span>
      {children}
    </h2>
  )
}

export function NearbySection({ carId }: { carId: number }) {
  const [sites, setSites] = useState<WebChargingSite[] | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    let live = true
    getNearbyChargingSites(carId)
      .then(result => {
        if (live) setSites(result.sites.slice(0, 5))
      })
      .catch(() => {
        if (live) setFailed(true)
      })
    return () => {
      live = false
    }
  }, [carId])

  const maxDistance = sites ? Math.max(0, ...sites.map(site => site.distanceKm ?? 0)) : 0

  return (
    <div className="apple-card">
      <SectionTitle icon="zap">附近充电</SectionTitle>
      {failed ? (
        <p className="text-secondary" style={{ fontSize: 14 }}>附近充电暂时不可用。</p>
      ) : sites == null ? (
        <Skeleton height={168} radius={16} />
      ) : sites.length ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          {sites.map((site, index) => {
            const stalls = siteStallsText(site)
            const nav = amapMarkerUrl(site)
            const barWidth = site.distanceKm != null && maxDistance > 0 ? Math.max(5, (site.distanceKm / maxDistance) * 100) : 0
            return (
              <div key={index} className="hover-lift" style={{ borderRadius: 14, padding: '10px 12px', margin: '0 -12px' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                  <span
                    style={{
                      width: 36, height: 36, borderRadius: 18, flexShrink: 0,
                      background: site.siteClosed ? 'var(--apple-surface-tertiary)' : 'var(--apple-teal-soft)',
                      color: site.siteClosed ? 'var(--apple-label-tertiary)' : 'var(--apple-teal)',
                      display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                    }}
                  >
                    <Icon name="plug" size={18} />
                  </span>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', gap: 12 }}>
                      <span style={{ fontSize: 14, fontWeight: 600 }}>
                        {site.name ?? '充电站'}
                        {site.siteClosed ? <span className="text-error" style={{ fontSize: 12, marginLeft: 6 }}>已关闭</span> : null}
                      </span>
                      <span className="tabular" style={{ fontSize: 14, fontWeight: 700, flexShrink: 0 }}>
                        {site.distanceKm != null ? `${site.distanceKm.toFixed(1)}` : '--'}
                        {site.distanceKm != null ? <span className="text-secondary" style={{ fontSize: 11.5, fontWeight: 500, marginLeft: 3 }}>km</span> : null}
                      </span>
                    </div>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 6 }}>
                      <span style={{ flex: 1, height: 6, borderRadius: 3, background: 'var(--apple-surface-tertiary)', overflow: 'hidden', display: 'block' }}>
                        <span
                          style={{
                            display: 'block', height: '100%', borderRadius: 3,
                            width: `${barWidth}%`,
                            background: `linear-gradient(90deg, ${TONES.blue}, ${TONES.teal})`,
                            transition: 'width 600ms ease',
                          }}
                        />
                      </span>
                      {stalls ? <span className="text-secondary tabular" style={{ fontSize: 12, flexShrink: 0 }}>空闲 {stalls}</span> : null}
                    </div>
                    <div className="text-secondary" style={{ fontSize: 12.5, marginTop: 5, display: 'flex', alignItems: 'center', gap: 5 }}>
                      <Icon name="wallet" size={13} />
                      {siteDisplayPrice(site)} · {siteDisplayParking(site)}
                    </div>
                  </div>
                  {nav ? (
                    <a
                      className="apple-btn-secondary"
                      href={nav}
                      target="_blank"
                      rel="noreferrer"
                      style={{ textDecoration: 'none', padding: '6px 13px', fontSize: 12.5, flexShrink: 0 }}
                    >
                      <Icon name="nav" size={13} />
                      导航
                    </a>
                  ) : null}
                </div>
              </div>
            )
          })}
        </div>
      ) : (
        <p className="text-secondary" style={{ fontSize: 14 }}>附近暂无充电站数据。</p>
      )}
    </div>
  )
}

export function PlacesSection({ carId, status }: { carId: number; status: WebCarStatus | null }) {
  const [places, setPlaces] = useState<WebPlace[] | null>(null)
  const [failed, setFailed] = useState(false)
  const [confirmDelete, setConfirmDelete] = useState<WebPlace | null>(null)
  const [form, setForm] = useState({ name: '', latitude: '', longitude: '', radius: '200' })
  const [formError, setFormError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  const load = useCallback(async () => {
    try {
      setPlaces(await getVehiclePlaces(carId))
      setFailed(false)
    } catch {
      setFailed(true)
    }
  }, [carId])

  useEffect(() => {
    void load()
  }, [load])

  useEffect(() => {
    if (status?.latitude != null && status?.longitude != null) {
      setForm(current => ({
        ...current,
        latitude: current.latitude || String(status.latitude),
        longitude: current.longitude || String(status.longitude),
      }))
    }
  }, [status?.latitude, status?.longitude])

  const create = async () => {
    const latitude = Number(form.latitude)
    const longitude = Number(form.longitude)
    const radius = Number(form.radius)
    if (!form.name.trim()) return setFormError('请填写地点名称。')
    if (!Number.isFinite(latitude) || latitude < -90 || latitude > 90 || !Number.isFinite(longitude) || longitude < -180 || longitude > 180) {
      return setFormError('经纬度不合法。')
    }
    if (!Number.isFinite(radius) || radius < 1) return setFormError('半径至少 1 米。')
    setFormError(null)
    setSaving(true)
    try {
      await createVehiclePlace(carId, { name: form.name.trim(), latitude, longitude, radius_m: radius })
      setForm({ name: '', latitude: '', longitude: '', radius: '200' })
      await load()
    } catch {
      setFormError('保存地点失败，请稍后重试。')
    } finally {
      setSaving(false)
    }
  }

  const remove = async (place: WebPlace) => {
    if (place.id == null) return
    try {
      await deleteVehiclePlace(carId, place.id)
      await load()
    } catch {
      /* list reload shows the current truth either way */
      await load()
    }
  }

  return (
    <div className="apple-card">
      <SectionTitle icon="pin">我的地点</SectionTitle>
      {failed ? (
        <p className="text-secondary" style={{ fontSize: 14 }}>地点暂时不可用。</p>
      ) : places == null ? (
        <Skeleton height={96} radius={16} />
      ) : places.length ? (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(230px, 1fr))', gap: 10 }}>
          {places.map(place => (
            <div
              key={place.id ?? place.name ?? ''}
              className="hover-lift"
              style={{ background: 'var(--apple-surface-secondary)', borderRadius: 16, padding: '13px 15px', display: 'flex', flexDirection: 'column', gap: 9 }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                <span
                  style={{
                    width: 34, height: 34, borderRadius: 17, flexShrink: 0,
                    background: 'var(--apple-teal-soft)', color: 'var(--apple-teal)',
                    display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                  }}
                >
                  <Icon name="pin" size={17} />
                </span>
                <span style={{ fontSize: 14.5, fontWeight: 600, flex: 1, minWidth: 0 }}>{place.name ?? '未命名地点'}</span>
                <span className="chip tabular" style={{ padding: '3px 9px', fontSize: 12 }}>半径 {place.radiusM ?? '--'} m</span>
              </div>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
                {place.latitude != null && place.longitude != null ? (
                  <a
                    className="text-teal"
                    href={amapCoordinateUrl(place.latitude, place.longitude, place.name ?? '地点')}
                    target="_blank"
                    rel="noreferrer"
                    style={{ fontSize: 12.5, fontWeight: 600, display: 'inline-flex', alignItems: 'center', gap: 4 }}
                  >
                    <Icon name="nav" size={13} />
                    在地图打开
                  </a>
                ) : (
                  <span className="text-tertiary" style={{ fontSize: 12 }}>无坐标</span>
                )}
                <button
                  className="apple-btn-secondary"
                  style={{ color: 'var(--apple-error)', padding: '5px 13px', fontSize: 12.5 }}
                  onClick={() => setConfirmDelete(place)}
                >
                  删除
                </button>
              </div>
            </div>
          ))}
        </div>
      ) : (
        <p className="text-secondary" style={{ fontSize: 14 }}>还没有保存的地点。</p>
      )}

      <div style={{ borderTop: '0.5px solid var(--apple-separator)', marginTop: 14, paddingTop: 14 }}>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(110px, 1fr))', gap: 8 }}>
          <input className="apple-input" placeholder="名称（如 家）" value={form.name} onChange={event => setForm({ ...form, name: event.target.value })} />
          <input className="apple-input tabular" placeholder="纬度" value={form.latitude} onChange={event => setForm({ ...form, latitude: event.target.value })} />
          <input className="apple-input tabular" placeholder="经度" value={form.longitude} onChange={event => setForm({ ...form, longitude: event.target.value })} />
          <input className="apple-input tabular" placeholder="半径（米）" value={form.radius} onChange={event => setForm({ ...form, radius: event.target.value })} />
        </div>
        {formError ? <p className="text-error" style={{ fontSize: 13, marginTop: 8 }}>{formError}</p> : null}
        <button className="apple-btn-secondary" style={{ marginTop: 10 }} disabled={saving} onClick={() => void create()}>
          {saving ? '保存中…' : '添加地点'}
        </button>
        <p className="text-tertiary" style={{ fontSize: 12, marginTop: 8 }}>经纬度已按车辆当前位置预填，可修改。</p>
      </div>

      {confirmDelete ? (
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.35)', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 20, zIndex: 50 }} onClick={() => setConfirmDelete(null)}>
          <div className="apple-card" style={{ maxWidth: 360, width: '100%' }} onClick={event => event.stopPropagation()}>
            <h3 style={{ fontSize: 17, fontWeight: 700, marginBottom: 8 }}>删除地点「{confirmDelete.name ?? ''}」？</h3>
            <p className="text-secondary" style={{ fontSize: 13, lineHeight: 1.7, marginBottom: 16 }}>删除后该地点的围栏标注将不再显示，此操作不可恢复。</p>
            <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
              <button className="apple-btn-secondary" onClick={() => setConfirmDelete(null)}>取消</button>
              <button
                className="apple-btn-primary"
                onClick={() => {
                  const target = confirmDelete
                  setConfirmDelete(null)
                  void remove(target)
                }}
              >
                删除
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  )
}
