import Taro from '@tarojs/taro'

export interface AppNavGeometry {
  statusBarHeight: number
  navBarTop: number // where content aligns (capsule top or safe status bar offset, min 44px)
  navBarHeight: number // height of the navbar row (capsule height or 36px)
  headerTotalHeight: number // total height from screen top to bottom of navbar header
  capsuleRightPadding: number // padding to avoid WeChat capsule collision
}

export function getAppNavGeometry(): AppNavGeometry {
  try {
    const sys = Taro.getSystemInfoSync?.()
    const statusBarHeight = typeof sys?.statusBarHeight === 'number' && sys.statusBarHeight > 0
      ? sys.statusBarHeight
      : 24
    const windowWidth = typeof sys?.windowWidth === 'number' && sys.windowWidth > 0
      ? sys.windowWidth
      : 375

    const menu = Taro.getMenuButtonBoundingClientRect?.()
    if (menu && typeof menu.top === 'number' && menu.top > 0 && menu.bottom > menu.top) {
      // Ensure navBarTop is aligned with capsule top, but NEVER above status bar or below 44px safe floor
      const navBarTop = Math.max(menu.top, statusBarHeight + 4, 44)
      const navBarHeight = Math.max(menu.height, 32)
      const capsuleGap = Math.max(4, menu.top - statusBarHeight)
      const headerTotalHeight = Math.max(
        menu.bottom + capsuleGap,
        navBarTop + navBarHeight + 8,
        88
      )
      const capsuleRightPadding = typeof menu.left === 'number' && menu.left > 0
        ? Math.max(16, windowWidth - menu.left + 12)
        : 100

      return {
        statusBarHeight,
        navBarTop,
        navBarHeight,
        headerTotalHeight,
        capsuleRightPadding
      }
    }

    if (statusBarHeight > 0) {
      const navBarTop = Math.max(statusBarHeight + 6, 44)
      const navBarHeight = 36
      const headerTotalHeight = Math.max(statusBarHeight + 48, navBarTop + navBarHeight + 8, 88)
      return {
        statusBarHeight,
        navBarTop,
        navBarHeight,
        headerTotalHeight,
        capsuleRightPadding: 100
      }
    }
  } catch {
    /* fallback for test environments or non-standard hosts */
  }

  return {
    statusBarHeight: 24,
    navBarTop: 44,
    navBarHeight: 36,
    headerTotalHeight: 88,
    capsuleRightPadding: 100
  }
}
