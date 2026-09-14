import Foundation
import Testing
@testable import M2Sync

struct M2ProtocolTests {
    @Test func crc16ArcMatchesReferenceVectors() {
        // Контрольное значение CRC-16/ARC для "123456789".
        #expect(M2Protocol.crc16Arc(Array("123456789".utf8)) == 0xBB3D)
        // Посчитано tools/m2.py на той же строке.
        #expect(M2Protocol.crc16Arc(Array("20260913085741.fit 259233".utf8)) == 0x6AE4)
    }

    @Test func fetchCommandEndsWithXorOfPrecedingBytes() {
        // Совпадает с tools/m2.py и с сессией из docs/PROTOCOL.md.
        let expected: [UInt8] = [0x05, 0x66, 0x69, 0x6C, 0x65, 0x6C, 0x69, 0x73, 0x74, 0x2E, 0x74, 0x78, 0x74, 0x57]
        #expect(Array(M2Protocol.command(M2Protocol.fetchCode, "filelist.txt")) == expected)
    }

    @Test func idleReplyIsOneByteOnM2AndThreeOnXoss() {
        #expect(M2Protocol.isIdle(Data([0x04])))
        #expect(M2Protocol.isIdle(Data([0x04, 0x00, 0x04])))
        #expect(!M2Protocol.isIdle(Data([0x0A, 0x31])))
        #expect(!M2Protocol.isIdle(Data()))
    }

    @Test func assemblesHeaderBlockFromSeveralNotifications() throws {
        let payload = padded("filelist.txt 130", to: 128)
        var assembler = BlockAssembler()
        var results: [BlockAssembler.Result] = []
        for packet in chunks(block(payload), size: 20) {
            results.append(try assembler.push(packet))
        }

        #expect(results.dropLast().allSatisfy { $0 == .needMore })
        #expect(results.last == .block(Data(payload)))
        #expect(M2Protocol.declaredSize(header: Data(payload)) == 130)
    }

    @Test func assemblesDataBlockSplitByMtu() throws {
        let payload = (0..<1024).map { UInt8($0 & 0xFF) }
        var assembler = BlockAssembler()
        var last: BlockAssembler.Result = .needMore
        // MTU 185: в уведомление помещается 182 байта.
        for packet in chunks(block(payload), size: 182) {
            last = try assembler.push(packet)
        }
        #expect(last == .block(Data(payload)))
    }

    @Test func rejectsCorruptedBlockAndRecoversOnTheNextOne() throws {
        let payload = padded("20260913085741.fit 259233", to: 128)
        var corrupted = block(payload)
        corrupted[10] ^= 0xFF

        var assembler = BlockAssembler()
        #expect(throws: M2Protocol.BlockError.badCRC) {
            _ = try assembler.push(Data(corrupted))
        }
        #expect(try assembler.push(Data(block(payload))) == .block(Data(payload)))
    }

    @Test func eotEndsTransferOnlyBetweenBlocks() throws {
        var assembler = BlockAssembler()
        #expect(try assembler.push(Data([M2Protocol.eot])) == .endOfTransmission)

        let payload = padded("x", to: 128)
        let bytes = block(payload)
        #expect(try assembler.push(Data(bytes.prefix(10))) == .needMore)
        // Внутри блока 0x04 — просто данные.
        #expect(try assembler.push(Data([M2Protocol.eot])) == .needMore)
    }

    @Test func fileListKeepsOnlyRidesWithTheirSizes() {
        let listing = "20260723122156.fit 36069\r\n20260723133759.fit 3813\nSetting.json 20\n20260724103005.fit\n"
        #expect(M2Protocol.parseFileList(Data(listing.utf8)) == [
            DeviceFile(name: "20260723122156.fit", size: 36069),
            DeviceFile(name: "20260723133759.fit", size: 3813),
            DeviceFile(name: "20260724103005.fit", size: 0),
        ])
    }

    @Test func diskSpaceReplyIsReadWithoutChecksum() {
        let reply = Data([0x0A]) + Data("15752/16384".utf8) + Data([0x29])
        #expect(M2Protocol.parseDiskSpace(reply) == "15752/16384")
        #expect(M2Protocol.parseDiskSpace(Data([0x04])) == nil)
    }

    private func block(_ payload: [UInt8], number: UInt8 = 0) -> [UInt8] {
        let start: UInt8 = payload.count == 1024 ? 0x02 : 0x01
        let crc = M2Protocol.crc16Arc(payload)
        return [start, number, ~number] + payload + [UInt8(crc >> 8), UInt8(crc & 0xFF)]
    }

    private func padded(_ text: String, to size: Int) -> [UInt8] {
        let bytes = Array(text.utf8)
        return bytes + [UInt8](repeating: 0, count: size - bytes.count)
    }

    private func chunks(_ bytes: [UInt8], size: Int) -> [Data] {
        stride(from: 0, to: bytes.count, by: size).map { Data(bytes[$0..<min($0 + size, bytes.count)]) }
    }
}
