// swift-tools-version: 5.7
import PackageDescription
import AppleProductTypes

let package = Package(
    name: "DSHMini",
    platforms: [
        .iOS("16.0")
    ],
    products: [
        .iOSApplication(
            name: "DSHMini",
            targets: ["AppModule"],
            displayVersion: "1.0",
            bundleVersion: "1",
            appIcon: .placeholder(icon: .star),
            accentColor: .presetColor(.blue),
            supportedDeviceFamilies: [
                .pad,
                .phone
            ],
            supportedInterfaceOrientations: [
                .portrait,
                .landscapeRight,
                .landscapeLeft
            ]
        )
    ],
    targets: [
        .executableTarget(
            name: "AppModule",
            path: "Sources/AppModule"
        )
    ]
)
