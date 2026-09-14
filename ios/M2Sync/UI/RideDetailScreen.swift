import Charts
import MapKit
import SwiftUI

/// Поездка крупным планом: маршрут на карте, итоги и графики. Протяжка по графику ведёт точку
/// по маршруту. Порт RideDetail.kt, но на системных компонентах: MapKit и Swift Charts.
struct RideDetailScreen: View {
    let summary: RideSummary
    let inHealth: Bool

    @State private var track: RideTrack?
    @State private var failed = false
    @State private var metric: RideTrack.Metric = .elevation
    @State private var selectedKm: Double?
    @State private var satellite = false

    var body: some View {
        List {
            if let track {
                if track.coordinates.count > 1 {
                    Section {
                        RouteMap(track: track, highlighted: highlighted(in: track), satellite: $satellite)
                            .frame(height: 300)
                            .listRowInsets(EdgeInsets())
                    }
                }

                Section {
                    StatsGrid(stats: track.stats, kilocalories: summary.activeKilocalories)
                }

                if !track.availableMetrics.isEmpty {
                    Section {
                        MetricChart(track: track, metric: $metric, selectedKm: $selectedKm)
                    }
                }

                if inHealth {
                    Section {
                        Label {
                            Text("Saved in Apple Health")
                        } icon: {
                            Image(systemName: "heart.fill")
                                .foregroundStyle(.pink)
                        }
                    }
                }
            } else if failed {
                ContentUnavailableView("Could not read this ride", systemImage: "exclamationmark.triangle")
            } else {
                HStack(spacing: 10) {
                    ProgressView()
                    Text("Reading ride…")
                        .foregroundStyle(.secondary)
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(Text("\(summary.distanceMeters.kilometres) km"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                VStack(spacing: 0) {
                    Text("\(summary.distanceMeters.kilometres) km")
                        .font(.headline)
                    Text(verbatim: summary.start.formatted(date: .abbreviated, time: .shortened))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .task { await load() }
    }

    private func highlighted(in track: RideTrack) -> RideTrack.Point? {
        guard let selectedKm, let index = track.pointIndex(nearestKm: selectedKm) else { return nil }
        return track.points[index]
    }

    private func load() async {
        guard track == nil, let url = try? RideFiles.standard().url(for: summary.fileName) else { return }
        let loaded = await Task.detached(priority: .userInitiated) {
            try? RideTrack(ride: FitParser.parse(url: url))
        }.value
        if let loaded {
            metric = loaded.availableMetrics.first ?? .elevation
            track = loaded
        } else {
            failed = true
        }
    }
}

struct RouteMap: View {
    let track: RideTrack
    let highlighted: RideTrack.Point?
    @Binding var satellite: Bool
    @State private var position: MapCameraPosition

    init(track: RideTrack, highlighted: RideTrack.Point?, satellite: Binding<Bool>) {
        self.track = track
        self.highlighted = highlighted
        _satellite = satellite
        _position = State(initialValue: .rect(Self.rect(for: track.coordinates)))
    }

    var body: some View {
        Map(position: $position) {
            MapPolyline(coordinates: track.coordinates)
                .stroke(Color.green, style: StrokeStyle(lineWidth: 5, lineCap: .round, lineJoin: .round))
            if let start = track.coordinates.first {
                Annotation("Start", coordinate: start) { marker(.green, size: 14) }
                    .annotationTitles(.hidden)
            }
            if let finish = track.coordinates.last {
                Annotation("Finish", coordinate: finish) { marker(.red, size: 14) }
                    .annotationTitles(.hidden)
            }
            if let coordinate = highlighted?.coordinate {
                Annotation("Selected", coordinate: coordinate) { marker(.blue, size: 18) }
                    .annotationTitles(.hidden)
            }
        }
        .mapStyle(satellite ? .hybrid(elevation: .realistic) : .standard(elevation: .realistic))
        .overlay(alignment: .topTrailing) {
            Button {
                satellite.toggle()
            } label: {
                Image(systemName: satellite ? "map" : "globe.europe.africa.fill")
                    .font(.body.weight(.semibold))
                    .frame(width: 40, height: 40)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            }
            .accessibilityLabel(satellite ? Text("Map") : Text("Satellite"))
            .padding(10)
        }
    }

    private func marker(_ color: Color, size: CGFloat) -> some View {
        Circle()
            .fill(color)
            .overlay(Circle().stroke(.white, lineWidth: 2.5))
            .frame(width: size, height: size)
            .shadow(radius: 1)
    }

    /// Прямоугольник маршрута с полями, чтобы трек не упирался в края карты.
    static func rect(for coordinates: [CLLocationCoordinate2D]) -> MKMapRect {
        let points = coordinates.map(MKMapPoint.init)
        guard let minX = points.map(\.x).min(), let maxX = points.map(\.x).max(),
              let minY = points.map(\.y).min(), let maxY = points.map(\.y).max()
        else { return .world }
        let rect = MKMapRect(x: minX, y: minY, width: max(maxX - minX, 200), height: max(maxY - minY, 200))
        return rect.insetBy(dx: -rect.width * 0.15, dy: -rect.height * 0.15)
    }
}

struct StatsGrid: View {
    let stats: RideTrack.Stats
    let kilocalories: Int?

    var body: some View {
        Grid(alignment: .leading, horizontalSpacing: 16, verticalSpacing: 16) {
            GridRow {
                Stat("Distance", Text("\(stats.distanceMeters.kilometres) km"))
                Stat("Moving Time", Text(verbatim: Self.clock(stats.movingSeconds)))
            }
            GridRow {
                Stat("Avg Speed", stats.averageSpeedKmh.map(Self.speed))
                Stat("Max Speed", stats.maxSpeedKmh.map(Self.speed))
            }
            GridRow {
                Stat("Elevation Gain", stats.ascentMeters.map { Text("\($0) m") })
                Stat("Active Energy", kilocalories.map { Text("\($0) kcal") })
            }
            if stats.averageHeartRate != nil || stats.maxHeartRate != nil {
                GridRow {
                    Stat("Avg Heart Rate", stats.averageHeartRate.map { Text("\($0) bpm") })
                    Stat("Max Heart Rate", stats.maxHeartRate.map { Text("\($0) bpm") })
                }
            }
            GridRow {
                Stat("Total Time", Text(verbatim: Self.clock(stats.elapsedSeconds)))
                Stat("Altitude", stats.altitudeRange.map {
                    Text("\(Int($0.lowerBound.rounded()))–\(Int($0.upperBound.rounded())) m")
                })
            }
        }
        .padding(.vertical, 6)
    }

    static func clock(_ seconds: Int) -> String {
        Duration.seconds(seconds).formatted(.time(pattern: .hourMinuteSecond))
    }

    static func speed(_ kmh: Double) -> Text {
        Text("\(kmh.formatted(.number.precision(.fractionLength(1)))) km/h")
    }

    private struct Stat: View {
        let title: LocalizedStringKey
        let value: Text?

        init(_ title: LocalizedStringKey, _ value: Text?) {
            self.title = title
            self.value = value
        }

        var body: some View {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                (value ?? Text(verbatim: "—"))
                    .font(.title3.weight(.semibold))
                    .monospacedDigit()
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
        }
    }
}

struct MetricChart: View {
    let track: RideTrack
    @Binding var metric: RideTrack.Metric
    @Binding var selectedKm: Double?

    var body: some View {
        let samples = track.chartSamples(metric)
        let values = samples.map(\.value)
        let low: Double = metric == .elevation ? (values.min() ?? 0) - 2 : 0
        let top = values.max() ?? 1
        let high = max(top + (top - low) * 0.08, low + 1)

        VStack(alignment: .leading, spacing: 12) {
            if track.availableMetrics.count > 1 {
                Picker("Chart", selection: $metric) {
                    ForEach(track.availableMetrics) { item in
                        Text(item.title).tag(item)
                    }
                }
                .pickerStyle(.segmented)
            }

            header(samples)

            Chart {
                ForEach(samples) { sample in
                    AreaMark(
                        x: .value("Distance", sample.distanceKm),
                        yStart: .value("Base", low),
                        yEnd: .value("Value", sample.value)
                    )
                    .foregroundStyle(
                        LinearGradient(
                            colors: [metric.color.opacity(0.35), metric.color.opacity(0.03)],
                            startPoint: .top,
                            endPoint: .bottom
                        )
                    )
                    LineMark(
                        x: .value("Distance", sample.distanceKm),
                        y: .value("Value", sample.value)
                    )
                    .foregroundStyle(metric.color)
                    .lineStyle(StrokeStyle(lineWidth: 2, lineJoin: .round))
                }
                if let selectedKm {
                    RuleMark(x: .value("Selected", selectedKm))
                        .foregroundStyle(Color.secondary)
                }
            }
            .chartYScale(domain: low...high)
            .chartXScale(domain: 0...max(track.stats.distanceMeters / 1000, samples.last?.distanceKm ?? 0, 0.1))
            .chartXAxisLabel(position: .bottom, alignment: .trailing) { Text("km") }
            .chartXSelection(value: $selectedKm)
            .frame(height: 200)
        }
        .padding(.vertical, 6)
        .onChange(of: metric) { selectedKm = nil }
    }

    /// Над графиком — значение под пальцем или среднее за поездку.
    private func header(_ samples: [RideTrack.ChartSample]) -> some View {
        let value: Double?
        let caption: Text
        if let selectedKm,
           let index = track.pointIndex(nearestKm: selectedKm),
           let raw = RideTrack.value(metric, of: track.points[index]) {
            value = raw
            let km = (track.points[index].distanceMeters / 1000).formatted(.number.precision(.fractionLength(2)))
            caption = Text("at \(km) km")
        } else {
            value = samples.isEmpty ? nil : samples.map(\.value).reduce(0, +) / Double(samples.count)
            caption = Text("average for the ride")
        }
        return HStack(alignment: .firstTextBaseline, spacing: 6) {
            Text(verbatim: value.map { Int($0.rounded()).formatted() } ?? "—")
                .font(.title2.weight(.semibold))
                .monospacedDigit()
            Text(metric.unit)
                .foregroundStyle(.secondary)
            Spacer()
            caption
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
    }
}

extension RideTrack.Metric {
    var title: LocalizedStringKey {
        switch self {
        case .elevation: "Elevation"
        case .speed: "Speed"
        case .heartRate: "Heart Rate"
        case .cadence: "Cadence"
        }
    }

    var unit: LocalizedStringKey {
        switch self {
        case .elevation: "m"
        case .speed: "km/h"
        case .heartRate: "bpm"
        case .cadence: "rpm"
        }
    }

    var color: Color {
        switch self {
        case .elevation: .green
        case .speed: .blue
        case .heartRate: .red
        case .cadence: .orange
        }
    }
}
