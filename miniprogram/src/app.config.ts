export default defineAppConfig({
  pages: [
    'pages/vehicle/index',
    'pages/drives/index',
    'pages/charges/index',
    'pages/me/index',
  ],
  window: {
    navigationBarTitleText: 'MateLink',
    navigationBarBackgroundColor: '#f7f8fa',
    navigationBarTextStyle: 'black',
    backgroundColor: '#f7f8fa',
    backgroundTextStyle: 'light',
  },
  tabBar: {
    color: '#8a8f98',
    selectedColor: '#111827',
    backgroundColor: '#ffffff',
    borderStyle: 'white',
    list: [
      { pagePath: 'pages/vehicle/index', text: '车辆' },
      { pagePath: 'pages/drives/index', text: '行程' },
      { pagePath: 'pages/charges/index', text: '充电' },
      { pagePath: 'pages/me/index', text: '我的' },
    ],
  },
})
