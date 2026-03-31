// swift-tools-version: 6.2
import PackageDescription

let package = Package(
    name: "AppleTranslate",
    platforms: [
        .macOS(.v26),
    ],
    targets: [
        .executableTarget(
            name: "AppleTranslate",
            swiftSettings: [
                .unsafeFlags(["-strict-concurrency=minimal"]),
            ]
        ),
    ]
)
