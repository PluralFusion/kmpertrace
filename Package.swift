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
            url: "https://github.com/pluralfusion/kmpertrace/releases/download/v0.3.3/KmperTraceRuntime.xcframework.zip",
            checksum: "fc7f2ce94aba7010d20c890e1104f675404e517995a35106a8133d5e42a8ae7c"
        )
    ]
)
