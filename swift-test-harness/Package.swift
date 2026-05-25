// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "SwiftTestHarness",
    dependencies: [
        .package(name: "KmIo", path: "../core/build/SPMPackage/macosArm64/Debug")
    ],
    targets: [
        .testTarget(
            name: "SwiftTestHarnessTests",
            dependencies: [
                .product(name: "KmIoLibrary", package: "KmIo")
            ],
            linkerSettings: [
                .unsafeFlags([
                    "-L", "../core/build/swift-test",
                    "-lKmIo",
                ]),
            ]
        ),
    ]
)
