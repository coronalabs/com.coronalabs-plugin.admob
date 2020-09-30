--
--  main.lua
--  AdMob Sample App
--
--  Copyright (c) 2016 Corona Labs Inc. All rights reserved.
--

local _, admob = pcall(require, "plugin.admob")
local widget = require("widget")
local json = require("json")

local appStatus = {
  customYTest = false,                -- adds UI elements to test custom Y positioning
  useAndroidImmersive = false         -- sets android ui visibility to immersiveSticky to test hidden UI bar
}

--------------------------------------------------------------------------
-- set up UI
--------------------------------------------------------------------------

display.setStatusBar( display.HiddenStatusBar )
display.setDefault( "background", 1 )
if appStatus.useAndroidImmersive then
  native.setProperty( "androidSystemUiVisibility", "immersiveSticky")
end

local admobLogo = display.newImage( "admoblogo.png" )
admobLogo.anchorY = 0
admobLogo:scale( 0.5, 0.5 )

local setRed = function(self)
  self:setFillColor(1,0,0)
end

local setGreen = function(self)
  self:setFillColor(0,1,0)
end

local r1
local r2

if (appStatus.customYTest) then
  r1 = display.newRect(0,0,50,50)
  r1.anchorX, r1.anchorY = 0, 0
  setRed(r1)
  r2 = display.newRect(0,0,50,50)
  r2.anchorX, r2.anchorY = 1, 1
  setRed(r2)
end

local subTitle = display.newText {
  text = "plugin for Corona SDK",
  font = display.systemFont,
  fontSize = 14
}
subTitle:setTextColor( 0.2, 0.2, 0.2 )

eventDataTextBox = native.newTextBox( display.contentCenterX, display.contentHeight - 50, display.contentWidth - 10, 150)
eventDataTextBox.placeholder = "Event data will appear here"
eventDataTextBox.hasBackground = false

local processEventTable = function(event)
  local logString = json.prettify(event):gsub("\\","")
  logString = "\nPHASE: "..event.phase.." - - - - - - - - - \n" .. logString
  print(logString)
  eventDataTextBox.text = logString .. eventDataTextBox.text
end

-- --------------------------------------------------------------------------
-- -- plugin implementation
-- --------------------------------------------------------------------------

-- forward declarations
local appId = "n/a"
local adUnits = {}
local platformName = system.getInfo("platformName")
local testMode = true
local testModeButton
local showTestWarning = true
local iReady
local bReady
local rReady
local bannerLine
local oldOrientation

if platformName == "Android" then
  appId = "ca-app-pub-7897780601981890~1957125968"
  adUnits = {
    interstitial="ca-app-pub-7897780601981890/4910592366",
    rewardedVideo="ca-app-pub-7897780601981890/6354495960",
    banner="ca-app-pub-7897780601981890/3433859164"
  }
elseif platformName == "iPhone OS" then
  appId = "ca-app-pub-7897780601981890~3573459965"
  adUnits = {
    interstitial="ca-app-pub-7897780601981890/6526926360",
    rewardedVideo="ca-app-pub-7897780601981890/8003659567",
    banner="ca-app-pub-7897780601981890/5050193163"
  }
else
  print "Unsupported platform"
end

print("App ID: "..appId)
print("Ad Units: "..json.prettify(adUnits))

local admobListener = function(event)
  processEventTable(event)

  if (event.phase == "loaded") then
    if (event.type == "interstitial") then
      setGreen(iReady)
    elseif (event.type == "rewardedVideo") then
      setGreen(rReady)
    elseif (event.type == "banner") then
      setGreen(bReady)
    end
  end
end

-- initialize AdMob
if admob then admob.init(admobListener, {appId=appId, testMode=true, videoAdVolume = 0.1}) end

testModeButton = widget.newButton {
  label = "Test mode: ON",
  width = 175,
  height = 40,
  labelColor = { default={ 0, 0, 0 }, over={ 0.7, 0.7, 0.7 } },
  onRelease = function(event)
    testMode = not testMode

    setRed(iReady)
    setRed(bReady)

    if testMode then
      testModeButton:setLabel("Test mode: ON")
    else
      testModeButton:setLabel("Test mode: OFF")
      if showTestWarning then
        showTestWarning = false
        native.showAlert(
        "WARNING!",
        "Do not click on or interact with live ads!\n"..
        "Doing so may cause Google to suspend our AdMob account.",
        { "Got it!" }
      )
      end
    end
  end
}

local interstitialBG = display.newRect(0,0,320,30)

local interstitialLabel = display.newText {
  text = "INTERSTITIAL",
  font = display.systemFontBold,
  fontSize = 18,
}
interstitialLabel:setTextColor(1)

local rewardedLabel = display.newText {
  text = "REWARDED",
  font = display.systemFontBold,
  fontSize = 18,
}
rewardedLabel:setTextColor(1)

local loadInterstitialButton = widget.newButton {
  label = "Load",
  width = 65,
  height = 40,
  labelColor = { default={ 0, 0, 0 }, over={ 0.7, 0.7, 0.7 } },
  onRelease = function(event)
    setRed(iReady)
    admob.load("interstitial", {
      adUnitId=adUnits.interstitial,
      testMode=testMode,
      keywords={"games", "platformer", "toys"}
    })
  end
}

local showInterstitialButton = widget.newButton {
  label = "Show",
  width = 65,
  height = 40,
  labelColor = { default={ 0, 0, 0 }, over={ 0.7, 0.7, 0.7 } },
  onRelease = function(event)
    setRed(iReady)
    admob.show("interstitial")
  end
}

local loadRewardedButton = widget.newButton {
  label = "Load",
  width = 65,
  height = 40,
  labelColor = { default={ 0, 0, 0 }, over={ 0.7, 0.7, 0.7 } },
  onRelease = function(event)
    admob.setVideoAdVolume( 0.0 )
    setRed(rReady)
    admob.load("rewardedVideo", {
      adUnitId=adUnits.rewardedVideo,
      testMode=testMode,
      keywords={"games", "platformer", "toys"}
    })
  end
}

local showRewardedButton = widget.newButton {
  label = "Show",
  width = 65,
  height = 40,
  labelColor = { default={ 0, 0, 0 }, over={ 0.7, 0.7, 0.7 } },
  onRelease = function(event)
    setRed(rReady)
    admob.show("rewardedVideo")
  end
}

local bannerBG = display.newRect(0,0,320,30)
bannerBG:setFillColor(1,0,0,0.7)

local bannerLabel = display.newText {
  text = "B A N N E R",
  font = display.systemFontBold,
  fontSize = 18,
}
bannerLabel:setTextColor(1)

local loadBannerButton = widget.newButton {
  label = "Load",
  width = 100,
  height = 40,
  labelColor = { default={ 0, 0, 0 }, over={ 0.7, 0.7, 0.7 } },
  onRelease = function(event)
    setRed(bReady)
    admob.load("banner", {adUnitId=adUnits.banner, testMode=testMode, designedForFamilies=true, childSafe=true, hasUserConsent=false})
  end
}

local hideBannerButton = widget.newButton {
  label = "Hide",
  width = 100,
  height = 40,
  labelColor = { default={ 0, 0, 0 }, over={ 0.7, 0.7, 0.7 } },
  onRelease = function(event)
    admob.hide()
  end
}

local showBannerButtonT = widget.newButton {
  label = "Top",
  fontSize = 14,
  width = 100,
  height = 40,
  labelColor = { default={ 0, 0, 0 }, over={ 0.7, 0.7, 0.7 } },
  onRelease = function(event)
    if appStatus.customYTest then
      admob.show("banner", {y=50, bgColor="#444444"})
    else
      admob.show("banner", {y="top", bgColor="#444444"})
    end
  end
}

local showBannerButtonB = widget.newButton {
  label = "Bottom",
  fontSize = 14,
  width = 100,
  height = 40,
  labelColor = { default={ 0, 0, 0 }, over={ 0.7, 0.7, 0.7 } },
  onRelease = function(event)
    if appStatus.customYTest then
      admob.show("banner", {y=-50, bgColor="#444444" })
    else
      admob.show("banner", {y="bottom", bgColor="#444444" })
    end
  end
}

local showBannerButtonBLine = widget.newButton {
  label = "Under Line",
  fontSize = 14,
  width = 100,
  height = 40,
  labelColor = { default={ 0, 0, 0 }, over={ 0.7, 0.7, 0.7 } },
  onRelease = function(event)
    admob.show("banner", {y=72+math.abs(display.screenOriginY), bgColor="#444444"})
  end
}


iReady = display.newCircle(10, 10, 6)
iReady.strokeWidth = 2
iReady:setStrokeColor(0)
setRed(iReady)

bReady = display.newCircle(10, 10, 6)
bReady.strokeWidth = 2
bReady:setStrokeColor(0)
setRed(bReady)

rReady = display.newCircle(10, 10, 6)
rReady.strokeWidth = 2
rReady:setStrokeColor(0)
setRed(rReady)

-- --------------------------------------------------------------------------
-- -- device orientation handling
-- --------------------------------------------------------------------------

local layoutDisplayObjects = function(orientation)
  admobLogo.x, admobLogo.y = display.contentCenterX, 0

  if (appStatus.customYTest) then
    r1.x = display.screenOriginX
    r1.y = display.screenOriginY
    r2.x = display.actualContentWidth + display.screenOriginX
    r2.y = display.actualContentHeight + display.screenOriginY
  end

  subTitle.x = display.contentCenterX
  subTitle.y = 60

  bannerLine = display.newLine( display.screenOriginX, 72, display.actualContentWidth, 72)
  bannerLine.strokeWidth = 2
  bannerLine:setStrokeColor(1,0,0)

  if (orientation == "portrait") then
    eventDataTextBox.x = display.contentCenterX
    eventDataTextBox.y = display.contentHeight - 50
    eventDataTextBox.width = display.contentWidth - 10
  else
    -- put it waaaay offscreen
    eventDataTextBox.y = 2000
  end

  testModeButton.x = display.contentCenterX
  testModeButton.y = 95

  interstitialBG.x, interstitialBG.y = display.contentCenterX, 140
  interstitialBG:setFillColor(1,0,0,0.7)

  interstitialLabel.x = display.contentCenterX - 70
  interstitialLabel.y = 140

  iReady.x = display.contentCenterX - 140
  iReady.y = 140
  setRed(iReady)

  loadInterstitialButton.x = display.contentCenterX - 120
  loadInterstitialButton.y = interstitialLabel.y + 40

  showInterstitialButton.x = display.contentCenterX - 50
  showInterstitialButton.y = interstitialLabel.y + 40

  rewardedLabel.x = display.contentCenterX + 80
  rewardedLabel.y = 140

  rReady.x = display.contentCenterX + 140
  rReady.y = 140
  setRed(rReady)

  loadRewardedButton.x = display.contentCenterX + 50
  loadRewardedButton.y = rewardedLabel.y + 40

  showRewardedButton.x = display.contentCenterX + 120
  showRewardedButton.y = rewardedLabel.y + 40

  bannerBG.x, bannerBG.y = display.contentCenterX, 220

  bannerLabel.x = display.contentCenterX
  bannerLabel.y = 220

  bReady.x = display.contentCenterX + 140
  bReady.y = 220
  setRed(bReady)

  loadBannerButton.x = display.contentCenterX - 50
  loadBannerButton.y = bannerLabel.y + 40

  hideBannerButton.x = display.contentCenterX + 50
  hideBannerButton.y = bannerLabel.y + 40

  showBannerButtonB.x = display.contentCenterX
  showBannerButtonB.y = bannerLabel.y + 80

  showBannerButtonT.x = display.contentCenterX - 100
  showBannerButtonT.y = bannerLabel.y + 80

  showBannerButtonBLine.x = display.contentCenterX + 100
  showBannerButtonBLine.y = bannerLabel.y + 80
end

local onOrientationChange = function(event)
  local eventType = event.type
  local orientation = eventType:starts("landscape") and "landscape" or eventType

  if (orientation == "portrait") or (orientation == "landscape") then
    if (oldOrientation == nil) then
      oldOrientation = orientation
    else
      if (orientation ~= oldOrientation) then
        oldOrientation = orientation
        admob.hide()
        layoutDisplayObjects(eventType)
      end
    end
  end
end

Runtime:addEventListener("orientation", onOrientationChange)

-- initial layout
layoutDisplayObjects(system.orientation)
