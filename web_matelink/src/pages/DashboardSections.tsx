import { useCallback, useEffect, useState } from 'react'
import { createVehiclePlace, deleteVehiclePlace, getNearbyChargingSites, getVehiclePlaces } from '../api/matelink'
import type { WebCarStatus, WebChargingSite, WebPlace } from '../api/matelink-types'
import { amapCoordinateUrl, amapMarkerUrl, siteDisplayParking, siteDisplayPrice, siteStallsText } from '../api/nearby'

/** Nearby charging + my places, the W3 sections of the dashboard (vehicle home parity with App/mini). */

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

  return (
    <div className="apple-card">
      <h2 style={{ fontSize: 16, fontWeight: 600, marginBottom: 12 }}>附近充电</h2>
      {failed ? (
        <p className="text-secondary" style={{ fontSize: 14 }}>附近充电暂时不可用。</p>
      ) : sites == null ? (
        <p className="text-secondary" style={{ fontSize: 14 }}>正在查找附近充电站…</p>
      ) : sites.length ? (
        <div style={{ display: 'flex', flexDirection: 'column' }}>
          {sites.map((site, index) => {
            const stalls = siteStallsText(site)
            const nav = amapMarkerUrl(site)
            return (
              <div key={index} style={{ padding: '10px 0', borderTop: index ? '0.5px solid var(--apple-separator)' : 'none' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12 }}>
                  <span style={{ fontSize: 14, fontWeight: 600 }}>
                    {site.name ?? '充电站'}
                    {site.siteClosed ? <span className="text-error" style={{ fontSize: 12, marginLeft: 6 }}>已关闭</span> : null}
                  </span>
                  <span className="tabular text-secondary" style={{ fontSize: 13 }}>
                    {site.distanceKm != null ? `${site.distanceKm.toFixed(1)} km` : ''}
                    {stalls ? ` · 空闲 ${stalls}` : ''}
                  </span>
                </div>
                <div className="text-secondary" style={{ fontSize: 13, marginTop: 4 }}>
                  {siteDisplayPrice(site)} · {siteDisplayParking(site)}
                  {nav ? (
                    <>
                      {' · '}
                      <a className="text-teal" href={nav} target="_blank" rel="noreferrer">导航</a>
                    </>
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
      <h2 style={{ fontSize: 16, fontWeight: 600, marginBottom: 12 }}>我的地点</h2>
      {failed ? (
        <p className="text-secondary" style={{ fontSize: 14 }}>地点暂时不可用。</p>
      ) : places == null ? (
        <p className="text-secondary" style={{ fontSize: 14 }}>正在读取地点…</p>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column' }}>
          {places.map(place => (
            <div key={place.id ?? place.name ?? ''} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '10px 0', borderTop: '0.5px solid var(--apple-separator)' }}>
              <div>
                <div style={{ fontSize: 14, fontWeight: 600 }}>{place.name ?? '未命名地点'}</div>
                <div className="text-secondary tabular" style={{ fontSize: 13, marginTop: 2 }}>
                  半径 {place.radiusM ?? '--'} m
                  {place.latitude != null && place.longitude != null ? (
                    <>
                      {' · '}
                      <a className="text-teal" href={amapCoordinateUrl(place.latitude, place.longitude, place.name ?? '地点')} target="_blank" rel="noreferrer">在地图打开</a>
                    </>
                  ) : null}
                </div>
              </div>
              <button className="apple-btn-secondary" style={{ color: 'var(--apple-error)' }} onClick={() => setConfirmDelete(place)}>删除</button>
            </div>
          ))}
          {!places.length ? <p className="text-secondary" style={{ fontSize: 14 }}>还没有保存的地点。</p> : null}
        </div>
      )}

      <div style={{ borderTop: '0.5px solid var(--apple-separator)', marginTop: 12, paddingTop: 12 }}>
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
