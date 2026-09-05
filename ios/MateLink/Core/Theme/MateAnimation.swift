import SwiftUI

/// Centralized animation specifications derived from Apple's
/// "Designing Fluid Interfaces" (WWDC 2018) and the apple-design skill.
///
/// Principles:
/// - Every animation must be interruptible and redirectable at any moment.
/// - Always animate from the *current* value, never the target value.
/// - Use springs for anything a user can touch.
/// - Default to critically damped (no overshoot); bounce only for momentum.
/// - Respect `@Environment(\.accessibilityReduceMotion)` — cross-fade instead of slide.
enum MateAnimation {

    // MARK: - Spring Parameters

    /// Default UI spring — critically damped, no overshoot.
    /// Use for: navigation pushes/pops, panel reveals, card expansions,
    /// any non-momentum-driven transition.
    static let defaultSpring = Animation.spring(
        response: 0.35,
        dampingFraction: 1.0,
        blendDuration: 0
    )

    /// Momentum / flick spring — slight bounce.
    /// Use for: flick-to-dismiss, drag-release, carousel snapping,
    /// anything where the user's gesture carried velocity.
    static let momentumSpring = Animation.spring(
        response: 0.35,
        dampingFraction: 0.8,
        blendDuration: 0
    )

    /// Snappy micro-interaction — fast, no overshoot.
    /// Use for: button press feedback, toggle flip, chip selection.
    static let snappy = Animation.spring(
        response: 0.2,
        dampingFraction: 1.0,
        blendDuration: 0
    )

    /// Slow settle — gentle, no overshoot.
    /// Use for: large sheet presentation, complex layout transitions.
    static let gentle = Animation.spring(
        response: 0.5,
        dampingFraction: 1.0,
        blendDuration: 0
    )

    // MARK: - Value Animations

    /// Linear duration-based — for things that genuinely need fixed timing.
    static let fadeIn = Animation.easeIn(duration: 0.2)
    static let fadeOut = Animation.easeOut(duration: 0.15)
    static let crossFade = Animation.easeInOut(duration: 0.25)

    // MARK: - View Modifiers

    /// Apply the default spring to any animatable value change.
    static func defaultSpring<V: Equatable>(for keyPath: KeyPath<WritableKeyPath<V, Bool>, Bool>) -> Animation {
        defaultSpring
    }

    /// Button press feedback — scale down on press, spring back on release.
    /// Matches apple-design skill: "highlight on touch-down, commit on touch-up".
    struct Pressable: ViewModifier {
        @State private var isPressed = false

        func body(content: Content) -> some View {
            content
                .scaleEffect(isPressed ? 0.97 : 1.0)
                .opacity(isPressed ? 0.85 : 1.0)
                .animation(isPressed ? snappy : defaultSpring, value: isPressed)
                .onLongPressGesture(minimumDuration: 0, pressing: { pressing in
                    isPressed = pressing
                }, perform: {})
        }
    }

    /// Number transition — matches Android's 220ms AnimatedContent.
    struct NumberTransition: ViewModifier {
        func body(content: Content) -> some View {
            if #available(iOS 17.0, *) {
                content
                    .contentTransition(.numericText())
                    .animation(MateAnimation.defaultSpring, value: UUID())
            } else {
                content
                    .animation(MateAnimation.defaultSpring, value: UUID())
            }
        }
    }

    /// Sheet presentation with spring — matches apple-design's drawer spec
    /// (damping 0.8, response 0.3).
    static let sheetSpring = Animation.spring(
        response: 0.3,
        dampingFraction: 0.8,
        blendDuration: 0
    )

    /// Refresh icon rotation — matches Android's PearlDriveMotion 450ms.
    static let refreshRotate = Animation.linear(duration: 0.45)
}

// MARK: - View Extensions

extension View {
    /// Apply button press feedback (scale + opacity on touch).
    func matePressable() -> some View {
        modifier(MateAnimation.Pressable())
    }

    /// Apply reduce-motion fallback — cross-fade instead of spring.
    /// Call this on views that use slide/spring transitions.
    func mateReducedMotionFallback() -> some View {
        modifier(ReducedMotionModifier())
    }
}

// MARK: - Reduced Motion

/// Per apple-design skill §14: "Reduced motion doesn't mean no feedback —
/// it means a gentler, non-vestibular equivalent."
private struct ReducedMotionModifier: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content
            .animation(reduceMotion ? .easeInOut(duration: 0.2) : nil, value: UUID())
    }
}
