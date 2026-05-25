import XCTest
import KmIo
import KmIoByteString

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
        let value = encodeToByteString("km-io")

        XCTAssertFalse(isEmpty(value))
        XCTAssertEqual(decodeToString(value), "km-io")
    }
}
