// swift-tools-version:5.7
import PackageDescription

let package = Package(
    name: "KmperTraceRuntime",
    platforms: [
        .iOS(.v13)
    ],
    products: [
        .library(
            name: "KmperTraceRuntime",
            targets: ["KmperTraceRuntime"]
        )
    ],
    targets: [
        .binaryTarget(
            name: "KmperTraceRuntime",
            url: "https://github.com/pluralfusion/kmpertrace/releases/download/v0.3.1/KmperTraceRuntime.xcframework.zip",
            checksum: "f2a2fb70b93d55263cafaf2820550408ab3f76cb5afd266c864ef8a234017303"
        )
    ]
)
