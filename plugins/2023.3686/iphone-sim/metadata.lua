local metadata =
{
	plugin =
	{
		format = 'staticLibrary',
		staticLibs = { 'plugin_admob', },
		frameworks = { 'GoogleMobileAds', 'FirebaseAnalytics', 'FirebaseAnalyticsSwift', 'FirebaseCore','FirebaseCoreInternal', 'FirebaseInstallations','GoogleAppMeasurement', 'GoogleAppMeasurementIdentitySupport', 'GoogleUtilities','nanopb', 'FBLPromises', 'UserMessagingPlatform' },
		frameworksOptional = { 'AppTrackingTransparency', 'JavascriptCore','SwiftUI' },
		usesSwift=true,
	},
}

return metadata
