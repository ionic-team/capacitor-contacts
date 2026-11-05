// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "CapacitorContacts",
    platforms: [.iOS(.v16)],
    products: [
        .library(
            name: "CapacitorContacts",
            targets: ["ContactsPlugin"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor.git", from: "9.0.0-beta.0")
    ],
    targets: [
        .target(
            name: "ContactsPlugin",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor")
            ],
            path: "ios/Sources/ContactsPlugin"),
        .testTarget(
            name: "ContactsPluginTests",
            dependencies: ["ContactsPlugin"],
            path: "ios/Tests/ContactsPluginTests")
    ]
)
