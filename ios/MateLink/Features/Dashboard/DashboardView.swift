import SwiftUI

struct DashboardView: View {
    @EnvironmentObject var state: AppState
    @State private var status: CarStatus?
    @State private var showCarSwitcher = false
    @State private var isRefreshing = false
    @State private var loadError: String?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    // Header
                    HStack {
                        Button(action: { showCarSwitcher.toggle() }) {
                            HStack(spacing: 6) {
                                Text(state.currentCar?.name ?? "Tesla")
                                    .font(.title2).bold().foregroundColor(.primary)
                                Image(systemName: "chevron.down").font(.caption).foregroundColor(.secondary)
                            }
                        }
                        Spacer()
                        if let s = status {
                            Text(StateColor.label(s.state)).font(.caption).fontWeight(.medium)
                                .padding(.horizontal, 10).padding(.vertical, 4)
                                .background(StateColor.forState(s.state)).foregroundColor(.white).clipShape(Capsule())
                        }
                    }.padding(.horizontal)

                    // Car image (F‑004)
                    CarImageView(color: state.carAccent, model: state.currentCar?.model ?? "")

                    // Location map with elevation
                    VStack(alignment: .leading, spacing: 4) {
                        Text("📍 Location").font(.caption).foregroundColor(.secondary)
                        if let s = status {
                            NavigationLink {
                                LocationDetailView(status: s)
                            } label: {
                                AmapView(latitude: s.latitude, longitude: s.longitude, title: "Current Location")
                                    .frame(height: 150)
                                    .clipShape(RoundedRectangle(cornerRadius: 8))
                            }
                            .buttonStyle(.plain)
                            Text("Elevation: \(Int(s.elevation))m").font(.caption2).foregroundColor(.secondary)
                        } else {
                            Text("Loading map...").font(.caption).foregroundColor(.secondary)
                                .frame(maxWidth: .infinity, minHeight: 150)
                        }
                    }
                    .padding()
                    .background(StitchColors.surface)
                    .stitchCard()
                    .padding(.horizontal)

                    // Battery + Range
                    if let s = status {
                        HStack(spacing: 12) {
                            NavigationLink {
                                BatteryHealthView()
                            } label: {
                                StatCard(title: "Battery", value: "\(Int(s.batteryLevel))%", subtitle: "\(s.usableBatteryRangeKm) km range", color: StitchColors.primary)
                            }
                            .buttonStyle(.plain)

                            NavigationLink {
                                MileageView()
                            } label: {
                                StatCard(title: "Odometer", value: "\(s.odometer.formatted()) km", subtitle: "Total mileage", color: .secondary)
                            }
                            .buttonStyle(.plain)
                        }.padding(.horizontal)

                        // High SOC Warning
                        if s.chargeLimitSoc > 90 {
                            HStack {
                                Image(systemName: "exclamationmark.triangle.fill").foregroundColor(StitchColors.warning)
                                Text("High charge level - consider reducing to 80-90% for daily use")
                                    .font(.caption).foregroundColor(StitchColors.warning)
                            }.padding(.horizontal)
                        }

                        // Charging card
                        if s.state == .charging {
                            NavigationLink {
                                CurrentChargeView()
                            } label: {
                                ChargingCard(status: s)
                            }
                            .buttonStyle(.plain)
                            .padding(.horizontal)
                        }

                        // Climate + Sentry + Lock + Plug
                        LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 4), spacing: 10) {
                            MiniCard(icon: "thermometer.medium", label: "Inside", value: "\(Int(s.insideTemp))°C")
                            MiniCard(icon: "sun.max", label: "Outside", value: "\(Int(s.outsideTemp))°C")
                            MiniCard(icon: "lock.fill", label: "Lock", value: s.locked ? "Locked" : "Unlocked", active: s.locked)
                            MiniCard(icon: "bolt.fill", label: "Plug", value: s.pluggedIn ? "Plugged" : "Unplugged", active: s.pluggedIn)
                        }.padding(.horizontal)

                        // Tire pressure
                        LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 4), spacing: 10) {
                            MiniCard(icon: "circle.circle", label: "FL", value: "\(String(format:"%.1f", s.tirePressureFrontLeft)) bar")
                            MiniCard(icon: "circle.circle", label: "FR", value: "\(String(format:"%.1f", s.tirePressureFrontRight)) bar")
                            MiniCard(icon: "circle.circle", label: "RL", value: "\(String(format:"%.1f", s.tirePressureRearLeft)) bar")
                            MiniCard(icon: "circle.circle", label: "RR", value: "\(String(format:"%.1f", s.tirePressureRearRight)) bar")
                        }.padding(.horizontal)

                        // 7-Day Battery Trend
                        NavigationLink {
                            StatisticsView()
                        } label: {
                            BatteryTrendCard()
                        }
                        .buttonStyle(.plain)
                    } else if let loadError {
                        EmptyStateView(
                            "Dashboard Unavailable",
                            systemImage: "exclamationmark.triangle",
                            message: loadError
                        )
                        .padding(40)
                    } else {
                        ProgressView("Loading...").padding(40)
                    }
                }.padding(.vertical)
            }
            .navigationBarHidden(true)
            .refreshable { await refresh() }
            .onReceive(Timer.publish(every: 5, on: .main, in: .common).autoconnect()) { _ in
                Task { await refresh() }
            }
            .task { await refresh() }
            .sheet(isPresented: $showCarSwitcher) { CarSwitcherView() }
        }
    }

    func refresh() async {
        guard !isRefreshing else { return }
        isRefreshing = true
        defer { isRefreshing = false }
        loadError = nil
        if state.isMockMode {
            status = await state.mock.mockStatus(state.currentCarId)
        } else if let api = state.real {
            do {
                status = try await api.fetch("/api/v1/cars/\(state.currentCarId)/status")
            } catch {
                status = nil
                loadError = "Unable to load real vehicle status: \(error.localizedDescription)"
            }
        } else {
            status = nil
            loadError = "No TeslaMate instance is configured."
        }
        // Write widget data to AppGroup UserDefaults
        if let s = status, let defaults = UserDefaults(suiteName: "group.com.matelink") {
            defaults.set(Int(s.batteryLevel), forKey: "widget_battery")
            defaults.set(Int(s.usableBatteryRangeKm), forKey: "widget_range")
            defaults.set(s.state.rawValue, forKey: "widget_state")
        }
    }

    // MARK: - Location Map Card (TG-05)
    private var locationMapCard: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("📍 Location").font(.caption).foregroundColor(.secondary)
            if let s = status {
                AmapView(latitude: s.latitude, longitude: s.longitude, title: "Current Location")
                    .frame(height: 150)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
            } else {
                Text("Loading map...").font(.caption).foregroundColor(.secondary)
                    .frame(maxWidth: .infinity, minHeight: 150)
            }
        }
        .padding()
        .background(StitchColors.surface)
        .stitchCard()
    }
}

struct StatCard: View {
    let title: String; let value: String; let subtitle: String; let color: Color
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.caption).foregroundColor(.secondary)
            Text(value).font(.largeTitle).bold().foregroundColor(.primary).monospacedDigit()
            Text(subtitle).font(.caption2).foregroundColor(.secondary)
        }.frame(maxWidth: .infinity, alignment: .leading).padding().background(StitchColors.surface).stitchCard()
    }
}

struct MiniCard: View {
    let icon: String; let label: String; let value: String; var active: Bool = false
    var body: some View {
        VStack(spacing: 6) {
            Image(systemName: icon).font(.title3).foregroundColor(active ? StitchColors.online : .secondary)
            Text(label).font(.caption2).foregroundColor(.secondary)
            Text(value).font(.caption).bold().monospacedDigit()
        }.frame(maxWidth: .infinity).padding(.vertical, 12).background(StitchColors.surface).stitchCard()
            .overlay(active ? RoundedRectangle(cornerRadius: 8).stroke(StitchColors.online.opacity(0.4), lineWidth: 1) : nil)
    }
}

struct ChargingCard: View {
    let status: CarStatus
    var body: some View {
        VStack(spacing: 10) {
            Label("Charging in Progress", systemImage: "bolt.fill").font(.headline).foregroundColor(StitchColors.warning)
            HStack {
                VStack { Text("Power").font(.caption2).foregroundColor(.secondary); Text("\(String(format:"%.1f",status.chargerPower)) kW").font(.title3).bold().monospacedDigit() }
                Spacer()
                VStack { Text("Added").font(.caption2).foregroundColor(.secondary); Text("\(String(format:"%.1f",status.chargeEnergyAdded)) kWh").font(.title3).bold().monospacedDigit() }
                Spacer()
                VStack { Text("Remaining").font(.caption2).foregroundColor(.secondary); Text("\(Int(status.timeToFullCharge*60)) min").font(.title3).bold().monospacedDigit() }
            }
        }.padding().background(StitchColors.warning.opacity(0.1)).stitchCard()
    }
}

struct CarImageView: View { // F‑004
    let color: Color; let model: String
    var body: some View {
        VStack(spacing: 4) {
            Image(systemName: "car.fill")
                .font(.system(size: 64)).foregroundColor(color)
                .frame(height: 100)
            Text(model).font(.caption).foregroundColor(.secondary)
        }.frame(maxWidth: .infinity).padding(.vertical, 8)
    }
}

// MARK: - Demo Data (hardcoded battery trend for UI preview)
struct BatteryTrendCard: View {
    let data: [Int] = [75, 72, 68, 70, 73, 76, 78] // Demo data — not live
    let labels: [String] = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("7-Day Battery Trend").font(.caption).foregroundColor(.secondary)
                Spacer()
                Text("Demo").font(.system(size: 8, weight: .semibold)).foregroundColor(StitchColors.warning)
                    .padding(.horizontal, 6).padding(.vertical, 2)
                    .background(StitchColors.warning.opacity(0.1)).clipShape(Capsule())
            }
            HStack(alignment: .bottom, spacing: 8) {
                ForEach(Array(data.enumerated()), id: \.offset) { index, value in
                    VStack(spacing: 4) {
                        Rectangle()
                            .fill(StitchColors.accent.opacity(0.6))
                            .frame(width: 24, height: CGFloat(value - 60) * 2)
                            .cornerRadius(4)
                        Text(labels[index]).font(.system(size: 8)).foregroundColor(.secondary)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .center)
        }
        .padding()
        .background(StitchColors.surface)
        .stitchCard()
        .padding(.horizontal)
    }
}

struct CarSwitcherView: View {
    @EnvironmentObject var state: AppState; @Environment(\.dismiss) var dismiss
    var body: some View {
        NavigationStack {
            List(state.cars) { car in
                Button(action: { state.currentCarId = car.id; dismiss() }) {
                    HStack {
                        VStack(alignment: .leading) { Text(car.name).font(.headline); Text("\(car.model) · \(car.totalDrives) drives").font(.caption).foregroundColor(.secondary) }
                        Spacer()
                        if car.id == state.currentCarId { Image(systemName: "checkmark").foregroundColor(StitchColors.primary) }
                    }
                }
            }.navigationTitle("Select Vehicle").navigationBarTitleDisplayMode(.inline)
        }
    }
}

extension View {
    func stitchCard() -> some View {
        self
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(StitchColors.outline, lineWidth: 1))
    }
}
