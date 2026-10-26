import Taro from '@tarojs/taro'

export interface AppNavGeometry {
  statusBarHeight: number
  navBarTop: number // where content aligns (menu.top, or statusBarHeight + 6)
  navBarHeight: number // height of the navbar row (menu.height or 36)
  headerTotalHeight: number // from screen top to bottom of navbar
  capsuleRightPadding: number // padding to avoid WeChat capsule
}

export function getAppNavGeometry(): AppNavGeometry {
  try {
    const menu = Taro.getMenuButtonBoundingClientRect?.()
    const sys = Taro.getSystemInfoSync?.()
    const statusBarHeight = sys?.statusBarHeight ?? 24
    const windowWidth = sys?.windowWidth ?? 375

    if (menu && typeof menu.top === 'number' && menu.top > 0 && menu.bottom > menu.top) {
      const capsuleGap = Math.max(4, menu.top - statusBarHeight)
      const navBarHeight = Math.max(menu.height, 32)
      const headerTotalHeight = menu.bottom + capsuleGap
      const capsuleRightPadding = typeof menu.left === 'number' && menu.left > 0
        ? Math.max(0, windowWidth - menu.left + 12)
        : 96

      return {
        statusBarHeight,
        navBarTop: menu.top,
        navBarHeight,
        headerTotalHeight,
        capsuleRightPadding
      }
    }

    if (statusBarHeight > 0) {
      return {
        statusBarHeight,
        navBarTop: statusBarHeight + 6,
        navBarHeight: 36,
        headerTotalHeight: statusBarHeight + 48,
        capsuleRightPadding: 96
      }
    }
  } catch {
    /* fallback for test environments or non-standard hosts */
  }

  return {
    statusBarHeight: 24,
    navBarTop: 30,
    navBarHeight: 36,
    headerTotalHeight: 72,
    capsuleRightPadding: 96
  }
}
