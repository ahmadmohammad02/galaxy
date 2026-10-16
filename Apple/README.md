# Galaxy TV for Apple platforms

This Xcode project contains separate iOS and tvOS app targets that share the SwiftUI screens and API client. The iPhone app is named **APP-IOS**.

## Open and run

Open `GalaxyTV.xcodeproj` in Xcode on macOS, select `GalaxyTV-iOS` or `GalaxyTV-tvOS`, then choose a simulator or a signed device destination.

The iOS target supports iOS 17 and later; the tvOS target supports tvOS 17 and later. This workspace is Linux-based and does not include Xcode, so Apple-platform builds and simulator testing must be run on macOS.

## Network transport

The API uses `http://maghrebtv.xyz:2082/iptv/V6/API-V6.php`. Both Apple targets have a narrowly scoped App Transport Security exception for this host so the app can connect. HTTP does not protect activation codes or API traffic from interception. Replace this with an HTTPS endpoint and remove the exception before production or App Store submission.

The client selects the lite package API on devices with 2 GB or less of physical memory or no more than two active processors. Apple does not expose the hardware MAC address to apps, so requests use an empty MAC and the vendor-scoped device identifier as the serial number. The configured failover endpoint is the same address as the main host, so it does not provide independent redundancy.

## Current scope

The first native version includes activation, home navigation, live/sports packages and channels, movie and series categories, video playback, and sign-out. Expiration handling, detailed movie/series pages, artwork polish, and full accessibility/focus testing still need parity work against the Android app.
