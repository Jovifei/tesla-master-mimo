import SwiftUI

struct ContentView: View {
    @EnvironmentObject var state: AppState

    var body: some View {
        TabView(selection: $state.selectedTab) {
            NavigationStack {
                DashboardView()
                    .navigationDestination(for: Route.self) { route in
                        RouteDestinationView(route: route)
                    }
            }
            .tabItem { Label(AppState.Tab.dashboard.label, systemImage: AppState.Tab.dashboard.icon) }
            .tag(AppState.Tab.dashboard)

            NavigationStack {
                DriveListView()
                    .navigationDestination(for: Route.self) { route in
                        RouteDestinationView(route: route)
                    }
            }
            .tabItem { Label(AppState.Tab.drives.label, systemImage: AppState.Tab.drives.icon) }
            .tag(AppState.Tab.drives)

            NavigationStack {
                ChargeListView()
                    .navigationDestination(for: Route.self) { route in
                        RouteDestinationView(route: route)
                    }
            }
            .tabItem { Label(AppState.Tab.charges.label, systemImage: AppState.Tab.charges.icon) }
            .tag(AppState.Tab.charges)

            NavigationStack {
                MoreView()
                    .navigationDestination(for: Route.self) { route in
                        RouteDestinationView(route: route)
                    }
            }
            .tabItem { Label(AppState.Tab.more.label, systemImage: AppState.Tab.more.icon) }
            .tag(AppState.Tab.more)
        }
        .environment(\.carPalette, state.carPalette)
    }
}
