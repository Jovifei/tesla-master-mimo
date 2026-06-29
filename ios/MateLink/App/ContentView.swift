import SwiftUI

struct ContentView: View {
    @EnvironmentObject var state: AppState

    var body: some View {
        TabView(selection: $state.selectedTab) {
            DashboardView().tabItem { Label(AppState.Tab.dashboard.label, systemImage: AppState.Tab.dashboard.icon) }.tag(AppState.Tab.dashboard)
            DriveListView().tabItem { Label(AppState.Tab.drives.label, systemImage: AppState.Tab.drives.icon) }.tag(AppState.Tab.drives)
            ChargeListView().tabItem { Label(AppState.Tab.charges.label, systemImage: AppState.Tab.charges.icon) }.tag(AppState.Tab.charges)
            MoreView().tabItem { Label(AppState.Tab.more.label, systemImage: AppState.Tab.more.icon) }.tag(AppState.Tab.more)
        }
    }
}
