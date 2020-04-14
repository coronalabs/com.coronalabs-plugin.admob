-- AdMob plugin

local Library = require "CoronaLibrary"

-- Create library
local lib = Library:new{ name="plugin.admob", publisherId="com.coronalabs", version=1 }

-------------------------------------------------------------------------------
-- BEGIN
-------------------------------------------------------------------------------

-- This sample implements the following Lua:
-- 
--    local admob = require "plugin.admob"
--    admob.init()
--    

local function showWarning(functionName)
    print( functionName .. " WARNING: The AdMob plugin is only supported on Android & iOS devices. Please build for device")
end

function lib.init()
    showWarning("admob.init()")
end

function lib.load()
    showWarning("admob.load()")
end

function lib.isLoaded()
    showWarning("admob.isLoaded()")
end

function lib.show()
    showWarning("admob.show()")
end

function lib.hide()
    showWarning("admob.hide()")
end

function lib.height()
    showWarning("admob.height()")
end

function lib.setVideoAdVolume()
    showWarning("admob.setVideoAdVolume()")
end

-------------------------------------------------------------------------------
-- END
-------------------------------------------------------------------------------

-- Return an instance
return lib
