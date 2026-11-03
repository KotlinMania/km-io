import XCTest
import KmIo

final class KmIoExportTests: XCTestCase {
    func testBufferRoundTrip() throws {
        let buffer = Buffer()

        XCTAssertTrue(buffer.exhausted())
        buffer.writeByte(byte: 0x41)
        XCTAssertEqual(buffer.size, 1)
        XCTAssertFalse(buffer.exhausted())
        XCTAssertEqual(buffer.readByte(), 0x41)
        XCTAssertTrue(buffer.exhausted())
    }

    func testByteStringRoundTrip() throws {
        typealias ByteStrings = ExportedKotlinPackages.io.github.kotlinmania.io.bytestring
        let value = ByteStrings.encodeToByteString("km-io")

        XCTAssertFalse(ByteStrings.isEmpty(value))
        XCTAssertEqual(ByteStrings.decodeToString(value), "km-io")
    }
}
