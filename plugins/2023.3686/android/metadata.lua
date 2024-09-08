local metadata =
{
    plugin =
    {
        format = 'jar',
        manifest =
        {
            permissions = {},
            usesPermissions =
            {
                "android.permission.INTERNET",
            },
            usesFeatures =
            {
            },
            applicationChildElements =
            {
                [[
                    <property
                    android:name="android.adservices.AD_SERVICES_CONFIG" 
                    android:resource="@xml/gma_ad_services_config"
                    tools:replace="android:resource"/>
                ]],
            }
        }
    },

    coronaManifest = {
    }
}

return metadata
