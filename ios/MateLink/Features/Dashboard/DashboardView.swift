import SwiftUI

struct DashboardView: View {
    @EnvironmentObject var state: AppState
    @State private var status: CarStatus?
    @State private var showCarSwitcher = false
    @State private var isRefreshing = false
    let timer = Timer.publish(every: 5, on: .main, in: .common).autoconnect()

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
                            AmapView(latitude: s.latitude, longitude: s.longitude, title: "Current Location")
                                .frame(height: 150)
                                .clipShape(RoundedRectangle(cornerRadius: 12))
                            Text("Elevation: \(Int(s.elevation))m").font(.caption2).foregroundColor(.secondary)
                        } else {
                            Text("Loading map...").font(.caption).foregroundColor(.secondary)
                                .frame(maxWidth: .infinity, minHeight: 150)
                        }
                    }
                    .padding()
                    .background(.regularMaterial)
                    .clipShape(RoundedRectangle(cornerRadius: 16))
                    .padding(.horizontal)

                    // Battery + Range
                    if let s = status {
                        HStack(spacing: 12) {
                            StatCard(title: "Battery", value: "\(Int(s.batteryLevel))%", subtitle: "\(s.usableBatteryRangeKm) km range", color: .blue)
                            StatCard(title: "Odometer", value: "\(s.odometer.formatted()) km", subtitle: "Total mileage", color: .secondary)
                        }.padding(.horizontal)

                        // High SOC Warning
                        if s.chargeLimitSoc > 90 {
                            HStack {
                                Image(systemName: "exclamationmark.triangle.fill").foregroundColor(.orange)
                                Text("High charge level - consider reducing to 80-90% for daily use")
                                    .font(.caption).foregroundColor(.orange)
                            }.padding(.horizontal)
                        }

                        // Charging card
                        if s.state == .charging {
                            ChargingCard(status: s).padding(.horizontal)
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
                        BatteryTrendCard()
                    } else {
                        ProgressView("Loading...").padding(40)
                    }
                }.padding(.vertical)
            }
            .navigationBarHidden(true)
            .refreshable { await refresh() }
            .onReceive(timer) { _ in Task { await refresh() } }
            .task { await refresh() }
            .sheet(isPresented: $showCarSwitcher) { CarSwitcherView() }
        }
    }

    func refresh() async {
        guard !isRefreshing else { return }
        isRefreshing = true
        defer { isRefreshing = false }
        if state.isMockMode {
            status = await state.mock.mockStatus(state.currentCarId)
        } else if let api = state.real {
            status = try? await api.fetch("/api/v1/cars/\(state.currentCarId)/status")
        }
        // Write widget data to AppGroup UserDefaults
        if let s = status, let defaults = UserDefaults(suiteName: "group.com.matelink") {
            defaults.set(Int(s.batteryLevel), forKey: "widget_battery")
            defaults.set(s.usableBatteryRangeKm, forKey: "widget_range")
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
                    .clipShape(RoundedRectangle(cornerRadius: 12))
            } else {
                Text("Loading map...").font(.caption).foregroundColor(.secondary)
                    .frame(maxWidth: .infinity, minHeight: 150)
            }
        }
        .padding()
        .background(.regularMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }
}

struct StatCard: View {
    let title: String; let value: String; let subtitle: String; let color: Color
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.caption).foregroundColor(.secondary)
            Text(value).font(.largeTitle).bold().foregroundColor(.primary)
            Text(subtitle).font(.caption2).foregroundColor(.secondary)
        }.frame(maxWidth: .infinity, alignment: .leading).padding().background(.regularMaterial).clipShape(RoundedRectangle(cornerRadius: 16))
    }
}

struct MiniCard: View {
    let icon: String; let label: String; let value: String; var active: Bool = false
    var body: some View {
        VStack(spacing: 6) {
            Image(systemName: icon).font(.title3).foregroundColor(active ? .green : .secondary)
            Text(label).font(.caption2).foregroundColor(.secondary)
            Text(value).font(.caption).bold()
        }.frame(maxWidth: .infinity).padding(.vertical, 12).background(.regularMaterial).clipShape(RoundedRectangle(cornerRadius: 12))
            .overlay(active ? RoundedRectangle(cornerRadius: 12).stroke(Color.green.opacity(0.4), lineWidth: 1) : nil)
    }
}

struct ChargingCard: View {
    let status: CarStatus
    var body: some View {
        VStack(spacing: 10) {
            Label("Charging in Progress", systemImage: "bolt.fill").font(.headline).foregroundColor(.orange)
            HStack {
                VStack { Text("Power").font(.caption2).foregroundColor(.secondary); Text("\(String(format:"%.1f",status.chargerPower)) kW").font(.title3).bold() }
                Spacer()
                VStack { Text("Added").font(.caption2).foregroundColor(.secondary); Text("\(String(format:"%.1f",status.chargeEnergyAdded)) kWh").font(.title3).bold() }
                Spacer()
                VStack { Text("Remaining").font(.caption2).foregroundColor(.secondary); Text("\(Int(status.timeToFullCharge*60)) min").font(.title3).bold() }
            }
        }.padding().background(.orange.opacity(0.1)).clipShape(RoundedRectangle(cornerRadius: 16))
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

struct BatteryTrendCard: View {
    let data: [Int] = [75, 72, 68, 70, 73, 76, 78]
    let labels: [String] = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("7-Day Battery Trend").font(.caption).foregroundColor(.secondary)
            HStack(alignment: .bottom, spacing: 8) {
                ForEach(Array(data.enumerated()), id: \.offset) { index, value in
                    VStack(spacing: 4) {
                        Rectangle()
                            .fill(Color.blue.opacity(0.6))
                            .frame(width: 24, height: CGFloat(value - 60) * 2)
                            .cornerRadius(4)
                        Text(labels[index]).font(.system(size: 8)).foregroundColor(.secondary)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .center)
        }
        .padding()
        .background(.regularMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 16))
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
                        if car.id == state.currentCarId { Image(systemName: "checkmark").foregroundColor(.blue) }
                    }
                }
            }.navigationTitle("Select Vehicle").navigationBarTitleDisplayMode(.inline)
        }
    }
}
