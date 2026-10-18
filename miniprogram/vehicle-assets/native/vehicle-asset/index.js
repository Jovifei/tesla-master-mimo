// Copied unchanged to a native component. Webpack must never rewrite this scope.
var manifest = require('./manifest.js')
var loaders = require('./packages.js')
var loadedPackages = Object.create(null)
var pendingPackages = Object.create(null)
function assetError(code) { var error = new Error('车辆图片暂时无法读取'); error.code = code; return error }
function loadPackage(name) {
  if (loadedPackages[name]) return Promise.resolve()
  if (pendingPackages[name]) return pendingPackages[name]
  var loader = loaders[name]
  if (!loader) return Promise.reject(assetError('invalid_plan'))
  var promise = Promise.resolve().then(function () { return loader() }).then(function (ready) {
    if (!ready || ready.packageName !== name) throw assetError('load_failed')
    loadedPackages[name] = true
  })
  pendingPackages[name] = promise
  promise.then(function () { delete pendingPackages[name] }, function () { delete pendingPackages[name] })
  return promise
}
Component({
  options: { styleIsolation: 'isolated' },
  data: { src: '', assetKey: '', generation: 0 },
  lifetimes: {
    created: function () { this._sequence = 0; this._attached = false; this._pending = null },
    attached: function () { this._attached = true },
    detached: function () { this._attached = false; this.clearAsset() }
  },
  methods: {
    clearAsset: function (key) {
      if (key && key !== this.data.assetKey) return
      this._sequence += 1
      var pending = this._pending
      this._pending = null
      if (pending) { clearTimeout(pending.timer); pending.reject(assetError('stale_request')) }
      this.setData({ src: '', assetKey: '', generation: this._sequence })
    },
    ensureAsset: function (key) {
      var component = this
      if (!component._attached) return Promise.reject(assetError('loader_unavailable'))
      var entry = Object.prototype.hasOwnProperty.call(manifest, key) ? manifest[key] : null
      if (!entry) return Promise.reject(assetError('invalid_plan'))
      if (component._pending && component._pending.key === key) return component._pending.promise
      if (component.data.assetKey === key && component.data.src === entry.assetPath && component._lastLoaded === key) return Promise.resolve(entry.assetPath)
      component.clearAsset()
      var generation = component._sequence
      component.setData({ assetKey: key, generation: generation })
      var resolve, reject
      var promise = new Promise(function (yes, no) { resolve = yes; reject = no })
      component._pending = { key: key, generation: generation, promise: promise, resolve: resolve, reject: reject,
        timer: setTimeout(function () { component._fail(generation, 'load_failed') }, 20000) }
      loadPackage(entry.packageName).then(function () {
        if (!component._attached || component._sequence !== generation || component.data.assetKey !== key) return
        component.setData({ src: entry.assetPath })
      }, function () { component._fail(generation, 'load_failed') })
      return promise
    },
    _fail: function (generation, code) {
      var pending = this._pending
      if (!pending || pending.generation !== generation || this._sequence !== generation) return
      clearTimeout(pending.timer); this._pending = null
      this.setData({ src: '' }); pending.reject(assetError(code))
      this.triggerEvent('error', { assetKey: pending.key, code: code })
    },
    _onImageLoad: function (event) {
      var pending = this._pending, dataset = event.currentTarget.dataset
      if (!pending || !this._attached || Number(dataset.generation) !== pending.generation || dataset.assetKey !== pending.key || this._sequence !== pending.generation) return
      if (event.detail.width !== 720 || event.detail.height !== 405) { this._fail(pending.generation, 'load_failed'); return }
      clearTimeout(pending.timer); this._pending = null; this._lastLoaded = pending.key
      pending.resolve(this.data.src)
      this.triggerEvent('load', { assetKey: pending.key, src: this.data.src, width: event.detail.width, height: event.detail.height })
    },
    _onImageError: function (event) {
      var pending = this._pending, dataset = event.currentTarget.dataset
      if (pending && Number(dataset.generation) === pending.generation && dataset.assetKey === pending.key) this._fail(pending.generation, 'load_failed')
    }
  }
})
