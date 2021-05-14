//
//  AdMobPlugin.mm
//  AdMob Plugin
//
//  Copyright (c) 2016 Corona Labs Inc. All rights reserved.
//

#import <Foundation/Foundation.h>
#import <AdSupport/ASIdentifierManager.h>
#import <CommonCrypto/CommonDigest.h>
#import <UIKit/UIImage.h>

#import "CoronaRuntime.h"
#import "CoronaAssert.h"
#import "CoronaEvent.h"
#import "CoronaLua.h"
#import "CoronaLibrary.h"
#import "CoronaLuaIOS.h"

// AdMob
#import "UIColor+HexString.h"
#import "AdMobPlugin.h"
#import <GoogleMobileAds/GoogleMobileAds.h>

// some macros to make life easier, and code more readable
#define UTF8StringWithFormat(format, ...) [[NSString stringWithFormat:format, ##__VA_ARGS__] UTF8String]
#define UTF8IsEqual(utf8str1, utf8str2) (strcmp(utf8str1, utf8str2) == 0)
#define MsgFormat(format, ...) [NSString stringWithFormat:format, ##__VA_ARGS__]

// ----------------------------------------------------------------------------
// Plugin Constants
// ----------------------------------------------------------------------------

#define PLUGIN_NAME        "plugin.admob"
#define PLUGIN_VERSION     "1.2.6"

static const char EVENT_NAME[]    = "adsRequest";
static const char PROVIDER_NAME[] = "admob";

// ad types
static const char TYPE_BANNER[]        = "banner";
static const char TYPE_INTERSTITIAL[]  = "interstitial";
static const char TYPE_REWARDEDVIDEO[] = "rewardedVideo";

// banner alignments
static const char ALIGN_TOP[]    = "top";
static const char ALIGN_BOTTOM[] = "bottom";

// valid ad types
static const NSArray *validAdTypes = @[
	@(TYPE_BANNER),
	@(TYPE_INTERSTITIAL),
	@(TYPE_REWARDEDVIDEO)
];

// event phases
static NSString * const PHASE_INIT      = @"init";
static NSString * const PHASE_DISPLAYED = @"displayed";
static NSString * const PHASE_REFRESHED = @"refreshed";
static NSString * const PHASE_LOADED    = @"loaded";
static NSString * const PHASE_FAILED    = @"failed";
static NSString * const PHASE_CLOSED    = @"closed";
static NSString * const PHASE_HIDDEN    = @"hidden";
static NSString * const PHASE_CLICKED   = @"clicked";
static NSString * const PHASE_REWARD    = @"reward";

// reward keys
static NSString * const REWARD_ITEM   = @"rewardItem";
static NSString * const REWARD_AMOUNT = @"rewardAmount";

// response keys
static NSString * const RESPONSE_LOAD_FAILED = @"loadFailed";

// missing Corona Event Keys
static NSString * const CORONA_EVENT_DATA_KEY = @"data";

// message constants
static NSString * const ERROR_MSG   = @"ERROR: ";
static NSString * const WARNING_MSG = @"WARNING: ";

// saved objects (ad state, etc)
static NSMutableDictionary *admobObjects;

// object dictionary keys
static NSString * const TESTMODE_KEY    = @"testMode";
static NSString * const TEST_DEVICE_KEY = @"testDevice";
static NSString * const Y_RATIO_KEY     = @"yRatio";        // used to calculate Corona -> UIKit coordinate ratio

// event data keys
static NSString * const DATA_ERRORMSG_KEY  = @"errorMsg";
static NSString * const DATA_ERRORCODE_KEY = @"errorCode";
static NSString * const DATA_ADUNIT_ID_KEY = @"adUnitId";

// ----------------------------------------------------------------------------
// plugin class and delegate definitions
// ----------------------------------------------------------------------------

@interface CoronaAdMobAdInstance: NSObject

@property (nonatomic, strong) NSObject *adInstance;
@property (nonatomic, copy)   NSString *adType;
@property (nonatomic, assign) BOOL     isLoaded;

@property (nonatomic, strong) UIViewController *viewController;

- (instancetype)initWithAd:(NSObject *)adInstance adType:(NSString *)adType;

- (void)positionBannerViewInsideSafeArea:(UIView *_Nonnull)bannerView
							  withYAlign:(NSString *)yAlign
							 withYOffset:(CGFloat)yOffset;
- (void)positionBannerViewInsideSafeAreaiOS9Plus:(UIView *_Nonnull)bannerView
									  withYAlign:(NSString *)yAlign
									 withYOffset:(CGFloat)yOffset NS_AVAILABLE_IOS(9.0);
- (void)positionBannerViewInsideSafeAreaPreiOS9:(UIView *_Nonnull)bannerView
									 withYAlign:(NSString *)yAlign
									withYOffset:(CGFloat)yOffset;

@end

@interface CoronaAdMobDelegate: NSObject <GADInterstitialDelegate, GADBannerViewDelegate, GADRewardedAdDelegate>

@property (nonatomic, assign) CoronaLuaRef coronaListener;             // Reference to the Lua listener
@property (nonatomic, assign) id<CoronaRuntime> coronaRuntime;         // Pointer to the Corona runtime

- (void)dispatchLuaEvent:(NSDictionary *)dict;
- (NSString *)getJSONStringForRewardedAd:(GADRewardedAd *)ad;
- (NSString *)getJSONStringForRewardedAd:(GADRewardedAd *)ad reward:(GADAdReward *)reward;
- (NSString *)getJSONStringForRewardedAd:(GADRewardedAd *)ad reward:(GADAdReward *)reward error:(NSError *)error;

@end

// ----------------------------------------------------------------------------

class AdMobPlugin
{
public:
	typedef AdMobPlugin Self;
	
public:
	static const char kName[];
	
public:
	static int Open(lua_State *L);
	static int Finalizer(lua_State *L);
	static Self *ToLibrary(lua_State *L);
	
protected:
	AdMobPlugin();
	bool Initialize(void *platformContext);
	
public: // plugin API
	static int init(lua_State *L);
	static int load(lua_State *L);
	static int isLoaded(lua_State *L);
	static int show(lua_State *L);
	static int hide(lua_State *L);
	static int height(lua_State *L);
	static int setVideoAdVolume(lua_State *L);
	
private: // internal helper functions
	static void logMsg(lua_State *L, NSString *msgType,  NSString *errorMsg);
	static bool isSDKInitialized(lua_State *L);
	
private:
	NSString *functionSignature;              // used in logMsg to identify function
	UIViewController *coronaViewController;   // application's view controller
};

const char AdMobPlugin::kName[] = PLUGIN_NAME;
CoronaAdMobDelegate *admobDelegate;                                 // AdMob delegate

// ----------------------------------------------------------------------------
// helper functions
// ----------------------------------------------------------------------------

// log message to console
void
AdMobPlugin::logMsg(lua_State *L, NSString* msgType, NSString* errorMsg)
{
	Self *context = ToLibrary(L);
	
	if (context) {
		Self& library = *context;
		
		NSString *functionID = [library.functionSignature copy];
		if (functionID.length > 0) {
			functionID = [functionID stringByAppendingString:@", "];
		}
		
		CoronaLuaLogPrefix(L, [msgType UTF8String], UTF8StringWithFormat(@"%@%@", functionID, errorMsg));
	}
}

// check if SDK calls can be made
bool
AdMobPlugin::isSDKInitialized(lua_State *L)
{
	if (admobDelegate.coronaListener == NULL) {
		logMsg(L, ERROR_MSG, @"admob.init() must be called before calling other API methods.");
		return false;
	}
	
	return true;
}

// ----------------------------------------------------------------------------
// plugin implementation
// ----------------------------------------------------------------------------

int
AdMobPlugin::Open( lua_State *L )
{
	// Register __gc callback
	const char kMetatableName[] = __FILE__; // Globally unique string to prevent collision
	CoronaLuaInitializeGCMetatable( L, kMetatableName, Finalizer );
	
	void *platformContext = CoronaLuaGetContext(L);
	
	// Set library as upvalue for each library function
	Self *library = new Self;
	
	if (library->Initialize(platformContext)) {
		// Functions in library
		static const luaL_Reg kFunctions[] = {
			{"init", init},
			{"load", load},
			{"isLoaded", isLoaded},
			{"show", show},
			{"hide", hide},
			{"height", height},
			{"setVideoAdVolume", setVideoAdVolume},
			{NULL, NULL}
		};
		
		// Register functions as closures, giving each access to the
		// 'library' instance via ToLibrary()
		{
			CoronaLuaPushUserdata(L, library, kMetatableName);
			luaL_openlib(L, kName, kFunctions, 1); // leave "library" on top of stack
		}
	}
	
	return 1;
}

int
AdMobPlugin::Finalizer( lua_State *L )
{
	Self *library = (Self *)CoronaLuaToUserdata(L, 1);
	
	// Free the Lua listener
	CoronaLuaDeleteRef(L, admobDelegate.coronaListener);
	
	admobDelegate = nil;
	delete library;
	
	return 0;
}

AdMobPlugin*
AdMobPlugin::ToLibrary( lua_State *L )
{
	// library is pushed as part of the closure
	Self *library = (Self *)CoronaLuaToUserdata( L, lua_upvalueindex( 1 ) );
	return library;
}

AdMobPlugin::AdMobPlugin()
: coronaViewController(nil)
{
}

bool
AdMobPlugin::Initialize( void *platformContext )
{
	bool shouldInit = (! coronaViewController);
	
	if (shouldInit) {
		id<CoronaRuntime> runtime = (__bridge id<CoronaRuntime>)platformContext;
		coronaViewController = runtime.appViewController;
		
		functionSignature = @"";
		
		// initialize delegate
		admobDelegate = [CoronaAdMobDelegate new];
		admobDelegate.coronaRuntime = runtime;
		
		// intialize ad object dictionary
		admobObjects = [NSMutableDictionary new];
		admobObjects[TESTMODE_KEY] = @(false);
	}
	
	return shouldInit;
}

// [Lua] init(listener, options)
int
AdMobPlugin::init(lua_State *L)
{
	Self *context = ToLibrary(L);
	
	if (! context) { // abort if no valid context
		return 0;
	}
	
	Self& library = *context;
	
	library.functionSignature = @"admob.init(listener, options)";
	
	// prevent init from being called twice
	if (admobDelegate.coronaListener != NULL) {
		logMsg(L, WARNING_MSG, @"init() should only be called once");
		return 0;
	}
	
	// check number or args
	int nargs = lua_gettop(L);
	if (nargs != 2) {
		logMsg(L, ERROR_MSG, MsgFormat(@"Expected 2 arguments, got %d", nargs));
		return 0;
	}
	
	bool testMode = false;
	CGFloat videoAdVolume = 1.0;
	
	// Get the listener (required)
	if (CoronaLuaIsListener(L, 1, PROVIDER_NAME)) {
		admobDelegate.coronaListener = CoronaLuaNewRef(L, 1);
	}
	else {
		logMsg(L, ERROR_MSG, MsgFormat(@"Listener expected, got: %s", luaL_typename(L, 1)));
		return 0;
	}
	
	// check for options table (required)
	if (lua_type(L, 2) == LUA_TTABLE) {
		// traverse and verify all options
		for (lua_pushnil(L); lua_next(L, 2) != 0; lua_pop(L, 1)) {
			const char *key = lua_tostring(L, -2);
			
			if (UTF8IsEqual(key, "appId")) {
				logMsg(L, ERROR_MSG, @"options.appId is not supported anymore. Move it to plist");
			}
			else if (UTF8IsEqual(key, "testMode")) {
				if (lua_type(L, -1) == LUA_TBOOLEAN) {
					testMode = lua_toboolean(L, -1);
				}
				else {
					logMsg(L, ERROR_MSG, MsgFormat(@"options.testMode (boolean) expected, got: %s", luaL_typename(L, -1)));
					return 0;
				}
			}
			else if (UTF8IsEqual(key, "videoAdVolume")) {
				if (lua_type(L, -1) == LUA_TNUMBER) {
					videoAdVolume = lua_tonumber(L, -1);
				}
				else {
					logMsg(L, ERROR_MSG, MsgFormat(@"options.videoAdVolume (number) expected, got: %s", luaL_typename(L, -1)));
					return 0;
				}
			}
			else {
				logMsg(L, ERROR_MSG, MsgFormat(@"Invalid option '%s'", key));
				return 0;
			}
		}
	}
	// no options table
	else {
		logMsg(L, ERROR_MSG, MsgFormat(@"options table expected, got %s", luaL_typename(L, 2)));
		return 0;
	}
	
	// generate Google Test ID for current device
	if (testMode) {
		NSUUID* deviceID = [[ASIdentifierManager sharedManager] advertisingIdentifier];
		const char *deviceStr = [deviceID.UUIDString UTF8String];
		unsigned char digest[16];
		CC_MD5(deviceStr, (CC_LONG)strlen(deviceStr), digest);
		
		NSMutableString *admobDeviceID = [NSMutableString stringWithCapacity:CC_MD5_DIGEST_LENGTH * 2];
		
		for(int i = 0; i < CC_MD5_DIGEST_LENGTH; i++) {
			[admobDeviceID appendFormat:@"%02x", digest[i]];
		}
		
		NSLog(@"%s: Generated AdMob Test ID '%@'", PLUGIN_NAME, admobDeviceID);
		admobObjects[TEST_DEVICE_KEY] = [NSString stringWithString:admobDeviceID];
	}
	
	// log plugin version to device log
	NSLog(@"%s: %s (SDK: %@)", PLUGIN_NAME, PLUGIN_VERSION, GADMobileAds.sharedInstance.sdkVersion);
	
	// save values for future use
	admobObjects[TESTMODE_KEY] = @(testMode);
	
	// initialize the SDK
	[GADMobileAds.sharedInstance disableSDKCrashReporting];
	[GADMobileAds.sharedInstance disableAutomatedInAppPurchaseReporting];
	[GADMobileAds.sharedInstance startWithCompletionHandler:^(GADInitializationStatus * _Nonnull status) {
		NSDictionary *coronaEvent = @{
			@(CoronaEventPhaseKey()) : PHASE_INIT
		};
		[admobDelegate dispatchLuaEvent:coronaEvent];
	}];
	
	// set desired volume for video ads. Default is 1.0
	[GADMobileAds sharedInstance].applicationVolume = videoAdVolume;
		
	return 0;
}

// [Lua] load(adtype, options)
int
AdMobPlugin::load(lua_State *L)
{
	Self *context = ToLibrary(L);
	
	if (! context) { // abort if no valid context
		return 0;
	}
	
	Self& library = *context;
	
	library.functionSignature = @"admob.load(adType, options)";
	
	if (! isSDKInitialized(L)) {
		return 0;
	}
	
	// check number or args
	int nargs = lua_gettop(L);
	if ((nargs != 2)) {
		logMsg(L, ERROR_MSG, MsgFormat(@"Expected 2 arguments, got %d", nargs));
		return 0;
	}
	
	GADMaxAdContentRating maxAdRaiting = nil;
	const char *adType = NULL;
	const char *adUnitId = NULL;
	bool childSafe = false;
	bool designedForFamilies = false;
	bool localTestMode = false;
	bool localTestModeIsSet = false;
	NSMutableArray *keywords = [NSMutableArray new];
	NSNumber *hasUserConsent = nil;
	
	// get the ad type
	if (lua_type(L, 1) == LUA_TSTRING) {
		adType = lua_tostring(L, 1);
	}
	else {
		logMsg(L, ERROR_MSG, MsgFormat(@"adType (string) expected, got %s", luaL_typename(L, 1)));
		return 0;
	}
	
	// check for options table (required)
	if (lua_type(L, 2) == LUA_TTABLE) {
		// traverse and validate all the options
		for (lua_pushnil(L); lua_next(L, 2) != 0; lua_pop(L, 1)) {
			const char *key = lua_tostring(L, -2);
			
			if (UTF8IsEqual(key, "adUnitId")) {
				if (lua_type(L, -1) == LUA_TSTRING) {
					adUnitId = lua_tostring(L, -1);
				}
				else {
					logMsg(L, ERROR_MSG, MsgFormat(@"options.adUnitId (string) expected, got %s", luaL_typename(L, -1)));
					return 0;
				}
			}
			else if (UTF8IsEqual(key, "childSafe")) {
				if (lua_type(L, -1) == LUA_TBOOLEAN) {
					childSafe = lua_toboolean(L, -1);
				}
				else {
					logMsg(L, ERROR_MSG, MsgFormat(@"options.childSafe (boolean) expected, got %s", luaL_typename(L, -1)));
					return 0;
				}
			}
			else if (UTF8IsEqual(key, "maxAdContentRating")) {
				if (lua_type(L, -1) == LUA_TSTRING) {
					NSString *str = [NSString stringWithUTF8String:lua_tostring(L, -1)];
					maxAdRaiting = (GADMaxAdContentRating)[(@{
						@"G":GADMaxAdContentRatingGeneral,
						@"PG":GADMaxAdContentRatingParentalGuidance,
						@"T":GADMaxAdContentRatingTeen,
						@"M":GADMaxAdContentRatingMatureAudience,
						@"MA":GADMaxAdContentRatingMatureAudience,
					}) objectForKey:str];
					if(maxAdRaiting == nil) {
						logMsg(L, ERROR_MSG, MsgFormat(@"options.maxAdContentRating must be one of string constants: 'G', 'PG', 'T' or 'MA', got: %s", lua_tostring(L, -1)));
						return 0;
					}
				}
				else {
					logMsg(L, ERROR_MSG, MsgFormat(@"options.maxAdContentRating (string) expected, got %s", luaL_typename(L, -1)));
					return 0;
				}
			}
			else if (UTF8IsEqual(key, "designedForFamilies")) {
				// Designed for Families only applies to Google Play, however we need to handle the option here
				if (lua_type(L, -1) == LUA_TBOOLEAN) {
					designedForFamilies = lua_toboolean(L, -1);
				}
				else {
					logMsg(L, ERROR_MSG, MsgFormat(@"options.designedForFamilies (boolean) expected, got %s", luaL_typename(L, -1)));
					return 0;
				}
			}
			else if (UTF8IsEqual(key, "keywords")) {
				if (lua_type(L, -1) == LUA_TTABLE) {
					// build supported ad types
					int ntypes = (int)lua_objlen(L, -1);
					
					if (ntypes > 0) {
						for (int i=1; i<=ntypes; i++) {
							//push array value onto stack
							lua_rawgeti(L, -1, i);
							
							// add keyword to array
							if (lua_type(L, -1) == LUA_TSTRING) {
								[keywords addObject:@(lua_tostring(L, -1))];
							}
							else {
								logMsg(L, ERROR_MSG, MsgFormat(@"options.keywords[%d] (string) expected, got: %s", i, luaL_typename(L, -1)));
								return 0;
							}
							lua_pop(L, 1);
						}
					}
					else {
						logMsg(L, ERROR_MSG, MsgFormat(@"options.keywords table cannot be empty"));
						return 0;
					}
				}
				else {
					logMsg(L, ERROR_MSG, MsgFormat(@"options.keywords (table) expected, got: %s", luaL_typename(L, -1)));
					return 0;
				}
			}
			else if (UTF8IsEqual(key, "testMode")) {
				if (lua_type(L, -1) == LUA_TBOOLEAN) {
					localTestMode = lua_toboolean(L, -1);
					localTestModeIsSet = true;
				}
				else {
					logMsg(L, ERROR_MSG, MsgFormat(@"options.testMode (boolean) expected, got %s", luaL_typename(L, -1)));
					return 0;
				}
			} else if (UTF8IsEqual(key, "hasUserConsent")) {
				if (lua_type(L, -1) == LUA_TBOOLEAN) {
					hasUserConsent = [NSNumber numberWithBool:lua_toboolean(L, -1)];
				}
				else {
					logMsg(L, ERROR_MSG, MsgFormat(@"options.hasUserConsent (boolean) expected, got: %s", luaL_typename(L, -1)));
					return 0;
				}
			}
			else {
				logMsg(L, ERROR_MSG, MsgFormat(@"Invalid option '%s'", key));
				return 0;
			}
		}
	}
	else {
		logMsg(L, ERROR_MSG, MsgFormat(@"options table expected, got %s", luaL_typename(L, 2)));
		return 0;
	}
	
	// check required params
	if (adUnitId == NULL) {
		logMsg(L, ERROR_MSG, @"options.adUnitId is required");
		return 0;
	}
	
	// check valid ad type
	if (! [validAdTypes containsObject:@(adType)]) {
		logMsg(L, ERROR_MSG, MsgFormat(@"Invalid adType '%s'", adType));
		return 0;
	}
	
	// initialize request object
	GADRequest *request = [GADRequest request];
	
	// set child safe flag
	[GADMobileAds.sharedInstance.requestConfiguration tagForChildDirectedTreatment:childSafe];
	
	if(maxAdRaiting) {
		[GADMobileAds.sharedInstance.requestConfiguration setMaxAdContentRating:maxAdRaiting];
	}
	
	// check user consent
	if (hasUserConsent != nil && ![hasUserConsent boolValue]) {
		GADExtras *extras = [[GADExtras alloc] init];
		extras.additionalParameters = @{@"npa": @"1"};
		[request registerAdNetworkExtras:extras];
	}
	
	// add keywords to request
	if (keywords.count > 0) {
		[request setKeywords:keywords];
	}
	
	// is test mode activated?
	bool globalTestMode = [admobObjects[TESTMODE_KEY] boolValue];
	
	// local test mode can only be activated if global test mode has been set
	// this error message is here to help migration from the legacy AdMob plugin
	if (localTestModeIsSet && ! globalTestMode) {
		logMsg(L, ERROR_MSG, @"testMode should be specified during init(). Please remove from load()");
		return 0;
	}
	
	if ((localTestModeIsSet && localTestMode) || ((!localTestModeIsSet) && globalTestMode)) {
		NSString *admobDeviceId = admobObjects[TEST_DEVICE_KEY];
		GADMobileAds.sharedInstance.requestConfiguration.testDeviceIdentifiers = @[kGADSimulatorID, admobDeviceId];
		NSLog(@"%s: Test mode active for device '%@'", PLUGIN_NAME, admobDeviceId);
	}
	
	// check old adInstance (if available)
	CoronaAdMobAdInstance *adInstance = admobObjects[@(adUnitId)];
	if ((adInstance != nil) && ! [adInstance.adType isEqualToString:@(adType)]) {
		logMsg(L, ERROR_MSG, MsgFormat(@"AdUnitId '%s' is not a %s", adUnitId, adType));
		return 0;
	}
	
	// load specified ad type
	if (UTF8IsEqual(adType, TYPE_INTERSTITIAL)) {
		// initialize object and set delegate
		GADInterstitial *interstitial = [[GADInterstitial alloc] initWithAdUnitID:@(adUnitId)];
		[interstitial setDelegate: admobDelegate];
		
		// create ad instance object (stores additional info about the ad not available in GADInterstitial)
		adInstance = [[CoronaAdMobAdInstance alloc] initWithAd:interstitial adType:@(adType)];
		
		// save for future use
		admobObjects[@(TYPE_INTERSTITIAL)] = @(adUnitId);
		admobObjects[@(adUnitId)] = adInstance;
		
		// load the interstitial
		[interstitial loadRequest:request];
	}
	else if (UTF8IsEqual(adType, TYPE_REWARDEDVIDEO)) {
		GADRewardedAd *rewardedAd = [[[GADRewardedAd alloc] initWithAdUnitID:@(adUnitId)] autorelease];
		
		// create ad instance object (stores additional info about the ad not available in GADRewardedAd)
		adInstance = [[CoronaAdMobAdInstance alloc] initWithAd:rewardedAd adType:@(adType)];
		
		// save for future use
		admobObjects[@(TYPE_REWARDEDVIDEO)] = @(adUnitId);
		admobObjects[@(adUnitId)] = adInstance;
		
		// load the rewarded ad
		[rewardedAd loadRequest:request completionHandler:^(GADRequestError * _Nullable error) {
			if (error) {
				// send Corona Lua event
				NSDictionary *coronaEvent = @{
					@(CoronaEventPhaseKey()) : PHASE_FAILED,
					@(CoronaEventTypeKey()) : @(TYPE_REWARDEDVIDEO),
					@(CoronaEventIsErrorKey()) : @(true),
					@(CoronaEventResponseKey()) : RESPONSE_LOAD_FAILED,
					CORONA_EVENT_DATA_KEY : [admobDelegate getJSONStringForRewardedAd:rewardedAd reward:nil error:error]
				};
				[admobDelegate dispatchLuaEvent:coronaEvent];
				
				adInstance.isLoaded = false;
			} else {
				// send Corona Lua event
				NSDictionary *coronaEvent = @{
					@(CoronaEventPhaseKey()) : PHASE_LOADED,
					@(CoronaEventTypeKey()) : @(TYPE_REWARDEDVIDEO),
					CORONA_EVENT_DATA_KEY : [admobDelegate getJSONStringForRewardedAd:rewardedAd]
				};
				[admobDelegate dispatchLuaEvent:coronaEvent];
				
				adInstance.isLoaded = true;
			}
		}];
	}
	else if (UTF8IsEqual(adType, TYPE_BANNER)) {
		
		// calculate the Corona->device coordinate ratio
		// we use Corona's built-in point conversion to take advantage of any device specific logic in the Corona core
		// we also need to re-calculate this value on every load as the ratio can change between orientation changes
		CGPoint point1 = {0, 0};
		CGPoint point2 = {1000, 1000};
		CGPoint uikitPoint1 = [admobDelegate.coronaRuntime coronaPointToUIKitPoint: point1];
		CGPoint uikitPoint2 = [admobDelegate.coronaRuntime coronaPointToUIKitPoint: point2];
		CGFloat yRatio = (uikitPoint2.y - uikitPoint1.y) / 1000.0;
		admobObjects[Y_RATIO_KEY] = @(yRatio);
		
		// set ad size
		UIInterfaceOrientation currentOrientation = [[UIApplication sharedApplication] statusBarOrientation];
		GADAdSize adSize = UIInterfaceOrientationIsLandscape(currentOrientation) ? kGADAdSizeSmartBannerLandscape : kGADAdSizeSmartBannerPortrait;
		
		// initialize object and set delegate
		GADBannerView *banner = [[GADBannerView alloc] initWithAdSize:adSize];
		banner.translatesAutoresizingMaskIntoConstraints = NO;
		[banner setDelegate:admobDelegate];
		[banner setAdUnitID:@(adUnitId)];
		[banner setRootViewController:library.coronaViewController];
		
		// create ad instance object (stores additional info about the ad not available in GADBannerView)
		adInstance = [[CoronaAdMobAdInstance alloc] initWithAd:banner adType:@(adType)];
		adInstance.viewController = library.coronaViewController;
		
		// save new banner for future use
		admobObjects[@(TYPE_BANNER)] = @(adUnitId);
		admobObjects[@(adUnitId)] = adInstance;
		
		// load the banner
		GADRequest *request = [GADRequest request];
		[banner loadRequest:request];
	}
	
	return 0;
}

// [Lua] show(adtype [, options ])
int
AdMobPlugin::show(lua_State *L)
{
	Self *context = ToLibrary(L);
	
	if (! context) { // abort if no valid context
		return 0;
	}
	
	Self& library = *context;
	
	library.functionSignature = @"admob.show(adType [, options ])";
	
	if (! isSDKInitialized(L)) {
		return 0;
	}
	
	// check number or args
	int nargs = lua_gettop(L);
	if ((nargs < 1) || (nargs > 2)) {
		logMsg(L, ERROR_MSG, MsgFormat(@"Expected 1 or 2 arguments, got %d", nargs));
		return 0;
	}
	
	const char *adType = NULL;
	const char *yAlign = NULL;
	const char *bgColor = NULL;
	const char *adUnitIdParam = NULL;
	CGFloat yOffset = 0;
	bool yIsSet = false;
	
	// get the ad type
	if (lua_type(L, 1) == LUA_TSTRING) {
		adType = lua_tostring(L, 1);
	}
	else {
		logMsg(L, ERROR_MSG, MsgFormat(@"adType (string) expected, got %s", luaL_typename(L, 1)));
		return 0;
	}
	
	// check for options table
	if (! lua_isnoneornil(L, 2)) {
		if (lua_type(L, 2) == LUA_TTABLE) {
			// traverse and validate all the options
			for (lua_pushnil(L); lua_next(L, 2) != 0; lua_pop(L, 1)) {
				const char *key = lua_tostring(L, -2);
				
				if (UTF8IsEqual(key, "adUnitId")) {
					if (lua_type(L, -1) == LUA_TSTRING) {
						adUnitIdParam = lua_tostring(L, -1);
					}
					else {
						logMsg(L, ERROR_MSG, MsgFormat(@"options.adUnitId (string) expected, got %s", luaL_typename(L, -1)));
						return 0;
					}
				}
				else if (UTF8IsEqual(key, "y")) {
					yIsSet = true;
					
					if (lua_type(L, -1) == LUA_TNUMBER) {
						yOffset = lua_tonumber(L, -1);
					}
					else if (lua_type(L, -1) == LUA_TSTRING) {
						yAlign = lua_tostring(L, -1);
					}
					else {
						logMsg(L, ERROR_MSG, MsgFormat(@"options.y (number or string) expected, got %s", luaL_typename(L, -1)));
						return 0;
					}
				}
				else if (UTF8IsEqual(key, "bgColor")) {
					if (lua_type(L, -1) == LUA_TSTRING) {
						bgColor = lua_tostring(L, -1);
					}
					else {
						logMsg(L, ERROR_MSG, MsgFormat(@"options.bgColor (string) expected, got %s", luaL_typename(L, -1)));
						return 0;
					}
				}
				else {
					logMsg(L, ERROR_MSG, MsgFormat(@"Invalid option '%s'", key));
					return 0;
				}
			}
		}
		else {
			logMsg(L, ERROR_MSG, MsgFormat(@"options table expected, got %s", luaL_typename(L, 2)));
			return 0;
		}
	}
	
	// validation section
	if (! [validAdTypes containsObject:@(adType)]) {
		logMsg(L, ERROR_MSG, MsgFormat(@"Invalid adType '%s'", adType));
		return 0;
	}
	
	if (! yIsSet) {
		yAlign = ALIGN_BOTTOM;
	}
	
	if (yAlign != NULL) {
		if (! UTF8IsEqual(yAlign, ALIGN_TOP) && ! UTF8IsEqual(yAlign, ALIGN_BOTTOM)) {
			logMsg(L, ERROR_MSG, MsgFormat(@"Invalid yAlign '%s'", yAlign));
			return 0;
		}
	}
	
	if (bgColor != NULL) {
		if (! [[NSString stringWithUTF8String:bgColor] hasPrefix:@"#"]) {
			logMsg(L, ERROR_MSG, MsgFormat(@"options.bgColor: Invalid color string '%s'. Must start with '#'", bgColor));
			return 0;
		}
		else {
			try {
				[UIColor colorWithHexString:@(bgColor)];
			}
			catch(NSException *e) {
				logMsg(L, ERROR_MSG, MsgFormat(@"options.bgColor: Unknown color '%s'", bgColor));
				return 0;
			}
		}
	}
	
	// get adUnitId (explicit or default)
	NSString *adUnitId;
	if (adUnitIdParam != NULL) {
		adUnitId = [NSString stringWithUTF8String:adUnitIdParam];
	}
	else { // default
		adUnitId = admobObjects[@(adType)];
		if (adUnitId == nil) {
			logMsg(L, WARNING_MSG, MsgFormat(@"%s not loaded", adType));
			return 0;
		}
	}
	
	CoronaAdMobAdInstance *adInstance = admobObjects[adUnitId];
	if (adInstance == nil) {
		logMsg(L, WARNING_MSG, MsgFormat(@"%s not loaded", adType));
		return 0;
	}
	
	// save value as default setting
	admobObjects[@(adType)] = adUnitId;
	
	// show specified ad type
	if (UTF8IsEqual(adType, TYPE_INTERSTITIAL)) {
		GADInterstitial *interstitial = (GADInterstitial *)adInstance.adInstance;
		if (! interstitial.isReady) {
			logMsg(L, WARNING_MSG, MsgFormat(@"Interstitial not loaded for adUnitId '%@'", adUnitId));
			return 0;
		}
		
		[interstitial presentFromRootViewController:library.coronaViewController];
	}
	else if (UTF8IsEqual(adType, TYPE_REWARDEDVIDEO)) {
		GADRewardedAd *rewardedAd = (GADRewardedAd *)adInstance.adInstance;
		if (! rewardedAd.isReady) {
			logMsg(L, WARNING_MSG, MsgFormat(@"Rewarded Video not loaded for adUnitId '%@'", adUnitId));
			return 0;
		}
		
		[rewardedAd presentFromRootViewController:library.coronaViewController delegate:admobDelegate];
	}
	else if (UTF8IsEqual(adType, TYPE_BANNER)) {
		GADBannerView *banner = (GADBannerView *)adInstance.adInstance;
		if (! adInstance.isLoaded) {
			logMsg(L, WARNING_MSG, MsgFormat(@"Banner not loaded for adUnitId '%@'", adUnitId));
			return 0;
		}
		
		if (banner.superview != nil) {
			logMsg(L, WARNING_MSG, @"Banner already visible");
			return 0;
		}
		//
		//      // make sure the banner frame is visible.
		//      // adjust it if the user has specified 'y' which will render it partially off-screen
		//      NSUInteger ySnap = 0;
		//      if (newBannerY + bannerFrame.size.height > orientedHeight) {
		//        logMsg(L, WARNING_MSG, @"Banner y position off screen. Adjusting position.");
		//        ySnap = newBannerY - orientedHeight + bannerFrame.size.height;
		//      }
		//      bannerFrame.origin.x = 0;
		//      bannerFrame.origin.y = newBannerY - ySnap;
		//    }
		//
		if (bgColor != NULL) {
			[banner setBackgroundColor:[UIColor colorWithHexString:@(bgColor)]];
		}
		//    [banner setFrame:bannerFrame];
		[library.coronaViewController.view addSubview:banner];
		
		NSString *align = nil;
		if (yAlign != NULL)
		{
			align = [NSString stringWithUTF8String:yAlign];
		}
		
		dispatch_async(dispatch_get_main_queue(), ^{
			[adInstance positionBannerViewInsideSafeArea:banner withYAlign:align withYOffset:yOffset];
		});
		
		// send Lua event
		[admobDelegate adViewWillPresentScreen:banner];
	}
	
	return 0;
}

// [Lua] hide()
int
AdMobPlugin::hide(lua_State *L)
{
	Self *context = ToLibrary(L);
	
	if (! context) { // abort if no valid context
		return 0;
	}
	
	Self& library = *context;
	
	library.functionSignature = @"admob.hide()";
	
	if (! isSDKInitialized(L)) {
		return 0;
	}
	
	// check number or args
	int nargs = lua_gettop(L);
	if (nargs != 0) {
		logMsg(L, ERROR_MSG, MsgFormat(@"Expected no arguments, got %d", nargs));
		return 0;
	}
	
	// get banner
	NSString *adUnitId = admobObjects[@(TYPE_BANNER)];
	if (adUnitId == nil) {
		logMsg(L, WARNING_MSG, @"Banner not loaded");
		return 0;
	}
	
	CoronaAdMobAdInstance *adInstance = admobObjects[adUnitId];
	
	GADBannerView *banner = (GADBannerView *)adInstance.adInstance;
	if (banner == nil) {
		logMsg(L, ERROR_MSG, MsgFormat(@"Banner not loaded for adUnitId '%@'", adUnitId));
		return 0;
	}
	
	if (banner.superview == nil) {
		logMsg(L, WARNING_MSG, MsgFormat(@"Banner not visible for adUnitId '%@'", adUnitId));
		return 0;
	}
	
	// hide the banner
	[banner removeFromSuperview];
	
	// send Lua event
	[admobDelegate adViewDidDismissScreen:banner];
	
	return 0;
}

// [Lua] height( [options] )
int
AdMobPlugin::height(lua_State *L)
{
	Self *context = ToLibrary(L);
	
	if (! context) { // abort if no valid context
		return 0;
	}
	
	Self& library = *context;
	
	library.functionSignature = @"admob.height( [options] )";
	
	if (! isSDKInitialized(L)) {
		return 0;
	}
	
	// check number or args
	int nargs = lua_gettop(L);
	if (nargs > 1) {
		logMsg(L, ERROR_MSG, MsgFormat(@"Expected 0 or 1 argument, got %d", nargs));
		return 0;
	}
	
	const char *adUnitIdParam = NULL;
	
	// check for options table
	if (! lua_isnoneornil(L, 1)) {
		if (lua_type(L, 1) == LUA_TTABLE) {
			// traverse and validate all the options
			for (lua_pushnil(L); lua_next(L, 1) != 0; lua_pop(L, 1)) {
				const char *key = lua_tostring(L, -2);
				
				if (UTF8IsEqual(key, "adUnitId")) {
					if (lua_type(L, -1) == LUA_TSTRING) {
						adUnitIdParam = lua_tostring(L, -1);
					}
					else {
						logMsg(L, ERROR_MSG, MsgFormat(@"options.adUnitId (string) expected, got %s", luaL_typename(L, -1)));
						return 0;
					}
				}
				else {
					logMsg(L, ERROR_MSG, MsgFormat(@"Invalid option '%s'", key));
					return 0;
				}
			}
		}
		else {
			logMsg(L, ERROR_MSG, MsgFormat(@"options table expected, got %s", luaL_typename(L, 1)));
			return 0;
		}
	}
	
	// get banner
	NSString *adUnitId;
	if (adUnitIdParam != NULL) {
		adUnitId = [NSString stringWithUTF8String:adUnitIdParam];
	}
	else {
		adUnitId = admobObjects[@(TYPE_BANNER)];
	}
	
	if (adUnitId == nil) {
		logMsg(L, WARNING_MSG, @"Banner not loaded");
		return 0;
	}
	
	double height = 0;
	
	CoronaAdMobAdInstance *adInstance = admobObjects[adUnitId];
	
	if (adInstance != nil) {
		GADBannerView *banner = (GADBannerView *)adInstance.adInstance;
		if (banner == nil) {
			logMsg(L, ERROR_MSG, MsgFormat(@"Banner not loaded for adUnitId '%@'", adUnitId));
		}
		else {
			height = floor(banner.frame.size.height / [admobObjects[Y_RATIO_KEY] floatValue]);
		}
	}
	
	lua_pushnumber(L, height);
	
	return 1;
}

// [Lua] setVideoAdVolume( videoAdVolume )
int
AdMobPlugin::setVideoAdVolume(lua_State *L)
{
	Self *context = ToLibrary(L);
	
	if (! context) { // abort if no valid context
		return 0;
	}
	
	Self& library = *context;
	
	library.functionSignature = @"admob.setVideoAdVolume( videoAdVolume )";
	
	if (! isSDKInitialized(L)) {
		return 0;
	}
	
	// check number of arguments
	int nargs = lua_gettop(L);
	if (nargs != 1) {
		logMsg(L, ERROR_MSG, MsgFormat(@"Expected 1 argument, got %d", nargs));
		return 0;
	}
	
	double videoAdVolume = 1.0;
	
	if (lua_type(L, -1) == LUA_TNUMBER) {
		videoAdVolume = lua_tonumber(L, -1);
	}
	else {
		logMsg(L, ERROR_MSG, MsgFormat(@"videoAdVolume (number) expected, got: %s", luaL_typename(L, -1)));
		return 0;
	}
	
	// set desired volume for video ads
	[GADMobileAds sharedInstance].applicationVolume = videoAdVolume;
	
	return 0;
}

// [Lua] isLoaded(adtype [, options])
int
AdMobPlugin::isLoaded(lua_State *L)
{
	Self *context = ToLibrary(L);
	
	if (! context) { // abort if no valid context
		return 0;
	}
	
	Self& library = *context;
	
	library.functionSignature = @"admob.isLoaded(adType [, options])";
	
	if (! isSDKInitialized(L)) {
		return 0;
	}
	
	// check number or args
	int nargs = lua_gettop(L);
	if ((nargs < 1) || (nargs > 2)) {
		logMsg(L, ERROR_MSG, MsgFormat(@"Expected 1 or 2 arguments, got %d", nargs));
		return 0;
	}
	
	const char *adType = NULL;
	const char *adUnitIdParam = NULL;
	
	// get the ad type
	if (lua_type(L, 1) == LUA_TSTRING) {
		adType = lua_tostring(L, 1);
	}
	else {
		logMsg(L, ERROR_MSG, MsgFormat(@"adType (string) expected, got %s", luaL_typename(L, 1)));
		return 0;
	}
	
	// check for options table
	if (! lua_isnoneornil(L, 2)) {
		if (lua_type(L, 2) == LUA_TTABLE) {
			// traverse and validate all the options
			for (lua_pushnil(L); lua_next(L, 2) != 0; lua_pop(L, 1)) {
				const char *key = lua_tostring(L, -2);
				
				if (UTF8IsEqual(key, "adUnitId")) {
					if (lua_type(L, -1) == LUA_TSTRING) {
						adUnitIdParam = lua_tostring(L, -1);
					}
					else {
						logMsg(L, ERROR_MSG, MsgFormat(@"options.adUnitId (string) expected, got %s", luaL_typename(L, -1)));
						return 0;
					}
				}
				else {
					logMsg(L, ERROR_MSG, MsgFormat(@"Invalid option '%s'", key));
					return 0;
				}
			}
		}
		else {
			logMsg(L, ERROR_MSG, MsgFormat(@"options table expected, got %s", luaL_typename(L, 2)));
			return 0;
		}
	}
	
	// check valid ad type
	if (! [validAdTypes containsObject:@(adType)]) {
		logMsg(L, ERROR_MSG, MsgFormat(@"Invalid adType '%s'", adType));
		return 0;
	}
	
	bool isLoaded = false;
	
	// get adUnitId (explicit or default)
	NSString *adUnitId;
	if (adUnitIdParam != NULL) {
		adUnitId = [NSString stringWithUTF8String:adUnitIdParam];
	}
	else { // default
		adUnitId = admobObjects[@(adType)];
	}
	
	if (adUnitId != nil) {
		CoronaAdMobAdInstance *adInstance = admobObjects[adUnitId];
		
		if (adInstance != nil) {
			// test specified ad type
			if (UTF8IsEqual(adType, TYPE_INTERSTITIAL)) {
				GADInterstitial *interstitial = (GADInterstitial *)adInstance.adInstance;
				isLoaded = (interstitial != nil) ? interstitial.isReady : false;
			}
			else if (UTF8IsEqual(adType, TYPE_REWARDEDVIDEO)) {
				GADRewardedAd *rewardedAd = (GADRewardedAd *)adInstance.adInstance;
				isLoaded = (rewardedAd != nil) ? rewardedAd.isReady : false;
			}
			else if (UTF8IsEqual(adType, TYPE_BANNER)) {
				GADBannerView *banner = (GADBannerView *)adInstance.adInstance;
				// need to use adInstance here since GADBannerView doesn't have an isLoaded property
				isLoaded = (banner != nil) ? adInstance.isLoaded : false;
			}
		}
	}
	
	lua_pushboolean(L, isLoaded);
	
	return 1;
}

// ============================================================================
// delegate implementation
// ============================================================================

@implementation CoronaAdMobDelegate

- (instancetype)init {
	if (self = [super init]) {
		self.coronaListener = NULL;
		self.coronaRuntime = NULL;
	}
	
	return self;
}

// create JSON string from interstitial info and error
- (NSString *)getJSONStringForInterstitial:(GADInterstitial *)ad error:(GADRequestError *)error
{
	NSMutableDictionary *dataDictionary = [NSMutableDictionary new];
	if (error != nil) {
		dataDictionary[DATA_ERRORMSG_KEY] = [error localizedDescription];
		dataDictionary[DATA_ERRORCODE_KEY] = @([error code]);
	}
	
	dataDictionary[DATA_ADUNIT_ID_KEY] = ad.adUnitID;
	
	NSData *jsonData = [NSJSONSerialization dataWithJSONObject:dataDictionary options:0 error:nil];
	
	return [[NSString alloc] initWithData:jsonData encoding:NSUTF8StringEncoding];
}

// create JSON string from rewarded ad info and error
- (NSString *)getJSONStringForRewardedAd:(GADRewardedAd *)ad
{
	return [self getJSONStringForRewardedAd:ad reward:nil];
}

- (NSString *)getJSONStringForRewardedAd:(GADRewardedAd *)ad reward:(GADAdReward *)reward
{
	return [self getJSONStringForRewardedAd:ad reward:reward error:nil];
}

- (NSString *)getJSONStringForRewardedAd:(GADRewardedAd *)ad reward:(GADAdReward *)reward error:(NSError *)error
{
	NSMutableDictionary *dataDictionary = [NSMutableDictionary new];
	
	if (error != nil) {
		dataDictionary[DATA_ERRORMSG_KEY] = [error localizedDescription];
		dataDictionary[DATA_ERRORCODE_KEY] = @([error code]);
	}
	
	if (reward != nil) {
		dataDictionary[REWARD_ITEM] = [reward type];
		dataDictionary[REWARD_AMOUNT] = [reward amount];
	}
	
	NSString *adUnitId = admobObjects[@(TYPE_REWARDEDVIDEO)];
	dataDictionary[DATA_ADUNIT_ID_KEY] = adUnitId;
	
	NSData *jsonData = [NSJSONSerialization dataWithJSONObject:dataDictionary options:0 error:nil];
	
	return [[NSString alloc] initWithData:jsonData encoding:NSUTF8StringEncoding];
}

// create JSON string from banner info and error
- (NSString *)getJSONStringForBanner:(GADBannerView *)banner error:(GADRequestError *)error
{
	NSMutableDictionary *dataDictionary = [NSMutableDictionary new];
	if (error != nil) {
		dataDictionary[DATA_ERRORMSG_KEY] = [error localizedDescription];
		dataDictionary[DATA_ERRORCODE_KEY] = @([error code]);
	}
	
	dataDictionary[DATA_ADUNIT_ID_KEY] = banner.adUnitID;
	
	NSData *jsonData = [NSJSONSerialization dataWithJSONObject:dataDictionary options:0 error:nil];
	
	return [[NSString alloc] initWithData:jsonData encoding:NSUTF8StringEncoding];
}

// dispatch a new Lua event
- (void)dispatchLuaEvent:(NSDictionary *)dict
{
	[[NSOperationQueue mainQueue] addOperationWithBlock:^{
		lua_State *L = self.coronaRuntime.L;
		CoronaLuaRef coronaListener = self.coronaListener;
		bool hasErrorKey = false;
		
		// create new event
		CoronaLuaNewEvent(L, EVENT_NAME);
		
		for (NSString *key in dict) {
			CoronaLuaPushValue(L, [dict valueForKey:key]);
			lua_setfield(L, -2, key.UTF8String);
			
			if (! hasErrorKey) {
				hasErrorKey = [key isEqualToString:@(CoronaEventIsErrorKey())];
			}
		}
		
		// add error key if not in dict
		if (! hasErrorKey) {
			lua_pushboolean(L, false);
			lua_setfield(L, -2, CoronaEventIsErrorKey());
		}
		
		// add provider
		lua_pushstring(L, PROVIDER_NAME );
		lua_setfield(L, -2, CoronaEventProviderKey());
		
		CoronaLuaDispatchEvent(L, coronaListener, 0);
	}];
}

// interstitial delegates --------------------------------------------------------------

/// Called when an interstitial ad request succeeded. Show it at the next transition point in your
/// application such as when transitioning between view controllers.
- (void)interstitialDidReceiveAd:(GADInterstitial *)ad
{
	// send Corona Lua event
	NSDictionary *coronaEvent = @{
		@(CoronaEventPhaseKey()) : PHASE_LOADED,
		@(CoronaEventTypeKey()) : @(TYPE_INTERSTITIAL),
		CORONA_EVENT_DATA_KEY : [self getJSONStringForInterstitial:ad error:nil]
	};
	[self dispatchLuaEvent:coronaEvent];
	
	CoronaAdMobAdInstance *adInstance = admobObjects[ad.adUnitID];
	adInstance.isLoaded = true;
	
}

/// Called just before presenting an interstitial. After this method finishes the interstitial will
/// animate onto the screen. Use this opportunity to stop animations and save the state of your
/// application in case the user leaves while the interstitial is on screen (e.g. to visit the App
/// Store from a link on the interstitial).
- (void)interstitialWillPresentScreen:(GADInterstitial *)ad
{
	// send Corona Lua event
	NSDictionary *coronaEvent = @{
		@(CoronaEventPhaseKey()) : PHASE_DISPLAYED,
		@(CoronaEventTypeKey()) : @(TYPE_INTERSTITIAL),
		CORONA_EVENT_DATA_KEY : [self getJSONStringForInterstitial:ad error:nil]
	};
	[self dispatchLuaEvent:coronaEvent];
	
	CoronaAdMobAdInstance *adInstance = admobObjects[ad.adUnitID];
	adInstance.isLoaded = false;
	
}

/// Called before the interstitial is to be animated off the screen.
- (void)interstitialWillDismissScreen:(GADInterstitial *)ad
{
	// NOP
	// We only need to use DidDismissScreen
}

/// Called just after dismissing an interstitial and it has animated off the screen.
- (void)interstitialDidDismissScreen:(GADInterstitial *)ad
{
	// send Corona Lua event
	NSDictionary *coronaEvent = @{
		@(CoronaEventPhaseKey()) : PHASE_CLOSED,
		@(CoronaEventTypeKey()) : @(TYPE_INTERSTITIAL),
		CORONA_EVENT_DATA_KEY : [self getJSONStringForInterstitial:ad error:nil]
	};
	[self dispatchLuaEvent:coronaEvent];
}

/// Called just before the application will background or terminate because the user clicked on an
/// ad that will launch another application (such as the App Store). The normal
/// UIApplicationDelegate methods, like applicationDidEnterBackground:, will be called immediately
/// before this.
- (void)interstitialWillLeaveApplication:(GADInterstitial *)ad
{
	// send Corona Lua event
	NSDictionary *coronaEvent = @{
		@(CoronaEventPhaseKey()) : PHASE_CLICKED,
		@(CoronaEventTypeKey()) : @(TYPE_INTERSTITIAL),
		CORONA_EVENT_DATA_KEY : [self getJSONStringForInterstitial:ad error:nil]
	};
	[self dispatchLuaEvent:coronaEvent];
}

/// Called when an interstitial ad request completed without an interstitial to
/// show. This is common since interstitials are shown sparingly to users.
- (void)interstitial:(GADInterstitial *)ad didFailToReceiveAdWithError:(GADRequestError *)error
{
	// send Corona Lua event
	NSDictionary *coronaEvent = @{
		@(CoronaEventPhaseKey()) : PHASE_FAILED,
		@(CoronaEventTypeKey()) : @(TYPE_INTERSTITIAL),
		@(CoronaEventIsErrorKey()) : @(true),
		@(CoronaEventResponseKey()) : RESPONSE_LOAD_FAILED,
		CORONA_EVENT_DATA_KEY : [self getJSONStringForInterstitial:ad error:error]
	};
	[self dispatchLuaEvent:coronaEvent];
	
	CoronaAdMobAdInstance *adInstance = admobObjects[ad.adUnitID];
	adInstance.isLoaded = false;
}

// rewarded video delegates --------------------------------------------------------------

// Tells the delegate that the reward based video ad has rewarded the user.
- (void)rewardedAd:(GADRewardedAd *)rewardedAd userDidEarnReward:(GADAdReward *)reward {
	// send Corona Lua event
	NSDictionary *coronaEvent = @{
		@(CoronaEventPhaseKey()) : PHASE_REWARD,
		@(CoronaEventTypeKey()) : @(TYPE_REWARDEDVIDEO),
		CORONA_EVENT_DATA_KEY : [self getJSONStringForRewardedAd:rewardedAd reward:reward]
	};
	[self dispatchLuaEvent:coronaEvent];
}

// Tells the delegate that the reward based video ad opened.
- (void)rewardedAdDidPresent:(GADRewardedAd *)rewardedAd {
	// send Corona Lua event
	NSDictionary *coronaEvent = @{
		@(CoronaEventPhaseKey()) : PHASE_DISPLAYED,
		@(CoronaEventTypeKey()) : @(TYPE_REWARDEDVIDEO),
		CORONA_EVENT_DATA_KEY : [self getJSONStringForRewardedAd:rewardedAd]
	};
	[self dispatchLuaEvent:coronaEvent];
	
	NSString *adUnitId = admobObjects[@(TYPE_REWARDEDVIDEO)];
	CoronaAdMobAdInstance *adInstance = admobObjects[adUnitId];
	adInstance.isLoaded = false;
}

- (void)rewardedAd:(GADRewardedAd *)rewardedAd didFailToPresentWithError:(NSError *)error {
	// send Corona Lua event
	NSDictionary *coronaEvent = @{
		@(CoronaEventPhaseKey()) : PHASE_FAILED,
		@(CoronaEventTypeKey()) : @(TYPE_REWARDEDVIDEO),
		@(CoronaEventIsErrorKey()) : @(true),
		CORONA_EVENT_DATA_KEY : [self getJSONStringForRewardedAd:rewardedAd reward:nil error:error]
	};
	[self dispatchLuaEvent:coronaEvent];
	
	NSString *adUnitId = admobObjects[@(TYPE_REWARDEDVIDEO)];
	CoronaAdMobAdInstance *adInstance = admobObjects[adUnitId];
	adInstance.isLoaded = false;
}


// Tells the delegate that the reward based video ad closed.
- (void)rewardedAdDidDismiss:(GADRewardedAd *)rewardedAd {
	// send Corona Lua event
	NSDictionary *coronaEvent = @{
		@(CoronaEventPhaseKey()) : PHASE_CLOSED,
		@(CoronaEventTypeKey()) : @(TYPE_REWARDEDVIDEO),
		CORONA_EVENT_DATA_KEY : [self getJSONStringForRewardedAd:rewardedAd]
	};
	[self dispatchLuaEvent:coronaEvent];
}

// banner delegates --------------------------------------------------------------

-(void)adViewDidReceiveAd:(GADBannerView *)bannerView
{
	CoronaAdMobAdInstance *adInstance = admobObjects[bannerView.adUnitID];
	
	// send Corona Lua event
	NSDictionary *coronaEvent = @{
		@(CoronaEventPhaseKey()) : adInstance.isLoaded ? PHASE_REFRESHED : PHASE_LOADED,
		@(CoronaEventTypeKey()) : @(TYPE_BANNER),
		CORONA_EVENT_DATA_KEY : [self getJSONStringForBanner:bannerView error:nil]
	};
	[self dispatchLuaEvent:coronaEvent];
	
	// flag the banner as loaded
	adInstance.isLoaded = true;
}

-(void)adViewWillPresentScreen:(GADBannerView *)bannerView
{
	// send Corona Lua event
	NSDictionary *coronaEvent = @{
		@(CoronaEventPhaseKey()) : PHASE_DISPLAYED,
		@(CoronaEventTypeKey()) : @(TYPE_BANNER),
		CORONA_EVENT_DATA_KEY : [self getJSONStringForBanner:bannerView error:nil]
	};
	[self dispatchLuaEvent:coronaEvent];
	
}

-(void)adViewDidDismissScreen:(GADBannerView *)bannerView
{
	// send Corona Lua event
	NSDictionary *coronaEvent = @{
		@(CoronaEventPhaseKey()) : PHASE_HIDDEN,
		@(CoronaEventTypeKey()) : @(TYPE_BANNER),
		CORONA_EVENT_DATA_KEY : [self getJSONStringForBanner:bannerView error:nil]
	};
	[self dispatchLuaEvent:coronaEvent];
}

-(void)adViewWillDismissScreen:(GADBannerView *)bannerView
{
	// NOP
	// We only need to use DidDismissScreen
}

-(void)adViewWillLeaveApplication:(GADBannerView *)bannerView
{
	// send Corona Lua event
	NSDictionary *coronaEvent = @{
		@(CoronaEventPhaseKey()) : PHASE_CLICKED,
		@(CoronaEventTypeKey()) : @(TYPE_BANNER),
		CORONA_EVENT_DATA_KEY : [self getJSONStringForBanner:bannerView error:nil]
	};
	[self dispatchLuaEvent:coronaEvent];
}

-(void)adView:(GADBannerView *)bannerView didFailToReceiveAdWithError:(GADRequestError *)error
{
	// send Corona Lua event
	NSDictionary *coronaEvent = @{
		@(CoronaEventPhaseKey()) : PHASE_FAILED,
		@(CoronaEventTypeKey()) : @(TYPE_BANNER),
		@(CoronaEventIsErrorKey()) : @(true),
		@(CoronaEventResponseKey()) : RESPONSE_LOAD_FAILED,
		CORONA_EVENT_DATA_KEY : [self getJSONStringForBanner:bannerView error:error]
	};
	[self dispatchLuaEvent:coronaEvent];
	
	CoronaAdMobAdInstance *adInstance = admobObjects[bannerView.adUnitID];
	adInstance.isLoaded = false;
}

@end

// ----------------------------------------------------------------------------

@implementation CoronaAdMobAdInstance

- (instancetype)init {
	return [self initWithAd:nil adType:nil];
}

- (instancetype)initWithAd:(NSObject *)adInstance adType:(NSString *)adType
{
	if (self = [super init]) {
		self.adInstance = adInstance;
		self.adType = adType;
		self.isLoaded = NO;
	}
	
	return self;
}

- (void)invalidateInfo
{
	if (self.adInstance != nil) {
		// make sure ad object gets deallocated
		if (UTF8IsEqual([self.adType UTF8String], TYPE_BANNER)) {
			GADBannerView *banner = (GADBannerView *)self.adInstance;
			[banner setDelegate:nil];
			[banner removeFromSuperview];
		}
		else if (UTF8IsEqual([self.adType UTF8String], TYPE_INTERSTITIAL)) {
			GADInterstitial *interstitial = (GADInterstitial *)self.adInstance;
			[interstitial setDelegate:nil];
		}
		else if(UTF8IsEqual([self.adType UTF8String], TYPE_REWARDEDVIDEO)) {
			
		}
		
		self.adInstance = nil;
	}
}

- (void)dealloc
{
	[self invalidateInfo];
	[super dealloc];
}

#pragma mark - banner view positioning

- (void)positionBannerViewInsideSafeArea:(UIView *_Nonnull)bannerView
							  withYAlign:(NSString *)yAlign
							 withYOffset:(CGFloat)yOffset {
	if (@available(ios 9.0, *)) {
		[self positionBannerViewInsideSafeAreaiOS9Plus:bannerView withYAlign:yAlign withYOffset:yOffset];
	} else {
		[self positionBannerViewInsideSafeAreaPreiOS9:bannerView withYAlign:yAlign withYOffset:yOffset];
	}
}

- (void)positionBannerViewInsideSafeAreaiOS9Plus:(UIView *_Nonnull)bannerView
									  withYAlign:(NSString *)yAlign
									 withYOffset:(CGFloat)yOffset NS_AVAILABLE_IOS(9.0) {
	// Position the banner. Stick it to the Safe Area(ios 11)/layout guides(ios 9-10).
	// Centered horizontally.
	NSLayoutXAxisAnchor *centerXAnchor;
	NSLayoutYAxisAnchor *anchor;
	UILayoutGuide *guide = nil;
	UIView *guideView;
	NSLayoutYAxisAnchor *bannerAnchor;
	
	if (yAlign != nil)
	{
		if (@available(ios 11.0, *))
		{
			guide = self.viewController.view.safeAreaLayoutGuide;
			centerXAnchor = guide.centerXAnchor;
			if (UTF8IsEqual([yAlign UTF8String], ALIGN_TOP))
			{
				anchor = guide.topAnchor;
				bannerAnchor = bannerView.topAnchor;
			}
			else
			{
				anchor = guide.bottomAnchor;
				bannerAnchor = bannerView.bottomAnchor;
			}
		}
		else
		{
			guideView = self.viewController.view;
			centerXAnchor = self.viewController.view.centerXAnchor;
			if (UTF8IsEqual([yAlign UTF8String], ALIGN_TOP))
			{
				anchor = self.viewController.topLayoutGuide.topAnchor;
				bannerAnchor = bannerView.topAnchor;
			}
			else
			{
				anchor = self.viewController.bottomLayoutGuide.topAnchor;
				bannerAnchor = bannerView.bottomAnchor;
			}
		}
	}
	else
	{
		if (@available(ios 11.0, *))
		{
			UIInterfaceOrientation currentOrientation = [[UIApplication sharedApplication] statusBarOrientation];
			if (UIInterfaceOrientationIsLandscape(currentOrientation))
			{
				guide = self.viewController.view.safeAreaLayoutGuide;
				centerXAnchor = guide.centerXAnchor;
				if (yOffset > 0)
				{
					anchor = guide.topAnchor;
					bannerAnchor = bannerView.topAnchor;
				}
				else if (yOffset < 0)
				{
					anchor = guide.bottomAnchor;
					bannerAnchor = bannerView.bottomAnchor;
				}
				else
				{
					anchor = guide.topAnchor;
					bannerAnchor = bannerView.topAnchor;
				}
			}
			else
			{
				guideView = self.viewController.view;
				centerXAnchor = self.viewController.view.centerXAnchor;
				if (yOffset > 0)
				{
					anchor = self.viewController.topLayoutGuide.topAnchor;
					bannerAnchor = bannerView.topAnchor;
				}
				else if (yOffset < 0)
				{
					anchor = self.viewController.bottomLayoutGuide.topAnchor;
					bannerAnchor = bannerView.bottomAnchor;
				}
				else
				{
					anchor = self.viewController.topLayoutGuide.topAnchor;
					bannerAnchor = bannerView.topAnchor;
				}
			}
		}
		else
		{
			guideView = self.viewController.view;
			centerXAnchor = self.viewController.view.centerXAnchor;
			if (yOffset > 0)
			{
				anchor = self.viewController.topLayoutGuide.topAnchor;
				bannerAnchor = bannerView.topAnchor;
			}
			else if (yOffset < 0)
			{
				anchor = self.viewController.bottomLayoutGuide.topAnchor;
				bannerAnchor = bannerView.bottomAnchor;
			}
			else
			{
				anchor = self.viewController.topLayoutGuide.topAnchor;
				bannerAnchor = bannerView.topAnchor;
			}
		}
	}
	
	if (guide != nil)
	{
		[NSLayoutConstraint activateConstraints:@[
			[bannerView.centerXAnchor constraintEqualToAnchor:centerXAnchor],
			[bannerAnchor constraintEqualToAnchor:anchor constant:floor(yOffset * [admobObjects[Y_RATIO_KEY] floatValue])],
			[bannerView.leftAnchor constraintEqualToAnchor:guide.leftAnchor],
			[bannerView.rightAnchor constraintEqualToAnchor:guide.rightAnchor]
		]];
	}
	else
	{
		[NSLayoutConstraint activateConstraints:@[
			[bannerView.centerXAnchor constraintEqualToAnchor:centerXAnchor],
			[bannerAnchor constraintEqualToAnchor:anchor constant:floor(yOffset * [admobObjects[Y_RATIO_KEY] floatValue])],
			[bannerView.leftAnchor constraintEqualToAnchor:guideView.leftAnchor],
			[bannerView.rightAnchor constraintEqualToAnchor:guideView.rightAnchor]
		]];
	}
}

- (void)positionBannerViewInsideSafeAreaPreiOS9:(UIView *_Nonnull)bannerView
									 withYAlign:(NSString *)yAlign
									withYOffset:(CGFloat)yOffset {
	[self.viewController.view addConstraint:[NSLayoutConstraint constraintWithItem:bannerView
																		 attribute:NSLayoutAttributeLeading
																		 relatedBy:NSLayoutRelationEqual
																			toItem:self.viewController.view
																		 attribute:NSLayoutAttributeLeading
																		multiplier:1
																		  constant:0]];
	[self.viewController.view addConstraint:[NSLayoutConstraint constraintWithItem:bannerView
																		 attribute:NSLayoutAttributeTrailing
																		 relatedBy:NSLayoutRelationEqual
																			toItem:self.viewController.view
																		 attribute:NSLayoutAttributeTrailing
																		multiplier:1
																		  constant:0]];
	
	NSLayoutAttribute attribute;
	id<UILayoutSupport> guide;
	
	if (yAlign != nil)
	{
		if (UTF8IsEqual([yAlign UTF8String], ALIGN_TOP))
		{
			attribute = NSLayoutAttributeTop;
			guide = self.viewController.topLayoutGuide;
		}
		else
		{
			attribute = NSLayoutAttributeBottom;
			guide = self.viewController.bottomLayoutGuide;
		}
	}
	else
	{
		if (yOffset > 0)
		{
			attribute = NSLayoutAttributeTop;
			guide = self.viewController.topLayoutGuide;
		}
		else if (yOffset < 0)
		{
			attribute = NSLayoutAttributeBottom;
			guide = self.viewController.bottomLayoutGuide;
		}
		else
		{
			attribute = NSLayoutAttributeTop;
			guide = self.viewController.topLayoutGuide;
		}
	}
	[self.viewController.view addConstraint:[NSLayoutConstraint constraintWithItem:bannerView
																		 attribute:attribute
																		 relatedBy:NSLayoutRelationEqual
																			toItem:guide
																		 attribute:NSLayoutAttributeTop
																		multiplier:1
																		  constant:floor(yOffset * [admobObjects[Y_RATIO_KEY] floatValue])]];
}

@end

// ----------------------------------------------------------------------------

CORONA_EXPORT int luaopen_plugin_admob(lua_State *L)
{
	return AdMobPlugin::Open(L);
}
