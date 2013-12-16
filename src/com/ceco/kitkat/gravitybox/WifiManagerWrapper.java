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

import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.provider.Settings;
import android.util.Log;

public class WifiManagerWrapper {
    public static final String WIFI_AP_STATE_CHANGED_ACTION = "android.net.wifi.WIFI_AP_STATE_CHANGED";
    public static final String EXTRA_WIFI_AP_STATE = "wifi_state";

    public static final int WIFI_STATE_DISABLING = 0;
    public static final int WIFI_STATE_DISABLED = 1;
    public static final int WIFI_STATE_ENABLING = 2;
    public static final int WIFI_STATE_ENABLED = 3;
    public static final int WIFI_STATE_UNKNOWN = 4;
    public static final String WIFI_SAVED_STATE = "wifi_saved_state";
    
    public static final int WIFI_AP_STATE_DISABLING = 10;
    public static final int WIFI_AP_STATE_DISABLED = 11;
    public static final int WIFI_AP_STATE_ENABLING = 12;
    public static final int WIFI_AP_STATE_ENABLED = 13;
    public static final int WIFI_AP_STATE_FAILED = 14;

    public static final String PREF_LOCKSCREEN_TRUSTED_WIFI_NETWORKS = "trusted_networks_array";

    private Context mContext;
    private WifiManager mWifiManager;
    private WifiApStateChangeListener mApStateChangeListener;
    private BroadcastReceiver mApStateChangeReceiver;
    private WifiStateChangeListener mWifiStateChangeListener;
    private BroadcastReceiver mWifiStateChangeReceiver;
    private SharedPreferences mPrefs;
    private SharedPreferences.Editor mPrefsEditor;

    public interface WifiApStateChangeListener {
        void onWifiApStateChanged(int wifiApState);
    }

    public interface WifiStateChangeListener {
        void onWifiStateChanging(boolean enabling);
    }

    public WifiManagerWrapper(Context context, WifiApStateChangeListener listener) {
        mContext = context;
        mWifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        setWifiApStateChangeListener(listener);
        mWifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        registerWifiStateChangeReceiver();
        final String prefsName = mContext.getPackageName() + "_preferences";
        mPrefs = mContext.getSharedPreferences(prefsName, Context.MODE_WORLD_READABLE);
    }

    public WifiManagerWrapper(Context context) {
        this(context, null);
        mContext = context;
    }

    public void setWifiApStateChangeListener(WifiApStateChangeListener listener) {
        if (listener == null) return;

        mApStateChangeListener = listener;
        registerApStateChangeReceiver();
    }

    public void setWifiStateChangeListener(WifiStateChangeListener listener) {
        if (listener == null) return;
        
        mWifiStateChangeListener = listener;
        registerWifiStateChangeReceiver();
    }

    private void registerWifiStateChangeReceiver() {
            if (mContext == null || mWifiStateChangeReceiver != null)
                return;

            mWifiStateChangeReceiver = new BroadcastReceiver() {

                @Override
                public void onReceive(Context context, Intent intent) {
                    Log.d("GravityBox", "OnReceive");
                	if (intent.getAction().equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                		Log.d("GravityBox", "after intent");
                		try {
                        WifiInfo network = intent.getParcelableExtra(WifiManager.EXTRA_WIFI_INFO);
                        Set<String> networks = mPrefs.getStringSet(PREF_LOCKSCREEN_TRUSTED_WIFI_NETWORKS, new HashSet<String>());
                        Log.d("GravityBox", "trusted networks: " + networks);
                        if((network != null) && networks.contains(network.getSSID())) {
                            Log.d("GravityBox", "In a trusted Network");
                            mPrefsEditor = mPrefs.edit();
                            mPrefsEditor.putBoolean("trusted_wifi", true);
                            mPrefsEditor.apply();
                        } else {
                            Log.d("GravityBox", "Not in trusted wifi");
                        	mPrefsEditor = mPrefs.edit();
                            mPrefsEditor.putBoolean("trusted_wifi", false);
                            mPrefsEditor.apply();
                        }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                    
                }
            };

            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
            mContext.registerReceiver(mWifiStateChangeReceiver, intentFilter);
        }

    private void registerApStateChangeReceiver() {
        if (mContext == null || mApStateChangeReceiver != null)
            return;

        mApStateChangeReceiver = new BroadcastReceiver() {

            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getAction().equals(WIFI_AP_STATE_CHANGED_ACTION) &&
                        intent.hasExtra(EXTRA_WIFI_AP_STATE)) {
                    int state = intent.getIntExtra(EXTRA_WIFI_AP_STATE, WIFI_AP_STATE_FAILED);
                    if (mApStateChangeListener != null) {
                        mApStateChangeListener.onWifiApStateChanged(state);
                    }
                }
            }
        };

        IntentFilter intentFilter = new IntentFilter(WIFI_AP_STATE_CHANGED_ACTION);
        mContext.registerReceiver(mApStateChangeReceiver, intentFilter);
    }

    public int getWifiState() {
        return mWifiManager.getWifiState();
    }

    public int getWifiApState() {
        return (Integer) XposedHelpers.callMethod(mWifiManager, "getWifiApState");
    }

    public String getWifiSsid() {
        WifiInfo wifiInfo = mWifiManager.getConnectionInfo();
        return (wifiInfo == null ? null : wifiInfo.getSSID());
    }

    public boolean isWifiEnabled() {
        return mWifiManager.isWifiEnabled();
    }

    public void setWifiEnabled(boolean enable) {
        if (mWifiStateChangeListener != null) {
            mWifiStateChangeListener.onWifiStateChanging(enable);
        }
        mWifiManager.setWifiEnabled(enable);
    }

    public void toggleWifiEnabled() {
        final boolean enable = 
                (getWifiState() != WifiManagerWrapper.WIFI_STATE_ENABLED);
        new AsyncTask<Void,Void,Void>() {
            @Override
            protected Void doInBackground(Void... args) {
                final int wifiApState = getWifiApState();
                if (enable && (wifiApState == WifiManagerWrapper.WIFI_AP_STATE_ENABLING
                               || wifiApState == WifiManagerWrapper.WIFI_AP_STATE_ENABLED)) {
                    setWifiApEnabled(false);
                }
                return null;
            }
            @Override
            protected void onPostExecute(Void args) {
                setWifiEnabled(enable);
            }
        }.execute();
    }

    public boolean isWifiApEnabled() {
        return (getWifiApState() == WIFI_AP_STATE_ENABLED);
    }

    public void setWifiApEnabled(boolean enable) {
        try {
            final ContentResolver cr = mContext.getContentResolver();

            int wifiState = getWifiState(); 
            if (enable && (wifiState == WIFI_STATE_ENABLING ||
                    wifiState == WIFI_STATE_ENABLED)) {
                setWifiEnabled(false);
                Settings.Global.putInt(cr, WIFI_SAVED_STATE, 1);
            }

            Class<?>[] paramArgs = new Class<?>[2];
            paramArgs[0] = WifiConfiguration.class;
            paramArgs[1] = boolean.class;
            XposedHelpers.callMethod(mWifiManager, "setWifiApEnabled", paramArgs, null, enable);

            if (!enable) {
                int wifiSavedState = 0;
                try {
                    wifiSavedState = Settings.Global.getInt(cr, WIFI_SAVED_STATE);
                } catch (Settings.SettingNotFoundException e) {
                    //
                }
                if (wifiSavedState == 1) {
                    setWifiEnabled(true);
                    Settings.Global.putInt(cr, WIFI_SAVED_STATE, 0);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }
}