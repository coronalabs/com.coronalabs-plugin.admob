local metadata =
{
	plugin =
	{
		format = 'staticLibrary',
		staticLibs = { 'plugin_admob', },
		frameworks = { 'GoogleAppMeasurement', 'GoogleMobileAds', 'FBLPromises', 'GoogleUtilities', 'nanopb', 'PromisesObjC', 'UserMessagingPlatform', "JavaScriptCore" },
		frameworksOptional = { 'AppTrackingTransparency', },
	},
}

return metadata
