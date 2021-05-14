//
// LuaLoader.java
// AdMob Plugin
//
// Copyright (c) 2016 CoronaLabs inc. All rights reserved.
//

// @formatter:off

package plugin.admob;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.graphics.Point;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

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
import com.google.android.gms.ads.InterstitialAd;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.RequestConfiguration;
import com.google.android.gms.ads.initialization.InitializationStatus;
import com.google.android.gms.ads.initialization.OnInitializationCompleteListener;
import com.google.android.gms.ads.rewarded.RewardItem;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdCallback;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;
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
    private static final String PLUGIN_VERSION = "1.2.6";
    private static final String PLUGIN_SDK_VERSION = "0";//getVersionString();

    private static final String EVENT_NAME = "adsRequest";
    private static final String PROVIDER_NAME = "admob";

    // ad types
    private static final String TYPE_BANNER = "banner";
    private static final String TYPE_INTERSTITIAL = "interstitial";
    private static final String TYPE_REWARDEDVIDEO = "rewardedVideo";

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
                new SetVideoAdVolume()
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
            validAdTypes.add(TYPE_BANNER);

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
                            banner.setAdListener(null);
                            banner.destroy();
                        } else if (object instanceof InterstitialAd) {
                            InterstitialAd interstitial = (InterstitialAd) object;
                            CoronaAdmobInterstitialDelegate oldListener = (CoronaAdmobInterstitialDelegate) interstitial.getAdListener();
                            if (oldListener != null) {
                                oldListener.interstitial = null;
                                interstitial.setAdListener(null);
                            }
                        }
                        //else if (object instanceof  RewardedAd) {
                        //  RewardedAd rewardedAd = (RewardedAd)object;
                        //}
                    }

                    if (runtime != null) {
                        CoronaLua.deleteRef(runtime.getLuaState(), coronaListener);
                    }
                    coronaListener = CoronaLua.REFNIL;

                    admobObjects.clear();
                    validAdTypes.clear();
                    coronaRuntimeTaskDispatcher = null;
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
            boolean childSafe = false;
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
            RequestConfiguration.Builder configurator = MobileAds.getRequestConfiguration().toBuilder().setTagForChildDirectedTreatment(childSafe ? RequestConfiguration.TAG_FOR_CHILD_DIRECTED_TREATMENT_TRUE : RequestConfiguration.TAG_FOR_CHILD_DIRECTED_TREATMENT_FALSE);
            if(maxAdContentRating != null) {
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
                                    InterstitialAd interstitial = new InterstitialAd(coronaActivity);
                                    interstitial.setAdUnitId(fAdUnitId);
                                    interstitial.setAdListener(new CoronaAdmobInterstitialDelegate(interstitial));

                                    try {
                                        // remove the old interstitial's delegate callback
                                        InterstitialAd oldInterstitial = (InterstitialAd) admobObjects.get(fAdUnitId);
                                        if (oldInterstitial != null) {
                                            CoronaAdmobInterstitialDelegate oldListener = (CoronaAdmobInterstitialDelegate) oldInterstitial.getAdListener();
                                            oldListener.interstitial = null;
                                            oldInterstitial.setAdListener(null);
                                        }
                                    } catch (ClassCastException e) {
                                        logMsg(ERROR_MSG, "adUnitId '" + fAdUnitId + "' is not an interstitial");
                                        return;
                                    } catch (Exception e) {
                                        logMsg(ERROR_MSG, "Unknown error while processing interstitial with adUnitId '" + fAdUnitId + "'");
                                        return;
                                    }

                                    // save for future use
                                    admobObjects.put(TYPE_INTERSTITIAL, fAdUnitId);
                                    admobObjects.put(fAdUnitId, interstitial);

                                    // load an interstitial
                                    interstitial.loadAd(fRequest);
                                    break;
                                case TYPE_REWARDEDVIDEO:
                                    // initialize object and set delegate
                                    RewardedAd rewardedAd = new RewardedAd(coronaActivity, fAdUnitId);
                                    // save for future use
                                    admobObjects.put(TYPE_REWARDEDVIDEO, fAdUnitId);
                                    admobObjects.put(fAdUnitId, rewardedAd);
                                    rewardedAd.loadAd(fRequest, new CoronaAdmobRewardedLoadDelegate(rewardedAd, fAdUnitId));
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
                                    banner.setAdSize(AdSize.SMART_BANNER);
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
            final String fAdType = adType;
            final String fAdUnitIdParam = adUnitIdParam;

            boolean isLoaded = false;

            try {
                if (coronaActivity != null) {
                    // since we're returning a value to Lua, we need to implement a FutureTask
                    FutureTask<Boolean> isLoadedTask = new FutureTask<>(new Callable<Boolean>() {
                        @Override
                        public Boolean call() {
                            boolean result = false;
                            String adUnitId;
                            if (fAdUnitIdParam != null) {
                                adUnitId = fAdUnitIdParam;
                            } else {
                                adUnitId = (String) admobObjects.get(fAdType);
                            }

                            if (adUnitId != null) {
                                switch (fAdType) {
                                    case TYPE_INTERSTITIAL:
                                        InterstitialAd interstitial = (InterstitialAd) admobObjects.get(adUnitId);
                                        result = (interstitial != null) && interstitial.isLoaded();
                                        break;
                                    case TYPE_REWARDEDVIDEO:
                                        RewardedAd rewardedAd = (RewardedAd) admobObjects.get(adUnitId);
                                        result = (rewardedAd != null) && rewardedAd.isLoaded();
                                        break;
                                    case TYPE_BANNER:
                                        AdView banner = (AdView) admobObjects.get(adUnitId);
                                        if (banner != null) {
                                            CoronaAdmobBannerDelegate bannerDelegate = (CoronaAdmobBannerDelegate) banner.getAdListener();
                                            result = bannerDelegate.isLoaded;
                                        }
                                        break;
                                }
                            }

                            // return result to FutureTask
                            return result;
                        }
                    });

                    // Run the task on the ui thread
                    coronaActivity.runOnUiThread(isLoadedTask);

                    // IMPORTANT! must use get() so FutureTask will block until it returns a value
                    isLoaded = isLoadedTask.get(2000, TimeUnit.MILLISECONDS);
                }
            } catch (Exception ex) {
                ex.printStackTrace();
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
                                InterstitialAd interstitial = (InterstitialAd) admobObjects.get(adUnitId);

                                if ((interstitial != null) && interstitial.isLoaded()) {
                                    // call special adOpened delegate (see delegate for more info)
                                    ((CoronaAdmobInterstitialDelegate) interstitial.getAdListener()).coronaAdOpened();
                                    interstitial.show();
                                } else {
                                    logMsg(WARNING_MSG, "Interstitial not loaded");
                                }
                                break;
                            case TYPE_REWARDEDVIDEO:
                                RewardedAd rewardedAd = (RewardedAd) admobObjects.get(adUnitId);

                                if ((rewardedAd != null) && rewardedAd.isLoaded()) {
                                    // call special adOpened delegate (see delegate for more info)
                                    CoronaAdmobRewardedShowDelegate delegate = new CoronaAdmobRewardedShowDelegate(rewardedAd, adUnitId);
                                    delegate.coronaAdOpened();
                                    rewardedAd.show(coronaActivity, delegate);
                                } else {
                                    logMsg(WARNING_MSG, "Rewarded Video not loaded");
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

                                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB_MR2) {
                                        if (orientation == ORIENTATION_PORTRAIT) {
                                            orientedHeight = display.getHeight();
                                        } else {
                                            orientedHeight = display.getWidth();
                                        }
                                    } else {
                                        Point size = new Point();
                                        display.getSize(size);

                                        if (orientation == ORIENTATION_PORTRAIT) {
                                            orientedHeight = size.y;
                                        } else {
                                            orientedHeight = size.x;
                                        }
                                    }

                                    // make sure the banner frame is visible.
                                    // adjust it if the user has specified 'y' which will render it partially off-screen
                                    if (newBannerY >= 0) { // offset from top
                                        if (newBannerY + banner.getAdSize().getHeight() > orientedHeight) {
                                            logMsg(WARNING_MSG, "Banner y position off screen. Adjusting position.");
                                            params.gravity = Gravity.BOTTOM | Gravity.CENTER;
                                        } else {
                                            params.gravity = Gravity.TOP | Gravity.CENTER;
                                            params.topMargin = (int) newBannerY;
                                        }
                                    } else {
                                        if (orientedHeight - banner.getAdSize().getHeight() + newBannerY < 0) {
                                            logMsg(WARNING_MSG, "Banner y position off screen. Adjusting position.");
                                            params.gravity = Gravity.TOP | Gravity.CENTER;
                                        } else {
                                            params.gravity = Gravity.BOTTOM | Gravity.CENTER;
                                            params.bottomMargin = Math.abs((int) newBannerY);
                                        }
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
                                    System.err.println();
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
                        if (banner.getVisibility() != View.VISIBLE) {
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

            if (!isSDKInitialized()) {
                return 0;
            }

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

                            if (adUnitId == null) {
                                logMsg(WARNING_MSG, "Banner not loaded");
                            } else {
                                AdView banner = (AdView) admobObjects.get(adUnitId);
                                if (banner != null) {
                                    result = banner.getAdSize().getHeightInPixels(coronaActivity) / (double) admobObjects.get(Y_RATIO_KEY);
                                }
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
            }

            luaState.pushNumber(height);

            return 1;
        }
    }

    // -------------------------------------------------------------------
    // Delegates
    // -------------------------------------------------------------------

    private static class CoronaAdmobDelegate extends AdListener {
        CoronaAdmobDelegate() {
        }

        String getAdRequestErrorMsg(int errorCode) {
            switch (errorCode) {
                case AdRequest.ERROR_CODE_INTERNAL_ERROR:
                    return "Internal Error";
                case AdRequest.ERROR_CODE_INVALID_REQUEST:
                    return "Invalid Request";
                case AdRequest.ERROR_CODE_NO_FILL:
                    return "No Ads Available";
                case AdRequest.ERROR_CODE_NETWORK_ERROR:
                    return "Network Error";
                default:
                    return "Unknown error";
            }
        }
    }

    // -------------------------------------------------------------------

    private class CoronaAdmobInterstitialDelegate extends CoronaAdmobDelegate {
        InterstitialAd interstitial;

        CoronaAdmobInterstitialDelegate(InterstitialAd interstitial) {
            this.interstitial = interstitial;
        }

        @Override
        public void onAdLoaded() {
            // create data
            JSONObject data = new JSONObject();
            try {
                data.put(DATA_ADUNIT_ID_KEY, interstitial.getAdUnitId());

                Map<String, Object> coronaEvent = new HashMap<>();
                coronaEvent.put(EVENT_PHASE_KEY, PHASE_LOADED);
                coronaEvent.put(EVENT_TYPE_KEY, TYPE_INTERSTITIAL);
                coronaEvent.put(EVENT_DATA_KEY, data.toString());
                dispatchLuaEvent(coronaEvent);
            } catch (Exception e) {
                System.err.println();
            }
        }

        public void coronaAdOpened() {
            // create data
            JSONObject data = new JSONObject();
            try {
                data.put(DATA_ADUNIT_ID_KEY, interstitial.getAdUnitId());

                Map<String, Object> coronaEvent = new HashMap<>();
                coronaEvent.put(EVENT_PHASE_KEY, PHASE_DISPLAYED);
                coronaEvent.put(EVENT_TYPE_KEY, TYPE_INTERSTITIAL);
                coronaEvent.put(EVENT_DATA_KEY, data.toString());
                dispatchLuaEvent(coronaEvent);
            } catch (Exception e) {
                System.err.println();
            }
        }

        @Override
        public void onAdOpened() {
            // NOP
            // ad activity takes control before Corona can process this event
            // so coronaAdOpened is called in show() instead
        }

        @Override
        public void onAdClosed() {
            // create data
            JSONObject data = new JSONObject();
            try {
                data.put(DATA_ADUNIT_ID_KEY, interstitial.getAdUnitId());

                Map<String, Object> coronaEvent = new HashMap<>();
                coronaEvent.put(EVENT_PHASE_KEY, PHASE_CLOSED);
                coronaEvent.put(EVENT_TYPE_KEY, TYPE_INTERSTITIAL);
                coronaEvent.put(EVENT_DATA_KEY, data.toString());
                dispatchLuaEvent(coronaEvent);
            } catch (Exception e) {
                System.err.println();
            }
        }

        @Override
        public void onAdLeftApplication() {
            // create data
            JSONObject data = new JSONObject();
            try {
                data.put(DATA_ADUNIT_ID_KEY, interstitial.getAdUnitId());

                Map<String, Object> coronaEvent = new HashMap<>();
                coronaEvent.put(EVENT_PHASE_KEY, PHASE_CLICKED);
                coronaEvent.put(EVENT_TYPE_KEY, TYPE_INTERSTITIAL);
                coronaEvent.put(EVENT_DATA_KEY, data.toString());
                dispatchLuaEvent(coronaEvent);
            } catch (Exception e) {
                System.err.println();
            }
        }

        @Override
        public void onAdFailedToLoad(int i) {
            // create data
            JSONObject data = new JSONObject();
            try {
                data.put(DATA_ERRORMSG_KEY, getAdRequestErrorMsg(i));
                data.put(DATA_ERRORCODE_KEY, i);
                data.put(DATA_ADUNIT_ID_KEY, interstitial.getAdUnitId());

                Map<String, Object> coronaEvent = new HashMap<>();
                coronaEvent.put(EVENT_PHASE_KEY, PHASE_FAILED);
                coronaEvent.put(EVENT_TYPE_KEY, TYPE_INTERSTITIAL);
                coronaEvent.put(CoronaLuaEvent.RESPONSE_KEY, RESPONSE_LOAD_FAILED);
                coronaEvent.put(EVENT_DATA_KEY, data.toString());
                dispatchLuaEvent(coronaEvent);
            } catch (Exception e) {
                System.err.println();
            }
        }
    }

    // -------------------------------------------------------------------

    private class CoronaAdmobRewardedShowDelegate extends RewardedAdCallback {
        RewardedAd rewardedAd;
        String adUnitId;

        CoronaAdmobRewardedShowDelegate(RewardedAd rewardedAd, String adUnitId) {
            this.rewardedAd = rewardedAd;
            this.adUnitId = adUnitId;
        }

        @Override
        public void onUserEarnedReward(@NonNull RewardItem rewardItem) {
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
                System.err.println();
            }
        }

        public void coronaAdOpened() {
            // create data
            JSONObject data = new JSONObject();
            try {
                data.put(DATA_ADUNIT_ID_KEY, adUnitId);

                Map<String, Object> coronaEvent = new HashMap<>();
                coronaEvent.put(EVENT_PHASE_KEY, PHASE_DISPLAYED);
                coronaEvent.put(EVENT_TYPE_KEY, TYPE_REWARDEDVIDEO);
                coronaEvent.put(EVENT_DATA_KEY, data.toString());
                dispatchLuaEvent(coronaEvent);
            } catch (Exception e) {
                System.err.println();
            }
        }

        @Override
        public void onRewardedAdOpened() {
            // NOP
            // ad activity takes control before Lua can process this event
            // so coronaAdOpened is called in show() instead
        }

        @Override
        public void onRewardedAdClosed() {
            // create data
            JSONObject data = new JSONObject();
            try {
                data.put(DATA_ADUNIT_ID_KEY, adUnitId);

                Map<String, Object> coronaEvent = new HashMap<>();
                coronaEvent.put(EVENT_PHASE_KEY, PHASE_CLOSED);
                coronaEvent.put(EVENT_TYPE_KEY, TYPE_REWARDEDVIDEO);
                coronaEvent.put(EVENT_DATA_KEY, data.toString());
                dispatchLuaEvent(coronaEvent);
            } catch (Exception e) {
                System.err.println();
            }
            invalidateAllViews();
        }

        @Override
        public void onRewardedAdFailedToShow(AdError err) {
            // create data
            JSONObject data = new JSONObject();
            try {
                data.put(DATA_ERRORMSG_KEY, err.toString());
                data.put(DATA_ERRORCODE_KEY, err.getCode());
                data.put(DATA_ADUNIT_ID_KEY, adUnitId);

                Map<String, Object> coronaEvent = new HashMap<>();
                coronaEvent.put(EVENT_PHASE_KEY, PHASE_FAILED);
                coronaEvent.put(EVENT_TYPE_KEY, TYPE_REWARDEDVIDEO);
                coronaEvent.put(CoronaLuaEvent.RESPONSE_KEY, RESPONSE_LOAD_FAILED);
                coronaEvent.put(EVENT_DATA_KEY, data.toString());
                coronaEvent.put(CoronaLuaEvent.ISERROR_KEY, true);
                dispatchLuaEvent(coronaEvent);
            } catch (Exception e) {
                System.err.println();
            }
        }
    }

    private class CoronaAdmobRewardedLoadDelegate extends  RewardedAdLoadCallback {
        RewardedAd rewardedAd;
        String adUnitId;

        CoronaAdmobRewardedLoadDelegate(RewardedAd rewardedAd, String adUnitId) {
            this.rewardedAd = rewardedAd;
            this.adUnitId = adUnitId;
        }

        @Override
        public void onRewardedAdLoaded() {
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
                System.err.println();
            }
        }

        @Override
        public void onRewardedAdFailedToLoad(LoadAdError adError) {
            JSONObject data = new JSONObject();
            try {
                data.put(DATA_ADUNIT_ID_KEY, adUnitId);
                Map<String, Object> coronaEvent = new HashMap<>();
                coronaEvent.put(EVENT_PHASE_KEY, PHASE_LOADED);
                coronaEvent.put(EVENT_TYPE_KEY, TYPE_REWARDEDVIDEO);
                coronaEvent.put(EVENT_DATA_KEY, data.toString());
                coronaEvent.put(CoronaLuaEvent.ISERROR_KEY, true);
                coronaEvent.put(CoronaLuaEvent.ERRORTYPE_KEY, adError.toString());
                logMsg(ERROR_MSG, "Error while loading ad " + adError);
                dispatchLuaEvent(coronaEvent);
            } catch (Exception e) {
                System.err.println();
            }
        }
    }


    // -------------------------------------------------------------------

    private class CoronaAdmobBannerDelegate extends CoronaAdmobDelegate {
        AdView currentBanner = null;
        boolean isLoaded = false;

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
                System.err.println();
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
                System.err.println();
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
                System.err.println();
            }
            invalidateAllViews();
        }

        @Override
        public void onAdLeftApplication() {
            // NOP
            // always preceded by onAdOpened
        }

        @Override
        public void onAdFailedToLoad(int i) {
            // create data
            JSONObject data = new JSONObject();
            try {
                data.put(DATA_ERRORMSG_KEY, getAdRequestErrorMsg(i));
                data.put(DATA_ERRORCODE_KEY, i);
                data.put(DATA_ADUNIT_ID_KEY, currentBanner.getAdUnitId());

                Map<String, Object> coronaEvent = new HashMap<>();
                coronaEvent.put(EVENT_PHASE_KEY, PHASE_FAILED);
                coronaEvent.put(EVENT_TYPE_KEY, TYPE_BANNER);
                coronaEvent.put(CoronaLuaEvent.RESPONSE_KEY, RESPONSE_LOAD_FAILED);
                coronaEvent.put(EVENT_DATA_KEY, data.toString());
                dispatchLuaEvent(coronaEvent);

                this.isLoaded = false;
            } catch (Exception e) {
                System.err.println();
            }
        }
    }
}
