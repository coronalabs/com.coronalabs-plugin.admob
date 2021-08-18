local metadata =
{
	plugin =
	{
		format = 'staticLibrary',
		staticLibs = { 'plugin_admob', },
		frameworks = { 'GoogleAppMeasurement', 'GoogleMobileAds', 'FBLPromises', 'GoogleUtilities', 'nanopb', },
		frameworksOptional = { 'AppTrackingTransparency', 'JavascriptCore', },
	},
}

return metadata
