// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "DSHMini",
    platforms: [.iOS(.v16)],
    products: [
        .executable(name: "DSHMini", targets: ["DSHMini"])
    ],
    targets: [
        .executableTarget(name: "DSHMini", path: "Sources/DSHMini")
    ]
)
