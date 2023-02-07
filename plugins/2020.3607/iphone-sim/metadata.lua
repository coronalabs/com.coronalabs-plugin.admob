local metadata =
{
	plugin =
	{
		format = 'staticLibrary',
		staticLibs = { 'plugin_admob', },
		frameworks = { 'GoogleMobileAds', 'FirebaseAnalytics', 'FirebaseAnalyticsSwift', 'FirebaseCore','FirebaseCoreInternal', 'FirebaseInstallations','GoogleAppMeasurement', 'GoogleAppMeasurementIdentitySupport', 'GoogleUtilities','nanopb', 'FBLPromises' },
		frameworksOptional = { 'AppTrackingTransparency', 'JavascriptCore', },
		usesSwift=true,
	},
}

return metadata
