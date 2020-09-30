local metadata =
{
	plugin =
	{
		format = 'staticLibrary',
		staticLibs = { 'plugin_admob', },
		frameworks = { 'GoogleAppMeasurement', 'GoogleMobileAds', 'FBLPromises', 'GoogleUtilities', 'nanopb', 'JavascriptCore'},
		frameworksOptional = {},
	},
}

return metadata
