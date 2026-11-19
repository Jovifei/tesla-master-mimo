import { vehicleAssetPackages } from '../vehicle-assets.config'

export default defineAppConfig({
  darkmode: true,
  // WeChat requires themeLocation whenever darkmode is true; without it the
  // client can fail to start the page and leave a blank/black shell.
  themeLocation: 'theme.json',
  pages: [
    'pages/vehicle/index',
    'pages/drives/index',
    'pages/charges/index',
    'pages/me/index',
    'pages/legal/index',
    'pages/auth/index',
    'pages/analysis/index',
    'pages/insights/index',
    'pages/tools/index',
  ],
  subpackages: vehicleAssetPackages,
  window: {
    navigationBarTitleText: 'MateLink',
    navigationBarBackgroundColor: '@navBgColor',
    navigationBarTextStyle: '@navTextStyle',
    backgroundColor: '@bgColor',
    backgroundTextStyle: '@bgTextStyle',
  },
  tabBar: {
    color: '#737373',
    selectedColor: '#1d1d1f',
    backgroundColor: '#ffffff',
    borderStyle: 'white',
    list: [
      { pagePath: 'pages/vehicle/index', text: '仪表盘', iconPath: 'assets/tabs/vehicle.png', selectedIconPath: 'assets/tabs/vehicle-selected.png' },
      { pagePath: 'pages/drives/index', text: '行程', iconPath: 'assets/tabs/route.png', selectedIconPath: 'assets/tabs/route-selected.png' },
      { pagePath: 'pages/charges/index', text: '充电', iconPath: 'assets/tabs/energy.png', selectedIconPath: 'assets/tabs/energy-selected.png' },
      { pagePath: 'pages/me/index', text: '更多', iconPath: 'assets/tabs/analysis.png', selectedIconPath: 'assets/tabs/analysis-selected.png' },
    ],
  },
})
