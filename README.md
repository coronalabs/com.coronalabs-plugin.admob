Sources for the plugin `plugin.admob`.

Add following to your `build.settings` to use:
```lua
{
    plugins = {
        "plugin.admob" = {
            publisherId = "com.coronalabs",
        },
    },
}
```

To use this plugin with Solar2D offline builds, modify build.settings to:

```lua
{
    plugins = {
        "plugin.admob" = {
            publisherId = "com.coronalabs",
            supportedPlatforms = {
                android = { url="https://github.com/coronalabs/com.coronalabs-plugin.admob/releases/download/v1/2020.3569-android.tgz" },
                iphone = { url="https://github.com/coronalabs/com.coronalabs-plugin.admob/releases/download/v1/2020.3569-iphone.tgz" },
                
                
            }
        },
    },
}
```

Choose the plugin version you want to use from the releases page: 
https://github.com/coronalabs/com.coronalabs-plugin.admob/releases
