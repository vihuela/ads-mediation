# Ads Mediation

`ads-mediation` provides one SDK-neutral API for app-open, interstitial, and rewarded ads. It can
activate direct AdMob GMA Next-Gen, the TopOn overseas SDK, or initialize both providers and run a
same-format cached-price competition for every show opportunity.

## Installation

The package is published from GitHub Packages as:

```kotlin
implementation("com.cashcraft:ads-mediation:1.0.0")
```

Add the GitHub Packages repository plus the SDK repositories used by its transitive dependencies:

```kotlin
// settings.gradle.kts
val localProperties = java.util.Properties().apply {
    file("local.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}

maven {
    url = uri("https://maven.pkg.github.com/vihuela/ads-mediation")
    credentials {
        username = providers.gradleProperty("github.packages.username").orNull
            ?: System.getenv("GITHUB_PACKAGES_USERNAME")
            ?: localProperties.getProperty("github.packages.username")
        password = providers.gradleProperty("github.packages.token").orNull
            ?: System.getenv("GITHUB_PACKAGES_TOKEN")
            ?: localProperties.getProperty("github.packages.token")
    }
}
maven("https://artifact.bytedance.com/repository/pangle/")
maven("https://dl-maven-android.mintegral.com/repository/mbridge_android_sdk_oversea")
maven("https://jfrog.anythinktech.com/artifactory/overseas_sdk")
```

GitHub Packages credentials need `read:packages` access. For local builds, keep them in the
consumer project's ignored `local.properties` or user-level Gradle properties; CI should use
encrypted secrets.

## Host API

```kotlin
import com.cashcraft.ads.mediation.*

Ads.initialize(
    application = this,
    config = AdsConfig(
        provider = AdMobProviderConfig(
            ids = AdMobIds(
                applicationId = "ca-app-pub-...~...",
                appOpenId = "ca-app-pub-.../...",
                interstitialId = "ca-app-pub-.../...",
                rewardedId = "ca-app-pub-.../...",
            ),
        ),
        // UMP is a global gate and runs before either AdMob or TopOn initialization.
        umpConsent = UmpConsentConfig(),
        eventListener = AdEventListener { event ->
            analytics.log(event.name.analyticsName, event.analyticsParameters())
        },
    ),
)
```

For TopOn overseas, only the provider configuration changes:

```kotlin
TopOnProviderConfig(
    ids = TopOnIds(
        applicationId = "your TopOn app id",
        applicationKey = "your TopOn app key",
        appOpenPlacementId = "your splash placement id",
        interstitialPlacementId = "your interstitial placement id",
        rewardedPlacementId = "your rewarded placement id",
    ),
)
```

For cached-price bidding, provide both configurations:

```kotlin
BiddingProviderConfig(
    admob = AdMobProviderConfig(ids = admobIds),
    topon = TopOnProviderConfig(ids = topOnIds),
)
```

Calls from Activities stay provider-neutral:

```kotlin
Ads.showInterstitial(activity, "game_level_complete") { result -> }
Ads.showRewarded(activity, "game_tool_refresh") { result ->
    if (result.rewardEarned) grantReward()
}
Ads.showAppOpen(activity, "manual") { result -> }
```

The module appends the ad type to every analytics `position`, using
`<business_scene>_<ad_type>`. Existing matching suffixes are not duplicated.
Module-owned ad and UMP diagnostics use `AdsConfig.logTag`. `AdsConfig.showLog` controls those logs
and the selected provider's SDK debug logging.

## Provider selection

Hosts select `AdMobProviderConfig`, `TopOnProviderConfig`, or `BiddingProviderConfig`. Bidding
initializes both providers after the single UMP gate.

The checked-in TopOn TU dependency set contains ADX, Pangle, Meta, Mintegral, Tramini, and GMA
Next-Gen. Adapter selection does not leak into business code.

Direct GMA Next-Gen is pinned to `1.2.1`, matching remax's static preload-queue reflection paths.
TopOn's GMA Next-Gen adapter is pinned to `1.2.1.1.0`. Never add the legacy `play-services-ads`
adapter beside them.

In bidding mode both providers preload the same three formats. Every show opportunity immediately
compares only the ads already cached at that moment; it never waits for a network load. TopOn starts
a background fill when its cache is empty, matching standalone-show behavior, while AdMob's
Next-Gen preloader continuously replenishes its buffers. Cached values are compared in USD per
impression. TopOn uses `publisherRevenue`, falling back to `ecpm / 1000`; AdMob reads the head
`AdValue.valueMicros` from the Next-Gen preload queue through the versioned asset
`google_next_gen_preload_reflection_paths.json`. The higher available value wins. AdMob wins an
available tie, including zero versus zero. If only one provider has an ad, that provider wins even
with a zero price. If neither has an ad, nothing is shown.
When `ads.showLog` is enabled, the `ad_bid_result` event log includes both availability flags,
both USD values, and the winner.

## Stable analytics contract

Both providers emit the same event names and fields:

- `ad_load_request`, `ad_load_result`
- `ad_position`, optional `ad_bid_result`, followed by exactly one `ad_impression` or `ad_show_fail`
- `ad_click`, `ad_close`, `ad_paid`, and `ad_reward_earned`

Every event includes `mediation_mode=admob|topon|bidding`. `ad_platform` continues to identify the
provider that owns that event, so bidding winners remain attributable without mixing bidding-mode
failures into standalone-provider reports.

Business eligibility and frequency rules are evaluated before calling this module. Every admitted
show call emits `ad_position`. In bidding mode, the winning provider emits it immediately before its
show call. Its session first emits `ad_position`, then `ad_bid_result`, and only then invokes the
winning SDK. When neither provider has an ad, the deterministic AdMob fallback session emits
`ad_position`, `ad_bid_result(result=no_candidate)`, and
`ad_show_fail(reason=no_preloaded_ad)`. A pre-bidding failure such as unavailable consent emits the
original `ad_position` and `ad_show_fail` pair without a bid result. The module does not emit
`condition_not_met` or `condition_check_failed` because an ineligible business opportunity is not
an ad show attempt.

`ad_bid_result` uses the same `session_id` as the surrounding show events. Its flat analytics
fields are `result`, `winner_platform`, `admob_available`,
`topon_available`, `admob_price_available`, `topon_price_available`, `admob_value`, `topon_value`,
`winning_value`, `currency`,
`admob_ad_unit_id`, and `topon_ad_unit_id`. Values are USD per impression. Every bid result has a
preceding position event; positions rejected before bidding intentionally have no bid result.
When a cached ad exposes no valid price, its price-availability flag is `false` and its value field
is omitted rather than reporting a misleading zero. A genuine zero price remains present as `0.0`.
When both providers have ads, a known price wins over an unavailable price; two unavailable prices
use the normal AdMob tie-break fallback.

Automatic bidding app-open waits up to seven seconds for both a focused Activity window and at
least one cached app-open ad. It then either runs the normal auction and show path, or closes the
opportunity with `ad_bid_result` followed by `ad_show_fail`; timeout and background paths never end
silently.

`ad_type`, `position`, `session_id`, `ad_unit_id`, and `number` retain their previous
meaning. `ad_platform` is `admob` or `topon`. TopOn placement IDs populate `ad_unit_id` so existing
warehouse columns remain usable.

The provider revenue callback emits `ad_paid`. AdMob keeps the original `value_micros` required by
Tenjin's AdMob ILRD API. TopOn converts its decimal publisher revenue to micros without a floating
point round-trip. In addition, TopOn forwards the original `TUAdInfo` through
the same `AdRevenueListener` used by AdMob, allowing the host to route each typed payload to
Tenjin's matching ILRD API without adding Tenjin as an ads-module dependency. The stable `ad_paid`
event is still emitted for observability, but revenue delivery no longer depends on that event.

## Consent

UMP is a provider-independent gate owned by the common `Ads` facade. It waits for a resumed
Activity, refreshes consent on every process launch, shows a required form, and initializes the
selected provider only after UMP reports that ads may be requested. This ordering applies equally
to direct AdMob and TopOn, because TopOn can initialize its GMA Next-Gen adapter. The host exposes
`Ads.showPrivacyOptions()` when `Ads.isPrivacyOptionsRequired` is true, regardless of provider.

The module does not override TopOn's personalized-ad or GDPR upload settings; TopOn uses its SDK
defaults after the common UMP gate allows ad requests.

## App-open behavior

Fixed providers own foreground detection and a seven-second eligibility window. In bidding mode the
common facade owns that window so only one provider can win the app-open opportunity. TopOn splash
ads are rendered into a temporary full-screen container attached to the resumed Activity and the
container is removed on dismissal or failure. Interstitial and rewarded ads preload again after
consumption.

The compatibility facade is available at `com.cashcraft.ads.mediation.admob.AdMobAds`, but new code
should use `com.cashcraft.ads.mediation.Ads`.

## Verification and publishing

`./gradlew testDebugUnitTest lintDebug :r8-smoke-app:assembleRelease publishToMavenLocal` runs unit
tests and lint, builds a minified smoke app that exercises both provider paths, and verifies the
published Maven artifacts locally. Tags matching `v*` publish the corresponding release version
through `.github/workflows/publish.yml`.
