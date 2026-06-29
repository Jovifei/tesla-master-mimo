import SwiftUI

@main
struct MateLinkApp: App {
    @StateObject private var state = AppState()

    var body: some Scene {
        WindowGroup {
            if state.onboardingDone {
                ContentView().environmentObject(state)
                    .preferredColorScheme(state.isDarkMode ? .dark : .light)
                    .tint(state.carAccent)
            } else {
                OnboardingView().environmentObject(state)
            }
        }
    }
}
