import SwiftUI
import UIKit

/// Per-vehicle color palette that drives accent colors in data-heavy views
/// (Dashboard, Drives, Charges, Battery, Stats, etc.). Maps Tesla exterior
/// paint codes to light/dark surface + accent pairs. Ported from Android's
/// CarColorPalette.kt with identical hex values and HSL harmonization.
struct CarColorPalette: Equatable {
    let surface: Color
    let accent: Color
    let accentDim: Color          // accent @ 30% opacity
    let onSurface: Color
    let onSurfaceVariant: Color   // onSurface @ 70% opacity
    let progressTrack: Color      // onSurface @ 10% opacity
    let acColor: Color            // AC charging (~green, harmonized with accent)
    let dcColor: Color            // DC charging (~orange, harmonized with accent)
}

// MARK: - Environment Key

private struct CarColorPaletteKey: EnvironmentKey {
    static let defaultValue = CarColorPalettes.forExteriorColor(nil, isDark: false)
}

extension EnvironmentValues {
    var carPalette: CarColorPalette {
        get { self[CarColorPaletteKey.self] }
        set { self[CarColorPaletteKey.self] = newValue }
    }
}

// MARK: - HSL Helpers

private struct HSL {
    var h: Double  // 0…1
    var s: Double  // 0…1
    var l: Double  // 0…1
}

extension Color {
    fileprivate var redComponent: CGFloat {
        UIColor(self).cgColor.components?[0] ?? 0
    }
    fileprivate var greenComponent: CGFloat {
        let c = UIColor(self).cgColor.components
        return c?.count == 4 ? (c?[1] ?? 0) : (c?[0] ?? 0)
    }
    fileprivate var blueComponent: CGFloat {
        let c = UIColor(self).cgColor.components
        return c?.count == 4 ? (c?[2] ?? 0) : (c?[0] ?? 0)
    }

    fileprivate var hsl: HSL {
        let r = Double(redComponent)
        let g = Double(greenComponent)
        let b = Double(blueComponent)
        let mx = max(r, g, b), mn = min(r, g, b)
        let delta = mx - mn
        let l = (mx + mn) / 2

        guard delta > 0 else { return HSL(h: 0, s: 0, l: l) }

        let s = l < 0.5 ? delta / (mx + mn) : delta / (2 - mx - mn)
        let h: Double
        switch mx {
        case r: h = ((g - b) / delta + (g < b ? 6 : 0)) / 6
        case g: h = ((b - r) / delta + 2) / 6
        default: h = ((r - g) / delta + 4) / 6
        }
        return HSL(h: h, s: s, l: l)
    }

    fileprivate init(hsl: HSL, alpha: Double = 1) {
        let h = hsl.h.coordinateRemainder(1)
        let s = hsl.s.coordinateRemainder(1)
        let l = hsl.l

        if s == 0 { self = Color(white: l, opacity: alpha); return }

        let q = l < 0.5 ? l * (1 + s) : l + s - l * s
        let p = 2 * l - q

        func hue2rgb(_ p: Double, _ q: Double, _ t: Double) -> Double {
            var t = t
            if t < 0 { t += 1 }
            if t > 1 { t -= 1 }
            if t < 1.0/6 { return p + (q - p) * 6 * t }
            if t < 1.0/2 { return q }
            if t < 2.0/3 { return p + (q - p) * (2.0/3 - t) * 6 }
            return p
        }

        self = Color(
            red: hue2rgb(p, q, h + 1.0/3),
            green: hue2rgb(p, q, h),
            blue: hue2rgb(p, q, h - 1.0/3),
            opacity: alpha
        )
    }

    /// Blend hue of `self` toward `target` by `factor` (0 = no shift, 1 = full).
    fileprivate func harmonizedHue(toward target: Color, factor: Double) -> Color {
        let src = self.hsl
        let tgt = target.hsl
        let newH = (src.h + (tgt.h - src.h) * factor).coordinateRemainder(1)
        return Color(hsl: HSL(h: newH, s: src.s, l: src.l))
    }
}

// MARK: - AC / DC Harmonization

private func harmonizeAcColor(accent: Color, isDark: Bool) -> Color {
    let base = isDark
        ? Color(red: 0x66/255, green: 0xBB/255, blue: 0x6A/255)  // Green 400
        : Color(red: 0x4C/255, green: 0xAF/255, blue: 0x50/255)  // Green 500
    let blended = base.harmonizedHue(toward: accent, factor: 0.20)
    let hsl = blended.hsl
    let newS = isDark
        ? (hsl.s * 0.95).clamped(to: 0.4...1)
        : (hsl.s * 0.85).clamped(to: 0.3...0.9)
    let newL = isDark
        ? (hsl.l * 0.55).clamped(to: 0.3...0.7)
        : (hsl.l * 0.95).clamped(to: 0.3...0.7)
    return Color(hsl: HSL(h: hsl.h, s: newS, l: newL))
}

private func harmonizeDcColor(accent: Color, isDark: Bool) -> Color {
    let base = isDark
        ? Color(red: 0xFF/255, green: 0xA7/255, blue: 0x26/255)  // Orange 400
        : Color(red: 0xFF/255, green: 0x98/255, blue: 0x00/255)  // Orange 500
    let blended = base.harmonizedHue(toward: accent, factor: 0.10)
    let hsl = blended.hsl
    let newS = isDark
        ? (hsl.s * 1.05).clamped(to: 0.6...1)
        : (hsl.s * 0.95).clamped(to: 0.5...0.9)
    let newL = isDark
        ? (hsl.l * 0.55).clamped(to: 0.35...0.75)
        : (hsl.l * 0.95).clamped(to: 0.35...0.75)
    return Color(hsl: HSL(h: hsl.h, s: newS, l: newL))
}

private extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        min(max(self, range.lowerBound), range.upperBound)
    }
}

// MARK: - Palettes (9 Tesla paint codes + default)

enum CarColorPalettes {

    // MARK: Default

    private static let defaultLight = CarColorPalette(
        surface: Color(white: 0.96),
        accent: Color(red: 0x8B/255, green: 0x73/255, blue: 0x55/255),
        accentDim: Color(red: 0x8B/255, green: 0x73/255, blue: 0x55/255).opacity(0.3),
        onSurface: Color(red: 0x2A/255, green: 0x25/255, blue: 0x20/255),
        onSurfaceVariant: Color(red: 0x2A/255, green: 0x25/255, blue: 0x20/255).opacity(0.7),
        progressTrack: Color(red: 0x2A/255, green: 0x25/255, blue: 0x20/255).opacity(0.1),
        acColor: harmonizeAcColor(accent: Color(red: 0x8B/255, green: 0x73/255, blue: 0x55/255), isDark: false),
        dcColor: harmonizeDcColor(accent: Color(red: 0x8B/255, green: 0x73/255, blue: 0x55/255), isDark: false)
    )

    private static let defaultDark = CarColorPalette(
        surface: Color(red: 0x1E/255, green: 0x25/255, blue: 0x30/255),
        accent: Color(red: 0x8B/255, green: 0xAE/255, blue: 0xE8/255),
        accentDim: Color(red: 0x8B/255, green: 0xAE/255, blue: 0xE8/255).opacity(0.3),
        onSurface: Color(red: 0xE8/255, green: 0xEE/255, blue: 0xF8/255),
        onSurfaceVariant: Color(red: 0xE8/255, green: 0xEE/255, blue: 0xF8/255).opacity(0.7),
        progressTrack: Color(red: 0xE8/255, green: 0xEE/255, blue: 0xF8/255).opacity(0.1),
        acColor: harmonizeAcColor(accent: Color(red: 0x8B/255, green: 0xAE/255, blue: 0xE8/255), isDark: true),
        dcColor: harmonizeDcColor(accent: Color(red: 0x8B/255, green: 0xAE/255, blue: 0xE8/255), isDark: true)
    )

    // MARK: White (PPSW)

    private static let whiteLight = CarColorPalette(
        surface: Color(white: 0.96),
        accent: Color(red: 0x8B/255, green: 0x73/255, blue: 0x55/255),
        accentDim: Color(red: 0x8B/255, green: 0x73/255, blue: 0x55/255).opacity(0.3),
        onSurface: Color(red: 0x2A/255, green: 0x25/255, blue: 0x20/255),
        onSurfaceVariant: Color(red: 0x2A/255, green: 0x25/255, blue: 0x20/255).opacity(0.7),
        progressTrack: Color(red: 0x2A/255, green: 0x25/255, blue: 0x20/255).opacity(0.1),
        acColor: harmonizeAcColor(accent: Color(red: 0x8B/255, green: 0x73/255, blue: 0x55/255), isDark: false),
        dcColor: harmonizeDcColor(accent: Color(red: 0x8B/255, green: 0x73/255, blue: 0x55/255), isDark: false)
    )
    private static let whiteDark = CarColorPalette(
        surface: Color(red: 0x1E/255, green: 0x25/255, blue: 0x30/255),
        accent: Color(red: 0x8B/255, green: 0xAE/255, blue: 0xE8/255),
        accentDim: Color(red: 0x8B/255, green: 0xAE/255, blue: 0xE8/255).opacity(0.3),
        onSurface: Color(red: 0xE8/255, green: 0xEE/255, blue: 0xF8/255),
        onSurfaceVariant: Color(red: 0xE8/255, green: 0xEE/255, blue: 0xF8/255).opacity(0.7),
        progressTrack: Color(red: 0xE8/255, green: 0xEE/255, blue: 0xF8/255).opacity(0.1),
        acColor: harmonizeAcColor(accent: Color(red: 0x8B/255, green: 0xAE/255, blue: 0xE8/255), isDark: true),
        dcColor: harmonizeDcColor(accent: Color(red: 0x8B/255, green: 0xAE/255, blue: 0xE8/255), isDark: true)
    )

    // MARK: Black (PBSB / PMBL)

    private static let blackLight = CarColorPalette(
        surface: Color(red: 0xD8/255, green: 0xDA/255, blue: 0xDC/255),
        accent: Color(red: 0x50/255, green: 0x54/255, blue: 0x58/255),
        accentDim: Color(red: 0x50/255, green: 0x54/255, blue: 0x58/255).opacity(0.3),
        onSurface: Color(red: 0x1E/255, green: 0x20/255, blue: 0x22/255),
        onSurfaceVariant: Color(red: 0x1E/255, green: 0x20/255, blue: 0x22/255).opacity(0.7),
        progressTrack: Color(red: 0x1E/255, green: 0x20/255, blue: 0x22/255).opacity(0.1),
        acColor: harmonizeAcColor(accent: Color(red: 0x50/255, green: 0x54/255, blue: 0x58/255), isDark: false),
        dcColor: harmonizeDcColor(accent: Color(red: 0x50/255, green: 0x54/255, blue: 0x58/255), isDark: false)
    )
    private static let blackDark = CarColorPalette(
        surface: Color(red: 0x2A/255, green: 0x25/255, blue: 0x20/255),
        accent: Color(red: 0xC9/255, green: 0xA6/255, blue: 0x6B/255),
        accentDim: Color(red: 0xC9/255, green: 0xA6/255, blue: 0x6B/255).opacity(0.3),
        onSurface: Color(red: 0xF5/255, green: 0xF3/255, blue: 0xF0/255),
        onSurfaceVariant: Color(red: 0xF5/255, green: 0xF3/255, blue: 0xF0/255).opacity(0.7),
        progressTrack: Color(red: 0xF5/255, green: 0xF3/255, blue: 0xF0/255).opacity(0.1),
        acColor: harmonizeAcColor(accent: Color(red: 0xC9/255, green: 0xA6/255, blue: 0x6B/255), isDark: true),
        dcColor: harmonizeDcColor(accent: Color(red: 0xC9/255, green: 0xA6/255, blue: 0x6B/255), isDark: true)
    )

    // MARK: Midnight Silver (PMNG)

    private static let midnightSilverLight = CarColorPalette(
        surface: Color(red: 0xEC/255, green: 0xEE/255, blue: 0xF0/255),
        accent: Color(red: 0x6B/255, green: 0x7A/255, blue: 0x8C/255),
        accentDim: Color(red: 0x6B/255, green: 0x7A/255, blue: 0x8C/255).opacity(0.3),
        onSurface: Color(red: 0x22/255, green: 0x26/255, blue: 0x2B/255),
        onSurfaceVariant: Color(red: 0x22/255, green: 0x26/255, blue: 0x2B/255).opacity(0.7),
        progressTrack: Color(red: 0x22/255, green: 0x26/255, blue: 0x2B/255).opacity(0.1),
        acColor: harmonizeAcColor(accent: Color(red: 0x6B/255, green: 0x7A/255, blue: 0x8C/255), isDark: false),
        dcColor: harmonizeDcColor(accent: Color(red: 0x6B/255, green: 0x7A/255, blue: 0x8C/255), isDark: false)
    )
    private static let midnightSilverDark = CarColorPalette(
        surface: Color(red: 0x22/255, green: 0x26/255, blue: 0x2B/255),
        accent: Color(red: 0x8F/255, green: 0xA4/255, blue: 0xB8/255),
        accentDim: Color(red: 0x8F/255, green: 0xA4/255, blue: 0xB8/255).opacity(0.3),
        onSurface: Color(red: 0xEC/255, green: 0xEE/255, blue: 0xF0/255),
        onSurfaceVariant: Color(red: 0xEC/255, green: 0xEE/255, blue: 0xF0/255).opacity(0.7),
        progressTrack: Color(red: 0xEC/255, green: 0xEE/255, blue: 0xF0/255).opacity(0.1),
        acColor: harmonizeAcColor(accent: Color(red: 0x8F/255, green: 0xA4/255, blue: 0xB8/255), isDark: true),
        dcColor: harmonizeDcColor(accent: Color(red: 0x8F/255, green: 0xA4/255, blue: 0xB8/255), isDark: true)
    )

    // MARK: Deep Blue (PPSB)

    private static let deepBlueLight = CarColorPalette(
        surface: Color(red: 0xE5/255, green: 0xEB/255, blue: 0xF5/255),
        accent: Color(red: 0x3B/255, green: 0x59/255, blue: 0x98/255),
        accentDim: Color(red: 0x3B/255, green: 0x59/255, blue: 0x98/255).opacity(0.3),
        onSurface: Color(red: 0x1A/255, green: 0x22/255, blue: 0x35/255),
        onSurfaceVariant: Color(red: 0x1A/255, green: 0x22/255, blue: 0x35/255).opacity(0.7),
        progressTrack: Color(red: 0x1A/255, green: 0x22/255, blue: 0x35/255).opacity(0.1),
        acColor: harmonizeAcColor(accent: Color(red: 0x3B/255, green: 0x59/255, blue: 0x98/255), isDark: false),
        dcColor: harmonizeDcColor(accent: Color(red: 0x3B/255, green: 0x59/255, blue: 0x98/255), isDark: false)
    )
    private static let deepBlueDark = CarColorPalette(
        surface: Color(red: 0x1A/255, green: 0x22/255, blue: 0x35/255),
        accent: Color(red: 0x6B/255, green: 0x8B/255, blue: 0xC3/255),
        accentDim: Color(red: 0x6B/255, green: 0x8B/255, blue: 0xC3/255).opacity(0.3),
        onSurface: Color(red: 0xE5/255, green: 0xEB/255, blue: 0xF5/255),
        onSurfaceVariant: Color(red: 0xE5/255, green: 0xEB/255, blue: 0xF5/255).opacity(0.7),
        progressTrack: Color(red: 0xE5/255, green: 0xEB/255, blue: 0xF5/255).opacity(0.1),
        acColor: harmonizeAcColor(accent: Color(red: 0x6B/255, green: 0x8B/255, blue: 0xC3/255), isDark: true),
        dcColor: harmonizeDcColor(accent: Color(red: 0x6B/255, green: 0x8B/255, blue: 0xC3/255), isDark: true)
    )

    // MARK: Red Multi-Coat (PPMR)

    private static let redLight = CarColorPalette(
        surface: Color(red: 0xF8/255, green: 0xE8/255, blue: 0xE8/255),
        accent: Color(red: 0xC4/255, green: 0x50/255, blue: 0x50/255),
        accentDim: Color(red: 0xC4/255, green: 0x50/255, blue: 0x50/255).opacity(0.3),
        onSurface: Color(red: 0x2E/255, green: 0x1A/255, blue: 0x1A/255),
        onSurfaceVariant: Color(red: 0x2E/255, green: 0x1A/255, blue: 0x1A/255).opacity(0.7),
        progressTrack: Color(red: 0x2E/255, green: 0x1A/255, blue: 0x1A/255).opacity(0.1),
        acColor: harmonizeAcColor(accent: Color(red: 0xC4/255, green: 0x50/255, blue: 0x50/255), isDark: false),
        dcColor: harmonizeDcColor(accent: Color(red: 0xC4/255, green: 0x50/255, blue: 0x50/255), isDark: false)
    )
    private static let redDark = CarColorPalette(
        surface: Color(red: 0x2E/255, green: 0x1A/255, blue: 0x1A/255),
        accent: Color(red: 0xE0/255, green: 0x70/255, blue: 0x70/255),
        accentDim: Color(red: 0xE0/255, green: 0x70/255, blue: 0x70/255).opacity(0.3),
        onSurface: Color(red: 0xF8/255, green: 0xE8/255, blue: 0xE8/255),
        onSurfaceVariant: Color(red: 0xF8/255, green: 0xE8/255, blue: 0xE8/255).opacity(0.7),
        progressTrack: Color(red: 0xF8/255, green: 0xE8/255, blue: 0xE8/255).opacity(0.1),
        acColor: harmonizeAcColor(accent: Color(red: 0xE0/255, green: 0x70/255, blue: 0x70/255), isDark: true),
        dcColor: harmonizeDcColor(accent: Color(red: 0xE0/255, green: 0x70/255, blue: 0x70/255), isDark: true)
    )

    // MARK: Quicksilver (PN00)

    private static let quicksilverLight = CarColorPalette(
        surface: Color(red: 0xF0/255, green: 0xED/255, blue: 0xE8/255),
        accent: Color(red: 0xA0/255, green: 0x90/255, blue: 0x80/255),
        accentDim: Color(red: 0xA0/255, green: 0x90/255, blue: 0x80/255).opacity(0.3),
        onSurface: Color(red: 0x25/255, green: 0x23/255, blue: 0x20/255),
        onSurfaceVariant: Color(red: 0x25/255, green: 0x23/255, blue: 0x20/255).opacity(0.7),
        progressTrack: Color(red: 0x25/255, green: 0x23/255, blue: 0x20/255).opacity(0.1),
        acColor: harmonizeAcColor(accent: Color(red: 0xA0/255, green: 0x90/255, blue: 0x80/255), isDark: false),
        dcColor: harmonizeDcColor(accent: Color(red: 0xA0/255, green: 0x90/255, blue: 0x80/255), isDark: false)
    )
    private static let quicksilverDark = CarColorPalette(
        surface: Color(red: 0x25/255, green: 0x23/255, blue: 0x20/255),
        accent: Color(red: 0xB0/255, green: 0xA0/255, blue: 0x90/255),
        accentDim: Color(red: 0xB0/255, green: 0xA0/255, blue: 0x90/255).opacity(0.3),
        onSurface: Color(red: 0xF0/255, green: 0xED/255, blue: 0xE8/255),
        onSurfaceVariant: Color(red: 0xF0/255, green: 0xED/255, blue: 0xE8/255).opacity(0.7),
        progressTrack: Color(red: 0xF0/255, green: 0xED/255, blue: 0xE8/255).opacity(0.1),
        acColor: harmonizeAcColor(accent: Color(red: 0xB0/255, green: 0xA0/255, blue: 0x90/255), isDark: true),
        dcColor: harmonizeDcColor(accent: Color(red: 0xB0/255, green: 0xA0/255, blue: 0x90/255), isDark: true)
    )

    // MARK: Stealth Grey (PN01)

    private static let stealthGreyLight = CarColorPalette(
        surface: Color(red: 0xEC/255, green: 0xED/255, blue: 0xEE/255),
        accent: Color(red: 0x60/255, green: 0x65/255, blue: 0x70/255),
        accentDim: Color(red: 0x60/255, green: 0x65/255, blue: 0x70/255).opacity(0.3),
        onSurface: Color(red: 0x1E/255, green: 0x20/255, blue: 0x22/255),
        onSurfaceVariant: Color(red: 0x1E/255, green: 0x20/255, blue: 0x22/255).opacity(0.7),
        progressTrack: Color(red: 0x1E/255, green: 0x20/255, blue: 0x22/255).opacity(0.1),
        acColor: harmonizeAcColor(accent: Color(red: 0x60/255, green: 0x65/255, blue: 0x70/255), isDark: false),
        dcColor: harmonizeDcColor(accent: Color(red: 0x60/255, green: 0x65/255, blue: 0x70/255), isDark: false)
    )
    private static let stealthGreyDark = CarColorPalette(
        surface: Color(red: 0x1E/255, green: 0x20/255, blue: 0x22/255),
        accent: Color(red: 0x90/255, green: 0x95/255, blue: 0x98/255),
        accentDim: Color(red: 0x90/255, green: 0x95/255, blue: 0x98/255).opacity(0.3),
        onSurface: Color(red: 0xEC/255, green: 0xED/255, blue: 0xEE/255),
        onSurfaceVariant: Color(red: 0xEC/255, green: 0xED/255, blue: 0xEE/255).opacity(0.7),
        progressTrack: Color(red: 0xEC/255, green: 0xED/255, blue: 0xEE/255).opacity(0.1),
        acColor: harmonizeAcColor(accent: Color(red: 0x90/255, green: 0x95/255, blue: 0x98/255), isDark: true),
        dcColor: harmonizeDcColor(accent: Color(red: 0x90/255, green: 0x95/255, blue: 0x98/255), isDark: true)
    )

    // MARK: Ultra Red (PR01)

    private static let ultraRedLight = CarColorPalette(
        surface: Color(red: 0xFA/255, green: 0xEB/255, blue: 0xEB/255),
        accent: Color(red: 0xE0/255, green: 0x30/255, blue: 0x30/255),
        accentDim: Color(red: 0xE0/255, green: 0x30/255, blue: 0x30/255).opacity(0.3),
        onSurface: Color(red: 0x30/255, green: 0x18/255, blue: 0x18/255),
        onSurfaceVariant: Color(red: 0x30/255, green: 0x18/255, blue: 0x18/255).opacity(0.7),
        progressTrack: Color(red: 0x30/255, green: 0x18/255, blue: 0x18/255).opacity(0.1),
        acColor: harmonizeAcColor(accent: Color(red: 0xE0/255, green: 0x30/255, blue: 0x30/255), isDark: false),
        dcColor: harmonizeDcColor(accent: Color(red: 0xE0/255, green: 0x30/255, blue: 0x30/255), isDark: false)
    )
    private static let ultraRedDark = CarColorPalette(
        surface: Color(red: 0x30/255, green: 0x18/255, blue: 0x18/255),
        accent: Color(red: 0xFF/255, green: 0x50/255, blue: 0x50/255),
        accentDim: Color(red: 0xFF/255, green: 0x50/255, blue: 0x50/255).opacity(0.3),
        onSurface: Color(red: 0xFA/255, green: 0xEB/255, blue: 0xEB/255),
        onSurfaceVariant: Color(red: 0xFA/255, green: 0xEB/255, blue: 0xEB/255).opacity(0.7),
        progressTrack: Color(red: 0xFA/255, green: 0xEB/255, blue: 0xEB/255).opacity(0.1),
        acColor: harmonizeAcColor(accent: Color(red: 0xFF/255, green: 0x50/255, blue: 0x50/255), isDark: true),
        dcColor: harmonizeDcColor(accent: Color(red: 0xFF/255, green: 0x50/255, blue: 0x50/255), isDark: true)
    )

    // MARK: Midnight Cherry (PR00)

    private static let midnightCherryLight = CarColorPalette(
        surface: Color(red: 0xF5/255, green: 0xE5/255, blue: 0xE8/255),
        accent: Color(red: 0x8B/255, green: 0x30/255, blue: 0x40/255),
        accentDim: Color(red: 0x8B/255, green: 0x30/255, blue: 0x40/255).opacity(0.3),
        onSurface: Color(red: 0x25/255, green: 0x15/255, blue: 0x18/255),
        onSurfaceVariant: Color(red: 0x25/255, green: 0x15/255, blue: 0x18/255).opacity(0.7),
        progressTrack: Color(red: 0x25/255, green: 0x15/255, blue: 0x18/255).opacity(0.1),
        acColor: harmonizeAcColor(accent: Color(red: 0x8B/255, green: 0x30/255, blue: 0x40/255), isDark: false),
        dcColor: harmonizeDcColor(accent: Color(red: 0x8B/255, green: 0x30/255, blue: 0x40/255), isDark: false)
    )
    private static let midnightCherryDark = CarColorPalette(
        surface: Color(red: 0x25/255, green: 0x15/255, blue: 0x18/255),
        accent: Color(red: 0xC0/255, green: 0x50/255, blue: 0x68/255),
        accentDim: Color(red: 0xC0/255, green: 0x50/255, blue: 0x68/255).opacity(0.3),
        onSurface: Color(red: 0xF5/255, green: 0xE5/255, blue: 0xE8/255),
        onSurfaceVariant: Color(red: 0xF5/255, green: 0xE5/255, blue: 0xE8/255).opacity(0.7),
        progressTrack: Color(red: 0xF5/255, green: 0xE5/255, blue: 0xE8/255).opacity(0.1),
        acColor: harmonizeAcColor(accent: Color(red: 0xC0/255, green: 0x50/255, blue: 0x68/255), isDark: true),
        dcColor: harmonizeDcColor(accent: Color(red: 0xC0/255, green: 0x50/255, blue: 0x68/255), isDark: true)
    )

    // MARK: Public Lookup

    /// Map a Tesla exterior color name/code to the correct light/dark palette.
    /// Returns the default palette if the color is nil or unrecognized.
    static func forExteriorColor(_ exteriorColor: String?, isDark: Bool) -> CarColorPalette {
        let key = exteriorColor?.lowercased().replacingOccurrences(of: " ", with: "") ?? ""

        if key.contains("white") || key == "ppsw" {
            return isDark ? whiteDark : whiteLight
        }
        if key.contains("black") || key == "pbsb" || key == "pmbl" {
            return isDark ? blackDark : blackLight
        }
        if key.contains("midnightsilver") || key.contains("steelgrey") || key == "pmng" {
            return isDark ? midnightSilverDark : midnightSilverLight
        }
        if key.contains("silver") || key == "pmss" {
            return isDark ? midnightSilverDark : midnightSilverLight
        }
        if key.contains("deepblue") || key == "ppsb" {
            return isDark ? deepBlueDark : deepBlueLight
        }
        if key.contains("quicksilver") || key == "pn00" {
            return isDark ? quicksilverDark : quicksilverLight
        }
        if key.contains("stealthgrey") || key.contains("stealth") || key == "pn01" {
            return isDark ? stealthGreyDark : stealthGreyLight
        }
        if key.contains("midnightcherry") || key == "pr00" {
            return isDark ? midnightCherryDark : midnightCherryLight
        }
        if key.contains("ultrared") || key == "pr01" {
            return isDark ? ultraRedDark : ultraRedLight
        }
        if key.contains("red") || key == "ppmr" {
            return isDark ? redDark : redLight
        }
        return isDark ? defaultDark : defaultLight
    }
}
