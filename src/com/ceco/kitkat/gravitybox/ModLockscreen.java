/*
 * Copyright (C) 2013 Peter Gregus for GravityBox Project (C3C076@xda)
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ceco.kitkat.gravitybox;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.List;

import com.ceco.kitkat.gravitybox.preference.AppPickerPreference;

import android.app.Activity;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View.OnLongClickListener;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.View;
import android.view.ViewManager;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;
import android.widget.TextView;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_InitPackageResources.InitPackageResourcesParam;

public class ModLockscreen {
    private static final String CLASS_PATH = "com.android.keyguard";
    private static final String TAG = "GB:ModLockscreen";
    public static final String PACKAGE_NAME = "com.android.keyguard";

    private static final String CLASS_KGVIEW_MANAGER = CLASS_PATH + ".KeyguardViewManager";
    private static final String CLASS_KG_HOSTVIEW = CLASS_PATH + ".KeyguardHostView";
    private static final String CLASS_KG_SELECTOR_VIEW = CLASS_PATH + ".KeyguardSelectorView";
    private static final String CLASS_TARGET_DRAWABLE = Utils.isMtkDevice() ?
            "com.android.internal.policy.impl.keyguard.TargetDrawable" :
            "com.android.internal.widget.multiwaveview.TargetDrawable";
    private static final String CLASS_TRIGGER_LISTENER = CLASS_PATH + ".KeyguardSelectorView$1";
    private static final String CLASS_KG_ABS_KEY_INPUT_VIEW = CLASS_PATH + ".KeyguardAbsKeyInputView";
    private static final String CLASS_KGVIEW_MEDIATOR = CLASS_PATH + ".KeyguardViewMediator";
    private static final String CLASS_KG_UPDATE_MONITOR = CLASS_PATH + ".KeyguardUpdateMonitor";
    private static final String CLASS_KG_UPDATE_MONITOR_BATTERY_STATUS = 
            CLASS_PATH + ".KeyguardUpdateMonitor.BatteryStatus";
    private static final String CLASS_KG_WIDGET_PAGER = CLASS_PATH + ".KeyguardWidgetPager";
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_ARC = false;

    private static final int STATUSBAR_DISABLE_RECENT = 0x01000000;
    private static final int STATUSBAR_DISABLE_NOTIFICATION_TICKER = 0x00080000;
    private static final int STATUSBAR_DISABLE_EXPAND = 0x00010000;
    private static final int STATUSBAR_DISABLE_SEARCH = 0x02000000;
    private static final int STATUSBAR_DISABLE_CLOCK = 0x00800000;

    private static final List<String> CLOCK_WIDGETS = new ArrayList<String>(Arrays.asList(
            "com.android.deskclock",
            "com.dvtonder.chronus",
            "net.nurik.roman.dashclock",
            "com.roymam.android.notificationswidget"
    ));

    private static XSharedPreferences mPrefs;
    private static Hashtable<String, AppInfo> mAppInfoCache = new Hashtable<String, AppInfo>();
    private static Class<?>[] mLaunchActivityArgs = new Class<?>[] 
            { Intent.class, boolean.class, boolean.class, Handler.class, Runnable.class };
    private static Constructor<?> mTargetDrawableConstructor;
    private static Object mKeyguardHostView;
    private static Handler mHandler;
    private static Context mGbContext;
    private static boolean mTorchEnabled;

    // Battery Arc
    private static HandleDrawable mHandleDrawable;
    private static Paint mArcPaint;
    private static RectF mArcRect;
    private static float mArcAngle = 0f;
    private static float mBatteryLevel;
    private static boolean mArcVisible;
    private static boolean mArcEnabled;
    private static View mGlowPadView;

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    public static void initPackageResources(final XSharedPreferences prefs, final InitPackageResourcesParam resparam) {
        try {
            boolean enableMenuKey = prefs.getBoolean(
                    GravityBoxSettings.PREF_KEY_LOCKSCREEN_MENU_KEY, false);
            resparam.res.setReplacement(PACKAGE_NAME, "bool", "config_disableMenuKeyInLockScreen", !enableMenuKey);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    public static void init(final XSharedPreferences prefs, final ClassLoader classLoader) {
        try {
            mPrefs = prefs;
            final Class<?> kgViewManagerClass = XposedHelpers.findClass(CLASS_KGVIEW_MANAGER, classLoader);
            final Class<?> kgHostViewClass = XposedHelpers.findClass(CLASS_KG_HOSTVIEW, classLoader);
            final Class<?> kgSelectorViewClass = XposedHelpers.findClass(CLASS_KG_SELECTOR_VIEW, classLoader);
            final Class<?> triggerListenerClass = XposedHelpers.findClass(CLASS_TRIGGER_LISTENER, classLoader);
            final Class<?> kgAbsKeyInputViewClass = XposedHelpers.findClass(CLASS_KG_ABS_KEY_INPUT_VIEW, classLoader);
            final Class<?> kgViewMediatorClass = XposedHelpers.findClass(CLASS_KGVIEW_MEDIATOR, classLoader);
            final Class<?> kgUpdateMonitorClass = XposedHelpers.findClass(CLASS_KG_UPDATE_MONITOR, classLoader);
            final Class<?> kgWidgetPagerClass = XposedHelpers.findClass(CLASS_KG_WIDGET_PAGER, classLoader);

            XposedHelpers.findAndHookMethod(kgViewManagerClass, "maybeCreateKeyguardLocked", 
                    boolean.class, boolean.class, Bundle.class, new XC_MethodHook() {

                @Override
                protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                    mPrefs.reload();
                    ViewManager viewManager = (ViewManager) XposedHelpers.getObjectField(
                            param.thisObject, "mViewManager");
                    FrameLayout keyGuardHost = (FrameLayout) XposedHelpers.getObjectField(
                            param.thisObject, "mKeyguardHost");
                    WindowManager.LayoutParams windowLayoutParams = (WindowManager.LayoutParams) 
                            XposedHelpers.getObjectField(param.thisObject, "mWindowLayoutParams");

                    final String bgType = prefs.getString(
                            GravityBoxSettings.PREF_KEY_LOCKSCREEN_BACKGROUND,
                            GravityBoxSettings.LOCKSCREEN_BG_DEFAULT);

                    if (!bgType.equals(GravityBoxSettings.LOCKSCREEN_BG_DEFAULT)) {
                        windowLayoutParams.flags &= ~WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
                    } else {
                        windowLayoutParams.flags |= WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
                    }
                    viewManager.updateViewLayout(keyGuardHost, windowLayoutParams);
                    if (DEBUG) log("maybeCreateKeyguardLocked: layout updated");
                }
            });

            XposedHelpers.findAndHookMethod(kgViewManagerClass, "inflateKeyguardView",
                    Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                    mPrefs.reload();

                    FrameLayout keyguardView = (FrameLayout) XposedHelpers.getObjectField(
                            param.thisObject, "mKeyguardView");

                    final String bgType = mPrefs.getString(
                            GravityBoxSettings.PREF_KEY_LOCKSCREEN_BACKGROUND, 
                            GravityBoxSettings.LOCKSCREEN_BG_DEFAULT);

                    Context context = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                    if (context != null && mGbContext == null) {
                        mGbContext = context.createPackageContext(GravityBox.PACKAGE_NAME, 0);
                    }

                    if (!bgType.equals(GravityBoxSettings.LOCKSCREEN_BG_DEFAULT)) {
                        FrameLayout flayout = new FrameLayout(context);
                        flayout.setTag("gb_wallpaper");
                        flayout.setLayoutParams(new LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, 
                                ViewGroup.LayoutParams.MATCH_PARENT));
                        if (bgType.equals(GravityBoxSettings.LOCKSCREEN_BG_COLOR)) {
                            int color = mPrefs.getInt(
                                    GravityBoxSettings.PREF_KEY_LOCKSCREEN_BACKGROUND_COLOR, Color.BLACK);
                            flayout.setBackgroundColor(color);
                            if (DEBUG) log("inflateKeyguardView: background color set");
                        } else if (bgType.equals(GravityBoxSettings.LOCKSCREEN_BG_IMAGE)) {
                            String wallpaperFile = mGbContext.getFilesDir() + "/lockwallpaper";
                            Bitmap background = BitmapFactory.decodeFile(wallpaperFile);
                            Drawable d = new BitmapDrawable(context.getResources(), background);
                            ImageView mLockScreenWallpaperImage = new ImageView(context);
                            mLockScreenWallpaperImage.setScaleType(ScaleType.CENTER_CROP);
                            mLockScreenWallpaperImage.setImageDrawable(d);
                            flayout.addView(mLockScreenWallpaperImage, -1, -1);
                            if (DEBUG) log("inflateKeyguardView: background image set");
                        }
                        keyguardView.addView(flayout,0);
                    }
                }
            });

            XposedHelpers.findAndHookMethod(kgViewManagerClass, "updateShowWallpaper",
                    boolean.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                    final boolean show = (Boolean) param.args[0];
                    if (DEBUG) log("updateShowWallpaper: " + show);

                    FrameLayout keyguardView = (FrameLayout) XposedHelpers.getObjectField(
                            param.thisObject, "mKeyguardView");
                    if (keyguardView != null && !show) {
                        View wp = keyguardView.findViewWithTag("gb_wallpaper");
                        if (wp != null) {
                            keyguardView.removeView(wp);
                            if (DEBUG) log("Custom wallpaper removed");
                        }
                    }
                }
            });

            XposedHelpers.findAndHookMethod(kgViewManagerClass, 
                    "shouldEnableScreenRotation", new XC_MethodReplacement() {

                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            prefs.reload();
                            return prefs.getBoolean(GravityBoxSettings.PREF_KEY_LOCKSCREEN_ROTATION, false);
                        }
            });

            XposedHelpers.findAndHookMethod(kgHostViewClass, "onFinishInflate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                    mKeyguardHostView = param.thisObject; 
                    Object slidingChallenge = XposedHelpers.getObjectField(
                            param.thisObject, "mSlidingChallengeLayout");
                    minimizeChallengeIfDesired(slidingChallenge);

                    if (slidingChallenge != null) {
                        try {
                            // find lock button and assign long click listener
                            // we assume there's only 1 ImageButton in sliding challenge layout
                            // we have to do it this way since there's no ID assigned in layout XML
                            final ViewGroup vg = (ViewGroup) slidingChallenge;
                            final int childCount = vg.getChildCount();
                            for (int i = 0; i < childCount; i++) {
                                View v = vg.getChildAt(i);
                                if (v instanceof ImageButton) {
                                    v.setOnLongClickListener(mLockButtonLongClickListener);
                                    break;
                                }
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(t);
                        }
                    }
                }
            });

            XposedHelpers.findAndHookMethod(kgHostViewClass, "onScreenTurnedOn", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                	mPrefs.reload();
                	if (DEBUG) log("OnScreenTurnedOn");
                    if (mPrefs.getBoolean(
                            GravityBoxSettings.PREF_KEY_LOCKSCREEN_TRUSTED_WIFI, false)) {
                        if(mPrefs.getBoolean("trusted_wifi", false)){
                            if (DEBUG) log("Trusted wifi yes");
                            final Object callback = 
                                XposedHelpers.getObjectField(param.thisObject, "mCallback");
                        XposedHelpers.callMethod(callback, "reportSuccessfulUnlockAttempt");
                        XposedHelpers.callMethod(callback, "dismiss", true);
                        if (DEBUG) log("Lockscreen Dismissed");
                        }
                    } else {
                        if (DEBUG) log("Trusted wifi off");
                    }
                    Object slidingChallenge = XposedHelpers.getObjectField(
                            param.thisObject, "mSlidingChallengeLayout");
                    minimizeChallengeIfDesired(slidingChallenge);
                }
//                @Override
//                protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
//                    Object slidingChallenge = XposedHelpers.getObjectField(
//                            param.thisObject, "mSlidingChallengeLayout");
//                    minimizeChallengeIfDesired(slidingChallenge);
//                }
            });

            XposedHelpers.findAndHookMethod(kgSelectorViewClass, "onFinishInflate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                    if (DEBUG) log("KeyGuardSelectorView onFinishInflate()");
                    prefs.reload();

                    final Context context = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                    final Resources res = context.getResources();
                    mGlowPadView = (View) XposedHelpers.getObjectField(param.thisObject, "mGlowPadView");

                    // apply custom bottom/right margin to shift unlock ring upwards/left
                    try {
                        final FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mGlowPadView.getLayoutParams();
                        final int bottomMarginOffsetPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 
                                prefs.getInt(GravityBoxSettings.PREF_KEY_LOCKSCREEN_TARGETS_VERTICAL_OFFSET, 0),
                                res.getDisplayMetrics());
                        final int rightMarginOffsetPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 
                                prefs.getInt(GravityBoxSettings.PREF_KEY_LOCKSCREEN_TARGETS_HORIZONTAL_OFFSET, 0),
                                res.getDisplayMetrics());
                        lp.setMargins(lp.leftMargin, lp.topMargin, lp.rightMargin - rightMarginOffsetPx, 
                                lp.bottomMargin - bottomMarginOffsetPx);
                        mGlowPadView.setLayoutParams(lp);
                    } catch (Throwable t) {
                        log("Lockscreen targets: error while trying to modify GlowPadView layout" + t.getMessage());
                    }

                    mTorchEnabled = prefs.getBoolean(GravityBoxSettings.PREF_KEY_LOCKSCREEN_RING_TORCH, false);

                    mArcEnabled = prefs.getBoolean(GravityBoxSettings.PREF_KEY_LOCKSCREEN_BATTERY_ARC, false);
                    // prepare Battery Arc
                    if (mArcEnabled) {
                        mArcVisible = true;
                        if (mHandleDrawable == null) {
                            mHandleDrawable = new HandleDrawable(
                                    XposedHelpers.getObjectField(mGlowPadView, "mHandleDrawable"));

                            mArcPaint = new Paint();
                            mArcPaint.setStrokeWidth(10.0f);
                            mArcPaint.setStyle(Paint.Style.STROKE); 
                            mArcRect = new RectF(mHandleDrawable.getPositionX() - mHandleDrawable.getWidth()/2, 
                                    mHandleDrawable.getPositionY() - mHandleDrawable.getHeight()/2,
                                    mHandleDrawable.getPositionX() + mHandleDrawable.getWidth()/2, 
                                    mHandleDrawable.getPositionY() + mHandleDrawable.getHeight()/2);

                            XposedHelpers.findAndHookMethod(
                                    mGlowPadView.getClass(), "onDraw", Canvas.class, glowPadViewOnDrawHook);
                            XposedHelpers.findAndHookMethod(mGlowPadView.getClass(), "showTargets",
                                    boolean.class, glowPadViewShowTargetsHook);
                            XposedHelpers.findAndHookMethod(mGlowPadView.getClass(), "hideTargets",
                                    boolean.class, boolean.class, glowPadViewHideTargetsHook);

                            if (DEBUG_ARC) log("Battery Arc initialized");
                        }
                        if (DEBUG_ARC) log("Battery Arc ready");
                    }

                    // finish if lockscreen targets disabled
                    if (!prefs.getBoolean(
                            GravityBoxSettings.PREF_KEY_LOCKSCREEN_TARGETS_ENABLE, false)) return;

                    @SuppressWarnings("unchecked")
                    final ArrayList<Object> targets = (ArrayList<Object>) XposedHelpers.getObjectField(
                            mGlowPadView, "mTargetDrawables");
                    final ArrayList<Object> newTargets = new ArrayList<Object>();
                    final ArrayList<String> newDescriptions = new ArrayList<String>();
                    final ArrayList<String> newDirections = new ArrayList<String>();
                    final ArrayList<AppInfo> appInfoList = new ArrayList<AppInfo>(5);
                    final int unlockDescResId = res.getIdentifier("description_target_unlock", 
                            "string", PACKAGE_NAME);
                    final int unlockDirecResId = res.getIdentifier("description_direction_right", 
                            "string", PACKAGE_NAME);

                    // fill appInfoList helper with apps from preferences
                    for (int i=0; i<=4; i++) {
                        String app = prefs.getString(
                                GravityBoxSettings.PREF_KEY_LOCKSCREEN_TARGETS_APP[i], null);
                        if (app != null) {
                            AppInfo appInfo = getAppInfo(context, app);
                            if (appInfo != null) {
                                appInfoList.add(appInfo);
                                if (DEBUG) log("appInfoList.add: " + app);
                            }
                        }
                    }

                    // get target from position 0 supposing it's unlock ring
                    newTargets.add(targets.get(0));
                    newDescriptions.add(unlockDescResId == 0 ? null : res.getString(unlockDescResId));
                    newDirections.add(unlockDirecResId == 0 ? null : res.getString(unlockDirecResId));

                    // create and fill custom targets with proper layout based on number of targets
                    switch(appInfoList.size()) {
                        case 1:
                            newTargets.add(createTargetDrawable(res, null));
                            newDescriptions.add(null);
                            newDirections.add(null);
                            newTargets.add(createTargetDrawable(res, appInfoList.get(0)));
                            newDescriptions.add(appInfoList.get(0).name);
                            newDirections.add(null);
                            newTargets.add(createTargetDrawable(res, null));
                            newDescriptions.add(null);
                            newDirections.add(null);
                            break;
                        case 2:
                            newTargets.add(createTargetDrawable(res, appInfoList.get(0)));
                            newDescriptions.add(appInfoList.get(0).name);
                            newDirections.add(null);
                            newTargets.add(createTargetDrawable(res, appInfoList.get(1)));
                            newDescriptions.add(appInfoList.get(1).name);
                            newDirections.add(null);
                            newTargets.add(createTargetDrawable(res, null));
                            newDescriptions.add(null);
                            newDirections.add(null);
                            break;
                        case 3:
                        case 4:
                        case 5:
                            for (int i=0; i<=4; i++) {
                                if (i >= appInfoList.size()) {
                                    newTargets.add(createTargetDrawable(res, null));
                                    newDescriptions.add(null);
                                } else {
                                    newTargets.add(createTargetDrawable(res, appInfoList.get(i)));
                                    newDescriptions.add(appInfoList.get(i).name);
                                }
                                newDirections.add(null);
                            }
                            break;
                    }
                    XposedHelpers.setObjectField(mGlowPadView, "mTargetDrawables", newTargets);
                    XposedHelpers.setObjectField(mGlowPadView, "mTargetDescriptions", newDescriptions);
                    XposedHelpers.setObjectField(mGlowPadView, "mDirectionDescriptions", newDirections);
                    XposedHelpers.callMethod(param.thisObject, "updateTargets");
                }
            });

            XposedHelpers.findAndHookMethod(triggerListenerClass, "onTrigger", 
                    View.class, int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(final MethodHookParam param) throws Throwable {
                    if (DEBUG) log("GlowPadView.OnTriggerListener; index=" + ((Integer) param.args[1]));
                    if (mHandler != null) {
                        mHandler.removeCallbacks(mToggleTorchRunnable);
                    }
                    prefs.reload();
                    if (!prefs.getBoolean(
                            GravityBoxSettings.PREF_KEY_LOCKSCREEN_TARGETS_ENABLE, false)) return;

                    final int index = (Integer) param.args[1];
                    @SuppressWarnings("unchecked")
                    final ArrayList<Object> targets = (ArrayList<Object>) XposedHelpers.getObjectField(
                            mGlowPadView, "mTargetDrawables");
                    final Object td = targets.get(index);

                    AppInfo appInfo = (AppInfo) XposedHelpers.getAdditionalInstanceField(td, "mGbAppInfo");
                    if (appInfo != null) {
                        final Object activityLauncher = XposedHelpers.getObjectField(
                                XposedHelpers.getSurroundingThis(param.thisObject), "mActivityLauncher");
                        XposedHelpers.callMethod(activityLauncher, "launchActivity", mLaunchActivityArgs,
                                appInfo.intent, false, true, null, null);
                    }
                }
            });

            XposedHelpers.findAndHookMethod(kgAbsKeyInputViewClass, "onFinishInflate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                    final TextView passwordEntry = 
                            (TextView) XposedHelpers.getObjectField(param.thisObject, "mPasswordEntry");
                    if (passwordEntry != null) {
                        passwordEntry.addTextChangedListener(new TextWatcher() {
                            @Override
                            public void afterTextChanged(Editable s) {
                                if (!mPrefs.getBoolean(
                                        GravityBoxSettings.PREF_KEY_LOCKSCREEN_QUICK_UNLOCK, false)) return;

                                final Object callback = 
                                        XposedHelpers.getObjectField(param.thisObject, "mCallback");
                                final Object lockPatternUtils = 
                                        XposedHelpers.getObjectField(param.thisObject, "mLockPatternUtils");
                                String entry = passwordEntry.getText().toString();

                                if (callback != null && lockPatternUtils != null &&
                                        entry.length() > 3 && 
                                        (Boolean) XposedHelpers.callMethod(
                                                lockPatternUtils, "checkPassword", entry)) {
                                    XposedHelpers.callMethod(callback, "reportSuccessfulUnlockAttempt");
                                    XposedHelpers.callMethod(callback, "dismiss", true);
                                }
                            }
                            @Override
                            public void beforeTextChanged(CharSequence arg0,
                                    int arg1, int arg2, int arg3) {
                            }
                            @Override
                            public void onTextChanged(CharSequence arg0,
                                    int arg1, int arg2, int arg3) {
                            }
                        });
                    }
                }
            });

            XposedHelpers.findAndHookMethod(kgViewMediatorClass, "adjustStatusBarLocked", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                    int policy = GravityBoxSettings.SBL_POLICY_DEFAULT;
                    try {
                        policy = Integer.valueOf(prefs.getString(
                            GravityBoxSettings.PREF_KEY_STATUSBAR_LOCK_POLICY, "0"));
                    } catch (NumberFormatException nfe) {
                        //
                    }
                    if (DEBUG) log("Statusbar lock policy = " + policy);
                    if (policy == GravityBoxSettings.SBL_POLICY_DEFAULT) return;

                    final Object sbManager = 
                            XposedHelpers.getObjectField(param.thisObject, "mStatusBarManager");
                    final Context context = 
                            (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                    final boolean showing = XposedHelpers.getBooleanField(param.thisObject, "mShowing");

                    if (showing && sbManager != null && !(context instanceof Activity)) {
                        int flags = STATUSBAR_DISABLE_RECENT;
                        if ((Boolean) XposedHelpers.callMethod(param.thisObject, "isSecure")) {
                            flags |= STATUSBAR_DISABLE_EXPAND;
                            flags |= STATUSBAR_DISABLE_NOTIFICATION_TICKER;
                        } else if (policy == GravityBoxSettings.SBL_POLICY_LOCKED) {
                            flags |= STATUSBAR_DISABLE_EXPAND;
                        }

                        try {
                            Method m = XposedHelpers.findMethodExact(
                                    kgViewMediatorClass, "isAssistantAvailable");
                            if (!(Boolean) m.invoke(param.thisObject)) {
                                flags |= STATUSBAR_DISABLE_SEARCH;
                            }
                        } catch(NoSuchMethodError nme) {
                            if (DEBUG) log("isAssistantAvailable method doesn't exist (Android < 4.2.2?)");
                        }

                        XposedHelpers.callMethod(sbManager, "disable", flags);
                        if (DEBUG) log("adjustStatusBarLocked: new flags = " + flags);
                    }
                }
            });

            XposedHelpers.findAndHookMethod(kgHostViewClass, "numWidgets", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(final MethodHookParam param) throws Throwable {
                    if (prefs.getBoolean(
                            GravityBoxSettings.PREF_KEY_LOCKSCREEN_WIDGET_LIMIT_DISABLE, false)) {
                        param.setResult(0);
                    }
                }
            });

            XposedHelpers.findAndHookMethod(kgUpdateMonitorClass, "handleBatteryUpdate",
                    CLASS_KG_UPDATE_MONITOR_BATTERY_STATUS, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                    if (mArcEnabled) {
                        updateLockscreenBattery(param.args[0]);
                    }
                }
            });

            XposedHelpers.findAndHookMethod(kgWidgetPagerClass, "onPageSwitched",
                    View.class, int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                    if (param.args[0] instanceof ViewGroup) {
                        final ViewGroup vg = (ViewGroup) param.args[0];
                        if (vg.getChildAt(0) instanceof AppWidgetHostView) {
                            final AppWidgetProviderInfo info = 
                                    ((AppWidgetHostView) vg.getChildAt(0)).getAppWidgetInfo();
                            final String widgetPackage = info.provider.getPackageName();
                            if (DEBUG) log("onPageSwitched: widget package = " + widgetPackage);
                            if (CLOCK_WIDGETS.contains(widgetPackage)) {
                                final View v = (View) param.thisObject; 
                                v.setSystemUiVisibility(v.getSystemUiVisibility() | STATUSBAR_DISABLE_CLOCK);
                            }
                        }
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static boolean shouldDrawBatteryArc() {
        return (mArcEnabled && mHandleDrawable != null && 
                mArcVisible && (mArcAngle > 0));
    }

    private static XC_MethodHook glowPadViewOnDrawHook = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
            Canvas canvas = (Canvas) param.args[0];
            if (canvas != null && shouldDrawBatteryArc()) {
                try {
                    mHandleDrawable.set(XposedHelpers.getObjectField(param.thisObject, "mHandleDrawable"));
                    mArcRect.set(mHandleDrawable.getPositionX() - mHandleDrawable.getWidth()/3,
                            mHandleDrawable.getPositionY() - mHandleDrawable.getHeight()/3,
                            mHandleDrawable.getPositionX() + mHandleDrawable.getWidth()/3,
                            mHandleDrawable.getPositionY() + mHandleDrawable.getHeight()/3);
                    canvas.drawArc(mArcRect, -90, mArcAngle, false, mArcPaint);
                } catch (Throwable t) {
                    log("GlowPadView onDraw exception: " + t.getMessage());
                }
            }
        }
    };

    private static XC_MethodHook glowPadViewShowTargetsHook = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
            mArcVisible = false;

            if (param.thisObject == mGlowPadView && mTorchEnabled) {
                if (mHandler == null) {
                    mHandler = new Handler();
                }
                mHandler.postDelayed(mToggleTorchRunnable, ViewConfiguration.getLongPressTimeout()*2);
            }
        }
    };

    private static XC_MethodHook glowPadViewHideTargetsHook = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
            mArcVisible = true;
            if (param.thisObject == mGlowPadView && mHandler != null) {
                mHandler.removeCallbacks(mToggleTorchRunnable);
            }
        }
    };

    private static Runnable mToggleTorchRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                Intent intent = new Intent(mGbContext, TorchService.class);
                intent.setAction(TorchService.ACTION_TOGGLE_TORCH);
                mGbContext.startService(intent);
            } catch (Throwable t) {
                log("Error toggling Torch: " + t.getMessage());
            }
        }
    };

    private static void minimizeChallengeIfDesired(Object challenge) {
        if (challenge == null) return;

        mPrefs.reload();
        if (mPrefs.getBoolean(GravityBoxSettings.PREF_KEY_LOCKSCREEN_MAXIMIZE_WIDGETS, false)) {
            if (DEBUG) log("minimizeChallengeIfDesired: challenge minimized");
            XposedHelpers.callMethod(challenge, "showChallenge", false);
        }
    }

    private static final OnLongClickListener mLockButtonLongClickListener = new OnLongClickListener() {
        @Override
        public boolean onLongClick(View view) {
            if (mKeyguardHostView == null) return false;
            try {
                XposedHelpers.callMethod(mKeyguardHostView, "showNextSecurityScreenOrFinish", false);
                return true;
            } catch (Throwable t) {
                XposedBridge.log(t);
                return false;
            }
        }
    };

    private static class AppInfo {
        public String key;
        public Intent intent;
        public Drawable icon;
        public String name;
    }

    private static AppInfo getAppInfo(Context context, String app) {
        try {
            if (mAppInfoCache.containsKey(app)) {
                if (DEBUG) log("AppInfo: returning from cache for " + app);
                return mAppInfoCache.get(app);
            }

            AppInfo appInfo = new AppInfo();
            appInfo.key = app;
            appInfo.intent = Intent.parseUri(app, 0);
            if (!appInfo.intent.hasExtra("mode")) {
                return null;
            }
            final int mode = appInfo.intent.getIntExtra("mode", AppPickerPreference.MODE_APP);
            Bitmap appIcon = null;
            final Resources res = context.getResources();
            if (mode == AppPickerPreference.MODE_APP) {
                PackageManager pm = context.getPackageManager();
                ActivityInfo ai = pm.getActivityInfo(appInfo.intent.getComponent(), 0);
                appInfo.name = (String) ai.loadLabel(pm);
                appIcon = Utils.drawableToBitmap(ai.loadIcon(pm));
            } else if (mode == AppPickerPreference.MODE_SHORTCUT) {
                appInfo.name = appInfo.intent.getStringExtra("label");
                final String appIconPath = appInfo.intent.getStringExtra("icon");
                if (appIconPath != null) {
                    File f = new File(appIconPath);
                    FileInputStream fis = new FileInputStream(f);
                    appIcon = BitmapFactory.decodeStream(fis);
                    fis.close();
                }
            }
            if (appIcon != null) {
                int sizePx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 40, 
                        res.getDisplayMetrics());
                appIcon = Bitmap.createScaledBitmap(appIcon, sizePx, sizePx, true);
                appInfo.icon = new BitmapDrawable(res, appIcon);
            }

            mAppInfoCache.put(appInfo.key, appInfo);
            if (DEBUG) log("AppInfo: storing to cache for " + app);
            return appInfo;
        } catch (Throwable t) {
            log("Error getting app info for " + app + "! Error: " + t.getMessage());
            return null;
        }
    }

    private static Object createTargetDrawable(Resources res, AppInfo appInfo) throws Throwable {
        if (mTargetDrawableConstructor == null) {
            mTargetDrawableConstructor = XposedHelpers.findConstructorExact(
                    XposedHelpers.findClass(CLASS_TARGET_DRAWABLE, null), 
                    Resources.class, int.class);
        }

        final Object td = mTargetDrawableConstructor.newInstance(res, 0);
        if (appInfo != null) {
            Drawable d = appInfo.icon == null ? null : appInfo.icon.mutate();
            XposedHelpers.setObjectField(td, "mDrawable", d);
            XposedHelpers.callMethod(td, "resizeDrawables");
            XposedHelpers.setAdditionalInstanceField(td, "mGbAppInfo", appInfo);
        }

        return td;
    }

    static class HandleDrawable {
        Object mHd;

        public HandleDrawable(Object handleDrawable) {
            mHd = handleDrawable;
        }

        public void set(Object handleDrawable) {
            mHd = handleDrawable;
        }

        public float getAlpha() {
            return (Float) XposedHelpers.callMethod(mHd, "getAlpha");
        }

        public float getPositionX() {
            return (Float) XposedHelpers.callMethod(mHd, "getPositionX");
        }

        public float getPositionY() {
            return (Float) XposedHelpers.callMethod(mHd, "getPositionY");
        }

        public int getWidth() {
            return (Integer) XposedHelpers.callMethod(mHd, "getWidth");
        }

        public int getHeight() {
            return (Integer) XposedHelpers.callMethod(mHd, "getHeight");
        }
    }

    private static void updateLockscreenBattery(Object status) {
        if (status != null) { 
            mBatteryLevel = XposedHelpers.getFloatField(status, "level");
            if (DEBUG_ARC) log("BatteryStatus: mBatteryLevel = " + mBatteryLevel);
        }

        float cappedBattery = mBatteryLevel;
        if (mBatteryLevel < 15) {
            cappedBattery = 15;
        } else if (mBatteryLevel > 90) {
            cappedBattery = 90;
        }

        final float hue = (cappedBattery - 15) * 1.6f;
        mArcAngle = mBatteryLevel * 3.6f;
        if (mArcPaint != null) {
            mArcPaint.setColor(Color.HSVToColor(0x80, new float[]{ hue, 1.f, 1.f }));
        }
        if (mGlowPadView != null) {
            mGlowPadView.invalidate();
        }
        if (DEBUG_ARC) log("Lockscreen battery arc updated");
    }
}