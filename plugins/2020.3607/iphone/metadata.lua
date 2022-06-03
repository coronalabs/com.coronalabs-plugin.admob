local metadata =
{
	plugin =
	{
		format = 'staticLibrary',
		staticLibs = { 'plugin_admob', },
		frameworks = { 'GoogleMobileAds', 'UserMessagingPlatform', 'FirebaseAnalytics', 'FirebaseCore','FirebaseCoreDiagnostics', 'FirebaseInstallations','GoogleAppMeasurement', 'GoogleAppMeasurementIdentitySupport', 'GoogleDataTransport', 'GoogleUtilities','nanopb', 'PromisesObjC' },
		frameworksOptional = { 'AppTrackingTransparency', 'JavascriptCore', },
	},
}

return metadata
