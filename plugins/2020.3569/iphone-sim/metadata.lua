local metadata =
{
	plugin =
	{
		format = "staticLibrary",

		-- This is the name without the 'lib' prefix.
		-- In this case, the static library is called: libSTATIC_LIB_NAME.a
		staticLibs = { "AdMobPlugin" }, 

		frameworks = { "CoreTelephony", 'GoogleAppMeasurement', 'GoogleMobileAds', 'GoogleUtilities', 'nanopb', },
		frameworksOptional = { "AdSupport" },
	}
}

return metadata
