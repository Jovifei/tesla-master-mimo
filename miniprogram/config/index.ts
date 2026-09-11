import { defineConfig } from '@tarojs/cli'

export default defineConfig({
  projectName: 'matelink-wechat-miniprogram',
  date: '2026-09-11',
  designWidth: 750,
  deviceRatio: {
    640: 2.34 / 2,
    750: 1,
    828: 1.81 / 2,
  },
  sourceRoot: 'src',
  outputRoot: 'dist',
  framework: 'react',
  compiler: 'webpack5',
  plugins: [
    '@tarojs/plugin-framework-react',
    '@tarojs/plugin-platform-weapp',
  ],
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
