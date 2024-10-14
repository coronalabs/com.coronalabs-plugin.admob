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
local riReady
local aReady
local fReady
local bannerLine
local oldOrientation

if platformName == "Android" then
  appId = "ca-app-pub-7897780601981890~1957125968"
  adUnits = {
    interstitial="ca-app-pub-3940256099942544/1033173712",
    rewardedVideo="ca-app-pub-3940256099942544/5224354917",
    banner="ca-app-pub-3940256099942544/6300978111",
    appOpen="ca-app-pub-3940256099942544/3419835294",
    rewardedInterstitial="ca-app-pub-3940256099942544/5354046379"
  }
elseif platformName == "iPhone OS" then
  appId = "ca-app-pub-3940256099942544~1458002511"
  adUnits = {
    interstitial="ca-app-pub-3940256099942544/4411468910",
    rewardedVideo="ca-app-pub-3940256099942544/1712485313",
    banner="ca-app-pub-3940256099942544/2435281174",
    appOpen="ca-app-pub-3940256099942544/5575463023",
    rewardedInterstitial="ca-app-pub-3940256099942544/6978759866"
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
    elseif (event.type == "rewardedInterstitial") then
      setGreen(riReady)
    elseif (event.type == "appOpen") then
      setGreen(aReady)
    elseif (event.type == "ump") then
      setGreen(fReady)
    end
  end
end

-- initialize AdMob
if admob then admob.init(admobListener, {appId=appId, testMode=true, videoAdVolume = 0.1}) end

-- Set UMP Form for debug
if admob then admob.updateConsentForm ({ underage=true ,debug= { geography = "EEA", testDeviceIdentifiers={"37043AAA-6436-57F6-A5DF-3E12E4018E80"} } }) end


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
      keywords={"games", "platformer", "toys"},
      maxAdContentRating="M",
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


local appOpenBG = display.newRect(0,0,320,30)

local appOpenLabel = display.newText {
  text = "APP OPEN",
  font = display.systemFontBold,
  fontSize = 18,
}
appOpenLabel:setTextColor(1)

local loadAppOpenButton = widget.newButton {
  label = "Load",
  width = 100,
  height = 40,
  labelColor = { default={ 0, 0, 0 }, over={ 0.7, 0.7, 0.7 } },
  onRelease = function(event)
    setRed(bReady)
    admob.load("appOpen", {adUnitId=adUnits.appOpen, testMode=testMode})
  end
}
local showAppOpenButton = widget.newButton {
  label = "Show",
  width = 100,
  height = 40,
  labelColor = { default={ 0, 0, 0 }, over={ 0.7, 0.7, 0.7 } },
  onRelease = function(event)
    admob.show("appOpen")
  end
}

local riLabel = display.newText {
  text = "REWARD INTER",
  font = display.systemFontBold,
  fontSize = 18,
}
riLabel:setTextColor(1)

local loadRIButton = widget.newButton {
  label = "Load",
  width = 100,
  height = 40,
  labelColor = { default={ 0, 0, 0 }, over={ 0.7, 0.7, 0.7 } },
  onRelease = function(event)
    setRed(bReady)
    admob.load("rewardedInterstitial", {adUnitId=adUnits.rewardedInterstitial, testMode=testMode})
  end
}
local showRIButton = widget.newButton {
  label = "Show",
  width = 100,
  height = 40,
  labelColor = { default={ 0, 0, 0 }, over={ 0.7, 0.7, 0.7 } },
  onRelease = function(event)
    admob.show("rewardedInterstitial")
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
    admob.load("banner", {adUnitId=adUnits.banner})
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

local formLabel = display.newText {
  text = "F O R M",
  font = display.systemFontBold,
  fontSize = 18,
}
formLabel:setTextColor(1)

local loadFormButton = widget.newButton {
  label = "Load Form",
  fontSize = 14,
  width = 100,
  height = 40,
  labelColor = { default={ 0, 0, 0 }, over={ 0.7, 0.7, 0.7 } },
  onRelease = function(event)
    admob.loadConsentForm()
  end
}

local showFormButton = widget.newButton {
  label = "Show Form",
  fontSize = 14,
  width = 100,
  height = 40,
  labelColor = { default={ 0, 0, 0 }, over={ 0.7, 0.7, 0.7 } },
  onRelease = function(event)
    admob.showConsentForm()
  end
}

local getStatusFormButton = widget.newButton {
  label = "Print Consent Status",
  fontSize = 14,
  width = 100,
  height = 40,
  labelColor = { default={ 0, 0, 0 }, over={ 0.7, 0.7, 0.7 } },
  onRelease = function(event)
    local formStatus, consentStatus = admob.getConsentFormStatus()
    logString = "\nForm Status: - - - - - - - - - \n" .. "Form Status:".. formStatus .."  Consent Status: "..consentStatus
    eventDataTextBox.text = logString .. eventDataTextBox.text
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

aReady = display.newCircle(10, 10, 6)
aReady.strokeWidth = 2
aReady:setStrokeColor(0)
setRed(aReady)

riReady = display.newCircle(10, 10, 6)
riReady.strokeWidth = 2
riReady:setStrokeColor(0)
setRed(riReady)

rReady = display.newCircle(10, 10, 6)
rReady.strokeWidth = 2
rReady:setStrokeColor(0)
setRed(rReady)

fReady = display.newCircle(10, 10, 6)
fReady.strokeWidth = 2
fReady:setStrokeColor(0)
setRed(fReady)

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
  testModeButton.y = 80

  interstitialBG.x, interstitialBG.y = display.contentCenterX, 110
  interstitialBG:setFillColor(1,0,0,0.7)

  interstitialLabel.x = display.contentCenterX - 70
  interstitialLabel.y = 110

  iReady.x = display.contentCenterX - 140
  iReady.y = 110
  setRed(iReady)

  loadInterstitialButton.x = display.contentCenterX - 120
  loadInterstitialButton.y = interstitialLabel.y + 30

  showInterstitialButton.x = display.contentCenterX - 50
  showInterstitialButton.y = interstitialLabel.y + 30

  appOpenBG.x, appOpenBG.y = display.contentCenterX, 180
  appOpenBG:setFillColor(1,0,0,0.7)

  appOpenLabel.x, appOpenLabel.y =  display.contentCenterX - 70, appOpenBG.y

  aReady.x = display.contentCenterX - 140
  aReady.y = 180
  setRed(aReady)

  loadAppOpenButton.x, loadAppOpenButton.y = display.contentCenterX - 120, appOpenLabel.y + 30
  showAppOpenButton.x, showAppOpenButton.y = display.contentCenterX - 50, appOpenLabel.y + 30

  riLabel.x, riLabel.y =  display.contentCenterX + 60, appOpenBG.y

  loadRIButton.x, loadRIButton.y =  display.contentCenterX + 50, riLabel.y + 30
  showRIButton.x, showRIButton.y =  display.contentCenterX + 120, riLabel.y + 30

  riReady.x = display.contentCenterX + 140
  riReady.y = appOpenBG.y
  setRed(rReady)

  rewardedLabel.x = display.contentCenterX + 80
  rewardedLabel.y = 110

  rReady.x = display.contentCenterX + 140
  rReady.y = 110
  setRed(rReady)

  loadRewardedButton.x = display.contentCenterX + 50
  loadRewardedButton.y = rewardedLabel.y + 30

  showRewardedButton.x = display.contentCenterX + 120
  showRewardedButton.y = rewardedLabel.y + 30

  bannerBG.x, bannerBG.y = display.contentCenterX, 260

  bannerLabel.x = display.contentCenterX + 80
  bannerLabel.y = 260

  bReady.x = display.contentCenterX + 140
  bReady.y = 260
  setRed(bReady)

  loadBannerButton.x = display.contentCenterX + 50
  loadBannerButton.y = bannerLabel.y + 30

  hideBannerButton.x = display.contentCenterX + 120
  hideBannerButton.y = bannerLabel.y + 30

  showBannerButtonB.x = display.contentCenterX + 70
  showBannerButtonB.y = bannerLabel.y + 50

  showBannerButtonT.x = display.contentCenterX + 20
  showBannerButtonT.y = bannerLabel.y + 50

  showBannerButtonBLine.x = display.contentCenterX + 130
  showBannerButtonBLine.y = bannerLabel.y + 50
  
  formLabel.x, formLabel.y = display.contentCenterX - 80, 260
  
  loadFormButton.x, loadFormButton.y = display.contentCenterX - 30, 290
  
  showFormButton.x, showFormButton.y = display.contentCenterX - 110, 290
  
  getStatusFormButton.x, getStatusFormButton.y = display.contentCenterX - 80, 310
  
  fReady.x, fReady.y = display.contentCenterX - 140, 260
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
