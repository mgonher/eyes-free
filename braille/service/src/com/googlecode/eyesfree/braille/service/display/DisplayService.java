/*
 * Copyright 2012 Google Inc.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package com.googlecode.eyesfree.braille.service.display;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;

import com.googlecode.eyesfree.braille.display.BrailleDisplayProperties;
import com.googlecode.eyesfree.braille.display.BrailleInputEvent;
import com.googlecode.eyesfree.braille.display.IBrailleService;
import com.googlecode.eyesfree.braille.display.IBrailleServiceCallback;
import com.googlecode.eyesfree.braille.service.R;
import com.googlecode.eyesfree.braille.utils.ZipResourceExtractor;

import java.io.File;

/**
 * An Andorid service that connects to braille displays and exposes a unified
 * interface to other Apps.
 */
public class DisplayService extends Service
        implements DriverThread.OnInputEventListener {
    private static final String LOG_TAG = DisplayService.class.getSimpleName();


    /**
     * Listener thread which reads from the braille device and forwards
     * data to the driver.
     */
    /** Written in main thread, read in IPC threads. */
    private volatile ReadThread mReadThread;
    /** The list of registered clients. */
    private final RemoteCallbackList<IBrailleServiceCallback> mClients =
            new RemoteCallbackList<IBrailleServiceCallback>();
    private final ServiceImpl mServiceImpl = new ServiceImpl();
    private final BroadcastReceiver mBroadcastReceiver =
        new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                Log.v(LOG_TAG, "Action to BrailleService: " + action);
                if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    // Try reconnecting to Braille device on screen on action.
                    // This is done so that if a display was turned on
                    // after the service is started, it will be
                    // connected the next time the user unlocks the device.
                    mHandler.unscheduleDisconnect();
                    connectBraille();
                } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    // Disconnect the display after the screen goes off, but
                    // wait a bit since it takes some time to reconnect, which
                    // is going to be frustrating if the screen went off
                    // accidentally.
                    mHandler.scheduleDisconnect(SCREEN_OFF_DISCONNECT_DELAY);
                } else {
                    Log.w(LOG_TAG, "Unexpected broadcast " + action);
            }
        }
    };

    private MainHandler mHandler = new MainHandler();

    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTED = 1;
    private int mConnectionState = STATE_DISCONNECTED;
    /**
     * Delay between the screen goes off and the display gets automatically
     * disconnected.
     */
    private static final long SCREEN_OFF_DISCONNECT_DELAY = 7000;
    private BrailleDisplayProperties mDisplayProperties;
    private File mTablesDir;
    private static final int FILES_ERROR = -1;
    private static final int FILES_NOT_EXTRACTED = 0;
    private static final int FILES_EXTRACTED = 1;
    private int mDataFileState = FILES_NOT_EXTRACTED;
    /** Set to {@code true} if a connect request comes in while
     * we are still disconnecting.
     */
    private boolean mConnectPending;

    @Override
    public void onCreate() {
        super.onCreate();
        mTablesDir = getDir("keytables", Context.MODE_PRIVATE);
        registerBroadcastReceiver();
        ensureDataFiles();
        Log.i(LOG_TAG, "Service started.");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(LOG_TAG, "Destroying service.");
        disconnectBraille();
        if (mBroadcastReceiver != null) {
            unregisterReceiver(mBroadcastReceiver);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.v(LOG_TAG, "onBind");
        connectBraille();
        return mServiceImpl;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        disconnectBraille();
        return true;
    }

    @Override
    public void onRebind(Intent intent) {
        connectBraille();
    }

    // NOTE: The methods in this class are invoked on threads created by the
    // IPC system and not the main application thread.
    private class ServiceImpl extends IBrailleService.Stub {
        @Override
        public boolean registerCallback(
            final IBrailleServiceCallback callback) {
            if (callback == null) {
                Log.e(LOG_TAG, "Registering null callback");
                return false;
            }
            mHandler.registerCallback(callback);
            return true;
        }

        @Override
        public void unregisterCallback(IBrailleServiceCallback callback) {
            if (callback == null) {
                Log.e(LOG_TAG, "Unregistering null callback");
                return;
            }
            if (!mClients.unregister(callback)) {
                Log.w(LOG_TAG, "Failed to unregister callback" + callback);
            }
        }

        @Override
        public void displayDots(final byte[] patterns) {
            if (patterns == null) {
                Log.e(LOG_TAG, "null dot patterns");
            }
            ReadThread localReadThread = mReadThread;
            if (localReadThread == null) {
                return;
            }
            DriverThread localDriverThread = localReadThread.getDriverThread();
            if (localDriverThread == null) {
                return;
            }
            localDriverThread.writeWindow(patterns);
        }
    }

    /*package*/ void onDisplayConnected(
        final BrailleDisplayProperties properties) {
        mHandler.onDisplayConnected(properties);
    }

    /*package*/ void onDisplayDisconnected() {
        mHandler.onDisplayDisconnected();
    }

    /**
     * Forwards input events from the driver thread to be broadcast
     * from the main service thread.
     */
    @Override
    public void onInputEvent(final BrailleInputEvent event) {
        mHandler.onInputEvent(event);
    }

    /**
     * Disconnects the service from the currently connected braille device,
     * sends notification to the clients about the state change.
     */
    private void disconnectBraille() {
        Log.i(LOG_TAG, "Disconnecting braille display");
        mConnectionState = STATE_DISCONNECTED;
        mDisplayProperties = null;
        broadcastConnectionState();
        if (mReadThread != null) {
            mReadThread.disconnect();
        }
    }

    /**
     * Tries to connect to the braille device, on success sends notification to
     * registered clients.
     */
    private void connectBraille() {
        if (mConnectionState == STATE_CONNECTED) {
            return;
        }

        if (mDataFileState != FILES_EXTRACTED) {
            return;
        }

        if (mReadThread != null) {
            // We must wait until the read thread is dead before retrying
            // a connect, so just set the flag here.
            mConnectPending = true;
        } else {
            mReadThread = new ReadThread(this, mTablesDir);
            mReadThread.start();
        }
    }

    private void registerBroadcastReceiver() {
        String[] actions = new String[] {
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_SCREEN_OFF,
        };
        IntentFilter filter = new IntentFilter();
        for (String action : actions) {
            filter.addAction(action);
        }
        registerReceiver(mBroadcastReceiver, filter, null, null);
    }

    private void sendConnectionState(IBrailleServiceCallback callback) {
        try {
            switch (mConnectionState) {
                case STATE_CONNECTED:
                    callback.onDisplayConnected(mDisplayProperties);
                    break;
                case STATE_DISCONNECTED:
                    callback.onDisplayDisconnected();
                    break;
                default:
                    Log.e(LOG_TAG,
                            "Unknown connection state: " + mConnectionState);
                    break;
            }
        } catch (RemoteException ex) {
            // Nothing to do, the callback list will remove the callback
            // later.
        }
    }

    private void broadcastConnectionState() {
        int i = mClients.beginBroadcast();
        try {
            while (i-- > 0) {
                sendConnectionState(mClients.getBroadcastItem(i));
            }
        } finally {
            mClients.finishBroadcast();
        }
    }

    private void broadcastInputEvent(BrailleInputEvent event) {
        int i = mClients.beginBroadcast();
        try {
            while (i-- > 0) {
                try {
                    mClients.getBroadcastItem(i).onInput(event);
                } catch (RemoteException ex) {
                    // Nothing to do, the callback list will remove the
                    // callback later.
                }
            }
        } finally {
            mClients.finishBroadcast();
        }
    }

    /**
     * Returns {@code true} if there are any registered clients that
     * haven't died or unregistered themselves.
     */
    private boolean haveClients() {
        // Unfortunately, the callback list doesn't provide this
        // information without doing this.
        int numClients = mClients.beginBroadcast();
        mClients.finishBroadcast();
        return numClients > 0;
    }

    private void ensureDataFiles() {
        if (mDataFileState != FILES_NOT_EXTRACTED) {
            return;
        }
        // TODO: When the zip file is larger than a few kilobytes, detect if
        // the data was already extracted and don't do this every time the
        // service starts.
        ZipResourceExtractor extractor = new ZipResourceExtractor(
            this, R.raw.keytables, mTablesDir) {
            @Override
            protected void onPostExecute(Integer result) {
                if (result == RESULT_OK) {
                    mDataFileState = FILES_EXTRACTED;
                    if (haveClients()) {
                        connectBraille();
                    }
                } else {
                    Log.e(LOG_TAG, "Couldn't extract data files");
                    // TODO: figure out a way to deal with this so a user
                    // doesn't get stuck in this state.
                    mDataFileState = FILES_ERROR;
                    broadcastConnectionState();
                }
            }
        };
        extractor.execute();
    }

    private class MainHandler extends Handler {
        private static final int MSG_REGISTER_CALLBACK = 1;
        private static final int MSG_ON_DISPLAY_CONNECTED = 2;
        private static final int MSG_ON_DISPLAY_DISCONNECTED = 3;
        private static final int MSG_ON_INPUT_EVENT = 4;
        private static final int MSG_DISCONNECT_BRAILLE = 5;

        public void registerCallback(IBrailleServiceCallback callback) {
            obtainMessage(MSG_REGISTER_CALLBACK, callback).sendToTarget();
        }

        public void onDisplayConnected(BrailleDisplayProperties properties) {
            obtainMessage(MSG_ON_DISPLAY_CONNECTED, properties).sendToTarget();
        }

        public void onDisplayDisconnected() {
            sendEmptyMessage(MSG_ON_DISPLAY_DISCONNECTED);
        }

        public void onInputEvent(BrailleInputEvent event) {
            obtainMessage(MSG_ON_INPUT_EVENT, event).sendToTarget();
        }

        public void unscheduleDisconnect() {
            removeMessages(MSG_DISCONNECT_BRAILLE);
        }

        public void scheduleDisconnect(long delayMillis) {
            sendEmptyMessageDelayed(MSG_DISCONNECT_BRAILLE, delayMillis);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_REGISTER_CALLBACK:
                    handleRegisterCallback((IBrailleServiceCallback) msg.obj);
                    break;
                case MSG_ON_DISPLAY_CONNECTED:
                    handleOnDisplayConnected(
                        (BrailleDisplayProperties) msg.obj);
                    break;
                case MSG_ON_DISPLAY_DISCONNECTED:
                    handleOnDisplayDisconnected();
                    break;
                case MSG_ON_INPUT_EVENT:
                    handleOnInputEvent((BrailleInputEvent) msg.obj);
                    break;
                case MSG_DISCONNECT_BRAILLE:
                    disconnectBraille();
                    break;
            }
        }

        private void handleRegisterCallback(IBrailleServiceCallback callback) {
            mClients.register(callback);
            if (mDataFileState == FILES_NOT_EXTRACTED ||
                    (mConnectionState == STATE_DISCONNECTED
                            && mReadThread != null)) {
                // Extraction or connection in progress, there will be a
                // broadcast of the state when it either succeeds or fails.
                return;
            }
            Log.d(LOG_TAG, "Broadcasting connection state right after connection established");
            sendConnectionState(callback);
        }

        private void handleOnDisplayConnected(
            BrailleDisplayProperties properties) {
            mConnectionState = STATE_CONNECTED;
            mDisplayProperties = properties;
            mConnectPending = false;
            broadcastConnectionState();
        }

        private void handleOnDisplayDisconnected() {
            mReadThread = null;
            if (mConnectionState != STATE_DISCONNECTED) {
                mConnectionState = STATE_DISCONNECTED;
                broadcastConnectionState();
            }
            if (mConnectPending) {
                // Don't get stuck retrying if connecting fails.
                mConnectPending = false;
                connectBraille();
            }
        }

        private void handleOnInputEvent(BrailleInputEvent event) {
            broadcastInputEvent(event);
        }
    }
}
