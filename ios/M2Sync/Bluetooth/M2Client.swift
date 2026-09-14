import CoreBluetooth
import Foundation

/// Ошибки обмена с велокомпьютером.
enum M2Error: LocalizedError, Equatable {
    case bluetoothUnavailable
    case notFound(prefix: String)
    case timeout(String)
    case disconnected
    case refused(String)
    case badHeader
    case truncated(received: Int, expected: Int)

    var errorDescription: String? {
        switch self {
        case .bluetoothUnavailable:
            String(localized: "Bluetooth is unavailable")
        case .notFound(let prefix):
            String(localized: "No bike computer named \(prefix)… nearby")
        case .timeout(let step):
            String(localized: "Timed out: \(step)")
        case .disconnected:
            String(localized: "The bike computer disconnected")
        case .refused(let reply):
            String(localized: "The bike computer refused the request: \(reply)")
        case .badHeader:
            String(localized: "Unreadable file header")
        case .truncated(let received, let expected):
            String(localized: "File is shorter than declared: \(received) of \(expected) bytes")
        }
    }
}

private enum GATT {
    static let uart = CBUUID(string: "6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    static let rx = CBUUID(string: "6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // сюда пишем ACK/NAK/'C'
    static let tx = CBUUID(string: "6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // отсюда идут блоки файла
    static let ctl = CBUUID(string: "6E400004-B5A3-F393-E0A9-E50E24DCCA9E") // команды и ответы на них
    static let deviceInformation = CBUUID(string: "180A")
    static let battery = CBUUID(string: "180F")
    static let batteryLevel = CBUUID(string: "2A19")
    static let modelNumber = CBUUID(string: "2A24")
    static let firmwareRevision = CBUUID(string: "2A26")
}

/// Разовое ожидание колбэка делегата с таймаутом. На каждую операцию — новый экземпляр,
/// иначе таймаут прошлого ожидания оборвёт следующее.
@MainActor
final class Pending<T> {
    private var continuation: CheckedContinuation<T, Error>?

    func wait(timeout: TimeInterval, what: String) async throws -> T {
        try await withCheckedThrowingContinuation { continuation in
            self.continuation = continuation
            DispatchQueue.main.asyncAfter(deadline: .now() + timeout) { [weak self] in
                MainActor.assumeIsolated { self?.resolve(.failure(M2Error.timeout(what))) }
            }
        }
    }

    func resolve(_ result: Result<T, Error>) {
        guard let continuation else { return }
        self.continuation = nil
        continuation.resume(with: result)
    }
}

/// Уведомления одной характеристики, выдаются по одному пакету.
@MainActor
final class PacketQueue {
    private var buffer: [Data] = []
    private var waiter: Pending<Data>?

    func push(_ packet: Data) {
        if let waiter {
            self.waiter = nil
            waiter.resolve(.success(packet))
        } else {
            buffer.append(packet)
        }
    }

    func drain() {
        buffer.removeAll()
    }

    func fail(_ error: Error) {
        waiter?.resolve(.failure(error))
        waiter = nil
    }

    func next(timeout: TimeInterval, what: String) async throws -> Data {
        if !buffer.isEmpty { return buffer.removeFirst() }
        let pending = Pending<Data>()
        waiter = pending
        defer { if waiter === pending { waiter = nil } }
        return try await pending.wait(timeout: timeout, what: what)
    }
}

/// Велокомпьютер по BLE: список поездок, скачивание файла, батарея, прошивка, свободное место.
/// Проверено на Cycplus M2 с прошивкой V1.4.0.
@MainActor
final class M2Client: NSObject {
    var log: (String) -> Void = { _ in }

    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var characteristics: [CBUUID: CBCharacteristic] = [:]
    private let ctlQueue = PacketQueue()
    private let txQueue = PacketQueue()

    private var namePrefix = "M2_"
    private var poweredOn: Pending<Void>?
    private var discovered: Pending<CBPeripheral>?
    private var connected: Pending<Void>?
    private var ready: Pending<Void>?
    private var reads: [CBUUID: Pending<Data>] = [:]
    private var servicesLeft = 0
    private var notificationsLeft = 0

    override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: .main)
    }

    var maxWriteLength: Int {
        peripheral?.maximumWriteValueLength(for: .withoutResponse) ?? 0
    }

    /// Ищет велокомпьютер по префиксу имени, подключается и подписывается на CTL и TX.
    /// Возвращает имя из рекламы, например `M2_E8E3`.
    func connect(prefix: String = "M2_", scanTimeout: TimeInterval = 30) async throws -> String {
        if central.state != .poweredOn {
            let pending = Pending<Void>()
            poweredOn = pending
            try await pending.wait(timeout: 10, what: "Bluetooth")
        }

        namePrefix = prefix
        let scan = Pending<CBPeripheral>()
        discovered = scan
        central.scanForPeripherals(withServices: nil)
        let device: CBPeripheral
        do {
            device = try await scan.wait(timeout: scanTimeout, what: "scan")
        } catch {
            central.stopScan()
            throw M2Error.notFound(prefix: prefix)
        }
        central.stopScan()
        peripheral = device
        device.delegate = self

        let connection = Pending<Void>()
        connected = connection
        central.connect(device)
        try await connection.wait(timeout: 30, what: "connect")

        let setup = Pending<Void>()
        ready = setup
        device.discoverServices([GATT.uart, GATT.battery, GATT.deviceInformation])
        try await setup.wait(timeout: 20, what: "services")

        return device.name ?? prefix
    }

    func disconnect() {
        if let peripheral { central.cancelPeripheralConnection(peripheral) }
    }

    func battery() async -> Int? {
        await read(GATT.batteryLevel)?.first.map(Int.init)
    }

    func model() async -> String? {
        await readString(GATT.modelNumber)
    }

    func firmware() async -> String? {
        await readString(GATT.firmwareRevision)
    }

    /// Свободное и общее место, строкой вида `15752/16384` (КБ).
    func diskSpace() async -> String? {
        drain()
        guard (try? write(M2Protocol.diskSpace, to: GATT.ctl)) != nil,
              let reply = try? await ctlQueue.next(timeout: 3, what: "disk space") else { return nil }
        return M2Protocol.parseDiskSpace(reply)
    }

    func listFiles() async throws -> [DeviceFile] {
        M2Protocol.parseFileList(try await fetch(M2Protocol.fileList))
    }

    /// Скачивает файл по YMODEM. `progress` получает число уже принятых байтов.
    func fetch(_ name: String, progress: ((Int) -> Void)? = nil) async throws -> Data {
        try await ensureIdle()
        drain()

        try write(M2Protocol.command(M2Protocol.fetchCode, name), to: GATT.ctl)
        let reply = try await ctlQueue.next(timeout: 5, what: "fetch \(name)")
        guard reply.first == M2Protocol.fetchAccepted else { throw M2Error.refused(reply.hexString) }

        try write(Data([M2Protocol.c]), to: GATT.rx)
        guard let header = try await readBlock(), let expected = M2Protocol.declaredSize(header: header) else {
            throw M2Error.badHeader
        }

        try write(Data([M2Protocol.ack]), to: GATT.rx)
        try write(Data([M2Protocol.c]), to: GATT.rx)

        var data = Data()
        while true {
            let block: Data?
            do {
                block = try await readBlock()
            } catch M2Protocol.BlockError.badCRC {
                log("retrying a block of \(name)")
                try write(Data([M2Protocol.nak]), to: GATT.rx)
                continue
            }
            guard let block else { break }
            data.append(block)
            progress?(min(data.count, expected))
            try write(Data([M2Protocol.ack]), to: GATT.rx)
        }

        // Конец передачи: NAK → второй EOT → ACK, и устройство возвращается в idle.
        try write(Data([M2Protocol.nak]), to: GATT.rx)
        _ = try? await txQueue.next(timeout: 5, what: "second EOT")
        try write(Data([M2Protocol.ack]), to: GATT.rx)
        _ = try? await ctlQueue.next(timeout: 3, what: "idle")

        guard data.count >= expected else {
            throw M2Error.truncated(received: data.count, expected: expected)
        }
        return data.prefix(expected)
    }

    private func ensureIdle() async throws {
        drain()
        try write(M2Protocol.status, to: GATT.ctl)
        let reply: Data
        do {
            reply = try await ctlQueue.next(timeout: 3, what: "status")
        } catch M2Error.timeout {
            try write(M2Protocol.idle, to: GATT.ctl)
            reply = try await ctlQueue.next(timeout: 3, what: "idle")
        }
        guard M2Protocol.isIdle(reply) else { throw M2Error.refused(reply.hexString) }
    }

    /// Один блок YMODEM; nil — это EOT.
    private func readBlock(timeout: TimeInterval = 15) async throws -> Data? {
        var assembler = BlockAssembler()
        while true {
            let packet = try await txQueue.next(timeout: timeout, what: "YMODEM block")
            switch try assembler.push(packet) {
            case .needMore: continue
            case .endOfTransmission: return nil
            case .block(let payload): return payload
            }
        }
    }

    private func read(_ uuid: CBUUID) async -> Data? {
        guard let peripheral, let characteristic = characteristics[uuid] else { return nil }
        let pending = Pending<Data>()
        reads[uuid] = pending
        defer { reads[uuid] = nil }
        peripheral.readValue(for: characteristic)
        return try? await pending.wait(timeout: 5, what: "read \(uuid.uuidString)")
    }

    private func readString(_ uuid: CBUUID) async -> String? {
        guard let data = await read(uuid) else { return nil }
        return String(decoding: data, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines.union(.controlCharacters))
    }

    private func write(_ value: Data, to uuid: CBUUID) throws {
        guard let peripheral, let characteristic = characteristics[uuid] else { throw M2Error.disconnected }
        let type: CBCharacteristicWriteType =
            characteristic.properties.contains(.writeWithoutResponse) ? .withoutResponse : .withResponse
        peripheral.writeValue(value, for: characteristic, type: type)
    }

    private func drain() {
        ctlQueue.drain()
        txQueue.drain()
    }

    private func failAll(_ error: Error) {
        for pending in [poweredOn, connected, ready] {
            pending?.resolve(.failure(error))
        }
        discovered?.resolve(.failure(error))
        for pending in reads.values {
            pending.resolve(.failure(error))
        }
        ctlQueue.fail(error)
        txQueue.fail(error)
    }
}

extension M2Client: CBCentralManagerDelegate {
    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        MainActor.assumeIsolated {
            switch central.state {
            case .poweredOn:
                poweredOn?.resolve(.success(()))
            case .unauthorized, .unsupported, .poweredOff:
                poweredOn?.resolve(.failure(M2Error.bluetoothUnavailable))
            default:
                break
            }
        }
    }

    nonisolated func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        MainActor.assumeIsolated {
            let name = advertisementData[CBAdvertisementDataLocalNameKey] as? String ?? peripheral.name ?? ""
            // Как в Android-версии: регистр в имени не важен, берём первое совпадение.
            if name.lowercased().hasPrefix(namePrefix.lowercased()) {
                discovered?.resolve(.success(peripheral))
            }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        MainActor.assumeIsolated { connected?.resolve(.success(())) }
    }

    nonisolated func centralManager(
        _ central: CBCentralManager,
        didFailToConnect peripheral: CBPeripheral,
        error: Error?
    ) {
        MainActor.assumeIsolated { connected?.resolve(.failure(error ?? M2Error.disconnected)) }
    }

    nonisolated func centralManager(
        _ central: CBCentralManager,
        didDisconnectPeripheral peripheral: CBPeripheral,
        error: Error?
    ) {
        MainActor.assumeIsolated {
            characteristics.removeAll()
            failAll(M2Error.disconnected)
        }
    }
}

extension M2Client: CBPeripheralDelegate {
    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        MainActor.assumeIsolated {
            let services = peripheral.services ?? []
            guard error == nil, services.contains(where: { $0.uuid == GATT.uart }) else {
                ready?.resolve(.failure(error ?? M2Error.refused("no Nordic UART service")))
                return
            }
            servicesLeft = services.count
            for service in services {
                peripheral.discoverCharacteristics(nil, for: service)
            }
        }
    }

    nonisolated func peripheral(
        _ peripheral: CBPeripheral,
        didDiscoverCharacteristicsFor service: CBService,
        error: Error?
    ) {
        MainActor.assumeIsolated {
            for characteristic in service.characteristics ?? [] {
                characteristics[characteristic.uuid] = characteristic
            }
            servicesLeft -= 1
            guard servicesLeft == 0 else { return }
            guard let ctl = characteristics[GATT.ctl],
                  let tx = characteristics[GATT.tx],
                  characteristics[GATT.rx] != nil
            else {
                ready?.resolve(.failure(M2Error.refused("no RX/TX/CTL characteristics")))
                return
            }
            notificationsLeft = 2
            peripheral.setNotifyValue(true, for: ctl)
            peripheral.setNotifyValue(true, for: tx)
        }
    }

    nonisolated func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateNotificationStateFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        MainActor.assumeIsolated {
            if let error {
                ready?.resolve(.failure(error))
                return
            }
            notificationsLeft -= 1
            if notificationsLeft == 0 { ready?.resolve(.success(())) }
        }
    }

    nonisolated func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        MainActor.assumeIsolated {
            guard let value = characteristic.value else { return }
            switch characteristic.uuid {
            case GATT.ctl: ctlQueue.push(value)
            case GATT.tx: txQueue.push(value)
            default: reads[characteristic.uuid]?.resolve(.success(value))
            }
        }
    }
}
