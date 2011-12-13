
package android.service;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SyncAdapterType;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.format.Time;
import android.util.Slog;

import com.android.internal.telephony.Phone;

import java.util.Calendar;
import java.util.concurrent.TimeUnit;

public class PowerSaverService extends BroadcastReceiver {

    private Context mContext;

    private static final String TAG = "PowerSaverService";

    public static final int POWER_SAVER_MODE_OFF = 10;
    public static final int POWER_SAVER_MODE_ON = 11;

    private int mMode = POWER_SAVER_MODE_OFF;

    // data options - for screen off
    public static final int DATA_UNTOUCHED = 20;
    public static final int DATA_2G = 21;
    public static final int DATA_OFF = 22;

    private int mScreenOffDataMode = DATA_UNTOUCHED;

    // sync options - for screen off
    public static final int SYNC_UNTOUCHED = 35;
    public static final int SYNC_TURN_OFF = 33;
    public static final int SYNC_INTERVAL = 34;

    private int mScreenOffSyncMode = SYNC_UNTOUCHED;

    // wifi options - for screen off
    public static final int WIFI_UNTOUCHED = 40;
    public static final int WIFI_OFF = 41;
    public static final int WIFI_ON = 42;

    private int mScreenOffWifiMode = WIFI_UNTOUCHED;

    // what type of data to use when syncing
    public static final int SYNCING_WIFI_ONLY = 50;
    public static final int SYNCING_WIFI_PREFERRED = 51;
    public static final int SYNCING_DATA_ONLY = 53;

    private int mSyncDataMode = SYNCING_WIFI_PREFERRED;

    // how to handle mobile data when forcing syncs
    public static final int SYNCING_DATA_NODATA = 61;
    public static final int SYNCING_DATA_PREFER_2G = 62;
    public static final int SYNCING_DATA_PREFER_3G = 63;

    private int mSyncMobileDataMode = SYNCING_DATA_PREFER_3G;

    private int mDataScreenOffSecondDelay = 1;
    private int mSyncScreenOffSecondInterval = 15 * 60;

    public static final String ACTION_NETWORK_MODE_CHANGED = "com.android.internal.telephony.NETWORK_MODE_CHANGED";
    public static final String ACTION_REQUEST_NETWORK_MODE = "com.android.internal.telephony.REQUEST_NETWORK_MODE";
    public static final String ACTION_MODIFY_NETWORK_MODE = "com.android.internal.telephony.MODIFY_NETWORK_MODE";
    public static final String EXTRA_NETWORK_MODE = "networkMode";

    private static final String ACTION_SCREEN_OFF = "android.service.PowerSaverService.ACTION_SCREEN_OFF";
    private static final String ACTION_SCREEN_ON = "android.service.PowerSaverService.ACTION_SCREEN_ON";
    private static final String ACTION_SYNC = "android.service.PowerSaverService.ACTION_SYNC";

    private TelephonyManager telephony;
    private ConnectivityManager connectivity;
    private WifiManager wifi;
    private AlarmManager alarms;

    private int originalNetworkMode = Phone.PREFERRED_NT_MODE;
    private boolean originalWifiEnabled = false;
    private boolean originalDataOn = false;

    private boolean isScreenOn = false;

    Handler handler;

    private long mDataScreenOnSecondDelay = 5;

    PendingIntent scheduleSyncTaskPendingIntent = null;
    PendingIntent scheduleScreenOffPendingIntent = null;
    PendingIntent scheduleScreenOnPendingIntent = null;

    public PowerSaverService(Context context) {
        mContext = context;

        Slog.i(TAG, "initialized");
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (mMode == POWER_SAVER_MODE_OFF)
            return;

        String action = intent.getAction();

        if (Intent.ACTION_SCREEN_OFF.equals(action)) {
            isScreenOn = false;
            cancelAllTasks();
            scheduleScreenOffTask();

        } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
            isScreenOn = true;
            cancelAllTasks();
            scheduleScreenOnTask();

        } else if (ACTION_NETWORK_MODE_CHANGED.equals(action)) {
            if (intent.getExtras() != null) {
                // originalNetworkMode = intent.getExtras().getInt(EXTRA_NETWORK_MODE);
                Slog.i(TAG, "received network mode from intent (ignoring): " + originalNetworkMode);
            }

        } else if (Intent.ACTION_SYNC_STATE_CHANGED.equals(action)) {
            if (isScreenOn)
                return;

            Slog.i(TAG, "Received sync changed intent");
            boolean isActive = intent.getBooleanExtra("active", false);
            if (!isActive) {
                // restore data to "off" state after sync finishes
                handleScreenOffData();
                // scheduleSyncTask();
            }
        } else if (ACTION_SCREEN_OFF.equals(action)) {
            handler.post(screenOffTask);
        } else if (ACTION_SCREEN_ON.equals(action)) {
            handler.post(screenOnTask);
        } else if (ACTION_SYNC.equals(action)) {
            handler.post(scheduledSyncTask);
        }
    }

    private void handleScreenOffData() {

        // set data off
        if (mScreenOffDataMode != DATA_UNTOUCHED) {

            if (mScreenOffDataMode == DATA_2G) {
                Slog.i(TAG, "requesting 2G only");
                requestPhoneStateChange(Phone.NT_MODE_GSM_ONLY);
            } else if (mScreenOffDataMode == DATA_OFF) {
                Slog.i(TAG, "turning data off");
                if (connectivity != null)
                    connectivity.setMobileDataEnabled(false);
            }
        }
    }

    private void scheduleScreenOffTask() {
        Intent i = new Intent(ACTION_SCREEN_OFF);
        scheduleScreenOffPendingIntent = PendingIntent.getBroadcast(mContext, 0, i, 0);
        alarms.set(AlarmManager.RTC_WAKEUP, mDataScreenOffSecondDelay,
                scheduleScreenOffPendingIntent);

        Slog.i(TAG, "scheduleScreenOffTask()");
    }

    private void scheduleScreenOnTask() {
        Intent i = new Intent(ACTION_SCREEN_ON);
        scheduleScreenOnPendingIntent = PendingIntent.getBroadcast(mContext, 0, i, 0);
        alarms.set(AlarmManager.RTC_WAKEUP, mDataScreenOnSecondDelay, scheduleScreenOnPendingIntent);

        Slog.i(TAG, "scheduleScreenOnask()");
    }

    private void scheduleSyncTask() {

        Calendar timeToStart = Calendar.getInstance();
        timeToStart.setTimeInMillis(System.currentTimeMillis());
        timeToStart.add(Calendar.SECOND, mSyncScreenOffSecondInterval);

        long interval = TimeUnit.MILLISECONDS.convert(mSyncScreenOffSecondInterval,
                TimeUnit.SECONDS);

        Intent i = new Intent(ACTION_SYNC);
        scheduleSyncTaskPendingIntent = PendingIntent.getBroadcast(mContext, 0, i, 0);
        alarms.setInexactRepeating(AlarmManager.RTC_WAKEUP, timeToStart.getTimeInMillis(),
                interval, scheduleSyncTaskPendingIntent);
        // alarms.set(AlarmManager.RTC_WAKEUP, mDataScreenOnSecondDelay,
        // scheduleSyncTaskPendingIntent);

        long secondsInterval = TimeUnit.SECONDS.convert(interval, TimeUnit.MILLISECONDS);

        Slog.i(TAG, "scheduleSyncTask() with interval: " + secondsInterval);
    }

    Runnable screenOnTask = new Runnable() {

        @Override
        public void run() {
            if (mMode == POWER_SAVER_MODE_OFF)
                return;

            Slog.i(TAG, "Running screen on scheduler");

            // restore data
            if (mScreenOffDataMode != DATA_UNTOUCHED) {
                Slog.i(TAG, "handleScreenOnDataChange");

                if (originalDataOn)
                    connectivity.setMobileDataEnabled(true);

                if (originalWifiEnabled)
                    wifi.setWifiEnabled(true);

                Slog.i(TAG, "Requesting to restore to original network mode: " +
                        originalNetworkMode);
                requestPhoneStateChange(originalNetworkMode);
            }
        }
    };

    Runnable screenOffTask = new Runnable() {

        @Override
        public void run() {
            if (mMode == POWER_SAVER_MODE_OFF)
                return;

            requestPreferredDataType();
            originalDataOn = Settings.Secure.getInt(
                    mContext.getContentResolver(), Settings.Secure.MOBILE_DATA, 0) == 1;
            originalWifiEnabled = Settings.Secure.getInt(mContext.getContentResolver(),
                    Settings.Secure.WIFI_ON, 0) == 1;

            handleScreenOffData();

            // set syncs
            if (mScreenOffSyncMode != SYNC_UNTOUCHED) {
                Slog.i(TAG, "scheduling syncs");
                scheduleSyncTask();
            }
        }

    };

    Runnable scheduledSyncTask = new Runnable() {

        @Override
        public void run() {
            Slog.i(TAG, "scheduled sync task starting");

            switch (mSyncDataMode) {
                case SYNCING_WIFI_ONLY:
                    wifi.setWifiEnabled(true);
                    connectivity.setMobileDataEnabled(false);
                    break;
                case SYNCING_WIFI_PREFERRED:
                    wifi.setWifiEnabled(true);
                    connectivity.setMobileDataEnabled(true);
                    break;
                case SYNCING_DATA_PREFER_2G:
                    wifi.setWifiEnabled(false);
                    connectivity.setMobileDataEnabled(true);
            }

            // turn on the network we had before
            if (originalDataOn) {
                connectivity.setMobileDataEnabled(true);
                requestPhoneStateChange(originalNetworkMode);
            }

            if (originalWifiEnabled)
                wifi.setWifiEnabled(true);

            // then enable sync, and force it
            syncEnabledServices();

            // TODO figure out when sync is done and then turn data back off
        }
    };

    private void syncAllServices() {
        Slog.i(TAG, "Syncing all services");
        AccountManager acm = AccountManager.get(mContext);
        Account[] acct = null;

        SyncAdapterType[] types = ContentResolver.getSyncAdapterTypes();
        for (SyncAdapterType type : types) {

            if (type.isUserVisible()) {

                acct = acm.getAccountsByType(type.accountType);
                for (int i = 0; i < acct.length; i++) {
                    Bundle extras = new Bundle();
                    extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
                    ContentResolver.requestSync(acct[i], type.authority, extras);
                }
            }
        }
    }

    private void requestPreferredDataType() {
        int settingVal = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.PREFERRED_NETWORK_MODE, Phone.PREFERRED_NT_MODE);
        Slog.i(TAG, "Network Mode from settings: " + settingVal);
        originalNetworkMode = settingVal;
        // mContext.sendBroadcast(new Intent(ACTION_REQUEST_NETWORK_MODE));
    }

    private void syncEnabledServices() {
        AccountManager acm = AccountManager.get(mContext);

        Account[] acct = null;

        SyncAdapterType[] types = ContentResolver.getSyncAdapterTypes();
        for (SyncAdapterType type : types) {
            if (type.isUserVisible()) {

                acct = acm.getAccountsByType(type.accountType);

                for (int i = 0; i < acct.length; i++) {

                    if (ContentResolver.getSyncAutomatically(acct[i], type.authority)) {
                        Bundle extras = new Bundle();
                        extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
                        ContentResolver.requestSync(acct[i], type.authority, extras);
                    }
                }
            }
        }
    }

    public void systemReady() {
        if (mContext == null)
            Slog.e(TAG, "mContext is null!");

        connectivity = (ConnectivityManager) mContext
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        wifi = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        telephony = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
        alarms = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);

        SettingsObserver settingsObserver = new SettingsObserver(new Handler());
        settingsObserver.observe();
        updateSettings(); // to initialize values

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(ACTION_NETWORK_MODE_CHANGED);
        filter.addAction(Intent.ACTION_SYNC_STATE_CHANGED);
        filter.addAction(ACTION_SCREEN_OFF);
        filter.addAction(ACTION_SCREEN_ON);
        filter.addAction(ACTION_SYNC);
        mContext.registerReceiver(this, filter);

        handler = new Handler();

        Slog.i(TAG, "system ready");
    }

    private void cancelAllTasks() {
        if (scheduleScreenOffPendingIntent != null)
            alarms.cancel(scheduleScreenOffPendingIntent);
        if (scheduleScreenOnPendingIntent != null)
            alarms.cancel(scheduleScreenOnPendingIntent);
        if (scheduleSyncTaskPendingIntent != null)
            alarms.cancel(scheduleSyncTaskPendingIntent);
    }

    private void requestPhoneStateChange(int newState) {
        // Slog.i(TAG, "Sending request to change phone network mode to: " + newState);
        Intent i = new Intent(ACTION_MODIFY_NETWORK_MODE);
        i.putExtra(EXTRA_NETWORK_MODE, newState);
        mContext.sendBroadcast(i);
    }

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.POWER_SAVER_MODE), false, this);
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.POWER_SAVER_SYNC_MODE), false, this);
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.POWER_SAVER_DATA_MODE), false, this);
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.POWER_SAVER_SYNC_INTERVAL), false, this);
        }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }
    }

    private void updateSettings() {
        // Slog.i(TAG, "updated settings values");
        ContentResolver cr = mContext.getContentResolver();

        mMode = Settings.Secure.getInt(cr, Settings.Secure.POWER_SAVER_MODE,
                POWER_SAVER_MODE_OFF);
        mScreenOffSyncMode = Settings.Secure.getInt(cr, Settings.Secure.POWER_SAVER_SYNC_MODE,
                SYNC_UNTOUCHED);
        mSyncScreenOffSecondInterval = Settings.Secure.getInt(cr,
                Settings.Secure.POWER_SAVER_SYNC_INTERVAL, 15 * 60 /* default */);
        mDataScreenOffSecondDelay = Settings.Secure.getInt(cr,
                Settings.Secure.POWER_SAVER_DATA_DELAY, 0 /* default */);
        mScreenOffDataMode = Settings.Secure.getInt(cr, Settings.Secure.POWER_SAVER_DATA_MODE,
                DATA_UNTOUCHED);

        if (mMode == POWER_SAVER_MODE_OFF) {
            cancelAllTasks();
        }

        if (false) {
            Slog.i(TAG, "mMode: " + mMode);
            Slog.i(TAG, "mSyncMode: " + mScreenOffSyncMode);
            Slog.i(TAG, "mDataMode: " + mScreenOffDataMode);
        }

    }

}