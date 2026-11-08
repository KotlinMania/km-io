import Foundation
import KmIo
import Testing

@Suite("KmIo Swift Export Tests")
struct KmIoExportTests {
    @Test("Buffer write and read round-trip")
    func testBufferRoundTrip() throws {
        let buffer = Buffer()

        #expect(buffer.exhausted())
        buffer.writeByte(byte: 0x41)
        #expect(buffer.size == 1)
        #expect(!buffer.exhausted())
        #expect(buffer.readByte() == 0x41)
        #expect(buffer.exhausted())
    }

    @Test("ByteString UTF-8 encode and decode round-trip")
    func testByteStringRoundTrip() throws {
        typealias ByteStrings = ExportedKotlinPackages.io.github.kotlinmania.io.bytestring
        let value = ByteStrings.encodeToByteString("km-io")

        #expect(!ByteStrings.isEmpty(value))
        #expect(ByteStrings.decodeToString(value) == "km-io")
    }

    @Test("NSData and ByteString Foundation interop round-trip")
    func testNSDataInteropRoundTrip() throws {
        typealias ByteStrings = ExportedKotlinPackages.io.github.kotlinmania.io.bytestring
        let original = ByteStrings.encodeToByteString("swift-foundation-interop")
        let nsData: Foundation.NSData = ByteStrings.toNSData(original)
        let roundTripped = ByteStrings.toByteString(nsData)

        #expect(nsData.length == 24)
        #expect(ByteStrings.decodeToString(roundTripped) == "swift-foundation-interop")
    }
}
