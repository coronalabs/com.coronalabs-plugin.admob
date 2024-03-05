//
// LuaLoader.java
// AdMob Plugin
//
// Copyright (c) 2016 CoronaLabs inc. All rights reserved.
//

// @formatter:off

package plugin.admob;


import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Point;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.ansca.corona.CoronaActivity;
import com.ansca.corona.CoronaEnvironment;
import com.ansca.corona.CoronaLua;
import com.ansca.corona.CoronaLuaEvent;
import com.ansca.corona.CoronaRuntime;
import com.ansca.corona.CoronaRuntimeListener;
import com.ansca.corona.CoronaRuntimeTask;
import com.ansca.corona.CoronaRuntimeTaskDispatcher;
import com.google.ads.mediation.admob.AdMobAdapter;
import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.OnUserEarnedRewardListener;
import com.google.android.gms.ads.RequestConfiguration;
import com.google.android.gms.ads.appopen.AppOpenAd;
import com.google.android.gms.ads.initialization.InitializationStatus;
import com.google.android.gms.ads.initialization.OnInitializationCompleteListener;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import com.google.android.gms.ads.rewarded.RewardItem;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd;
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAdLoadCallback;
import com.google.android.ump.ConsentDebugSettings;
import com.google.android.ump.ConsentForm;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.FormError;
import com.google.android.ump.UserMessagingPlatform;
import com.naef.jnlua.JavaFunction;
import com.naef.jnlua.LuaState;
import com.naef.jnlua.LuaType;
import com.naef.jnlua.NamedJavaFunction;

import org.json.JSONObject;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static java.lang.Math.ceil;

// Plugin imports

/**
 * Implements the Lua interface for the AdMob Plugin.
 * <p>
 * Only one instance of this class will be created by Corona for the lifetime of the application.
 * This instance will be re-used for every new Corona activity that gets created.
 */
@SuppressWarnings({"unused", "RedundantSuppression"})
public class LuaLoader implements JavaFunction, CoronaRuntimeListener {
    private static final String PLUGIN_NAME = "plugin.admob";
    private static final String PLUGIN_VERSION = "3.9.0";
    private static final String PLUGIN_SDK_VERSION = "0";//getVersionString();

    private static final String EVENT_NAME = "adsRequest";
    private static final String PROVIDER_NAME = "admob";

    // ad types
    private static final String TYPE_BANNER = "banner";
    private static final String TYPE_INTERSTITIAL = "interstitial";
    private static final String TYPE_REWARDEDVIDEO = "rewardedVideo";
    private static final String TYPE_REWARDEDINTERSTITIAL = "rewardedInterstitial";
    private static final String TYPE_APPOPEN = "appOpen";
    private static final String TYPE_UMP= "ump";

    // banner alignments
    private static final String ALIGN_TOP = "top";
    private static final String ALIGN_BOTTOM = "bottom";

    // valid ad types
    private static final List<String> validAdTypes = new ArrayList<>();

    // event phases
    private static final String PHASE_INIT = "init";
    private static final String PHASE_DISPLAYED = "displayed";
    private static final String PHASE_REFRESHED = "refreshed";
    private static final String PHASE_HIDDEN = "hidden";
    private static final String PHASE_LOADED = "loaded";
    private static final String PHASE_FAILED = "failed";
    private static final String PHASE_CLOSED = "closed";
    private static final String PHASE_CLICKED = "clicked";
    private static final String PHASE_REWARD = "reward";

    // reward keys
    private static final String REWARD_ITEM = "rewardItem";
    private static final String REWARD_AMOUNT = "rewardAmount";

    // response keys
    private static final String RESPONSE_LOAD_FAILED = "loadFailed";

    // missing Corona Event Keys
    private static final String EVENT_PHASE_KEY = "phase";
    private static final String EVENT_TYPE_KEY = "type";
    private static final String EVENT_DATA_KEY = "data";

    // event data keys
    private static final String DATA_ERRORMSG_KEY = "errorMsg";
    private static final String DATA_ERRORCODE_KEY = "errorCode";
    private static final String DATA_ADUNIT_ID_KEY = "adUnitId";

    // message constants
    private static final String CORONA_TAG = "Corona";
    private static final String ERROR_MSG = "ERROR: ";
    private static final String WARNING_MSG = "WARNING: ";

    private static String functionSignature = "";                             // used in error reporting functions
    private static final Map<String, Object> admobObjects = new HashMap<>();  // keep track of loaded objects

    // object dictionary keys
    private static final String HAS_RECEIVED_INIT_EVENT_KEY = "hasReceivedInitEvent";
    private static final String Y_RATIO_KEY = "yRatio";

    private static int coronaListener = CoronaLua.REFNIL;
    private static CoronaRuntimeTaskDispatcher coronaRuntimeTaskDispatcher = null;

    private static ConsentForm umpForm = null;

    private static void invalidateAllViews() {
        final CoronaActivity activity = CoronaEnvironment.getCoronaActivity();
        if (activity != null) {
            invalidateChildren(activity.getWindow().getDecorView());
        }
    }

    private static void invalidateChildren(View v) {
        if (v instanceof ViewGroup) {
            ViewGroup viewgroup = (ViewGroup) v;
            for (int i = 0; i < viewgroup.getChildCount(); i++) {
                invalidateChildren(viewgroup.getChildAt(i));
            }
        }
        v.invalidate();
    }


    // -------------------------------------------------------
    // Plugin lifecycle events
    // -------------------------------------------------------

    /**
     * <p>
     * Note that a new LuaLoader instance will not be created for every CoronaActivity instance.
     * That is, only one instance of this class will be created for the lifetime of the application process.
     * This gives a plugin the option to do operations in the background while the CoronaActivity is destroyed.
     */
    @SuppressWarnings("unused")
    public LuaLoader() {
        // Set up this plugin to listen for Corona runtime events to be received by methods
        // onLoaded(), onStarted(), onSuspended(), onResumed(), and onExiting().

        CoronaEnvironment.addRuntimeListener(this);
    }

    /**
     * Called when this plugin is being loaded via the Lua require() function.
     * <p>
     * Note that this method will be called every time a new CoronaActivity has been launched.
     * This means that you'll need to re-initialize this plugin here.
     * <p>
     * Warning! This method is not called on the main UI thread.
     *
     * @param L Reference to the Lua state that the require() function was called from.
     * @return Returns the number of values that the require() function will return.
     * <p>
     * Expected to return 1, the library that the require() function is loading.
     */
    @Override
    public int invoke(LuaState L) {
        // Register this plugin into Lua with the following functions.
        NamedJavaFunction[] luaFunctions = new NamedJavaFunction[]{
                new Init(),
                new Load(),
                new IsLoaded(),
                new Show(),
                new Hide(),
                new Height(),
                new SetVideoAdVolume(),
                new UpdateConsentForm(),
                new LoadConsentForm(),
                new ShowConsentForm(),
                new GetConsentFormStatus(),
        };
        String libName = L.toString(1);
        L.register(libName, luaFunctions);

        // Returning 1 indicates that the Lua require() function will return the above Lua library
        return 1;
    }

    /**
     * Called after the Corona runtime has been created and just before executing the "main.lua" file.
     * <p>
     * Warning! This method is not called on the main thread.
     *
     * @param runtime Reference to the CoronaRuntime object that has just been loaded/initialized.
     *                Provides a LuaState object that allows the application to extend the Lua API.
     */
    @Override
    public void onLoaded(CoronaRuntime runtime) {
        // Note that this method will not be called the first time a Corona activity has been launched.
        // This is because this listener cannot be added to the CoronaEnvironment until after
        // this plugin has been required-in by Lua, which occurs after the onLoaded() event.
        // However, this method will be called when a 2nd Corona activity has been created.

        if (coronaRuntimeTaskDispatcher == null) {
            coronaRuntimeTaskDispatcher = new CoronaRuntimeTaskDispatcher(runtime);

            // populate validation lists
            validAdTypes.add(TYPE_INTERSTITIAL);
            validAdTypes.add(TYPE_REWARDEDVIDEO);
            validAdTypes.add(TYPE_REWARDEDINTERSTITIAL);
            validAdTypes.add(TYPE_BANNER);
            validAdTypes.add(TYPE_APPOPEN);
            validAdTypes.add(TYPE_UMP);

            admobObjects.put(HAS_RECEIVED_INIT_EVENT_KEY, false);
        }
    }

    /**
     * Called just after the Corona runtime has executed the "main.lua" file.
     * <p>
     * Warning! This method is not called on the main thread.
     *
     * @param runtime Reference to the CoronaRuntime object that has just been started.
     */
    @Override
    public void onStarted(CoronaRuntime runtime) {
    }

    /**
     * Called just after the Corona runtime has been suspended which pauses all rendering, audio, timers,
     * and other Corona related operations. This can happen when another Android activity (ie: window) has
     * been displayed, when the screen has been powered off, or when the screen lock is shown.
     * <p>
     * Warning! This method is not called on the main thread.
     *
     * @param runtime Reference to the CoronaRuntime object that has just been suspended.
     */
    @Override
    public void onSuspended(CoronaRuntime runtime) {
        final CoronaActivity coronaActivity = CoronaEnvironment.getCoronaActivity();

        if (coronaActivity != null) {
            coronaActivity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    String adUnitId = (String) admobObjects.get(TYPE_BANNER);
                    if (adUnitId != null) {
                        AdView banner = (AdView) admobObjects.get(adUnitId);
                        if (banner != null) {
                            banner.pause();
                        }
                    }
                }
            });
        }
    }

    /**
     * Called just after the Corona runtime has been resumed after a suspend.
     * <p>
     * Warning! This method is not called on the main thread.
     *
     * @param runtime Reference to the CoronaRuntime object that has just been resumed.
     */
    @Override
    public void onResumed(CoronaRuntime runtime) {
        final CoronaActivity coronaActivity = CoronaEnvironment.getCoronaActivity();

        if (coronaActivity != null) {
            coronaActivity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    String adUnitId = (String) admobObjects.get(TYPE_BANNER);
                    if (adUnitId != null) {
                        AdView banner = (AdView) admobObjects.get(adUnitId);
                        if (banner != null) {
                            banner.resume();
                        }
                    }
                }
            });
        }
    }

    /**
     * Called just before the Corona runtime terminates.
     * <p>
     * This happens when the Corona activity is being destroyed which happens when the user presses the Back button
     * on the activity, when the native.requestExit() method is called in Lua, or when the activity's finish()
     * method is called. This does not mean that the application is exiting.
     * <p>
     * Warning! This method is not called on the main thread.
     *
     * @param runtime Reference to the CoronaRuntime object that is being terminated.
     */
    @Override
    public void onExiting(final CoronaRuntime runtime) {
        final CoronaActivity coronaActivity = CoronaEnvironment.getCoronaActivity();

        if (coronaActivity != null) {
            coronaActivity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    // clear the saved ad objects
                    for (String key : admobObjects.keySet()) {
                        Object object = admobObjects.get(key);

                        if (object instanceof AdView) {
                            AdView banner = (AdView) object;
                            //noinspection ConstantConditions
                            banner.setAdListener(null);
                            banner.destroy();
                        } else if (object instanceof CoronaAdmobInterstitialLoadDelegate) {
                            CoronaAdmobInterstitialLoadDelegate interstitial = (CoronaAdmobInterstitialLoadDelegate) object;
                            if (interstitial.interstitialAd != null) {
                                interstitial.interstitialAd.setFullScreenContentCallback(null);
                                interstitial.interstitialAd = null;
                            }
                        } else if (object instanceof CoronaAdmobRewardedLoadDelegate) {
                            CoronaAdmobRewardedLoadDelegate rewardedAd = (CoronaAdmobRewardedLoadDelegate) object;
                            if (rewardedAd.rewardedAd != null) {
                                rewardedAd.rewardedAd.setFullScreenContentCallback(null);
                                rewardedAd.rewardedAd = null;
                            }
                        }
                    }

                    if (runtime != null) {
                        CoronaLua.deleteRef(runtime.getLuaState(), coronaListener);
                    }
                    coronaListener = CoronaLua.REFNIL;

                    admobObjects.clear();
                    validAdTypes.clear();
                    coronaRuntimeTaskDispatcher = null;
                    umpForm = null;
                    functionSignature = "";
                }
            });
        }
    }

    // --------------------------------------------------------------------------
    // helper functions
    // --------------------------------------------------------------------------

    @SuppressWarnings("ALL")
    private String md5(final String s) {
        try {
            MessageDigest digest = java.security.MessageDigest.getInstance("MD5");
            digest.update(s.getBytes());
            byte[] messageDigest = digest.digest();

            StringBuffer hexString = new StringBuffer();
            for (int i = 0; i < messageDigest.length; i++) {
                String h = Integer.toHexString(0xFF & messageDigest[i]);
                while (h.length() < 2) {
                    h = "0" + h;
                }
                hexString.append(h);
            }

            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            Log.e(CORONA_TAG, "Can't generate md5 hash");
        }

        return "";
    }

    // log message to console
    private void logMsg(String msgType, String errorMsg) {
        String functionID = functionSignature;
        if (!functionID.isEmpty()) {
            functionID += ", ";
        }

        Log.i(CORONA_TAG, msgType + functionID + errorMsg);
    }

    private boolean isSDKInitialized() {
        // check to see if SDK is properly initialized
        if (coronaListener == CoronaLua.REFNIL) {
            logMsg(ERROR_MSG, "admob.init() must be called before calling other API functions");
            return false;
        }

        if (!(boolean) admobObjects.get(HAS_RECEIVED_INIT_EVENT_KEY)) {
            logMsg(ERROR_MSG, "You must wait for the 'init' event before calling other API functions");
            return false;
        }

        return true;
    }

    /**
     * getMetadata for App to check
     */
    public static Bundle getMetadata() {
        try {
            return CoronaEnvironment.getCoronaActivity().getPackageManager()
                    .getApplicationInfo(CoronaEnvironment.getCoronaActivity().getPackageName(), PackageManager.GET_META_DATA)
                    .metaData;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    // dispatch a Lua event to our callback (dynamic handling of properties through map)
    private void dispatchLuaEvent(final Map<String, Object> event) {
        if (coronaRuntimeTaskDispatcher != null) {
            coronaRuntimeTaskDispatcher.send(new CoronaRuntimeTask() {
                @Override
                public void executeUsing(CoronaRuntime runtime) {
                    try {
                        LuaState L = runtime.getLuaState();
                        CoronaLua.newEvent(L, EVENT_NAME);
                        boolean hasErrorKey = false;

                        // add event parameters from map
                        for (String key : event.keySet()) {
                            CoronaLua.pushValue(L, event.get(key));           // push value
                            L.setField(-2, key);                              // push key

                            if (!hasErrorKey) {
                                hasErrorKey = key.equals(CoronaLuaEvent.ISERROR_KEY);
                            }
                        }

                        // add error key if not in map
                        if (!hasErrorKey) {
                            L.pushBoolean(false);
                            L.setField(-2, CoronaLuaEvent.ISERROR_KEY);
                        }

                        // add provider
                        L.pushString(PROVIDER_NAME);
                        L.setField(-2, CoronaLuaEvent.PROVIDER_KEY);

                        CoronaLua.dispatchEvent(L, coronaListener, 0);
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
            });
        }
    }

    // -------------------------------------------------------
    // plugin implementation
    // -------------------------------------------------------

    // [Lua] init(listener, options)
    private class Init implements NamedJavaFunction {
        /**
         * Gets the name of the Lua function as it would appear in the Lua script.
         *
         * @return Returns the name of the custom Lua function.
         */
        @Override
        public String getName() {
            return "init";
        }

        /**
         * This method is called when the Lua function is called.
         * <p>
         * Warning! This method is not called on the main UI thread.
         *
         * @param luaState Reference to the Lua state.
         *                 Needed to retrieve the Lua function's parameters and to return values back to Lua.
         * @return Returns the number of values to be returned by the Lua function.
         */
        @Override
        public int invoke(final LuaState luaState) {
            functionSignature = "admob.init(listener, options)";

            //Disable ads on Kindle if no AdMob app ID is found to prevent crash
            //This is only on Kindle because this use not work on Kindles could crash apps
            boolean isAmazon = false;
            luaState.getGlobal("store");
            if (luaState.isTable(-1)) {
                luaState.getField(-1, "target");
                if(luaState.isString(-1)){
                    if(luaState.toString(-1).equals("amazon")){
                        isAmazon = true;
                    }
                }
                luaState.pop(1);
            }
            luaState.pop(1);
            if(!getMetadata().containsKey("com.google.android.gms.ads.APPLICATION_ID") && isAmazon){
                logMsg(WARNING_MSG, "No Ads APPLICATION_ID in metadata found for Kindle/Amazon, so skipping init");
                return 0;
            }

            // prevent init from being called twice
            if (coronaListener != CoronaLua.REFNIL) {
                logMsg(WARNING_MSG, "init() should only be called once");
                return 0;
            }

            // check number of args
            int nargs = luaState.getTop();
            if (nargs != 2) {
                logMsg(ERROR_MSG, "Expected 2 arguments, got " + nargs);
                return 0;
            }

            boolean testMode = false;
            double videoAdVolume = 1.0;

            // Get the listener (required)
            if (CoronaLua.isListener(luaState, 1, PROVIDER_NAME)) {
                coronaListener = CoronaLua.newRef(luaState, 1);
            } else {
                logMsg(ERROR_MSG, "Listener expected, got: " + luaState.typeName(1));
                return 0;
            }

            // check for options table (required)
            if (luaState.type(2) == LuaType.TABLE) {
                // traverse and validate all the options
                for (luaState.pushNil(); luaState.next(2); luaState.pop(1)) {
                    String key = luaState.toString(-2);

                    // check for appId (required)
                    switch (key) {
                        case "appId":
                            logMsg(WARNING_MSG, "AppId is ignored and should be in build.settings");
                            break;
                        case "testMode":
                            if (luaState.type(-1) == LuaType.BOOLEAN) {
                                testMode = luaState.toBoolean(-1);
                            } else {
                                logMsg(ERROR_MSG, "options.testMode (boolean) expected, got " + luaState.typeName(-1));
                                return 0;
                            }
                            break;
                        case "videoAdVolume":
                            if (luaState.type(-1) == LuaType.NUMBER) {
                                videoAdVolume = luaState.toNumber(-1);
                            } else {
                                logMsg(ERROR_MSG, "options.videoAdVolume (number) expected, got " + luaState.typeName(-1));
                                return 0;
                            }
                            break;
                        default:
                            logMsg(ERROR_MSG, "Invalid option '" + key + "'");
                            return 0;
                    }
                }
            } else {
                logMsg(ERROR_MSG, "options table expected, got " + luaState.typeName(2));
                return 0;
            }

            // declare final variables for inner loop
            final CoronaActivity coronaActivity = CoronaEnvironment.getCoronaActivity();
            final double fVideoAdVolume = videoAdVolume;

            List<String> testDeviceIds = new ArrayList<String>();
            testDeviceIds.add(AdRequest.DEVICE_ID_EMULATOR);
            // generate Google Test ID for current device
            if (testMode) {
                //get MD5 hashed device id
                @SuppressLint("HardwareIds") String android_id = Settings.Secure.getString(coronaActivity.getContentResolver(), Settings.Secure.ANDROID_ID);
                String adMobDeviceID = md5(android_id).toUpperCase();

                Log.i(CORONA_TAG, PLUGIN_NAME + ": Generated AdMob Test ID '" + adMobDeviceID + "'");
                testDeviceIds.add(adMobDeviceID);
            }
            RequestConfiguration configuration = new RequestConfiguration.Builder().setTestDeviceIds(testDeviceIds).build();
            MobileAds.setRequestConfiguration(configuration);


            if (coronaActivity != null) {
                coronaActivity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // initialize ads SDK
                        MobileAds.initialize(coronaActivity, new OnInitializationCompleteListener() {
                            @Override
                            public void onInitializationComplete(InitializationStatus initializationStatus) {
                                // used in isSDKInitialized() to determine if plugin API calls can be made
                                admobObjects.put(HAS_RECEIVED_INIT_EVENT_KEY, true);
                                MobileAds.setAppVolume((float) fVideoAdVolume);

                                // log plugin version
                                Log.i(CORONA_TAG, PLUGIN_NAME + ": " + PLUGIN_VERSION + " (SDK: " + PLUGIN_SDK_VERSION + ")");

                                // send Corona Lua event
                                Map<String, Object> coronaEvent = new HashMap<>();
                                coronaEvent.put(EVENT_PHASE_KEY, PHASE_INIT);
                                dispatchLuaEvent(coronaEvent);
                            }
                        });
                        //BY-ME: AEZ.Zytoona
                        admobObjects.put(HAS_RECEIVED_INIT_EVENT_KEY, true);
                        // calculate the Corona->device coordinate ratio
                        // we use Corona's built-in point conversion to take advantage of any device specific logic in the Corona core
                        // we also need to re-calculate this value on every load as the ratio changes between orientation changes
                        Point point1 = coronaActivity.convertCoronaPointToAndroidPoint(0, 0);
                        Point point2 = coronaActivity.convertCoronaPointToAndroidPoint(1000, 1000);
                        double yRatio = 1.0;
                        if (point1 != null && point2 != null) {
                            yRatio = (double) (point2.y - point1.y) / 1000.0;
                        }
                        admobObjects.put(Y_RATIO_KEY, yRatio);
                    }
                });
            }

            return 0;
        }
    }


    // [Lua] load(adType, options)
    private class Load implements NamedJavaFunction {
        /**
         * Gets the name of the Lua function as it would appear in the Lua script.
         *
         * @return Returns the name of the custom Lua function.
         */
        @Override
        public String getName() {
            return "load";
        }

        private AdSize getAdSize(CoronaActivity activity) {
            return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, AdSize.FULL_WIDTH);
        }


        /**
         * This method is called when the Lua function is called.
         * <p>
         * Warning! This method is not called on the main UI thread.
         *
         * @param luaState Reference to the Lua state.
         *                 Needed to retrieve the Lua function's parameters and to return values back to Lua.
         * @return Returns the number of values to be returned by the Lua function.
         */
        @Override
        public int invoke(final LuaState luaState) {
            functionSignature = "admob.load(adType, options)";

            if (!isSDKInitialized()) {
                return 0;
            }

            // check number of args
            int nargs = luaState.getTop();
            if (nargs != 2) {
                logMsg(ERROR_MSG, "Expected 2 arguments, got " + nargs);
                return 0;
            }

            String adType;
            String adUnitId = null;
            Boolean childSafe = null;
            String maxAdContentRating = null;
            boolean designedForFamilies = false;
            boolean localTestMode = false;
            ArrayList<String> keywords = new ArrayList<>();
            Boolean hasUserConsent = null;

            // get the ad type
            if (luaState.type(1) == LuaType.STRING) {
                adType = luaState.toString(1);
            } else {
                logMsg(ERROR_MSG, "adType (string) expected, got " + luaState.typeName(1));
                return 0;
            }

            // check for options table (required)
            if (luaState.type(2) == LuaType.TABLE) {
                // traverse and validate all the options
                for (luaState.pushNil(); luaState.next(2); luaState.pop(1)) {
                    String key = luaState.toString(-2);

                    switch (key) {
                        case "adUnitId":
                            if (luaState.type(-1) == LuaType.STRING) {
                                adUnitId = luaState.toString(-1);
                            } else {
                                logMsg(ERROR_MSG, "options.adUnitId (string) expected, got " + luaState.typeName(-1));
                                return 0;
                            }
                            break;
                        case "childSafe":
                            if (luaState.type(-1) == LuaType.BOOLEAN) {
                                childSafe = luaState.toBoolean(-1);
                            } else {
                                logMsg(ERROR_MSG, "options.childSafe (boolean) expected, got " + luaState.typeName(-1));
                                return 0;
                            }
                            break;
                        case "maxAdContentRating":
                            if (luaState.type(-1) == LuaType.STRING) {
                                maxAdContentRating = luaState.toString(-1);
                                switch (maxAdContentRating) {
                                    case "G":
                                        maxAdContentRating = RequestConfiguration.MAX_AD_CONTENT_RATING_G;
                                        break;
                                    case "PG":
                                        maxAdContentRating = RequestConfiguration.MAX_AD_CONTENT_RATING_PG;
                                        break;
                                    case "T":
                                        maxAdContentRating = RequestConfiguration.MAX_AD_CONTENT_RATING_T;
                                        break;
                                    case "M":
                                    case "MA":
                                        maxAdContentRating = RequestConfiguration.MAX_AD_CONTENT_RATING_MA;
                                        break;
                                    default:
                                        logMsg(ERROR_MSG, "options.maxAdContentRating must be one of string constants: 'G', 'PG', 'T' or 'M', got: " + luaState.toString(-1));
                                        return 0;
                                }
                            } else {
                                logMsg(ERROR_MSG, "options.maxAdContentRating (string) expected, got " + luaState.typeName(-1));
                                return 0;
                            }
                            break;
                        case "designedForFamilies":
                            if (luaState.type(-1) == LuaType.BOOLEAN) {
                                designedForFamilies = luaState.toBoolean(-1);
                            } else {
                                logMsg(ERROR_MSG, "options.designedForFamilies (boolean) expected, got " + luaState.typeName(-1));
                                return 0;
                            }
                            break;
                        case "keywords":
                            if (luaState.type(-1) == LuaType.TABLE) {
                                // build supported ad types
                                int ntypes = luaState.length(-1);

                                if (ntypes > 0) {
                                    for (int i = 1; i <= ntypes; i++) {
                                        // push array value onto stack
                                        luaState.rawGet(-1, i);

                                        // add keyword to array
                                        if (luaState.type(-1) == LuaType.STRING) {
                                            keywords.add(luaState.toString(-1));
                                        } else {
                                            logMsg(ERROR_MSG, "options.keywords[" + i + "] (string) expected, got: " + luaState.typeName(-1));
                                            return 0;
                                        }
                                        luaState.pop(1);
                                    }
                                } else {
                                    logMsg(ERROR_MSG, "options.keywords table cannot be empty");
                                    return 0;
                                }
                            } else {
                                logMsg(ERROR_MSG, "options.keywords (table) expected, got: " + luaState.typeName(-1));
                                return 0;
                            }
                            break;
                        case "testMode":
                            logMsg(WARNING_MSG, "load parameter testMode is ignored");
                            break;
                        case "hasUserConsent":
                            if (luaState.type(-1) == LuaType.BOOLEAN) {
                                hasUserConsent = luaState.toBoolean(-1);
                            } else {
                                logMsg(ERROR_MSG, "options.hasUserConsent expected (boolean). Got " + luaState.typeName(-1));
                                return 0;
                            }
                            break;
                        default:
                            logMsg(ERROR_MSG, "Invalid option '" + key + "'");
                            return 0;
                    }
                }
            } else {
                logMsg(ERROR_MSG, "options table expected, got " + luaState.typeName(2));
                return 0;
            }

            // check required params
            if (adUnitId == null) {
                logMsg(ERROR_MSG, "options.adUnitId is required");
                return 0;
            }

            // check valid ad type
            if (!validAdTypes.contains(adType)) {
                logMsg(ERROR_MSG, "Invalid adType '" + adType + "'");
                return 0;
            }

            // initialize request object
            AdRequest.Builder builder = new AdRequest.Builder();

            Bundle extras = new Bundle();

            if (designedForFamilies) {
                extras.putBoolean("is_designed_for_families", true);
            }
            if (hasUserConsent != null && !hasUserConsent) {
                extras.putString("npa", "1");
            }

            builder.addNetworkExtrasBundle(AdMobAdapter.class, extras);

            // add keywords to builder
            int keywordIndex = 0;
            while (keywords.size() >= keywordIndex + 1) {
                builder.addKeyword(keywords.get(keywordIndex));
                keywordIndex++;
            }
            RequestConfiguration.Builder configurator = MobileAds.getRequestConfiguration().toBuilder();
            if (childSafe != null) {
                configurator.setTagForChildDirectedTreatment(childSafe ? RequestConfiguration.TAG_FOR_CHILD_DIRECTED_TREATMENT_TRUE : RequestConfiguration.TAG_FOR_CHILD_DIRECTED_TREATMENT_FALSE);
            }
            if (maxAdContentRating != null) {
                configurator.setMaxAdContentRating(maxAdContentRating);
            }
            MobileAds.setRequestConfiguration(configurator.build());

            AdRequest request = builder.build();

            // declare final variables for inner loop
            final CoronaActivity coronaActivity = CoronaEnvironment.getCoronaActivity();
            final String fAdType = adType;
            final String fAdUnitId = adUnitId;
            final AdRequest fRequest = request;

            if (coronaActivity != null) {
                coronaActivity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // load specified ad type
                            switch (fAdType) {
                                case TYPE_INTERSTITIAL:
                                    // initialize object and set delegate
                                    CoronaAdmobInterstitialLoadDelegate interstitialLoadDelegate = new CoronaAdmobInterstitialLoadDelegate(fAdUnitId);
                                    InterstitialAd.load(coronaActivity, fAdUnitId, fRequest, interstitialLoadDelegate);

                                    // save for future use
                                    admobObjects.put(TYPE_INTERSTITIAL, fAdUnitId);
                                    admobObjects.put(fAdUnitId, interstitialLoadDelegate);
                                    break;
                                case TYPE_REWARDEDVIDEO:
                                    // initialize object and set delegate
                                    CoronaAdmobRewardedLoadDelegate
                                            rewardedLoadDelegate = new CoronaAdmobRewardedLoadDelegate(fAdUnitId);
                                    RewardedAd.load(coronaActivity, fAdUnitId, fRequest, rewardedLoadDelegate);
                                    // save for future use
                                    admobObjects.put(TYPE_REWARDEDVIDEO, fAdUnitId);
                                    admobObjects.put(fAdUnitId, rewardedLoadDelegate);
                                    break;
                                case TYPE_REWARDEDINTERSTITIAL:
                                    // initialize object and set delegate
                                    CoronaAdmobRewardedInterstitialLoadDelegate
                                            rewardedInterstitialLoadDelegate = new CoronaAdmobRewardedInterstitialLoadDelegate(fAdUnitId);
                                    RewardedInterstitialAd.load(coronaActivity, fAdUnitId, fRequest, rewardedInterstitialLoadDelegate);
                                    // save for future use
                                    admobObjects.put(TYPE_REWARDEDINTERSTITIAL, fAdUnitId);
                                    admobObjects.put(fAdUnitId, rewardedInterstitialLoadDelegate);
                                    break;
                                case TYPE_APPOPEN:
                                    // initialize object and set delegate
                                    CoronaAdmobAppOpenLoadDelegate
                                            appOpenLoadDelegate = new CoronaAdmobAppOpenLoadDelegate(fAdUnitId);
                                    AppOpenAd.load(coronaActivity, fAdUnitId, fRequest, appOpenLoadDelegate);
                                    // save for future use
                                    admobObjects.put(TYPE_APPOPEN, fAdUnitId);
                                    admobObjects.put(fAdUnitId, appOpenLoadDelegate);
                                    break;
                                case TYPE_BANNER:
                                    // calculate the Corona->device coordinate ratio
                                    // we use Corona's built-in point conversion to take advantage of any device specific logic in the Corona core
                                    // we also need to re-calculate this value on every load as the ratio changes between orientation changes
                                    Point point1 = coronaActivity.convertCoronaPointToAndroidPoint(0, 0);
                                    Point point2 = coronaActivity.convertCoronaPointToAndroidPoint(1000, 1000);
                                    double yRatio = 1.0;
                                    if (point1 != null && point2 != null) {
                                        yRatio = (double) (point2.y - point1.y) / 1000.0;
                                    }
                                    admobObjects.put(Y_RATIO_KEY, yRatio);

                                    AdView banner = new AdView(coronaActivity);
                                    banner.setAdUnitId(fAdUnitId);
                                    banner.setAdSize(getAdSize(coronaActivity));
                                    banner.setAdListener(new CoronaAdmobBannerDelegate(banner));
                                    banner.setVisibility(View.INVISIBLE);

                                    // set layout params
                                    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                                            FrameLayout.LayoutParams.WRAP_CONTENT,
                                            FrameLayout.LayoutParams.WRAP_CONTENT
                                    );

                                    // we need to add the smart banner to the hierarchy temporarily in order for it to get the proper size when loading
                                    // we'll remove this later in show() to set the final position
                                    params.gravity = Gravity.BOTTOM | Gravity.CENTER;
                                    coronaActivity.getOverlayView().addView(banner, params);

                                    // remove old banner
                                    try {
                                        AdView oldBanner = (AdView) admobObjects.get(fAdUnitId);
                                        if (oldBanner != null) {
                                            oldBanner.setVisibility(View.INVISIBLE);
                                            //noinspection ConstantConditions
                                            oldBanner.setAdListener(null);
                                            coronaActivity.getOverlayView().removeView(oldBanner);
                                            oldBanner.destroy();
                                        }
                                    } catch (ClassCastException e) {
                                        logMsg(ERROR_MSG, "adUnitId '" + fAdUnitId + "' is not a banner");
                                        return;
                                    } catch (Exception e) {
                                        logMsg(ERROR_MSG, "Unknown error while processing banner with adUnitId '" + fAdUnitId + "'");
                                        return;
                                    }

                                    // save for future use
                                    admobObjects.put(TYPE_BANNER, fAdUnitId);
                                    admobObjects.put(fAdUnitId, banner);

                                    // load a banner
                                    banner.loadAd(fRequest);
                                    break;
                            }
                        } catch (Exception e) {
                            logMsg(ERROR_MSG, "Unknown error while loading banner with adUnitId '" + fAdUnitId + "'");
                        }
                    }
                });
            }

            return 0;
        }
    }

    // [Lua] isLoaded(adType [, options])
    private class IsLoaded implements NamedJavaFunction {
        /**
         * Gets the name of the Lua function as it would appear in the Lua script.
         *
         * @return Returns the name of the custom Lua function.
         */
        @Override
        public String getName() {
            return "isLoaded";
        }

        /**
         * This method is called when the Lua function is called.
         * <p>
         * Warning! This method is not called on the main UI thread.
         *
         * @param luaState Reference to the Lua state.
         *                 Needed to retrieve the Lua function's parameters and to return values back to Lua.
         * @return Returns the number of values to be returned by the Lua function.
         */
        @Override
        public int invoke(final LuaState luaState) {
            functionSignature = "admob.isLoaded(adType [, options])";

            if (!isSDKInitialized()) {
                return 0;
            }

            // check number of args
            int nargs = luaState.getTop();
            if ((nargs < 1) || (nargs > 2)) {
                logMsg(ERROR_MSG, "Expected 1 or 2 arguments, got " + nargs);
                return 0;
            }

            String adType;
            String adUnitIdParam = null;
            String tag = null;

            // get the ad type
            if (luaState.type(1) == LuaType.STRING) {
                adType = luaState.toString(1);
            } else {
                logMsg(ERROR_MSG, "adType (string) expected, got " + luaState.typeName(1));
                return 0;
            }

            // check for options table
            if (!luaState.isNoneOrNil(2)) {
                if (luaState.type(2) == LuaType.TABLE) {
                    // traverse and validate all the options
                    for (luaState.pushNil(); luaState.next(2); luaState.pop(1)) {
                        String key = luaState.toString(-2);

                        if (key.equals("adUnitId")) {
                            if (luaState.type(-1) == LuaType.STRING) {
                                adUnitIdParam = luaState.toString(-1);
                            } else {
                                logMsg(ERROR_MSG, "options.adUnitId (string) expected, got " + luaState.typeName(-1));
                                return 0;
                            }
                        } else {
                            logMsg(ERROR_MSG, "Invalid option '" + key + "'");
                            return 0;
                        }
                    }
                } else {
                    logMsg(ERROR_MSG, "options table expected, got " + luaState.typeName(2));
                    return 0;
                }
            }

            // check valid ad type
            if (!validAdTypes.contains(adType)) {
                logMsg(ERROR_MSG, "Invalid adType '" + adType + "'");
                return 0;
            }

            // declare final values for inner loop
            final CoronaActivity coronaActivity = CoronaEnvironment.getCoronaActivity();
            if (coronaActivity == null) return 0;
            final String fAdType = adType;
            final String fAdUnitIdParam = adUnitIdParam;

            boolean isLoaded = false;
            final String fAdUnitId;
            if (fAdUnitIdParam != null) {
                fAdUnitId = fAdUnitIdParam;
            } else {
                fAdUnitId = (String) admobObjects.get(fAdType);
            }
            if (fAdUnitId != null) {

                switch (fAdType) {
                    case TYPE_INTERSTITIAL:
                        CoronaAdmobInterstitialLoadDelegate interstitial = (CoronaAdmobInterstitialLoadDelegate) admobObjects.get(fAdUnitId);
                        isLoaded = interstitial != null && interstitial.interstitialAd != null;
                        break;
                    case TYPE_REWARDEDVIDEO:
                        CoronaAdmobRewardedLoadDelegate rewardedAd = (CoronaAdmobRewardedLoadDelegate) admobObjects.get(fAdUnitId);
                        isLoaded = rewardedAd != null && rewardedAd.rewardedAd != null;
                        break;
                    case TYPE_REWARDEDINTERSTITIAL:
                        CoronaAdmobRewardedInterstitialLoadDelegate rewardedInterstitialAd = (CoronaAdmobRewardedInterstitialLoadDelegate) admobObjects.get(fAdUnitId);
                        isLoaded = rewardedInterstitialAd != null && rewardedInterstitialAd.rewardedInterstitialAd != null;
                        break;
                    case TYPE_APPOPEN:
                        CoronaAdmobAppOpenLoadDelegate appOpenAd = (CoronaAdmobAppOpenLoadDelegate) admobObjects.get(fAdUnitId);
                        isLoaded = appOpenAd != null && appOpenAd.appOpenAd != null;
                        break;
                    case TYPE_BANNER:
                        try {
                            // since we're returning a value to Lua, we need to implement a FutureTask
                            FutureTask<Boolean> isLoadedTask = new FutureTask<>(new Callable<Boolean>() {
                                @Override
                                public Boolean call() {
                                    AdView banner = (AdView) admobObjects.get(fAdUnitId);
                                    if (banner != null) {
                                        CoronaAdmobBannerDelegate bannerDelegate = (CoronaAdmobBannerDelegate) banner.getAdListener();
                                        return bannerDelegate.isLoaded;
                                    }
                                    return false;
                                }
                            });

                            // Run the task on the ui thread
                            coronaActivity.runOnUiThread(isLoadedTask);

                            // IMPORTANT! must use get() so FutureTask will block until it returns a value
                            isLoaded = isLoadedTask.get(2000, TimeUnit.MILLISECONDS);
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                        break;
                }
            }
            luaState.pushBoolean(isLoaded);

            return 1;
        }
    }

    // [Lua] setVideoAdVolume( videoAdVolume )
    private class SetVideoAdVolume implements NamedJavaFunction {
        /**
         * Gets the name of the Lua function as it would appear in the Lua script.
         *
         * @return Returns the name of the custom Lua function.
         */
        @Override
        public String getName() {
            return "setVideoAdVolume";
        }

        /**
         * This method is called when the Lua function is called.
         * <p>
         * Warning! This method is not called on the main UI thread.
         *
         * @param luaState Reference to the Lua state.
         *                 Needed to retrieve the Lua function's parameters and to return values back to Lua.
         * @return Returns the number of values to be returned by the Lua function.
         */
        @Override
        public int invoke(final LuaState luaState) {
            functionSignature = "admob.setVideoAdVolume( videoAdVolume )";

            if (!isSDKInitialized()) {
                return 0;
            }

            // check number of args
            int nargs = luaState.getTop();
            if (nargs != 1) {
                logMsg(ERROR_MSG, "Expected 1 argument, got " + nargs);
                return 0;
            }

            if (luaState.type(1) == LuaType.NUMBER) {
                MobileAds.setAppVolume((float) luaState.toNumber(1));
            } else {
                logMsg(ERROR_MSG, "videoAdVolume (number) expected, got " + luaState.typeName(1));
                return 0;
            }

            return 0;
        }
    }

    // [Lua] show(adType [, options ])
    private class Show implements NamedJavaFunction {
        /**
         * Gets the name of the Lua function as it would appear in the Lua script.
         *
         * @return Returns the name of the custom Lua function.
         */
        @Override
        public String getName() {
            return "show";
        }

        /**
         * This method is called when the Lua function is called.
         * <p>
         * Warning! This method is not called on the main UI thread.
         *
         * @param luaState Reference to the Lua state.
         *                 Needed to retrieve the Lua function's parameters and to return values back to Lua.
         * @return Returns the number of values to be returned by the Lua function.
         */
        @Override
        public int invoke(final LuaState luaState) {
            functionSignature = "admob.show(adType [, options ])";

            if (!isSDKInitialized()) {
                return 0;
            }

            // check number of args
            int nargs = luaState.getTop();
            if ((nargs < 1) || (nargs > 2)) {
                logMsg(ERROR_MSG, "Expected 1 or 2 arguments, got " + nargs);
                return 0;
            }

            String adType;
            String yAlign = null;
            String bgColor = null;
            String adUnitIdParam = null;
            double yOffset = 0;
            boolean yIsSet = false;

            // get the ad type
            if (luaState.type(1) == LuaType.STRING) {
                adType = luaState.toString(1);
            } else {
                logMsg(ERROR_MSG, "adType (string) expected, got " + luaState.typeName(1));
                return 0;
            }

            // check for options table
            if (!luaState.isNoneOrNil(2)) {
                if (luaState.type(2) == LuaType.TABLE) {
                    // traverse and validate all the options
                    for (luaState.pushNil(); luaState.next(2); luaState.pop(1)) {
                        String key = luaState.toString(-2);

                        switch (key) {
                            case "adUnitId":
                                if (luaState.type(-1) == LuaType.STRING) {
                                    adUnitIdParam = luaState.toString(-1);
                                } else {
                                    logMsg(ERROR_MSG, "options.adUnitId (string) expected, got " + luaState.typeName(-1));
                                    return 0;
                                }
                                break;
                            case "y":
                                yIsSet = true;

                                if (luaState.type(-1) == LuaType.NUMBER) {
                                    yOffset = luaState.toNumber(-1);
                                } else if (luaState.type(-1) == LuaType.STRING) {
                                    yAlign = luaState.toString(-1);
                                } else {
                                    logMsg(ERROR_MSG, "options.y (number) expected, got " + luaState.typeName(-1));
                                    return 0;
                                }
                                break;
                            case "bgColor":
                                if (luaState.type(-1) == LuaType.STRING) {
                                    bgColor = luaState.toString(-1);
                                } else {
                                    logMsg(ERROR_MSG, "options.bgColor (string) expected, got " + luaState.typeName(-1));
                                    return 0;
                                }
                                break;
                            default:
                                logMsg(ERROR_MSG, "Invalid option '" + key + "'");
                                return 0;
                        }
                    }
                } else {
                    logMsg(ERROR_MSG, "options table expected, got " + luaState.typeName(2));
                    return 0;
                }
            }

            // validate
            if (!validAdTypes.contains(adType)) {
                logMsg(ERROR_MSG, "Invalid adType '" + adType + "'");
                return 0;
            }

            // if no specific y has been given, set default value
            if (!yIsSet) {
                yAlign = ALIGN_BOTTOM;
            }

            if (yAlign != null) {
                if (!yAlign.equals(ALIGN_TOP) && !yAlign.equals(ALIGN_BOTTOM)) {
                    logMsg(ERROR_MSG, "Invalid yAlign '" + yAlign + "'");
                    return 0;
                }
            }

            if (bgColor != null) {
                if (!bgColor.startsWith("#")) {
                    logMsg(ERROR_MSG, "options.bgColor: Invalid color string '" + bgColor + "'. Must start with '#'");
                    return 0;
                } else {
                    try {
                        int color = Color.parseColor(bgColor);
                    } catch (Exception e) {
                        logMsg(ERROR_MSG, "options.bgColor: Unknown color '" + bgColor + "'");
                        return 0;
                    }
                }
            }

            // declare final variables for inner loop
            final CoronaActivity coronaActivity = CoronaEnvironment.getCoronaActivity();
            final String fAdType = adType;
            final String fYAlign = yAlign;
            final String fBgColor = bgColor;
            final String fadUnitIdParam = adUnitIdParam;
            final int fYOffset = (int) yOffset;

            if (coronaActivity != null) {
                coronaActivity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        String adUnitId;
                        if (fadUnitIdParam != null) {
                            adUnitId = fadUnitIdParam;
                            admobObjects.put(fAdType, adUnitId); // save setting as default value
                        } else {
                            adUnitId = (String) admobObjects.get(fAdType);
                        }

                        if (adUnitId == null) {
                            logMsg(WARNING_MSG, fAdType + " not loaded");
                            return;
                        }

                        // show specified ad type
                        switch (fAdType) {
                            case TYPE_INTERSTITIAL:
                                CoronaAdmobInterstitialLoadDelegate interstitial = (CoronaAdmobInterstitialLoadDelegate) admobObjects.get(adUnitId);
                                if ((interstitial != null) && interstitial.interstitialAd != null) {
                                    CoronaAdmobFullScreenDelegate delegate = new CoronaAdmobFullScreenDelegate(TYPE_INTERSTITIAL, interstitial.adUnitId);
                                    interstitial.interstitialAd.setFullScreenContentCallback(delegate);
                                    delegate.coronaAdOpened();
                                    interstitial.interstitialAd.show(coronaActivity);
                                    interstitial.interstitialAd = null;
                                } else {
                                    logMsg(WARNING_MSG, "Interstitial not loaded");
                                }
                                break;
                            case TYPE_REWARDEDVIDEO:
                                CoronaAdmobRewardedLoadDelegate rewardedAd = (CoronaAdmobRewardedLoadDelegate) admobObjects.get(adUnitId);
                                if ((rewardedAd != null) && rewardedAd.rewardedAd != null) {
                                    CoronaAdmobFullScreenDelegate delegate = new CoronaAdmobFullScreenDelegate(TYPE_REWARDEDVIDEO, adUnitId);
                                    delegate.coronaAdOpened();
                                    rewardedAd.rewardedAd.setFullScreenContentCallback(delegate);
                                    rewardedAd.rewardedAd.show(coronaActivity, delegate);
                                    rewardedAd.rewardedAd = null;
                                } else {
                                    logMsg(WARNING_MSG, "Rewarded Video not loaded");
                                }
                                break;
                            case TYPE_REWARDEDINTERSTITIAL:
                                CoronaAdmobRewardedInterstitialLoadDelegate rewardedInterstitialAd = (CoronaAdmobRewardedInterstitialLoadDelegate) admobObjects.get(adUnitId);
                                if ((rewardedInterstitialAd != null) && rewardedInterstitialAd.rewardedInterstitialAd != null) {
                                    CoronaAdmobFullScreenDelegate delegate = new CoronaAdmobFullScreenDelegate(TYPE_REWARDEDINTERSTITIAL, adUnitId);
                                    delegate.coronaAdOpened();
                                    rewardedInterstitialAd.rewardedInterstitialAd.setFullScreenContentCallback(delegate);
                                    rewardedInterstitialAd.rewardedInterstitialAd.show(coronaActivity, delegate);
                                    rewardedInterstitialAd.rewardedInterstitialAd = null;
                                } else {
                                    logMsg(WARNING_MSG, "Rewarded Interstitial not loaded");
                                }
                                break;
                            case TYPE_APPOPEN:
                                CoronaAdmobAppOpenLoadDelegate appOpenAd = (CoronaAdmobAppOpenLoadDelegate) admobObjects.get(adUnitId);
                                if ((appOpenAd != null) && appOpenAd.appOpenAd != null) {
                                    CoronaAdmobFullScreenDelegate delegate = new CoronaAdmobFullScreenDelegate(TYPE_APPOPEN, adUnitId);
                                    delegate.coronaAdOpened();
                                    appOpenAd.appOpenAd.setFullScreenContentCallback(delegate);
                                    appOpenAd.appOpenAd.show(coronaActivity);
                                    appOpenAd.appOpenAd = null;
                                } else {
                                    logMsg(WARNING_MSG, "Rewarded Interstitial not loaded");
                                }
                                break;
                            case TYPE_BANNER:
                                AdView banner = (AdView) admobObjects.get(adUnitId);

                                if ((banner == null) || (!((CoronaAdmobBannerDelegate) banner.getAdListener()).isLoaded)) {
                                    logMsg(WARNING_MSG, "Banner not loaded");
                                    return;
                                }

                                if (banner.getVisibility() == View.VISIBLE) {
                                    logMsg(WARNING_MSG, "Banner already visible");
                                    return;
                                }

                                // remove old layout
                                if (banner.getParent() != null) {
                                    coronaActivity.getOverlayView().removeView(banner);
                                }

                                // set final layout params
                                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                                        FrameLayout.LayoutParams.WRAP_CONTENT,
                                        FrameLayout.LayoutParams.WRAP_CONTENT
                                );

                                if (fYAlign != null) {
                                    params.gravity = Gravity.TOP | Gravity.CENTER;

                                    if (fYAlign.equals(ALIGN_BOTTOM)) {
                                        params.gravity = Gravity.BOTTOM | Gravity.CENTER;
                                    }
                                } else {
                                    double newBannerY = ceil(fYOffset * (double) admobObjects.get(Y_RATIO_KEY));
                                    Display display = coronaActivity.getWindowManager().getDefaultDisplay();
                                    int orientation = coronaActivity.getResources().getConfiguration().orientation;
                                    int orientedHeight;

                                    Point size = new Point();
                                    display.getSize(size);

                                    orientedHeight = (orientation == ORIENTATION_PORTRAIT) ? size.y : size.x;

                                    AdSize bannerSize = banner.getAdSize();
                                    if (bannerSize != null) {
                                        // make sure the banner frame is visible.
                                        // adjust it if the user has specified 'y' which will render it partially off-screen
                                        if (newBannerY >= 0) { // offset from top
                                            if (newBannerY + bannerSize.getHeight() > orientedHeight) {
                                                logMsg(WARNING_MSG, "Banner y position off screen. Adjusting position.");
                                                params.gravity = Gravity.BOTTOM | Gravity.CENTER;
                                            } else {
                                                params.gravity = Gravity.TOP | Gravity.CENTER;
                                                params.topMargin = (int) newBannerY;
                                            }
                                        } else {
                                            if (orientedHeight - bannerSize.getHeight() + newBannerY < 0) {
                                                logMsg(WARNING_MSG, "Banner y position off screen. Adjusting position.");
                                                params.gravity = Gravity.TOP | Gravity.CENTER;
                                            } else {
                                                params.gravity = Gravity.BOTTOM | Gravity.CENTER;
                                                params.bottomMargin = Math.abs((int) newBannerY);
                                            }
                                        }
                                    } else {
                                        params.gravity = Gravity.BOTTOM | Gravity.CENTER;
                                    }
                                }

                                coronaActivity.getOverlayView().addView(banner, params);

                                if (fBgColor != null) {
                                    banner.setBackgroundColor(Color.parseColor(fBgColor));
                                }
                                banner.setVisibility(View.VISIBLE);
                                banner.bringToFront();

                                // send Corona Lua event
                                // AdMob has no 'displayed' event in their Android banner listener so we fake it here
                                JSONObject data = new JSONObject();
                                try {
                                    data.put(DATA_ADUNIT_ID_KEY, banner.getAdUnitId());
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }

                                Map<String, Object> coronaEvent = new HashMap<>();
                                coronaEvent.put(EVENT_PHASE_KEY, PHASE_DISPLAYED);
                                coronaEvent.put(EVENT_TYPE_KEY, TYPE_BANNER);
                                coronaEvent.put(EVENT_DATA_KEY, data.toString());
                                dispatchLuaEvent(coronaEvent);
                                break;
                        }
                    }
                });
            }

            return 0;
        }
    }

    // [Lua] hide()
    private class Hide implements NamedJavaFunction {
        /**
         * Gets the name of the Lua function as it would appear in the Lua script.
         *
         * @return Returns the name of the custom Lua function.
         */
        @Override
        public String getName() {
            return "hide";
        }

        /**
         * This method is called when the Lua function is called.
         * <p>
         * Warning! This method is not called on the main UI thread.
         *
         * @param luaState Reference to the Lua state.
         *                 Needed to retrieve the Lua function's parameters and to return values back to Lua.
         * @return Returns the number of values to be returned by the Lua function.
         */
        @Override
        public int invoke(final LuaState luaState) {
            functionSignature = "admob.hide()";

            if (!isSDKInitialized()) {
                return 0;
            }

            // check number of args
            int nargs = luaState.getTop();
            if (nargs != 0) {
                logMsg(ERROR_MSG, "Expected no arguments, got " + nargs);
                return 0;
            }

            // declare final variables for inner loop
            final CoronaActivity coronaActivity = CoronaEnvironment.getCoronaActivity();

            if (coronaActivity != null) {
                coronaActivity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        String adUnitId = (String) admobObjects.get(TYPE_BANNER);
                        if (adUnitId == null) {
                            logMsg(WARNING_MSG, "Banner not loaded");
                            return;
                        }

                        AdView banner = (AdView) admobObjects.get(adUnitId);
                        if (banner == null || banner.getVisibility() != View.VISIBLE) {
                            logMsg(WARNING_MSG, "Banner not visible");
                            return;
                        }

                        // hide banner
                        banner.setVisibility(View.INVISIBLE);
                        coronaActivity.getOverlayView().removeView(banner);

                        // use AdMob onAdClosed to send a 'hidden' event
                        banner.getAdListener().onAdClosed();
                    }
                });
            }

            return 0;
        }
    }

    // [Lua] height( [options] )
    private class Height implements NamedJavaFunction {
        /**
         * Gets the name of the Lua function as it would appear in the Lua script.
         *
         * @return Returns the name of the custom Lua function.
         */
        @Override
        public String getName() {
            return "height";
        }
        //BY-ME: AEZ.Zytoona
        public AdSize getAdaptiveAdSize(Activity activity) {
            // Step 2 - Determine the screen width (less decorations) to use for the ad width.
            Display display = activity.getWindowManager().getDefaultDisplay();
            DisplayMetrics outMetrics = new DisplayMetrics();
            display.getMetrics(outMetrics);

            // Step 3 - Get adaptive ad size and return for setting on the ad view.
            return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, AdSize.FULL_WIDTH);
        }
        /**
         * This method is called when the Lua function is called.
         * <p>
         * Warning! This method is not called on the main UI thread.
         *
         * @param luaState Reference to the Lua state.
         *                 Needed to retrieve the Lua function's parameters and to return values back to Lua.
         * @return Returns the number of values to be returned by the Lua function.
         */
        @Override
        public int invoke(final LuaState luaState) {
            functionSignature = "admob.height( [options] )";
    //BY-ME: AEZ.Zytoona	
    //BYME: if no banner do static calculation for banner height
//            if (!isSDKInitialized()) {
//                return 0;
//            }

            // check number of args
            int nargs = luaState.getTop();
            if (nargs > 1) {
                logMsg(ERROR_MSG, "Expected 0 or 1 argument, got " + nargs);
                return 0;
            }

            String adUnitIdParam = null;

            // check for options table
            if (!luaState.isNoneOrNil(1)) {
                if (luaState.type(1) == LuaType.TABLE) {
                    // traverse and validate all the options
                    for (luaState.pushNil(); luaState.next(1); luaState.pop(1)) {
                        String key = luaState.toString(-2);

                        if (key.equals("adUnitId")) {
                            if (luaState.type(-1) == LuaType.STRING) {
                                adUnitIdParam = luaState.toString(-1);
                            } else {
                                logMsg(ERROR_MSG, "options.adUnitId (string) expected, got " + luaState.typeName(-1));
                                return 0;
                            }
                        } else {
                            logMsg(ERROR_MSG, "Invalid option '" + key + "'");
                            return 0;
                        }
                    }
                } else {
                    logMsg(ERROR_MSG, "options table expected, got " + luaState.typeName(1));
                    return 0;
                }
            }

            double height = 0;

            final CoronaActivity coronaActivity = CoronaEnvironment.getCoronaActivity();
            final String fAdUnitIdParam = adUnitIdParam;

            try {
                if (coronaActivity != null) {
                    // since we're returning a value to Lua, we need to implement a FutureTask
                    FutureTask<Double> heightTask = new FutureTask<>(new Callable<Double>() {
                        @Override
                        public Double call() {
                            String adUnitId;
                            if (fAdUnitIdParam != null) {
                                adUnitId = fAdUnitIdParam;
                            } else {
                                adUnitId = (String) admobObjects.get(TYPE_BANNER);
                            }

                            double result = 0.0;
							    //BYME: 
                            boolean resultValid = false;


                            if (adUnitId == null) {
                                logMsg(WARNING_MSG, "Banner not loaded");
                            } else {
                                AdView banner = (AdView) admobObjects.get(adUnitId);
                                if (banner != null) {
                                    AdSize size = banner.getAdSize();
                                    if (size != null) {
                                        result = size.getHeightInPixels(coronaActivity) / (double) admobObjects.get(Y_RATIO_KEY);
										//BYME: moved this calculation to init from	banner load method
                                        resultValid = true;
                                        Log.i(CORONA_TAG, "banner height, from loaded banner size:"+result);
                                    }
                                }
                            }
                            if(!resultValid) {
                                result = getAdaptiveAdSize(coronaActivity).getHeightInPixels(coronaActivity) / (double) admobObjects.get(Y_RATIO_KEY);
                                Log.i(CORONA_TAG,"banner height, from Api size:"+result);
                            }
                            // return result to FutureTask
                            return result;
                        }
                    });

                    coronaActivity.runOnUiThread(heightTask);

                    // IMPORTANT! must use get() so FutureTask will block until it returns a value
                    height = heightTask.get(2000, TimeUnit.MILLISECONDS);
                }
            } catch (Exception ex) {
                ex.printStackTrace();
		      //BYME: 
                height = getAdaptiveAdSize(coronaActivity).getHeightInPixels(coronaActivity) / (double) admobObjects.get(Y_RATIO_KEY);
                Log.i(CORONA_TAG,"had exception get API size:" + height);
            }

            luaState.pushNumber(height);

            return 1;
        }
    }

    // [Lua] updateConsentForm( [options] )
    private class UpdateConsentForm implements NamedJavaFunction {
        /**
         * Gets the name of the Lua function as it would appear in the Lua script.
         *
         * @return Returns the name of the custom Lua function.
         */
        @Override
        public String getName() {
            return "updateConsentForm";
        }

        /**
         * This method is called when the Lua function is called.
         * <p>
         * Warning! This method is not called on the main UI thread.
         *
         * @param luaState Reference to the Lua state.
         *                 Needed to retrieve the Lua function's parameters and to return values back to Lua.
         * @return Returns the number of values to be returned by the Lua function.
         */
        @Override
        public int invoke(final LuaState luaState) {
            functionSignature = "admob.updateConsentForm( [options] )";

            if (!isSDKInitialized()) {
                return 0;
            }

            // check number of args
            int nargs = luaState.getTop();
            if (nargs > 1) {
                logMsg(ERROR_MSG, "Expected 0 or 1 argument, got " + nargs);
                return 0;
            }

            ConsentRequestParameters.Builder params = new ConsentRequestParameters.Builder();

            // check for options table
            if (!luaState.isNoneOrNil(1)) {
                if (luaState.type(1) == LuaType.TABLE) {
                    // traverse and validate all the options
                    for (luaState.pushNil(); luaState.next(1); luaState.pop(1)) {
                        String key = luaState.toString(-2);

                        if (key.equals("underage")) {
                            if (luaState.type(-1) == LuaType.BOOLEAN) {
                                params.setTagForUnderAgeOfConsent(luaState.toBoolean(-1));
                            } else {
                                logMsg(ERROR_MSG, "options.underage (boolean) expected, got " + luaState.typeName(-1));
                                return 0;
                            }
                        }else if (key.equals("debug")) {
                            ConsentDebugSettings.Builder debugSettings = new ConsentDebugSettings.Builder(CoronaEnvironment.getApplicationContext());
                            if (luaState.type(-1) == LuaType.TABLE) {
                                luaState.getField(-1, "geography");
                                if(luaState.isString(-1)){
                                    if(luaState.toString(-1).equals("EEA")){
                                        debugSettings.setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA);
                                    }else if(luaState.toString(-1).equals("notEEA")){
                                        debugSettings.setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_NOT_EEA);
                                    }else{
                                        debugSettings.setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_DISABLED);
                                    }
                                }
                                luaState.pop(1);
                                luaState.getField(-1, "testDeviceIdentifiers");
                                if(luaState.isTable(-1)){
                                    int arrayLength = luaState.length(-1);
                                    if (arrayLength > 0) {
                                        for (int index = 1; index <= arrayLength; index++) {
                                            // Push the next Lua array value onto the Lua stack.
                                            luaState.rawGet(-1, index);
                                            if(luaState.isString(-1)){
                                                debugSettings.addTestDeviceHashedId(luaState.toString(-1));
                                            }
                                            luaState.pop(1);
                                        }
                                    }
                                }
                                params.setConsentDebugSettings(debugSettings.build());
                                luaState.pop(1);
                            } else {
                                logMsg(ERROR_MSG, "options.debug (table) expected, got " + luaState.typeName(-1));
                                return 0;
                            }
                        } else {
                            logMsg(ERROR_MSG, "Invalid option '" + key + "'");
                            return 0;
                        }
                    }
                } else {
                    logMsg(ERROR_MSG, "options table expected, got " + luaState.typeName(1));
                    return 0;
                }
            }
            ConsentInformation consentInformation = UserMessagingPlatform.getConsentInformation(CoronaEnvironment.getApplicationContext());
            consentInformation.requestConsentInfoUpdate(CoronaEnvironment.getCoronaActivity(),
                params.build(),

                new ConsentInformation.OnConsentInfoUpdateSuccessListener() {
                    @Override
                    public void onConsentInfoUpdateSuccess() {
                        Map<String, Object> coronaEvent = new HashMap<>();
                        coronaEvent.put(EVENT_PHASE_KEY, PHASE_REFRESHED);
                        coronaEvent.put(EVENT_TYPE_KEY, TYPE_UMP);
                        coronaEvent.put(CoronaLuaEvent.ISERROR_KEY, false);
                        dispatchLuaEvent(coronaEvent);
                    }
                },
                new ConsentInformation.OnConsentInfoUpdateFailureListener() {
                    @Override
                    public void onConsentInfoUpdateFailure(@NonNull FormError formError) {
                        Map<String, Object> coronaEvent = new HashMap<>();
                        coronaEvent.put(EVENT_PHASE_KEY, PHASE_FAILED);
                        coronaEvent.put(CoronaLuaEvent.ERRORTYPE_KEY, formError.getMessage());
                        coronaEvent.put(EVENT_TYPE_KEY, TYPE_UMP);
                        coronaEvent.put(CoronaLuaEvent.ISERROR_KEY, true);
                        dispatchLuaEvent(coronaEvent);
                    }
                }
            );

            return 0;
        }
    }

    // [Lua] loadConsentForm(  )
    private class LoadConsentForm implements NamedJavaFunction {
        /**
         * Gets the name of the Lua function as it would appear in the Lua script.
         *
         * @return Returns the name of the custom Lua function.
         */
        @Override
        public String getName() {
            return "loadConsentForm";
        }

        /**
         * This method is called when the Lua function is called.
         * <p>
         * Warning! This method is not called on the main UI thread.
         *
         * @param luaState Reference to the Lua state.
         *                 Needed to retrieve the Lua function's parameters and to return values back to Lua.
         * @return Returns the number of values to be returned by the Lua function.
         */
        @Override
        public int invoke(final LuaState luaState) {
            functionSignature = "admob.loadConsentForm( )";

            if (!isSDKInitialized()) {
                return 0;
            }
            CoronaEnvironment.getCoronaActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    UserMessagingPlatform.loadConsentForm(
                            CoronaEnvironment.getCoronaActivity(),
                            new UserMessagingPlatform.OnConsentFormLoadSuccessListener() {
                                @Override
                                public void onConsentFormLoadSuccess(ConsentForm consentForm) {
                                    umpForm = consentForm;
                                    Map<String, Object> coronaEvent = new HashMap<>();
                                    coronaEvent.put(EVENT_PHASE_KEY, PHASE_LOADED);
                                    coronaEvent.put(EVENT_TYPE_KEY, TYPE_UMP);
                                    coronaEvent.put(CoronaLuaEvent.ISERROR_KEY, false);
                                    dispatchLuaEvent(coronaEvent);
                                }
                            },
                            new UserMessagingPlatform.OnConsentFormLoadFailureListener() {
                                @Override
                                public void onConsentFormLoadFailure(FormError formError) {
                                    Map<String, Object> coronaEvent = new HashMap<>();
                                    coronaEvent.put(EVENT_PHASE_KEY, PHASE_FAILED);
                                    coronaEvent.put(CoronaLuaEvent.ERRORTYPE_KEY, formError.getMessage());
                                    coronaEvent.put(EVENT_TYPE_KEY, TYPE_UMP);
                                    coronaEvent.put(CoronaLuaEvent.ISERROR_KEY, true);
                                    dispatchLuaEvent(coronaEvent);
                                }
                            }
                    );
                }
            });

            return 0;
        }
    }

    // [Lua] showConsentForm(  )
    private class ShowConsentForm implements NamedJavaFunction {
        /**
         * Gets the name of the Lua function as it would appear in the Lua script.
         *
         * @return Returns the name of the custom Lua function.
         */
        @Override
        public String getName() {
            return "showConsentForm";
        }

        /**
         * This method is called when the Lua function is called.
         * <p>
         * Warning! This method is not called on the main UI thread.
         *
         * @param luaState Reference to the Lua state.
         *                 Needed to retrieve the Lua function's parameters and to return values back to Lua.
         * @return Returns the number of values to be returned by the Lua function.
         */
        @Override
        public int invoke(final LuaState luaState) {
            functionSignature = "admob.showConsentForm( )";

            if (!isSDKInitialized()) {
                return 0;
            }
            ConsentInformation consentInformation = UserMessagingPlatform.getConsentInformation(CoronaEnvironment.getApplicationContext());
            if(umpForm != null){
                CoronaEnvironment.getCoronaActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        umpForm.show(
                                CoronaEnvironment.getCoronaActivity(),
                                new ConsentForm.OnConsentFormDismissedListener() {
                                    @Override
                                    public void onConsentFormDismissed(@Nullable FormError formError) {
                                        if(formError == null){
                                            Map<String, Object> coronaEvent = new HashMap<>();
                                            coronaEvent.put(EVENT_PHASE_KEY, PHASE_FAILED);
                                            coronaEvent.put(CoronaLuaEvent.ERRORTYPE_KEY, formError.getMessage());
                                            coronaEvent.put(EVENT_TYPE_KEY, TYPE_UMP);
                                            coronaEvent.put(CoronaLuaEvent.ISERROR_KEY, true);
                                            dispatchLuaEvent(coronaEvent);
                                        }else{
                                            Map<String, Object> coronaEvent = new HashMap<>();
                                            coronaEvent.put(EVENT_PHASE_KEY, PHASE_HIDDEN);
                                            coronaEvent.put(EVENT_TYPE_KEY, TYPE_UMP);
                                            coronaEvent.put(CoronaLuaEvent.ISERROR_KEY, false);
                                            dispatchLuaEvent(coronaEvent);
                                        }

                                    }
                                });
                    }
                });
            }else{
                Map<String, Object> coronaEvent = new HashMap<>();
                coronaEvent.put(EVENT_PHASE_KEY, PHASE_FAILED);
                coronaEvent.put(CoronaLuaEvent.ERRORTYPE_KEY, "Consent Form not Loaded");
                coronaEvent.put(EVENT_TYPE_KEY, TYPE_UMP);
                coronaEvent.put(CoronaLuaEvent.ISERROR_KEY, true);
                dispatchLuaEvent(coronaEvent);
            }

            return 0;
        }
    }

    // [Lua] getConsentFormStatus(  )
    private class GetConsentFormStatus implements NamedJavaFunction {
        /**
         * Gets the name of the Lua function as it would appear in the Lua script.
         *
         * @return Returns the name of the custom Lua function.
         */
        @Override
        public String getName() {
            return "getConsentFormStatus";
        }

        /**
         * This method is called when the Lua function is called.
         * <p>
         * Warning! This method is not called on the main UI thread.
         *
         * @param luaState Reference to the Lua state.
         *                 Needed to retrieve the Lua function's parameters and to return values back to Lua.
         * @return Returns the number of values to be returned by the Lua function.
         */
        @Override
        public int invoke(final LuaState luaState) {
            functionSignature = "admob.getConsentFormStatus( )";

            if (!isSDKInitialized()) {
                return 0;
            }
            ConsentInformation consentInformation = UserMessagingPlatform.getConsentInformation(CoronaEnvironment.getApplicationContext());

            if(consentInformation.isConsentFormAvailable()){
                luaState.pushString("available");
            }else {
                luaState.pushString("unavailable");
            }

            if(consentInformation.getConsentStatus() == ConsentInformation.ConsentStatus.OBTAINED){
                luaState.pushString("obtained");
            }else if(consentInformation.getConsentStatus() == ConsentInformation.ConsentStatus.NOT_REQUIRED){
                luaState.pushString("notRequired");
            }else if(consentInformation.getConsentStatus() == ConsentInformation.ConsentStatus.REQUIRED){
                luaState.pushString("required");
            }else{
                luaState.pushString("unknown");
            }

            return 2;
        }
    }

    // -------------------------------------------------------------------

    private class CoronaAdmobFullScreenDelegate extends FullScreenContentCallback implements OnUserEarnedRewardListener {
        String adUnitId;
        String adType;

        CoronaAdmobFullScreenDelegate(String adType, String adUnitId) {
            this.adUnitId = adUnitId;
            this.adType = adType;
        }

        @Override
        public void onUserEarnedReward(RewardItem rewardItem) {
            // create data
            JSONObject data = new JSONObject();
            try {
                data.put(DATA_ADUNIT_ID_KEY, adUnitId);
                data.put(REWARD_ITEM, rewardItem.getType());
                data.put(REWARD_AMOUNT, rewardItem.getAmount());

                Map<String, Object> coronaEvent = new HashMap<>();
                coronaEvent.put(EVENT_PHASE_KEY, PHASE_REWARD);
                coronaEvent.put(EVENT_TYPE_KEY, TYPE_REWARDEDVIDEO);
                coronaEvent.put(EVENT_DATA_KEY, data.toString());
                dispatchLuaEvent(coronaEvent);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        public void coronaAdOpened() {
            // create data
            JSONObject data = new JSONObject();
            try {
                data.put(DATA_ADUNIT_ID_KEY, adUnitId);

                Map<String, Object> coronaEvent = new HashMap<>();
                coronaEvent.put(EVENT_PHASE_KEY, PHASE_DISPLAYED);
                coronaEvent.put(EVENT_TYPE_KEY, adType);
                coronaEvent.put(EVENT_DATA_KEY, data.toString());
                dispatchLuaEvent(coronaEvent);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }


        @Override
        public void onAdDismissedFullScreenContent() {
            // create data
            JSONObject data = new JSONObject();
            try {
                data.put(DATA_ADUNIT_ID_KEY, adUnitId);

                Map<String, Object> coronaEvent = new HashMap<>();
                coronaEvent.put(EVENT_PHASE_KEY, PHASE_CLOSED);
                coronaEvent.put(EVENT_TYPE_KEY, adType);
                coronaEvent.put(EVENT_DATA_KEY, data.toString());
                dispatchLuaEvent(coronaEvent);
            } catch (Exception e) {
                e.printStackTrace();
            }
            invalidateAllViews();
        }

        @Override
        public void onAdFailedToShowFullScreenContent(AdError err) {
            // create data
            JSONObject data = new JSONObject();
            try {
                data.put(DATA_ERRORMSG_KEY, err.toString());
                data.put(DATA_ERRORCODE_KEY, err.getCode());
                data.put(DATA_ADUNIT_ID_KEY, adUnitId);

                Map<String, Object> coronaEvent = new HashMap<>();
                coronaEvent.put(EVENT_PHASE_KEY, PHASE_FAILED);
                coronaEvent.put(EVENT_TYPE_KEY, adType);
                coronaEvent.put(EVENT_DATA_KEY, data.toString());
                coronaEvent.put(CoronaLuaEvent.ISERROR_KEY, true);
                dispatchLuaEvent(coronaEvent);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private class CoronaAdmobInterstitialLoadDelegate extends InterstitialAdLoadCallback {
        public InterstitialAd interstitialAd;
        String adUnitId;

        CoronaAdmobInterstitialLoadDelegate(String adUnitId) {
            this.adUnitId = adUnitId;
        }

        @Override
        public void onAdLoaded(InterstitialAd ad) {
            interstitialAd = ad;
            // create data
            JSONObject data = new JSONObject();
            try {
                data.put(DATA_ADUNIT_ID_KEY, adUnitId);
                Map<String, Object> coronaEvent = new HashMap<>();
                coronaEvent.put(EVENT_PHASE_KEY, PHASE_LOADED);
                coronaEvent.put(EVENT_TYPE_KEY, TYPE_INTERSTITIAL);
                coronaEvent.put(EVENT_DATA_KEY, data.toString());
                dispatchLuaEvent(coronaEvent);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onAdFailedToLoad(LoadAdError adError) {
            JSONObject data = new JSONObject();
            try {
                data.put(DATA_ADUNIT_ID_KEY, adUnitId);
                Map<String, Object> coronaEvent = new HashMap<>();
                coronaEvent.put(EVENT_PHASE_KEY, PHASE_FAILED);
                coronaEvent.put(EVENT_TYPE_KEY, TYPE_INTERSTITIAL);
                coronaEvent.put(CoronaLuaEvent.RESPONSE_KEY, RESPONSE_LOAD_FAILED);
                coronaEvent.put(EVENT_DATA_KEY, data.toString());
                coronaEvent.put(CoronaLuaEvent.ISERROR_KEY, true);
                coronaEvent.put(CoronaLuaEvent.ERRORTYPE_KEY, adError.toString());
                logMsg(ERROR_MSG, "Error while loading ad " + adError);
                dispatchLuaEvent(coronaEvent);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private class CoronaAdmobRewardedLoadDelegate extends RewardedAdLoadCallback {
        public RewardedAd rewardedAd;
        String adUnitId;

        CoronaAdmobRewardedLoadDelegate(String adUnitId) {
            this.adUnitId = adUnitId;
        }

        @Override
        public void onAdLoaded(RewardedAd ad) {
            rewardedAd = ad;
            // create data
            JSONObject data = new JSONObject();
            try {
                data.put(DATA_ADUNIT_ID_KEY, adUnitId);
                Map<String, Object> coronaEvent = new HashMap<>();
                coronaEvent.put(EVENT_PHASE_KEY, PHASE_LOADED);
                coronaEvent.put(EVENT_TYPE_KEY, TYPE_REWARDEDVIDEO);
                coronaEvent.put(EVENT_DATA_KEY, data.toString());
                dispatchLuaEvent(coronaEvent);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onAdFailedToLoad(LoadAdError adError) {
            JSONObject data = new JSONObject();
            try {
                data.put(DATA_ADUNIT_ID_KEY, adUnitId);
                Map<String, Object> coronaEvent = new HashMap<>();
                coronaEvent.put(EVENT_PHASE_KEY, PHASE_FAILED);
                coronaEvent.put(EVENT_TYPE_KEY, TYPE_REWARDEDVIDEO);
                coronaEvent.put(CoronaLuaEvent.RESPONSE_KEY, RESPONSE_LOAD_FAILED);
                coronaEvent.put(EVENT_DATA_KEY, data.toString());
                coronaEvent.put(CoronaLuaEvent.ISERROR_KEY, true);
                coronaEvent.put(CoronaLuaEvent.ERRORTYPE_KEY, adError.toString());
                logMsg(ERROR_MSG, "Error while loading ad " + adError);
                dispatchLuaEvent(coronaEvent);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }


    // -------------------------------------------------------------------

    private class CoronaAdmobRewardedInterstitialLoadDelegate extends RewardedInterstitialAdLoadCallback {
        public RewardedInterstitialAd rewardedInterstitialAd;
        String adUnitId;

        CoronaAdmobRewardedInterstitialLoadDelegate(String adUnitId) {
            this.adUnitId = adUnitId;
        }

        @Override
        public void onAdLoaded(RewardedInterstitialAd ad) {
            rewardedInterstitialAd = ad;
            // create data
            JSONObject data = new JSONObject();
            try {
                data.put(DATA_ADUNIT_ID_KEY, adUnitId);
                Map<String, Object> coronaEvent = new HashMap<>();
                coronaEvent.put(EVENT_PHASE_KEY, PHASE_LOADED);
                coronaEvent.put(EVENT_TYPE_KEY, TYPE_REWARDEDINTERSTITIAL);
                coronaEvent.put(EVENT_DATA_KEY, data.toString());
                dispatchLuaEvent(coronaEvent);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onAdFailedToLoad(LoadAdError adError) {
            JSONObject data = new JSONObject();
            try {
                data.put(DATA_ADUNIT_ID_KEY, adUnitId);
                Map<String, Object> coronaEvent = new HashMap<>();
                coronaEvent.put(EVENT_PHASE_KEY, PHASE_FAILED);
                coronaEvent.put(EVENT_TYPE_KEY, TYPE_REWARDEDINTERSTITIAL);
                coronaEvent.put(CoronaLuaEvent.RESPONSE_KEY, RESPONSE_LOAD_FAILED);
                coronaEvent.put(EVENT_DATA_KEY, data.toString());
                coronaEvent.put(CoronaLuaEvent.ISERROR_KEY, true);
                coronaEvent.put(CoronaLuaEvent.ERRORTYPE_KEY, adError.toString());
                logMsg(ERROR_MSG, "Error while loading ad " + adError);
                dispatchLuaEvent(coronaEvent);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }


    // -------------------------------------------------------------------

    private class CoronaAdmobAppOpenLoadDelegate extends AppOpenAd.AppOpenAdLoadCallback {
        public AppOpenAd appOpenAd;
        String adUnitId;

        CoronaAdmobAppOpenLoadDelegate(String adUnitId) {
            this.adUnitId = adUnitId;
        }

        @Override
        public void onAdLoaded(AppOpenAd ad) {
            appOpenAd = ad;
            // create data
            JSONObject data = new JSONObject();
            try {
                data.put(DATA_ADUNIT_ID_KEY, adUnitId);
                Map<String, Object> coronaEvent = new HashMap<>();
                coronaEvent.put(EVENT_PHASE_KEY, PHASE_LOADED);
                coronaEvent.put(EVENT_TYPE_KEY, TYPE_APPOPEN);
                coronaEvent.put(EVENT_DATA_KEY, data.toString());
                dispatchLuaEvent(coronaEvent);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        @Override
        public void onAdFailedToLoad(LoadAdError adError) {
            JSONObject data = new JSONObject();
            try {
                data.put(DATA_ADUNIT_ID_KEY, adUnitId);
                Map<String, Object> coronaEvent = new HashMap<>();
                coronaEvent.put(EVENT_PHASE_KEY, PHASE_FAILED);
                coronaEvent.put(EVENT_TYPE_KEY, TYPE_APPOPEN);
                coronaEvent.put(CoronaLuaEvent.RESPONSE_KEY, RESPONSE_LOAD_FAILED);
                coronaEvent.put(EVENT_DATA_KEY, data.toString());
                coronaEvent.put(CoronaLuaEvent.ISERROR_KEY, true);
                coronaEvent.put(CoronaLuaEvent.ERRORTYPE_KEY, adError.toString());
                logMsg(ERROR_MSG, "Error while loading ad " + adError);
                dispatchLuaEvent(coronaEvent);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }


    // -------------------------------------------------------------------

    private class CoronaAdmobBannerDelegate extends AdListener {
        AdView currentBanner;
        boolean isLoaded;

        CoronaAdmobBannerDelegate(AdView banner) {
            this.currentBanner = banner;
            this.isLoaded = false;
        }

        @Override
        public void onAdLoaded() {
            // create data
            JSONObject data = new JSONObject();
            try {
                data.put(DATA_ADUNIT_ID_KEY, currentBanner.getAdUnitId());

                Map<String, Object> coronaEvent = new HashMap<>();
                coronaEvent.put(EVENT_PHASE_KEY, this.isLoaded ? PHASE_REFRESHED : PHASE_LOADED);
                coronaEvent.put(EVENT_TYPE_KEY, TYPE_BANNER);
                coronaEvent.put(EVENT_DATA_KEY, data.toString());
                dispatchLuaEvent(coronaEvent);

                this.isLoaded = true;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onAdOpened() {
            // create data
            JSONObject data = new JSONObject();
            try {
                data.put(DATA_ADUNIT_ID_KEY, currentBanner.getAdUnitId());

                Map<String, Object> coronaEvent = new HashMap<>();
                coronaEvent.put(EVENT_PHASE_KEY, PHASE_CLICKED);
                coronaEvent.put(EVENT_TYPE_KEY, TYPE_BANNER);
                coronaEvent.put(EVENT_DATA_KEY, data.toString());
                dispatchLuaEvent(coronaEvent);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onAdClosed() {
            // create data
            JSONObject data = new JSONObject();
            try {
                data.put(DATA_ADUNIT_ID_KEY, currentBanner.getAdUnitId());

                Map<String, Object> coronaEvent = new HashMap<>();
                coronaEvent.put(EVENT_PHASE_KEY, PHASE_HIDDEN);
                coronaEvent.put(EVENT_TYPE_KEY, TYPE_BANNER);
                coronaEvent.put(EVENT_DATA_KEY, data.toString());
                dispatchLuaEvent(coronaEvent);
            } catch (Exception e) {
                e.printStackTrace();
            }
            invalidateAllViews();
        }

        @Override
        public void onAdFailedToLoad(LoadAdError error) {
            // create data
            JSONObject data = new JSONObject();
            try {
                data.put(DATA_ERRORMSG_KEY, error.getMessage());
                data.put(DATA_ERRORCODE_KEY, error.getCode());
                data.put(DATA_ADUNIT_ID_KEY, currentBanner.getAdUnitId());

                Map<String, Object> coronaEvent = new HashMap<>();
                coronaEvent.put(EVENT_PHASE_KEY, PHASE_FAILED);
                coronaEvent.put(EVENT_TYPE_KEY, TYPE_BANNER);
                coronaEvent.put(CoronaLuaEvent.RESPONSE_KEY, RESPONSE_LOAD_FAILED);
                coronaEvent.put(EVENT_DATA_KEY, data.toString());
                dispatchLuaEvent(coronaEvent);

                this.isLoaded = false;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
