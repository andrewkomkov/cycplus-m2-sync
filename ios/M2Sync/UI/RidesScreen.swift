import SwiftUI

/// Главный экран по Human Interface Guidelines: системный список с большим заголовком,
/// действия в тулбаре, «потянуть вниз» — синхронизировать.
struct RidesScreen: View {
    @StateObject private var sync = SyncController()
    @State private var path: [RideSummary] = []
    @State private var showingLog = false
    @State private var showingProfile = false
    @State private var editMode: EditMode = .inactive
    @State private var selection = Set<String>()
    @AppStorage("sync-on-launch") private var syncOnLaunch = true

    var body: some View {
        NavigationStack(path: $path) {
            content
                .navigationTitle("Rides")
                .toolbar { toolbar }
                .navigationDestination(isPresented: $showingLog) {
                    LogScreen(lines: sync.log)
                }
                .navigationDestination(for: RideSummary.self) { ride in
                    RideDetailScreen(summary: ride, inHealth: sync.imported.contains(ride.fileName))
                }
                .sheet(isPresented: $showingProfile) {
                    ProfileScreen(sync: sync)
                }
                .task {
                    await sync.reload()
                    #if DEBUG
                    // Для скриншотов из симулятора: SIMCTL_CHILD_M2SYNC_OPEN_RIDE=<файл> сразу открывает поездку.
                    if let name = ProcessInfo.processInfo.environment["M2SYNC_OPEN_RIDE"],
                       let ride = sync.rides.first(where: { $0.fileName == name }) {
                        path = [ride]
                    }
                    #endif
                    // Как на Android: открыл приложение — оно само забрало новые поездки.
                    if path.isEmpty, SyncController.syncsOnLaunch(enabled: syncOnLaunch, device: sync.device) {
                        await sync.sync()
                    }
                }
        }
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItem(placement: .topBarLeading) {
            Menu {
                Button {
                    editMode = .active
                } label: {
                    Label("Select Rides", systemImage: "checkmark.circle")
                }
                .disabled(sync.rides.isEmpty)
                Toggle(isOn: $syncOnLaunch) {
                    Label("Sync on Launch", systemImage: "bolt.horizontal.circle")
                }
                Button {
                    showingProfile = true
                } label: {
                    Label("Profile for Calories", systemImage: "person.crop.circle")
                }
                Button {
                    showingLog = true
                } label: {
                    Label("Log", systemImage: "list.bullet.rectangle")
                }
            } label: {
                Label("More", systemImage: "ellipsis.circle")
            }
        }

        ToolbarItem(placement: .topBarTrailing) {
            if editMode.isEditing {
                Button("Done") { finishSelecting() }
            } else if sync.busy {
                ProgressView()
            } else {
                Button {
                    Task { await sync.sync() }
                } label: {
                    Label("Sync", systemImage: "arrow.triangle.2.circlepath")
                }
            }
        }

        if editMode.isEditing {
            ToolbarItemGroup(placement: .bottomBar) {
                Button {
                    selection = allSelected ? [] : Set(sync.rides.map(\.fileName))
                } label: {
                    allSelected ? Text("Deselect All") : Text("Select All")
                }
                Spacer()
                Text("\(selection.count) selected")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Spacer()
                ShareLink(
                    items: selectedExports,
                    preview: { SharePreview($0.fileName) },
                    label: { Label("Share", systemImage: "square.and.arrow.up") }
                )
                .disabled(selectedExports.isEmpty)
            }
        }
    }

    @ViewBuilder
    private var content: some View {
        if sync.rides.isEmpty, sync.device == nil, !sync.busy, !sync.loading {
            ContentUnavailableView {
                Label("No Rides Yet", systemImage: "bicycle")
            } description: {
                Text("Switch on the bike computer and sync to download your rides.")
            } actions: {
                Button("Sync") {
                    Task { await sync.sync() }
                }
                .buttonStyle(.borderedProminent)
            }
        } else {
            List(selection: $selection) {
                if sync.busy {
                    SyncStatusSection(progress: sync.progress)
                }

                if let device = sync.device {
                    DeviceSection(device: device)
                }

                if !sync.rides.isEmpty {
                    Section {
                        TotalsRow(rides: sync.rides, imported: sync.imported)
                    }
                }

                Section("Rides") {
                    if sync.rides.isEmpty {
                        if sync.loading {
                            HStack(spacing: 10) {
                                ProgressView()
                                Text("Reading rides…")
                                    .foregroundStyle(.secondary)
                            }
                        } else {
                            Text("No rides on this iPhone yet.")
                                .foregroundStyle(.secondary)
                        }
                    }
                    ForEach(sync.rides) { ride in
                        NavigationLink(value: ride) {
                            RideRow(ride: ride, inHealth: sync.imported.contains(ride.fileName))
                        }
                        .contextMenu {
                            if let export = export(ride) {
                                ShareLink(item: export, preview: SharePreview(export.fileName))
                            }
                        }
                    }
                }
            }
            .listStyle(.insetGrouped)
            .environment(\.editMode, $editMode)
            .refreshable { await sync.sync() }
        }
    }

    private var allSelected: Bool {
        !sync.rides.isEmpty && selection.count == sync.rides.count
    }

    private var selectedExports: [RideExport] {
        guard let files = try? RideFiles.standard() else { return [] }
        return sync.rides
            .filter { selection.contains($0.fileName) }
            .compactMap { try? RideExport(summary: $0, files: files) }
    }

    private func export(_ ride: RideSummary) -> RideExport? {
        try? RideExport(summary: ride, files: RideFiles.standard())
    }

    private func finishSelecting() {
        editMode = .inactive
        selection = []
    }
}

struct SyncStatusSection: View {
    let progress: SyncProgress?

    var body: some View {
        Section {
            if let progress {
                VStack(alignment: .leading, spacing: 8) {
                    LabeledContent {
                        Text("\(progress.index) of \(progress.count)")
                            .monospacedDigit()
                    } label: {
                        switch progress.phase {
                        case .download:
                            Label("Downloading", systemImage: "arrow.down.circle")
                        case .health:
                            Label("Saving to Health", systemImage: "heart.text.square")
                        }
                    }
                    ProgressView(value: progress.fraction)
                    Text(verbatim: progress.fileName)
                        .font(.caption.monospaced())
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 4)
            } else {
                HStack(spacing: 10) {
                    ProgressView()
                    Text("Connecting to the bike computer…")
                        .foregroundStyle(.secondary)
                }
            }
        }
    }
}

struct DeviceSection: View {
    let device: DeviceSnapshot

    var body: some View {
        Section("Bike Computer") {
            HStack(spacing: 14) {
                if let battery = device.battery {
                    Gauge(value: Double(battery), in: 0...100) {
                        Text("Battery")
                    } currentValueLabel: {
                        Text("\(battery)")
                    }
                    .gaugeStyle(.accessoryCircularCapacity)
                    .tint(battery > 20 ? Color.green : Color.red)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: "Cycplus M2")
                        .font(.headline)
                    Text(verbatim: device.name)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.vertical, 4)

            if let firmware = device.firmware {
                LabeledContent("Firmware", value: firmware)
            }
            if let free = device.freeKB, let total = device.totalKB, total > 0 {
                LabeledContent("Memory") {
                    Text("\(kilobytes(total - free)) of \(kilobytes(total))")
                }
            }
            LabeledContent("Last Seen") {
                Text(verbatim: device.seenAt.formatted(.relative(presentation: .named)))
            }
        }
    }

    private func kilobytes(_ value: Int) -> String {
        Int64(value * 1024).formatted(.byteCount(style: .memory))
    }
}

struct TotalsRow: View {
    let rides: [RideSummary]
    let imported: Set<String>

    var body: some View {
        let kilometres = rides.reduce(0) { $0 + $1.distanceMeters } / 1000
        let hours = Double(rides.reduce(0) { $0 + $1.movingMinutes }) / 60
        let inHealth = rides.filter { imported.contains($0.fileName) }.count
        HStack {
            Stat(value: rides.count.formatted(), label: "rides")
            Divider()
            Stat(value: kilometres.formatted(.number.precision(.fractionLength(0))), label: "km")
            Divider()
            Stat(value: hours.formatted(.number.precision(.fractionLength(1))), label: "hours")
            Divider()
            Stat(value: inHealth.formatted(), label: "in Health")
        }
        .padding(.vertical, 6)
    }

    private struct Stat: View {
        let value: String
        let label: LocalizedStringKey

        var body: some View {
            VStack(spacing: 2) {
                Text(verbatim: value)
                    .font(.title2.weight(.semibold))
                    .monospacedDigit()
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                Text(label)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            .frame(maxWidth: .infinity)
        }
    }
}

struct RideRow: View {
    let ride: RideSummary
    let inHealth: Bool

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "figure.outdoor.cycle")
                .font(.title3)
                .foregroundStyle(.green)
                .frame(width: 40, height: 40)
                .background(Color.green.opacity(0.15), in: Circle())

            VStack(alignment: .leading, spacing: 4) {
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text("\(ride.distanceMeters.kilometres) km")
                        .font(.title3.weight(.semibold))
                        .monospacedDigit()
                    if inHealth {
                        Image(systemName: "heart.fill")
                            .font(.caption)
                            .foregroundStyle(.pink)
                            .accessibilityLabel(Text("In Health"))
                    }
                    Spacer(minLength: 8)
                    Text(verbatim: ride.start.formatted(.dateTime.day().month(.abbreviated).hour().minute()))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                FlowLayout {
                    Metric(icon: "stopwatch", text: Text(verbatim: movingTime))
                    if let heartRate = ride.avgHeartRate {
                        Metric(icon: "heart", text: Text("\(heartRate) bpm"))
                    }
                    if let cadence = ride.avgCadence {
                        Metric(icon: "arrow.clockwise", text: Text("\(cadence) rpm"))
                    }
                    if let ascent = ride.ascent, ascent > 0 {
                        Metric(icon: "arrow.up.right", text: Text("\(ascent) m"))
                    }
                    if let kilocalories = ride.activeKilocalories {
                        Metric(icon: "flame", text: Text("\(kilocalories) kcal"))
                    }
                }
                .font(.subheadline)
                .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }

    private var movingTime: String {
        Duration.seconds(ride.movingMinutes * 60)
            .formatted(.units(allowed: [.hours, .minutes], width: .abbreviated))
    }

    private struct Metric: View {
        let icon: String
        let text: Text

        var body: some View {
            HStack(spacing: 4) {
                Image(systemName: icon)
                    .imageScale(.small)
                text
            }
        }
    }
}

struct LogScreen: View {
    let lines: [String]

    var body: some View {
        List {
            if lines.isEmpty {
                Text("Empty")
                    .foregroundStyle(.secondary)
            }
            ForEach(Array(lines.enumerated()), id: \.offset) { entry in
                Text(verbatim: entry.element)
                    .font(.footnote.monospaced())
            }
        }
        .textSelection(.enabled)
        .navigationTitle("Log")
        .navigationBarTitleDisplayMode(.inline)
    }
}
