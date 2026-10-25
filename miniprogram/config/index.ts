import { defineConfig } from '@tarojs/cli'
import { execFileSync } from 'node:child_process'
import { resolve } from 'node:path'
import { vehicleAssetPackages } from '../vehicle-assets.config'

const apiBaseUrl = process.env.TARO_APP_API_BASE_URL ?? ''
// Compile-time constant: mini-program runtime has no process global; a bare
// process.env reference would throw at module load and blank every page.
const mockFixture = process.env.TARO_APP_MOCK_FIXTURE === 'true' ? 'true' : 'false'
const sourceRevision = (() => {
  try {
    const head = execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim()
    let dirty = true
    try { execFileSync('git', [`--work-tree=${resolve(process.cwd(), '..')}`, 'diff', '--quiet', 'HEAD'], { stdio: 'ignore' }); dirty = false } catch { /* An uncommitted source is labeled explicitly. */ }
    return `${head}${dirty ? '-dirty' : ''}`
  } catch { return 'unknown' }
})()

export default defineConfig({
  projectName: 'matelink-wechat-miniprogram',
  date: '2026-09-13',
  designWidth: 750,
  deviceRatio: {
    640: 2.34 / 2,
    750: 1,
    828: 1.81 / 2,
  },
  sourceRoot: 'src',
  outputRoot: 'dist',
  copy: {
    patterns: [
      ...vehicleAssetPackages.map(packageInfo => ({ from: resolve(__dirname, '../vehicle-assets', packageInfo.root), to: resolve(__dirname, '../dist', packageInfo.root) })),
      { from: resolve(__dirname, '../vehicle-assets/native'), to: resolve(__dirname, '../dist/native') },
    ],
    options: {},
  },
  framework: 'react',
  compiler: 'webpack5',
  plugins: [
    '@tarojs/plugin-framework-react',
    '@tarojs/plugin-platform-weapp',
  ],
  defineConstants: {
    'process.env.TARO_APP_API_BASE_URL': JSON.stringify(apiBaseUrl),
    'process.env.TARO_APP_MOCK_FIXTURE': JSON.stringify(mockFixture),
    'process.env.TARO_APP_SOURCE_REVISION': JSON.stringify(sourceRevision),
  },
  mini: {
    postcss: {
      pxtransform: {
        enable: true,
      },
      url: {
        enable: false,
      },
      cssModules: {
        enable: false,
      },
    },
  },
})
