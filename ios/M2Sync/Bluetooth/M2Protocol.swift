import Foundation

/// Формат обмена Cycplus M2: команды на канал CTL и файлы по YMODEM поверх Nordic UART.
/// Без Bluetooth — только байты, поэтому всё здесь проверяется юнит-тестами.
/// Описание протокола: docs/PROTOCOL.md.
enum M2Protocol {
    static let stx: UInt8 = 0x02
    static let eot: UInt8 = 0x04
    static let ack: UInt8 = 0x06
    static let nak: UInt8 = 0x15
    static let c: UInt8 = 0x43

    static let status = Data([0xFF, 0x00, 0xFF])
    static let idle = Data([0x04, 0x00, 0x04])
    static let diskSpace = Data([0x09, 0x00, 0x09])
    static let fetchCode: UInt8 = 0x05
    static let fetchAccepted: UInt8 = 0x06
    static let diskSpaceReply: UInt8 = 0x0A

    static let fileList = "filelist.txt"

    enum BlockError: Error, Equatable {
        case truncated
        case badCRC
    }

    /// CRC16/ARC (poly 0xA001, отражённый) — не CRC16/XMODEM, как можно было бы ждать от YMODEM.
    static func crc16Arc(_ bytes: some Sequence<UInt8>) -> UInt16 {
        var crc: UInt16 = 0
        for byte in bytes {
            crc ^= UInt16(byte)
            for _ in 0..<8 {
                crc = crc & 1 != 0 ? (crc >> 1) ^ 0xA001 : crc >> 1
            }
        }
        return crc
    }

    /// Команда с именем файла: код, имя и XOR всех предыдущих байтов.
    static func command(_ code: UInt8, _ name: String) -> Data {
        var bytes = [code] + Array(name.utf8)
        bytes.append(bytes.reduce(0, ^))
        return Data(bytes)
    }

    /// M2 отвечает одним байтом 0x04, XOSS G+ — тройкой 04 00 04.
    static func isIdle(_ reply: Data) -> Bool {
        reply == idle || reply == Data([eot])
    }

    /// Размер блока целиком по первому байту: SOH несёт 128 байт, STX — 1024.
    static func blockSize(startingWith first: UInt8) -> Int {
        3 + (first == stx ? 1024 : 128) + 2
    }

    /// Проверяет собранный блок `SOH|STX, номер, ~номер, данные, CRC16` и возвращает данные.
    static func payload(ofBlock block: [UInt8]) throws -> Data {
        guard let first = block.first else { throw BlockError.truncated }
        let size = blockSize(startingWith: first)
        guard block.count >= size else { throw BlockError.truncated }
        let payload = Array(block[3..<(size - 2)])
        let crc = UInt16(block[size - 2]) << 8 | UInt16(block[size - 1])
        guard crc == crc16Arc(payload) else { throw BlockError.badCRC }
        return Data(payload)
    }

    /// Нулевой блок YMODEM: `"<имя> <размер>"`, добитый нулями.
    static func declaredSize(header: Data) -> Int? {
        let fields = String(decoding: header, as: UTF8.self)
            .trimmingCharacters(in: CharacterSet(charactersIn: "\0"))
            .split(whereSeparator: \.isWhitespace)
        guard fields.count > 1 else { return nil }
        return Int(fields[1])
    }

    /// `filelist.txt`: по строке `имя размер` на файл; нас интересуют только поездки.
    static func parseFileList(_ data: Data) -> [DeviceFile] {
        String(decoding: data, as: UTF8.self)
            .split(whereSeparator: \.isNewline)
            .compactMap { line in
                let parts = line.split(whereSeparator: \.isWhitespace)
                guard let name = parts.first, name.hasSuffix(".fit") else { return nil }
                let size = parts.count > 1 ? Int(parts[1]) ?? 0 : 0
                return DeviceFile(name: String(name), size: size)
            }
    }

    /// Ответ на запрос свободного места: `0a "15752/16384" <crc8>`, килобайты.
    static func parseDiskSpace(_ reply: Data) -> String? {
        guard reply.first == diskSpaceReply, reply.count >= 2 else { return nil }
        return String(decoding: reply.dropFirst().dropLast(), as: UTF8.self)
            .trimmingCharacters(in: .whitespaces)
    }
}

struct DeviceFile: Equatable, Hashable {
    let name: String
    let size: Int
}

/// Собирает блок YMODEM из уведомлений BLE. При MTU 185 блок SOH приходит одним
/// пакетом, а STX на 1029 байт — несколькими, поэтому размер берётся из первого байта.
struct BlockAssembler {
    enum Result: Equatable {
        case needMore
        case endOfTransmission
        case block(Data)
    }

    private var buffer: [UInt8] = []
    private var size = -1

    mutating func push(_ packet: Data) throws -> Result {
        if buffer.isEmpty, packet == Data([M2Protocol.eot]) { return .endOfTransmission }
        buffer += packet
        if size < 0 { size = M2Protocol.blockSize(startingWith: buffer[0]) }
        guard buffer.count >= size else { return .needMore }
        // Хвост сверх размера блока отбрасываем, а после ошибки CRC начинаем с чистого листа.
        defer {
            buffer.removeAll()
            size = -1
        }
        return .block(try M2Protocol.payload(ofBlock: Array(buffer.prefix(size))))
    }
}

extension Data {
    var hexString: String {
        map { String(format: "%02x", $0) }.joined(separator: " ")
    }
}
