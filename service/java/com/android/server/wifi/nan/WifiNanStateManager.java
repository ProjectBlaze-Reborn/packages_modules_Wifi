/*
 * Copyright (C) 2016 The Android Open Source Project
 *
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

package com.android.server.wifi.nan;

import android.content.Context;
import android.content.Intent;
import android.net.wifi.RttManager;
import android.net.wifi.nan.ConfigRequest;
import android.net.wifi.nan.IWifiNanDiscoverySessionCallback;
import android.net.wifi.nan.IWifiNanEventCallback;
import android.net.wifi.nan.PublishConfig;
import android.net.wifi.nan.SubscribeConfig;
import android.net.wifi.nan.WifiNanManager;
import android.os.Bundle;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.MessageUtils;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.internal.util.WakeupMessage;

import libcore.util.HexEncoding;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manages the state of the Wi-Fi NAN system service.
 */
public class WifiNanStateManager {
    private static final String TAG = "WifiNanStateManager";
    private static final boolean DBG = false;
    private static final boolean VDBG = false; // STOPSHIP if true

    @VisibleForTesting
    public static final String HAL_COMMAND_TIMEOUT_TAG = TAG + " HAL Command Timeout";

    @VisibleForTesting
    public static final String HAL_SEND_MESSAGE_TIMEOUT_TAG = TAG + " HAL Send Message Timeout";

    @VisibleForTesting
    public static final String HAL_DATA_PATH_CONFIRM_TIMEOUT_TAG =
            TAG + " HAL Data Path Confirm Timeout";

    private static WifiNanStateManager sNanStateManagerSingleton;

    /*
     * State machine message types. There are sub-types for the messages (except for TIMEOUTs).
     * Format:
     * - Message.arg1: contains message sub-type
     * - Message.arg2: contains transaction ID for RESPONSE & RESPONSE_TIMEOUT
     */
    private static final int MESSAGE_TYPE_COMMAND = 1;
    private static final int MESSAGE_TYPE_RESPONSE = 2;
    private static final int MESSAGE_TYPE_NOTIFICATION = 3;
    private static final int MESSAGE_TYPE_RESPONSE_TIMEOUT = 4;
    private static final int MESSAGE_TYPE_SEND_MESSAGE_TIMEOUT = 5;
    private static final int MESSAGE_TYPE_DATA_PATH_TIMEOUT = 6;

    /*
     * Message sub-types:
     */
    private static final int COMMAND_TYPE_CONNECT = 100;
    private static final int COMMAND_TYPE_DISCONNECT = 101;
    private static final int COMMAND_TYPE_TERMINATE_SESSION = 102;
    private static final int COMMAND_TYPE_PUBLISH = 103;
    private static final int COMMAND_TYPE_UPDATE_PUBLISH = 104;
    private static final int COMMAND_TYPE_SUBSCRIBE = 105;
    private static final int COMMAND_TYPE_UPDATE_SUBSCRIBE = 106;
    private static final int COMMAND_TYPE_ENQUEUE_SEND_MESSAGE = 107;
    private static final int COMMAND_TYPE_ENABLE_USAGE = 108;
    private static final int COMMAND_TYPE_DISABLE_USAGE = 109;
    private static final int COMMAND_TYPE_START_RANGING = 110;
    private static final int COMMAND_TYPE_GET_CAPABILITIES = 111;
    private static final int COMMAND_TYPE_CREATE_ALL_DATA_PATH_INTERFACES = 112;
    private static final int COMMAND_TYPE_DELETE_ALL_DATA_PATH_INTERFACES = 113;
    private static final int COMMAND_TYPE_CREATE_DATA_PATH_INTERFACE = 114;
    private static final int COMMAND_TYPE_DELETE_DATA_PATH_INTERFACE = 115;
    private static final int COMMAND_TYPE_INITIATE_DATA_PATH_SETUP = 116;
    private static final int COMMAND_TYPE_RESPOND_TO_DATA_PATH_SETUP_REQUEST = 117;
    private static final int COMMAND_TYPE_END_DATA_PATH = 118;
    private static final int COMMAND_TYPE_TRANSMIT_NEXT_MESSAGE = 119;

    private static final int RESPONSE_TYPE_ON_CONFIG_SUCCESS = 200;
    private static final int RESPONSE_TYPE_ON_CONFIG_FAIL = 201;
    private static final int RESPONSE_TYPE_ON_SESSION_CONFIG_SUCCESS = 202;
    private static final int RESPONSE_TYPE_ON_SESSION_CONFIG_FAIL = 203;
    private static final int RESPONSE_TYPE_ON_MESSAGE_SEND_QUEUED_SUCCESS = 204;
    private static final int RESPONSE_TYPE_ON_MESSAGE_SEND_QUEUED_FAIL = 205;
    private static final int RESPONSE_TYPE_ON_CAPABILITIES_UPDATED = 206;
    private static final int RESPONSE_TYPE_ON_CREATE_INTERFACE = 207;
    private static final int RESPONSE_TYPE_ON_DELETE_INTERFACE = 208;
    private static final int RESPONSE_TYPE_ON_INITIATE_DATA_PATH_SUCCESS = 209;
    private static final int RESPONSE_TYPE_ON_INITIATE_DATA_PATH_FAIL = 210;
    private static final int RESPONSE_TYPE_ON_RESPOND_TO_DATA_PATH_SETUP_REQUEST = 211;
    private static final int RESPONSE_TYPE_ON_END_DATA_PATH = 212;

    private static final int NOTIFICATION_TYPE_INTERFACE_CHANGE = 301;
    private static final int NOTIFICATION_TYPE_CLUSTER_CHANGE = 302;
    private static final int NOTIFICATION_TYPE_MATCH = 303;
    private static final int NOTIFICATION_TYPE_SESSION_TERMINATED = 304;
    private static final int NOTIFICATION_TYPE_MESSAGE_RECEIVED = 305;
    private static final int NOTIFICATION_TYPE_NAN_DOWN = 306;
    private static final int NOTIFICATION_TYPE_ON_MESSAGE_SEND_SUCCESS = 307;
    private static final int NOTIFICATION_TYPE_ON_MESSAGE_SEND_FAIL = 308;
    private static final int NOTIFICATION_TYPE_ON_DATA_PATH_REQUEST = 309;
    private static final int NOTIFICATION_TYPE_ON_DATA_PATH_CONFIRM = 310;
    private static final int NOTIFICATION_TYPE_ON_DATA_PATH_END = 311;

    private static final SparseArray<String> sSmToString = MessageUtils.findMessageNames(
            new Class[]{WifiNanStateManager.class},
            new String[]{"MESSAGE_TYPE", "COMMAND_TYPE", "RESPONSE_TYPE", "NOTIFICATION_TYPE"});

    /*
     * Keys used when passing (some) arguments to the Handler thread (too many
     * arguments to pass in the short-cut Message members).
     */
    private static final String MESSAGE_BUNDLE_KEY_SESSION_TYPE = "session_type";
    private static final String MESSAGE_BUNDLE_KEY_SESSION_ID = "session_id";
    private static final String MESSAGE_BUNDLE_KEY_CONFIG = "config";
    private static final String MESSAGE_BUNDLE_KEY_MESSAGE = "message";
    private static final String MESSAGE_BUNDLE_KEY_MESSAGE_PEER_ID = "message_peer_id";
    private static final String MESSAGE_BUNDLE_KEY_MESSAGE_ID = "message_id";
    private static final String MESSAGE_BUNDLE_KEY_SSI_DATA = "ssi_data";
    private static final String MESSAGE_BUNDLE_KEY_FILTER_DATA = "filter_data";
    private static final String MESSAGE_BUNDLE_KEY_MAC_ADDRESS = "mac_address";
    private static final String MESSAGE_BUNDLE_KEY_MESSAGE_DATA = "message_data";
    private static final String MESSAGE_BUNDLE_KEY_REQ_INSTANCE_ID = "req_instance_id";
    private static final String MESSAGE_BUNDLE_KEY_RANGING_ID = "ranging_id";
    private static final String MESSAGE_BUNDLE_KEY_SEND_MESSAGE_ENQUEUE_TIME = "message_queue_time";
    private static final String MESSAGE_BUNDLE_KEY_RETRY_COUNT = "retry_count";
    private static final String MESSAGE_BUNDLE_KEY_SUCCESS_FLAG = "success_flag";
    private static final String MESSAGE_BUNDLE_KEY_STATUS_CODE = "status_code";
    private static final String MESSAGE_BUNDLE_KEY_INTERFACE_NAME = "interface_name";
    private static final String MESSAGE_BUNDLE_KEY_CHANNEL_REQ_TYPE = "channel_request_type";
    private static final String MESSAGE_BUNDLE_KEY_CHANNEL = "channel";
    private static final String MESSAGE_BUNDLE_KEY_PEER_ID = "peer_id";
    private static final String MESSAGE_BUNDLE_KEY_UID = "uid";
    private static final String MESSAGE_BUNDLE_KEY_PID = "pid";
    private static final String MESSAGE_BUNDLE_KEY_CALLING_PACKAGE = "calling_package";
    private static final String MESSAGE_BUNDLE_KEY_SENT_MESSAGE = "send_message";
    private static final String MESSAGE_BUNDLE_KEY_MESSAGE_ARRIVAL_SEQ = "message_arrival_seq";
    private static final String MESSAGE_BUNDLE_KEY_NOTIFY_IDENTITY_CHANGE = "notify_identity_chg";

    /*
     * Asynchronous access with no lock
     */
    private volatile boolean mUsageEnabled = false;

    /*
     * Synchronous access: state is only accessed through the state machine
     * handler thread: no need to use a lock.
     */
    private Context mContext;
    /* package */ WifiNanNative.Capabilities mCapabilities;
    private WifiNanStateMachine mSm;
    private WifiNanRttStateManager mRtt;
    private WifiNanDataPathStateManager mDataPathMgr;

    private final SparseArray<WifiNanClientState> mClients = new SparseArray<>();
    private ConfigRequest mCurrentNanConfiguration = null;

    private static final byte[] ALL_ZERO_MAC = new byte[] {0, 0, 0, 0, 0, 0};
    private byte[] mCurrentDiscoveryInterfaceMac = ALL_ZERO_MAC;

    private WifiNanStateManager() {
        // EMPTY: singleton pattern
    }

    /**
     * Access the singleton NAN state manager. Use a singleton since need to be
     * accessed (for now) from several other child classes.
     *
     * @return The state manager singleton.
     */
    public static WifiNanStateManager getInstance() {
        if (sNanStateManagerSingleton == null) {
            sNanStateManagerSingleton = new WifiNanStateManager();
        }

        return sNanStateManagerSingleton;
    }

    /**
     * Initialize the handler of the state manager with the specified thread
     * looper.
     *
     * @param looper Thread looper on which to run the handler.
     */
    public void start(Context context, Looper looper) {
        Log.i(TAG, "start()");

        mContext = context;
        mSm = new WifiNanStateMachine(TAG, looper);
        mSm.setDbg(DBG);
        mSm.start();

        mRtt = new WifiNanRttStateManager();
        mDataPathMgr = new WifiNanDataPathStateManager(this);
        mDataPathMgr.start(mContext, mSm.getHandler().getLooper());
    }

    /**
     * Initialize the late-initialization sub-services: depend on other services already existing.
     */
    public void startLate() {
        mRtt.start(mContext, mSm.getHandler().getLooper());
    }

    /**
     * Get the client state for the specified ID (or null if none exists).
     */
    /* package */ WifiNanClientState getClient(int clientId) {
        return mClients.get(clientId);
    }

    /*
     * COMMANDS
     */

    /**
     * Place a request for a new client connection on the state machine queue.
     */
    public void connect(int clientId, int uid, int pid, String callingPackage,
            IWifiNanEventCallback callback, ConfigRequest configRequest,
            boolean notifyOnIdentityChanged) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_CONNECT;
        msg.arg2 = clientId;
        msg.obj = callback;
        msg.getData().putParcelable(MESSAGE_BUNDLE_KEY_CONFIG, configRequest);
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_UID, uid);
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_PID, pid);
        msg.getData().putString(MESSAGE_BUNDLE_KEY_CALLING_PACKAGE, callingPackage);
        msg.getData().putBoolean(MESSAGE_BUNDLE_KEY_NOTIFY_IDENTITY_CHANGE,
                notifyOnIdentityChanged);
        mSm.sendMessage(msg);
    }

    /**
     * Place a request to disconnect (destroy) an existing client on the state
     * machine queue.
     */
    public void disconnect(int clientId) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_DISCONNECT;
        msg.arg2 = clientId;
        mSm.sendMessage(msg);
    }

    /**
     * Place a request to stop a discovery session on the state machine queue.
     */
    public void terminateSession(int clientId, int sessionId) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_TERMINATE_SESSION;
        msg.arg2 = clientId;
        msg.obj = sessionId;
        mSm.sendMessage(msg);
    }

    /**
     * Place a request to start a new publish discovery session on the state
     * machine queue.
     */
    public void publish(int clientId, PublishConfig publishConfig,
            IWifiNanDiscoverySessionCallback callback) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_PUBLISH;
        msg.arg2 = clientId;
        msg.obj = callback;
        msg.getData().putParcelable(MESSAGE_BUNDLE_KEY_CONFIG, publishConfig);
        mSm.sendMessage(msg);
    }

    /**
     * Place a request to modify an existing publish discovery session on the
     * state machine queue.
     */
    public void updatePublish(int clientId, int sessionId, PublishConfig publishConfig) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_UPDATE_PUBLISH;
        msg.arg2 = clientId;
        msg.obj = publishConfig;
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_SESSION_ID, sessionId);
        mSm.sendMessage(msg);
    }

    /**
     * Place a request to start a new subscribe discovery session on the state
     * machine queue.
     */
    public void subscribe(int clientId, SubscribeConfig subscribeConfig,
            IWifiNanDiscoverySessionCallback callback) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_SUBSCRIBE;
        msg.arg2 = clientId;
        msg.obj = callback;
        msg.getData().putParcelable(MESSAGE_BUNDLE_KEY_CONFIG, subscribeConfig);
        mSm.sendMessage(msg);
    }

    /**
     * Place a request to modify an existing subscribe discovery session on the
     * state machine queue.
     */
    public void updateSubscribe(int clientId, int sessionId, SubscribeConfig subscribeConfig) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_UPDATE_SUBSCRIBE;
        msg.arg2 = clientId;
        msg.obj = subscribeConfig;
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_SESSION_ID, sessionId);
        mSm.sendMessage(msg);
    }

    /**
     * Place a request to send a message on a discovery session on the state
     * machine queue.
     */
    public void sendMessage(int clientId, int sessionId, int peerId, byte[] message, int messageId,
            int retryCount) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_ENQUEUE_SEND_MESSAGE;
        msg.arg2 = clientId;
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_SESSION_ID, sessionId);
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_MESSAGE_PEER_ID, peerId);
        msg.getData().putByteArray(MESSAGE_BUNDLE_KEY_MESSAGE, message);
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_MESSAGE_ID, messageId);
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_RETRY_COUNT, retryCount);
        mSm.sendMessage(msg);
    }

    /**
     * Place a request to range a peer on the discovery session on the state machine queue.
     */
    public void startRanging(int clientId, int sessionId, RttManager.RttParams[] params,
                             int rangingId) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_START_RANGING;
        msg.arg2 = clientId;
        msg.obj = params;
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_SESSION_ID, sessionId);
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_RANGING_ID, rangingId);
        mSm.sendMessage(msg);
    }

    /**
     * Enable usage of NAN. Doesn't actually turn on NAN (form clusters) - that
     * only happens when a connection is created.
     */
    public void enableUsage() {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_ENABLE_USAGE;
        mSm.sendMessage(msg);
    }

    /**
     * Disable usage of NAN. Terminates all existing clients with onNanDown().
     */
    public void disableUsage() {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_DISABLE_USAGE;
        mSm.sendMessage(msg);
    }

    /**
     * Checks whether NAN usage is enabled (not necessarily that NAN is up right
     * now) or disabled.
     *
     * @return A boolean indicating whether NAN usage is enabled (true) or
     *         disabled (false).
     */
    public boolean isUsageEnabled() {
        return mUsageEnabled;
    }

    /**
     * Get the capabilities of the current NAN firmware.
     */
    public void getCapabilities() {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_GET_CAPABILITIES;
        mSm.sendMessage(msg);
    }

    /**
     * Create all NAN data path interfaces which are supported by the firmware capabilities.
     */
    public void createAllDataPathInterfaces() {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_CREATE_ALL_DATA_PATH_INTERFACES;
        mSm.sendMessage(msg);
    }

    /**
     * delete all NAN data path interfaces.
     */
    public void deleteAllDataPathInterfaces() {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_DELETE_ALL_DATA_PATH_INTERFACES;
        mSm.sendMessage(msg);
    }

    /**
     * Create the specified data-path interface. Doesn't actually creates a data-path.
     */
    public void createDataPathInterface(String interfaceName) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_CREATE_DATA_PATH_INTERFACE;
        msg.obj = interfaceName;
        mSm.sendMessage(msg);
    }

    /**
     * Deletes the specified data-path interface.
     */
    public void deleteDataPathInterface(String interfaceName) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_DELETE_DATA_PATH_INTERFACE;
        msg.obj = interfaceName;
        mSm.sendMessage(msg);
    }

    /**
     * Command to initiate a data-path (executed by the initiator).
     */
    public void initiateDataPathSetup(String networkSpecifier, int peerId, int channelRequestType,
            int channel, byte[] peer, String interfaceName, byte[] token) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_INITIATE_DATA_PATH_SETUP;
        msg.obj = networkSpecifier;
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_PEER_ID, peerId);
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_CHANNEL_REQ_TYPE, channelRequestType);
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_CHANNEL, channel);
        msg.getData().putByteArray(MESSAGE_BUNDLE_KEY_MAC_ADDRESS, peer);
        msg.getData().putString(MESSAGE_BUNDLE_KEY_INTERFACE_NAME, interfaceName);
        msg.getData().putByteArray(MESSAGE_BUNDLE_KEY_MESSAGE, token);
        mSm.sendMessage(msg);
    }

    /**
     * Command to respond to the data-path request (executed by the responder).
     */
    public void respondToDataPathRequest(boolean accept, int ndpId, String interfaceName,
            String token) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_RESPOND_TO_DATA_PATH_SETUP_REQUEST;
        msg.arg2 = ndpId;
        msg.obj = accept;
        msg.getData().putString(MESSAGE_BUNDLE_KEY_INTERFACE_NAME, interfaceName);
        msg.getData().putString(MESSAGE_BUNDLE_KEY_MESSAGE, token);
        mSm.sendMessage(msg);
    }

    /**
     * Command to terminate the specified data-path.
     */
    public void endDataPath(int ndpId) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_END_DATA_PATH;
        msg.arg2 = ndpId;
        mSm.sendMessage(msg);
    }

    /**
     * NAN follow-on messages (L2 messages) are queued by the firmware for transmission
     * on-the-air. The firmware has limited queue depth. The host queues all messages and doles
     * them out to the firmware when possible. This command removes the next messages for
     * transmission from the host queue and attempts to send it through the firmware. The queues
     * are inspected when the command is executed - not when the command is placed on the handler
     * (i.e. not evaluated here).
     */
    private void transmitNextMessage() {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_TRANSMIT_NEXT_MESSAGE;
        mSm.sendMessage(msg);
    }

    /*
     * RESPONSES
     */

    /**
     * Place a callback request on the state machine queue: configuration
     * request completed (successfully).
     */
    public void onConfigSuccessResponse(short transactionId) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_RESPONSE);
        msg.arg1 = RESPONSE_TYPE_ON_CONFIG_SUCCESS;
        msg.arg2 = transactionId;
        mSm.sendMessage(msg);
    }

    /**
     * Place a callback request on the state machine queue: configuration
     * request failed.
     */
    public void onConfigFailedResponse(short transactionId, int reason) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_RESPONSE);
        msg.arg1 = RESPONSE_TYPE_ON_CONFIG_FAIL;
        msg.arg2 = transactionId;
        msg.obj = reason;
        mSm.sendMessage(msg);
    }

    /**
     * Place a callback request on the state machine queue: session
     * configuration (new or update) request succeeded.
     */
    public void onSessionConfigSuccessResponse(short transactionId, boolean isPublish,
            int pubSubId) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_RESPONSE);
        msg.arg1 = RESPONSE_TYPE_ON_SESSION_CONFIG_SUCCESS;
        msg.arg2 = transactionId;
        msg.obj = pubSubId;
        msg.getData().putBoolean(MESSAGE_BUNDLE_KEY_SESSION_TYPE, isPublish);
        mSm.sendMessage(msg);
    }

    /**
     * Place a callback request on the state machine queue: session
     * configuration (new or update) request failed.
     */
    public void onSessionConfigFailResponse(short transactionId, boolean isPublish, int reason) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_RESPONSE);
        msg.arg1 = RESPONSE_TYPE_ON_SESSION_CONFIG_FAIL;
        msg.arg2 = transactionId;
        msg.obj = reason;
        msg.getData().putBoolean(MESSAGE_BUNDLE_KEY_SESSION_TYPE, isPublish);
        mSm.sendMessage(msg);
    }

    /**
     * Place a callback request on the state machine queue: message has been queued successfully.
     */
    public void onMessageSendQueuedSuccessResponse(short transactionId) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_RESPONSE);
        msg.arg1 = RESPONSE_TYPE_ON_MESSAGE_SEND_QUEUED_SUCCESS;
        msg.arg2 = transactionId;
        mSm.sendMessage(msg);
    }

    /**
     * Place a callback request on the state machine queue: attempt to queue the message failed.
     */
    public void onMessageSendQueuedFailResponse(short transactionId, int reason) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_RESPONSE);
        msg.arg1 = RESPONSE_TYPE_ON_MESSAGE_SEND_QUEUED_FAIL;
        msg.arg2 = transactionId;
        msg.obj = reason;
        mSm.sendMessage(msg);
    }

    /**
     * Place a callback request on the state machine queue: update vendor
     * capabilities of the NAN stack.
     */
    public void onCapabilitiesUpdateResponse(short transactionId,
            WifiNanNative.Capabilities capabilities) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_RESPONSE);
        msg.arg1 = RESPONSE_TYPE_ON_CAPABILITIES_UPDATED;
        msg.arg2 = transactionId;
        msg.obj = capabilities;
        mSm.sendMessage(msg);
    }

    /**
     * Places a callback request on the state machine queue: data-path interface creation command
     * completed.
     */
    public void onCreateDataPathInterfaceResponse(short transactionId, boolean success,
            int reasonOnFailure) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_RESPONSE);
        msg.arg1 = RESPONSE_TYPE_ON_CREATE_INTERFACE;
        msg.arg2 = transactionId;
        msg.getData().putBoolean(MESSAGE_BUNDLE_KEY_SUCCESS_FLAG, success);
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_STATUS_CODE, reasonOnFailure);
        mSm.sendMessage(msg);
    }

    /**
     * Places a callback request on the state machine queue: data-path interface deletion command
     * completed.
     */
    public void onDeleteDataPathInterfaceResponse(short transactionId, boolean success,
            int reasonOnFailure) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_RESPONSE);
        msg.arg1 = RESPONSE_TYPE_ON_DELETE_INTERFACE;
        msg.arg2 = transactionId;
        msg.getData().putBoolean(MESSAGE_BUNDLE_KEY_SUCCESS_FLAG, success);
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_STATUS_CODE, reasonOnFailure);
        mSm.sendMessage(msg);
    }

    /**
     * Response from firmware to {@link #initiateDataPathSetup(String, int, int, int, byte[],
     * String, byte[])}. Indicates that command has started succesfully (not completed!).
     */
    public void onInitiateDataPathResponseSuccess(short transactionId, int ndpId) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_RESPONSE);
        msg.arg1 = RESPONSE_TYPE_ON_INITIATE_DATA_PATH_SUCCESS;
        msg.arg2 = transactionId;
        msg.obj = ndpId;
        mSm.sendMessage(msg);
    }

    /**
     * Response from firmware to
     * {@link #initiateDataPathSetup(String, int, int, int, byte[], String, byte[])}. Indicates
     * that command has failed.
     */
    public void onInitiateDataPathResponseFail(short transactionId, int reason) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_RESPONSE);
        msg.arg1 = RESPONSE_TYPE_ON_INITIATE_DATA_PATH_FAIL;
        msg.arg2 = transactionId;
        msg.obj = reason;
        mSm.sendMessage(msg);
    }

    /**
     * Response from firmware to {@link #respondToDataPathRequest(boolean, int, String, String)}.
     */
    public void onRespondToDataPathSetupRequestResponse(short transactionId, boolean success,
            int reasonOnFailure) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_RESPONSE);
        msg.arg1 = RESPONSE_TYPE_ON_RESPOND_TO_DATA_PATH_SETUP_REQUEST;
        msg.arg2 = transactionId;
        msg.getData().putBoolean(MESSAGE_BUNDLE_KEY_SUCCESS_FLAG, success);
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_STATUS_CODE, reasonOnFailure);
        mSm.sendMessage(msg);
    }

    /**
     * Response from firmware to {@link #endDataPath(int)}.
     */
    public void onEndDataPathResponse(short transactionId, boolean success, int reasonOnFailure) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_RESPONSE);
        msg.arg1 = RESPONSE_TYPE_ON_END_DATA_PATH;
        msg.arg2 = transactionId;
        msg.getData().putBoolean(MESSAGE_BUNDLE_KEY_SUCCESS_FLAG, success);
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_STATUS_CODE, reasonOnFailure);
        mSm.sendMessage(msg);
    }

    /*
     * NOTIFICATIONS
     */

    /**
     * Place a callback request on the state machine queue: the discovery
     * interface has changed.
     */
    public void onInterfaceAddressChangeNotification(byte[] mac) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_NOTIFICATION);
        msg.arg1 = NOTIFICATION_TYPE_INTERFACE_CHANGE;
        msg.obj = mac;
        mSm.sendMessage(msg);
    }

    /**
     * Place a callback request on the state machine queue: the cluster
     * membership has changed (e.g. due to starting a new cluster or joining
     * another cluster).
     */
    public void onClusterChangeNotification(int flag, byte[] clusterId) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_NOTIFICATION);
        msg.arg1 = NOTIFICATION_TYPE_CLUSTER_CHANGE;
        msg.arg2 = flag;
        msg.obj = clusterId;
        mSm.sendMessage(msg);
    }

    /**
     * Place a callback request on the state machine queue: a discovery match
     * has occurred - e.g. our subscription discovered someone else publishing a
     * matching service (to the one we were looking for).
     */
    public void onMatchNotification(int pubSubId, int requestorInstanceId, byte[] peerMac,
            byte[] serviceSpecificInfo, byte[] matchFilter) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_NOTIFICATION);
        msg.arg1 = NOTIFICATION_TYPE_MATCH;
        msg.arg2 = pubSubId;
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_REQ_INSTANCE_ID, requestorInstanceId);
        msg.getData().putByteArray(MESSAGE_BUNDLE_KEY_MAC_ADDRESS, peerMac);
        msg.getData().putByteArray(MESSAGE_BUNDLE_KEY_SSI_DATA, serviceSpecificInfo);
        msg.getData().putByteArray(MESSAGE_BUNDLE_KEY_FILTER_DATA, matchFilter);
        mSm.sendMessage(msg);
    }

    /**
     * Place a callback request on the state machine queue: a session (publish
     * or subscribe) has terminated (per plan or due to an error).
     */
    public void onSessionTerminatedNotification(int pubSubId, int reason, boolean isPublish) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_NOTIFICATION);
        msg.arg1 = NOTIFICATION_TYPE_SESSION_TERMINATED;
        msg.arg2 = pubSubId;
        msg.obj = reason;
        msg.getData().putBoolean(MESSAGE_BUNDLE_KEY_SESSION_TYPE, isPublish);
        mSm.sendMessage(msg);
    }

    /**
     * Place a callback request on the state machine queue: a message has been
     * received as part of a discovery session.
     */
    public void onMessageReceivedNotification(int pubSubId, int requestorInstanceId, byte[] peerMac,
            byte[] message) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_NOTIFICATION);
        msg.arg1 = NOTIFICATION_TYPE_MESSAGE_RECEIVED;
        msg.arg2 = pubSubId;
        msg.obj = requestorInstanceId;
        msg.getData().putByteArray(MESSAGE_BUNDLE_KEY_MAC_ADDRESS, peerMac);
        msg.getData().putByteArray(MESSAGE_BUNDLE_KEY_MESSAGE_DATA, message);
        mSm.sendMessage(msg);
    }

    /**
     * Place a callback request on the state machine queue: NAN is going down.
     */
    public void onNanDownNotification(int reason) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_NOTIFICATION);
        msg.arg1 = NOTIFICATION_TYPE_NAN_DOWN;
        msg.arg2 = reason;
        mSm.sendMessage(msg);
    }

    /**
     * Notification that a message has been sent successfully (i.e. an ACK has been received).
     */
    public void onMessageSendSuccessNotification(short transactionId) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_NOTIFICATION);
        msg.arg1 = NOTIFICATION_TYPE_ON_MESSAGE_SEND_SUCCESS;
        msg.arg2 = transactionId;
        mSm.sendMessage(msg);
    }

    /**
     * Notification that a message transmission has failed due to the indicated reason - e.g. no ACK
     * was received.
     */
    public void onMessageSendFailNotification(short transactionId, int reason) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_NOTIFICATION);
        msg.arg1 = NOTIFICATION_TYPE_ON_MESSAGE_SEND_FAIL;
        msg.arg2 = transactionId;
        msg.obj = reason;
        mSm.sendMessage(msg);
    }

    /**
     * Place a callback request on the state machine queue: data-path request (from peer) received.
     */
    public void onDataPathRequestNotification(int pubSubId, byte[] mac, int ndpId, byte[] message) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_NOTIFICATION);
        msg.arg1 = NOTIFICATION_TYPE_ON_DATA_PATH_REQUEST;
        msg.arg2 = pubSubId;
        msg.obj = ndpId;
        msg.getData().putByteArray(MESSAGE_BUNDLE_KEY_MAC_ADDRESS, mac);
        msg.getData().putByteArray(MESSAGE_BUNDLE_KEY_MESSAGE_DATA, message);
        mSm.sendMessage(msg);
    }

    /**
     * Place a callback request on the state machine queue: data-path confirmation received - i.e.
     * data-path is now up.
     */
    public void onDataPathConfirmNotification(int ndpId, byte[] mac, boolean accept, int reason,
            byte[] message) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_NOTIFICATION);
        msg.arg1 = NOTIFICATION_TYPE_ON_DATA_PATH_CONFIRM;
        msg.arg2 = ndpId;
        msg.getData().putByteArray(MESSAGE_BUNDLE_KEY_MAC_ADDRESS, mac);
        msg.getData().putBoolean(MESSAGE_BUNDLE_KEY_SUCCESS_FLAG, accept);
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_STATUS_CODE, reason);
        msg.getData().putByteArray(MESSAGE_BUNDLE_KEY_MESSAGE_DATA, message);
        mSm.sendMessage(msg);
    }

    /**
     * Place a callback request on the state machine queue: the specified data-path has been
     * terminated.
     */
    public void onDataPathEndNotification(int ndpId) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_NOTIFICATION);
        msg.arg1 = NOTIFICATION_TYPE_ON_DATA_PATH_END;
        msg.arg2 = ndpId;
        mSm.sendMessage(msg);
    }

    /**
     * State machine.
     */
    @VisibleForTesting
    class WifiNanStateMachine extends StateMachine {
        private static final int TRANSACTION_ID_IGNORE = 0;

        private DefaultState mDefaultState = new DefaultState();
        private WaitState mWaitState = new WaitState();
        private WaitForResponseState mWaitForResponseState = new WaitForResponseState();

        private short mNextTransactionId = 1;
        public int mNextSessionId = 1;

        private Message mCurrentCommand;
        private short mCurrentTransactionId = TRANSACTION_ID_IGNORE;

        private static final long NAN_SEND_MESSAGE_TIMEOUT = 10_000;
        private int mSendArrivalSequenceCounter = 0;
        private boolean mSendQueueBlocked = false;
        private final SparseArray<Message> mHostQueuedSendMessages = new SparseArray<>();
        private final Map<Short, Message> mFwQueuedSendMessages = new LinkedHashMap<>();
        private WakeupMessage mSendMessageTimeoutMessage = new WakeupMessage(mContext, getHandler(),
                HAL_SEND_MESSAGE_TIMEOUT_TAG, MESSAGE_TYPE_SEND_MESSAGE_TIMEOUT);

        private static final long NAN_WAIT_FOR_DP_CONFIRM_TIMEOUT = 5_000;
        private final Map<String, WakeupMessage> mDataPathConfirmTimeoutMessages = new ArrayMap<>();

        WifiNanStateMachine(String name, Looper looper) {
            super(name, looper);

            addState(mDefaultState);
            /* --> */ addState(mWaitState, mDefaultState);
            /* --> */ addState(mWaitForResponseState, mDefaultState);

            setInitialState(mWaitState);
        }

        public void onNanDownCleanupSendQueueState() {
            mSendQueueBlocked = false;
            mHostQueuedSendMessages.clear();
            mFwQueuedSendMessages.clear();
        }

        private class DefaultState extends State {
            @Override
            public boolean processMessage(Message msg) {
                if (VDBG) {
                    Log.v(TAG, getName() + msg.toString());
                }

                switch (msg.what) {
                    case MESSAGE_TYPE_NOTIFICATION:
                        processNotification(msg);
                        return HANDLED;
                    case MESSAGE_TYPE_SEND_MESSAGE_TIMEOUT:
                        processSendMessageTimeout();
                        return HANDLED;
                    case MESSAGE_TYPE_DATA_PATH_TIMEOUT: {
                        String networkSpecifier = (String) msg.obj;

                        if (VDBG) {
                            Log.v(TAG, "MESSAGE_TYPE_DATA_PATH_TIMEOUT: networkSpecifier="
                                    + networkSpecifier);
                        }

                        mDataPathMgr.handleDataPathTimeout(networkSpecifier);
                        mDataPathConfirmTimeoutMessages.remove(networkSpecifier);
                        return HANDLED;
                    }
                    default:
                        /* fall-through */
                }

                Log.wtf(TAG,
                        "DefaultState: should not get non-NOTIFICATION in this state: msg=" + msg);
                return NOT_HANDLED;
            }
        }

        private class WaitState extends State {
            @Override
            public boolean processMessage(Message msg) {
                if (VDBG) {
                    Log.v(TAG, getName() + msg.toString());
                }

                switch (msg.what) {
                    case MESSAGE_TYPE_COMMAND:
                        if (processCommand(msg)) {
                            transitionTo(mWaitForResponseState);
                        }
                        return HANDLED;
                    case MESSAGE_TYPE_RESPONSE:
                        /* fall-through */
                    case MESSAGE_TYPE_RESPONSE_TIMEOUT:
                        /*
                         * remnants/delayed/out-of-sync messages - but let
                         * WaitForResponseState deal with them (identified as
                         * out-of-date by transaction ID).
                         */
                        deferMessage(msg);
                        return HANDLED;
                    default:
                        /* fall-through */
                }

                return NOT_HANDLED;
            }
        }

        private class WaitForResponseState extends State {
            private static final long NAN_COMMAND_TIMEOUT = 5_000;
            private WakeupMessage mTimeoutMessage;

            @Override
            public void enter() {
                mTimeoutMessage = new WakeupMessage(mContext, getHandler(), HAL_COMMAND_TIMEOUT_TAG,
                        MESSAGE_TYPE_RESPONSE_TIMEOUT, mCurrentCommand.arg1, mCurrentTransactionId);
                mTimeoutMessage.schedule(SystemClock.elapsedRealtime() + NAN_COMMAND_TIMEOUT);
            }

            @Override
            public void exit() {
                mTimeoutMessage.cancel();
            }

            @Override
            public boolean processMessage(Message msg) {
                if (VDBG) {
                    Log.v(TAG, getName() + msg.toString());
                }

                switch (msg.what) {
                    case MESSAGE_TYPE_COMMAND:
                        /*
                         * don't want COMMANDs in this state - defer until back
                         * in WaitState
                         */
                        deferMessage(msg);
                        return HANDLED;
                    case MESSAGE_TYPE_RESPONSE:
                        if (msg.arg2 == mCurrentTransactionId) {
                            processResponse(msg);
                            transitionTo(mWaitState);
                        } else {
                            Log.w(TAG,
                                    "WaitForResponseState: processMessage: non-matching "
                                            + "transaction ID on RESPONSE (a very late "
                                            + "response) -- msg=" + msg);
                            /* no transition */
                        }
                        return HANDLED;
                    case MESSAGE_TYPE_RESPONSE_TIMEOUT:
                        if (msg.arg2 == mCurrentTransactionId) {
                            processTimeout(msg);
                            transitionTo(mWaitState);
                        } else {
                            Log.w(TAG, "WaitForResponseState: processMessage: non-matching "
                                    + "transaction ID on RESPONSE_TIMEOUT (either a non-cancelled "
                                    + "timeout or a race condition with cancel) -- msg=" + msg);
                            /* no transition */
                        }
                        return HANDLED;
                    default:
                        /* fall-through */
                }

                return NOT_HANDLED;
            }
        }

        private void processNotification(Message msg) {
            if (VDBG) {
                Log.v(TAG, "processNotification: msg=" + msg);
            }

            switch (msg.arg1) {
                case NOTIFICATION_TYPE_INTERFACE_CHANGE: {
                    byte[] mac = (byte[]) msg.obj;

                    onInterfaceAddressChangeLocal(mac);
                    break;
                }
                case NOTIFICATION_TYPE_CLUSTER_CHANGE: {
                    int flag = msg.arg2;
                    byte[] clusterId = (byte[]) msg.obj;

                    onClusterChangeLocal(flag, clusterId);
                    break;
                }
                case NOTIFICATION_TYPE_MATCH: {
                    int pubSubId = msg.arg2;
                    int requestorInstanceId = msg.getData()
                            .getInt(MESSAGE_BUNDLE_KEY_REQ_INSTANCE_ID);
                    byte[] peerMac = msg.getData().getByteArray(MESSAGE_BUNDLE_KEY_MAC_ADDRESS);
                    byte[] serviceSpecificInfo = msg.getData()
                            .getByteArray(MESSAGE_BUNDLE_KEY_SSI_DATA);
                    byte[] matchFilter = msg.getData().getByteArray(MESSAGE_BUNDLE_KEY_FILTER_DATA);

                    onMatchLocal(pubSubId, requestorInstanceId, peerMac, serviceSpecificInfo,
                            matchFilter);
                    break;
                }
                case NOTIFICATION_TYPE_SESSION_TERMINATED: {
                    int pubSubId = msg.arg2;
                    int reason = (Integer) msg.obj;
                    boolean isPublish = msg.getData().getBoolean(MESSAGE_BUNDLE_KEY_SESSION_TYPE);

                    onSessionTerminatedLocal(pubSubId, isPublish, reason);
                    break;
                }
                case NOTIFICATION_TYPE_MESSAGE_RECEIVED: {
                    int pubSubId = msg.arg2;
                    int requestorInstanceId = (Integer) msg.obj;
                    byte[] peerMac = msg.getData().getByteArray(MESSAGE_BUNDLE_KEY_MAC_ADDRESS);
                    byte[] message = msg.getData().getByteArray(MESSAGE_BUNDLE_KEY_MESSAGE_DATA);

                    onMessageReceivedLocal(pubSubId, requestorInstanceId, peerMac, message);
                    break;
                }
                case NOTIFICATION_TYPE_NAN_DOWN: {
                    int reason = msg.arg2;

                    /*
                     * TODO: b/28615938. Use reason code to determine whether or not need clean-up
                     * local state (only needed if NAN_DOWN is due to internal firmware reason, e.g.
                     * concurrency, rather than due to a requested shutdown).
                     */

                    onNanDownLocal();

                    break;
                }
                case NOTIFICATION_TYPE_ON_MESSAGE_SEND_SUCCESS: {
                    short transactionId = (short) msg.arg2;
                    Message queuedSendCommand = mFwQueuedSendMessages.get(transactionId);
                    if (VDBG) {
                        Log.v(TAG, "NOTIFICATION_TYPE_ON_MESSAGE_SEND_SUCCESS: queuedSendCommand="
                                + queuedSendCommand);
                    }
                    if (queuedSendCommand == null) {
                        Log.w(TAG,
                                "processNotification: NOTIFICATION_TYPE_ON_MESSAGE_SEND_SUCCESS:"
                                        + " transactionId=" + transactionId
                                        + " - no such queued send command (timed-out?)");
                    } else {
                        mFwQueuedSendMessages.remove(transactionId);
                        updateSendMessageTimeout();
                        onMessageSendSuccessLocal(queuedSendCommand);
                    }
                    mSendQueueBlocked = false;
                    transmitNextMessage();

                    break;
                }
                case NOTIFICATION_TYPE_ON_MESSAGE_SEND_FAIL: {
                    short transactionId = (short) msg.arg2;
                    int reason = (Integer) msg.obj;
                    Message sentMessage = mFwQueuedSendMessages.get(transactionId);
                    if (VDBG) {
                        Log.v(TAG, "NOTIFICATION_TYPE_ON_MESSAGE_SEND_FAIL: sentMessage="
                                + sentMessage);
                    }
                    if (sentMessage == null) {
                        Log.w(TAG,
                                "processNotification: NOTIFICATION_TYPE_ON_MESSAGE_SEND_FAIL:"
                                        + " transactionId=" + transactionId
                                        + " - no such queued send command (timed-out?)");
                    } else {
                        mFwQueuedSendMessages.remove(transactionId);
                        updateSendMessageTimeout();

                        int retryCount = sentMessage.getData()
                                .getInt(MESSAGE_BUNDLE_KEY_RETRY_COUNT);
                        if (retryCount > 0 && (reason == WifiNanNative.NAN_STATUS_NO_OTA_ACK
                                || reason == WifiNanNative.NAN_STATUS_TX_FAIL)) {
                            if (DBG) {
                                Log.d(TAG,
                                        "NOTIFICATION_TYPE_ON_MESSAGE_SEND_FAIL: transactionId="
                                                + transactionId + ", reason=" + reason
                                                + ": retransmitting - retryCount=" + retryCount);
                            }
                            sentMessage.getData().putInt(MESSAGE_BUNDLE_KEY_RETRY_COUNT,
                                    retryCount - 1);

                            int arrivalSeq = sentMessage.getData().getInt(
                                    MESSAGE_BUNDLE_KEY_MESSAGE_ARRIVAL_SEQ);
                            mHostQueuedSendMessages.put(arrivalSeq, sentMessage);
                        } else {
                            onMessageSendFailLocal(sentMessage, reason);
                        }
                        mSendQueueBlocked = false;
                        transmitNextMessage();
                    }
                    break;
                }
                case NOTIFICATION_TYPE_ON_DATA_PATH_REQUEST: {
                    String networkSpecifier = mDataPathMgr.onDataPathRequest(msg.arg2,
                            msg.getData().getByteArray(MESSAGE_BUNDLE_KEY_MAC_ADDRESS),
                            (int) msg.obj,
                            msg.getData().getByteArray(MESSAGE_BUNDLE_KEY_MESSAGE_DATA));

                    if (networkSpecifier != null) {
                        WakeupMessage timeout = new WakeupMessage(mContext, getHandler(),
                                HAL_DATA_PATH_CONFIRM_TIMEOUT_TAG, MESSAGE_TYPE_DATA_PATH_TIMEOUT,
                                0, 0, networkSpecifier);
                        mDataPathConfirmTimeoutMessages.put(networkSpecifier, timeout);
                        timeout.schedule(
                                SystemClock.elapsedRealtime() + NAN_WAIT_FOR_DP_CONFIRM_TIMEOUT);
                    }

                    break;
                }
                case NOTIFICATION_TYPE_ON_DATA_PATH_CONFIRM: {
                    String networkSpecifier = mDataPathMgr.onDataPathConfirm(msg.arg2,
                            msg.getData().getByteArray(MESSAGE_BUNDLE_KEY_MAC_ADDRESS),
                            msg.getData().getBoolean(MESSAGE_BUNDLE_KEY_SUCCESS_FLAG),
                            msg.getData().getInt(MESSAGE_BUNDLE_KEY_STATUS_CODE),
                            msg.getData().getByteArray(MESSAGE_BUNDLE_KEY_MESSAGE_DATA));

                    if (networkSpecifier != null) {
                        WakeupMessage timeout = mDataPathConfirmTimeoutMessages.remove(
                                networkSpecifier);
                        if (timeout != null) {
                            timeout.cancel();
                        }
                    }

                    break;
                }
                case NOTIFICATION_TYPE_ON_DATA_PATH_END:
                    mDataPathMgr.onDataPathEnd(msg.arg2);
                    break;
                default:
                    Log.wtf(TAG, "processNotification: this isn't a NOTIFICATION -- msg=" + msg);
                    return;
            }
        }

        /**
         * Execute the command specified by the input Message. Returns a true if
         * need to wait for a RESPONSE, otherwise a false. We may not have to
         * wait for a RESPONSE if there was an error in the state (so no command
         * is sent to HAL) OR if we choose not to wait for response - e.g. for
         * disconnected/terminate commands failure is not possible.
         */
        private boolean processCommand(Message msg) {
            if (VDBG) {
                Log.v(TAG, "processCommand: msg=" + msg);
            }

            if (mCurrentCommand != null) {
                Log.wtf(TAG,
                        "processCommand: receiving a command (msg=" + msg
                                + ") but current (previous) command isn't null (prev_msg="
                                + mCurrentCommand + ")");
                mCurrentCommand = null;
            }

            mCurrentTransactionId = mNextTransactionId++;

            boolean waitForResponse = true;

            switch (msg.arg1) {
                case COMMAND_TYPE_CONNECT: {
                    int clientId = msg.arg2;
                    IWifiNanEventCallback callback = (IWifiNanEventCallback) msg.obj;
                    ConfigRequest configRequest = (ConfigRequest) msg.getData()
                            .getParcelable(MESSAGE_BUNDLE_KEY_CONFIG);
                    int uid = msg.getData().getInt(MESSAGE_BUNDLE_KEY_UID);
                    int pid = msg.getData().getInt(MESSAGE_BUNDLE_KEY_PID);
                    String callingPackage = msg.getData().getString(
                            MESSAGE_BUNDLE_KEY_CALLING_PACKAGE);
                    boolean notifyIdentityChange = msg.getData().getBoolean(
                            MESSAGE_BUNDLE_KEY_NOTIFY_IDENTITY_CHANGE);

                    waitForResponse = connectLocal(mCurrentTransactionId, clientId, uid, pid,
                            callingPackage, callback, configRequest, notifyIdentityChange);
                    break;
                }
                case COMMAND_TYPE_DISCONNECT: {
                    int clientId = msg.arg2;

                    waitForResponse = disconnectLocal(mCurrentTransactionId, clientId);
                    break;
                }
                case COMMAND_TYPE_TERMINATE_SESSION: {
                    int clientId = msg.arg2;
                    int sessionId = (Integer) msg.obj;

                    terminateSessionLocal(clientId, sessionId);
                    waitForResponse = false;
                    break;
                }
                case COMMAND_TYPE_PUBLISH: {
                    int clientId = msg.arg2;
                    IWifiNanDiscoverySessionCallback callback =
                            (IWifiNanDiscoverySessionCallback) msg.obj;
                    PublishConfig publishConfig = (PublishConfig) msg.getData()
                            .getParcelable(MESSAGE_BUNDLE_KEY_CONFIG);

                    waitForResponse = publishLocal(mCurrentTransactionId, clientId, publishConfig,
                            callback);
                    break;
                }
                case COMMAND_TYPE_UPDATE_PUBLISH: {
                    int clientId = msg.arg2;
                    int sessionId = msg.getData().getInt(MESSAGE_BUNDLE_KEY_SESSION_ID);
                    PublishConfig publishConfig = (PublishConfig) msg.obj;

                    waitForResponse = updatePublishLocal(mCurrentTransactionId, clientId, sessionId,
                            publishConfig);
                    break;
                }
                case COMMAND_TYPE_SUBSCRIBE: {
                    int clientId = msg.arg2;
                    IWifiNanDiscoverySessionCallback callback =
                            (IWifiNanDiscoverySessionCallback) msg.obj;
                    SubscribeConfig subscribeConfig = (SubscribeConfig) msg.getData()
                            .getParcelable(MESSAGE_BUNDLE_KEY_CONFIG);

                    waitForResponse = subscribeLocal(mCurrentTransactionId, clientId,
                            subscribeConfig, callback);
                    break;
                }
                case COMMAND_TYPE_UPDATE_SUBSCRIBE: {
                    int clientId = msg.arg2;
                    int sessionId = msg.getData().getInt(MESSAGE_BUNDLE_KEY_SESSION_ID);
                    SubscribeConfig subscribeConfig = (SubscribeConfig) msg.obj;

                    waitForResponse = updateSubscribeLocal(mCurrentTransactionId, clientId,
                            sessionId, subscribeConfig);
                    break;
                }
                case COMMAND_TYPE_ENQUEUE_SEND_MESSAGE: {
                    if (VDBG) {
                        Log.v(TAG, "processCommand: ENQUEUE_SEND_MESSAGE - messageId="
                                + msg.getData().getInt(MESSAGE_BUNDLE_KEY_MESSAGE_ID)
                                + ", mSendArrivalSequenceCounter=" + mSendArrivalSequenceCounter);
                    }
                    Message sendMsg = obtainMessage(msg.what);
                    sendMsg.copyFrom(msg);
                    sendMsg.getData().putInt(MESSAGE_BUNDLE_KEY_MESSAGE_ARRIVAL_SEQ,
                            mSendArrivalSequenceCounter);
                    mHostQueuedSendMessages.put(mSendArrivalSequenceCounter, sendMsg);
                    mSendArrivalSequenceCounter++;
                    waitForResponse = false;

                    if (!mSendQueueBlocked) {
                        transmitNextMessage();
                    }

                    break;
                }
                case COMMAND_TYPE_TRANSMIT_NEXT_MESSAGE: {
                    if (mSendQueueBlocked || mHostQueuedSendMessages.size() == 0) {
                        if (VDBG) {
                            Log.v(TAG, "processCommand: SEND_TOP_OF_QUEUE_MESSAGE - blocked or "
                                    + "empty host queue");
                        }
                        waitForResponse = false;
                    } else {
                        if (VDBG) {
                            Log.v(TAG, "processCommand: SEND_TOP_OF_QUEUE_MESSAGE - "
                                    + "sendArrivalSequenceCounter="
                                    + mHostQueuedSendMessages.keyAt(0));
                        }
                        Message sendMessage = mHostQueuedSendMessages.valueAt(0);
                        mHostQueuedSendMessages.removeAt(0);

                        Bundle data = sendMessage.getData();
                        int clientId = sendMessage.arg2;
                        int sessionId = sendMessage.getData().getInt(MESSAGE_BUNDLE_KEY_SESSION_ID);
                        int peerId = data.getInt(MESSAGE_BUNDLE_KEY_MESSAGE_PEER_ID);
                        byte[] message = data.getByteArray(MESSAGE_BUNDLE_KEY_MESSAGE);
                        int messageId = data.getInt(MESSAGE_BUNDLE_KEY_MESSAGE_ID);

                        msg.getData().putParcelable(MESSAGE_BUNDLE_KEY_SENT_MESSAGE, sendMessage);

                        waitForResponse = sendFollowonMessageLocal(mCurrentTransactionId, clientId,
                                sessionId, peerId, message, messageId);
                    }
                    break;
                }
                case COMMAND_TYPE_ENABLE_USAGE:
                    enableUsageLocal();
                    waitForResponse = false;
                    break;
                case COMMAND_TYPE_DISABLE_USAGE:
                    disableUsageLocal();
                    waitForResponse = false;
                    break;
                case COMMAND_TYPE_START_RANGING: {
                    Bundle data = msg.getData();

                    int clientId = msg.arg2;
                    RttManager.RttParams[] params = (RttManager.RttParams[]) msg.obj;
                    int sessionId = data.getInt(MESSAGE_BUNDLE_KEY_SESSION_ID);
                    int rangingId = data.getInt(MESSAGE_BUNDLE_KEY_RANGING_ID);

                    startRangingLocal(clientId, sessionId, params, rangingId);
                    waitForResponse = false;
                    break;
                }
                case COMMAND_TYPE_GET_CAPABILITIES:
                    if (mCapabilities == null) {
                        waitForResponse = WifiNanNative.getInstance().getCapabilities(
                                mCurrentTransactionId);
                    } else {
                        if (VDBG) {
                            Log.v(TAG, "COMMAND_TYPE_GET_CAPABILITIES: already have capabilities - "
                                    + "skipping");
                        }
                        waitForResponse = false;
                    }
                    break;
                case COMMAND_TYPE_CREATE_ALL_DATA_PATH_INTERFACES:
                    mDataPathMgr.createAllInterfaces();
                    waitForResponse = false;
                    break;
                case COMMAND_TYPE_DELETE_ALL_DATA_PATH_INTERFACES:
                    mDataPathMgr.deleteAllInterfaces();
                    waitForResponse = false;
                    break;
                case COMMAND_TYPE_CREATE_DATA_PATH_INTERFACE:
                    waitForResponse = WifiNanNative.getInstance().createNanNetworkInterface(
                            mCurrentTransactionId, (String) msg.obj);
                    break;
                case COMMAND_TYPE_DELETE_DATA_PATH_INTERFACE:
                    waitForResponse = WifiNanNative.getInstance().deleteNanNetworkInterface(
                            mCurrentTransactionId, (String) msg.obj);
                    break;
                case COMMAND_TYPE_INITIATE_DATA_PATH_SETUP: {
                    Bundle data = msg.getData();

                    String networkSpecifier = (String) msg.obj;

                    int peerId = data.getInt(MESSAGE_BUNDLE_KEY_PEER_ID);
                    int channelRequestType = data.getInt(MESSAGE_BUNDLE_KEY_CHANNEL_REQ_TYPE);
                    int channel = data.getInt(MESSAGE_BUNDLE_KEY_CHANNEL);
                    byte[] peer = data.getByteArray(MESSAGE_BUNDLE_KEY_MAC_ADDRESS);
                    String interfaceName = data.getString(MESSAGE_BUNDLE_KEY_INTERFACE_NAME);
                    byte[] token = data.getByteArray(MESSAGE_BUNDLE_KEY_MESSAGE);

                    waitForResponse = initiateDataPathSetupLocal(mCurrentTransactionId, peerId,
                            channelRequestType, channel, peer, interfaceName, token);

                    if (waitForResponse) {
                        WakeupMessage timeout = new WakeupMessage(mContext, getHandler(),
                                HAL_DATA_PATH_CONFIRM_TIMEOUT_TAG, MESSAGE_TYPE_DATA_PATH_TIMEOUT,
                                0, 0, networkSpecifier);
                        mDataPathConfirmTimeoutMessages.put(networkSpecifier, timeout);
                        timeout.schedule(
                                SystemClock.elapsedRealtime() + NAN_WAIT_FOR_DP_CONFIRM_TIMEOUT);
                    }
                    break;
                }
                case COMMAND_TYPE_RESPOND_TO_DATA_PATH_SETUP_REQUEST: {
                    Bundle data = msg.getData();

                    int ndpId = msg.arg2;
                    boolean accept = (boolean) msg.obj;
                    String interfaceName = data.getString(MESSAGE_BUNDLE_KEY_INTERFACE_NAME);
                    String token = data.getString(MESSAGE_BUNDLE_KEY_MESSAGE);

                    waitForResponse = respondToDataPathRequestLocal(mCurrentTransactionId, accept,
                            ndpId, interfaceName, token);

                    break;
                }
                case COMMAND_TYPE_END_DATA_PATH:
                    waitForResponse = endDataPathLocal(mCurrentTransactionId, msg.arg2);
                    break;
                default:
                    waitForResponse = false;
                    Log.wtf(TAG, "processCommand: this isn't a COMMAND -- msg=" + msg);
                    /* fall-through */
            }

            if (!waitForResponse) {
                mCurrentTransactionId = TRANSACTION_ID_IGNORE;
            } else {
                mCurrentCommand = obtainMessage(msg.what);
                mCurrentCommand.copyFrom(msg);
            }

            return waitForResponse;
        }

        private void processResponse(Message msg) {
            if (VDBG) {
                Log.v(TAG, "processResponse: msg=" + msg);
            }

            if (mCurrentCommand == null) {
                Log.wtf(TAG, "processResponse: no existing command stored!? msg=" + msg);
                mCurrentTransactionId = TRANSACTION_ID_IGNORE;
                return;
            }

            switch (msg.arg1) {
                case RESPONSE_TYPE_ON_CONFIG_SUCCESS:
                    onConfigCompletedLocal(mCurrentCommand);
                    break;
                case RESPONSE_TYPE_ON_CONFIG_FAIL: {
                    int reason = (Integer) msg.obj;

                    onConfigFailedLocal(mCurrentCommand, reason);
                    break;
                }
                case RESPONSE_TYPE_ON_SESSION_CONFIG_SUCCESS: {
                    int pubSubId = (Integer) msg.obj;
                    boolean isPublish = msg.getData().getBoolean(MESSAGE_BUNDLE_KEY_SESSION_TYPE);

                    onSessionConfigSuccessLocal(mCurrentCommand, pubSubId, isPublish);
                    break;
                }
                case RESPONSE_TYPE_ON_SESSION_CONFIG_FAIL: {
                    int reason = (Integer) msg.obj;
                    boolean isPublish = msg.getData().getBoolean(MESSAGE_BUNDLE_KEY_SESSION_TYPE);

                    onSessionConfigFailLocal(mCurrentCommand, isPublish, reason);
                    break;
                }
                case RESPONSE_TYPE_ON_MESSAGE_SEND_QUEUED_SUCCESS: {
                    Message sentMessage = mCurrentCommand.getData().getParcelable(
                            MESSAGE_BUNDLE_KEY_SENT_MESSAGE);
                    sentMessage.getData().putLong(MESSAGE_BUNDLE_KEY_SEND_MESSAGE_ENQUEUE_TIME,
                            SystemClock.elapsedRealtime());
                    mFwQueuedSendMessages.put(mCurrentTransactionId, sentMessage);
                    updateSendMessageTimeout();
                    if (!mSendQueueBlocked) {
                        transmitNextMessage();
                    }

                    if (VDBG) {
                        Log.v(TAG, "processResponse: ON_MESSAGE_SEND_QUEUED_SUCCESS - arrivalSeq="
                                + sentMessage.getData().getInt(
                                MESSAGE_BUNDLE_KEY_MESSAGE_ARRIVAL_SEQ));
                    }
                    break;
                }
                case RESPONSE_TYPE_ON_MESSAGE_SEND_QUEUED_FAIL: {
                    if (VDBG) {
                        Log.v(TAG, "processResponse: ON_MESSAGE_SEND_QUEUED_FAIL - blocking!");
                    }
                    // TODO: b/29459286 - once there's a unique code for "queue is full" use it!
                    int reason = (Integer) msg.obj;

                    Message sentMessage = mCurrentCommand.getData().getParcelable(
                            MESSAGE_BUNDLE_KEY_SENT_MESSAGE);
                    int arrivalSeq = sentMessage.getData().getInt(
                            MESSAGE_BUNDLE_KEY_MESSAGE_ARRIVAL_SEQ);
                    mHostQueuedSendMessages.put(arrivalSeq, sentMessage);
                    mSendQueueBlocked = true;

                    if (VDBG) {
                        Log.v(TAG,
                                "processResponse: ON_MESSAGE_SEND_QUEUED_FAIL - arrivalSeq="
                                        + arrivalSeq + " -- blocking");
                    }
                    break;
                }
                case RESPONSE_TYPE_ON_CAPABILITIES_UPDATED: {
                    onCapabilitiesUpdatedResponseLocal((WifiNanNative.Capabilities) msg.obj);
                    break;
                }
                case RESPONSE_TYPE_ON_CREATE_INTERFACE:
                    onCreateDataPathInterfaceResponseLocal(mCurrentCommand,
                            msg.getData().getBoolean(MESSAGE_BUNDLE_KEY_SUCCESS_FLAG),
                            msg.getData().getInt(MESSAGE_BUNDLE_KEY_STATUS_CODE));
                    break;
                case RESPONSE_TYPE_ON_DELETE_INTERFACE:
                    onDeleteDataPathInterfaceResponseLocal(mCurrentCommand,
                            msg.getData().getBoolean(MESSAGE_BUNDLE_KEY_SUCCESS_FLAG),
                            msg.getData().getInt(MESSAGE_BUNDLE_KEY_STATUS_CODE));
                    break;
                case RESPONSE_TYPE_ON_INITIATE_DATA_PATH_SUCCESS:
                    onInitiateDataPathResponseSuccessLocal(mCurrentCommand, (int) msg.obj);
                    break;
                case RESPONSE_TYPE_ON_INITIATE_DATA_PATH_FAIL:
                    onInitiateDataPathResponseFailLocal(mCurrentCommand, (int) msg.obj);
                    break;
                case RESPONSE_TYPE_ON_RESPOND_TO_DATA_PATH_SETUP_REQUEST:
                    onRespondToDataPathSetupRequestResponseLocal(mCurrentCommand,
                            msg.getData().getBoolean(MESSAGE_BUNDLE_KEY_SUCCESS_FLAG),
                            msg.getData().getInt(MESSAGE_BUNDLE_KEY_STATUS_CODE));
                    break;
                case RESPONSE_TYPE_ON_END_DATA_PATH:
                    onEndPathEndResponseLocal(mCurrentCommand,
                            msg.getData().getBoolean(MESSAGE_BUNDLE_KEY_SUCCESS_FLAG),
                            msg.getData().getInt(MESSAGE_BUNDLE_KEY_STATUS_CODE));
                    break;
                default:
                    Log.wtf(TAG, "processResponse: this isn't a RESPONSE -- msg=" + msg);
                    mCurrentCommand = null;
                    mCurrentTransactionId = TRANSACTION_ID_IGNORE;
                    return;
            }

            mCurrentCommand = null;
            mCurrentTransactionId = TRANSACTION_ID_IGNORE;
        }

        private void processTimeout(Message msg) {
            if (VDBG) {
                Log.v(TAG, "processTimeout: msg=" + msg);
            }

            if (mCurrentCommand == null) {
                Log.wtf(TAG, "processTimeout: no existing command stored!? msg=" + msg);
                mCurrentTransactionId = TRANSACTION_ID_IGNORE;
                return;
            }

            /*
             * Only have to handle those COMMANDs which wait for a response.
             */
            switch (msg.arg1) {
                case COMMAND_TYPE_CONNECT: {
                    onConfigFailedLocal(mCurrentCommand, WifiNanNative.NAN_STATUS_ERROR);
                    break;
                }
                case COMMAND_TYPE_DISCONNECT: {
                    /*
                     * Will only get here on DISCONNECT if was downgrading. The
                     * callback will do a NOP - but should still call it.
                     */
                    onConfigFailedLocal(mCurrentCommand, WifiNanNative.NAN_STATUS_ERROR);
                    break;
                }
                case COMMAND_TYPE_TERMINATE_SESSION: {
                    Log.wtf(TAG, "processTimeout: TERMINATE_SESSION - shouldn't be waiting!");
                    break;
                }
                case COMMAND_TYPE_PUBLISH: {
                    onSessionConfigFailLocal(mCurrentCommand, true, WifiNanNative.NAN_STATUS_ERROR);
                    break;
                }
                case COMMAND_TYPE_UPDATE_PUBLISH: {
                    onSessionConfigFailLocal(mCurrentCommand, true, WifiNanNative.NAN_STATUS_ERROR);
                    break;
                }
                case COMMAND_TYPE_SUBSCRIBE: {
                    onSessionConfigFailLocal(mCurrentCommand, false,
                            WifiNanNative.NAN_STATUS_ERROR);
                    break;
                }
                case COMMAND_TYPE_UPDATE_SUBSCRIBE: {
                    onSessionConfigFailLocal(mCurrentCommand, false,
                            WifiNanNative.NAN_STATUS_ERROR);
                    break;
                }
                case COMMAND_TYPE_ENQUEUE_SEND_MESSAGE: {
                    Log.wtf(TAG, "processTimeout: ENQUEUE_SEND_MESSAGE - shouldn't be waiting!");
                    break;
                }
                case COMMAND_TYPE_TRANSMIT_NEXT_MESSAGE: {
                    Message sentMessage = mCurrentCommand.getData().getParcelable(
                            MESSAGE_BUNDLE_KEY_SENT_MESSAGE);
                    onMessageSendFailLocal(sentMessage, WifiNanNative.NAN_STATUS_ERROR);
                    mSendQueueBlocked = false;
                    transmitNextMessage();
                    break;
                }
                case COMMAND_TYPE_ENABLE_USAGE:
                    Log.wtf(TAG, "processTimeout: ENABLE_USAGE - shouldn't be waiting!");
                    break;
                case COMMAND_TYPE_DISABLE_USAGE:
                    Log.wtf(TAG, "processTimeout: DISABLE_USAGE - shouldn't be waiting!");
                    break;
                case COMMAND_TYPE_START_RANGING:
                    Log.wtf(TAG, "processTimeout: START_RANGING - shouldn't be waiting!");
                    break;
                case COMMAND_TYPE_GET_CAPABILITIES:
                    Log.e(TAG,
                            "processTimeout: GET_CAPABILITIES timed-out - strange, will try again"
                                    + " when next enabled!?");
                    break;
                case COMMAND_TYPE_CREATE_ALL_DATA_PATH_INTERFACES:
                    Log.wtf(TAG,
                            "processTimeout: CREATE_ALL_DATA_PATH_INTERFACES - shouldn't be "
                                    + "waiting!");
                    break;
                case COMMAND_TYPE_DELETE_ALL_DATA_PATH_INTERFACES:
                    Log.wtf(TAG,
                            "processTimeout: DELETE_ALL_DATA_PATH_INTERFACES - shouldn't be "
                                    + "waiting!");
                    break;
                case COMMAND_TYPE_CREATE_DATA_PATH_INTERFACE:
                    // TODO: fix status: timeout
                    onCreateDataPathInterfaceResponseLocal(mCurrentCommand, false, 0);
                    break;
                case COMMAND_TYPE_DELETE_DATA_PATH_INTERFACE:
                    // TODO: fix status: timeout
                    onDeleteDataPathInterfaceResponseLocal(mCurrentCommand, false, 0);
                    break;
                case COMMAND_TYPE_INITIATE_DATA_PATH_SETUP:
                    // TODO: fix status: timeout
                    onInitiateDataPathResponseFailLocal(mCurrentCommand, 0);
                    break;
                case COMMAND_TYPE_RESPOND_TO_DATA_PATH_SETUP_REQUEST:
                    // TODO: fix status: timeout
                    onRespondToDataPathSetupRequestResponseLocal(mCurrentCommand, false, 0);
                    break;
                case COMMAND_TYPE_END_DATA_PATH:
                    // TODO: fix status: timeout
                    onEndPathEndResponseLocal(mCurrentCommand, false, 0);
                    break;
                default:
                    Log.wtf(TAG, "processTimeout: this isn't a COMMAND -- msg=" + msg);
                    /* fall-through */
            }

            mCurrentCommand = null;
            mCurrentTransactionId = TRANSACTION_ID_IGNORE;
        }

        private void updateSendMessageTimeout() {
            if (VDBG) {
                Log.v(TAG, "updateSendMessageTimeout: mHostQueuedSendMessages.size()="
                        + mHostQueuedSendMessages.size() + ", mFwQueuedSendMessages.size()="
                        + mFwQueuedSendMessages.size() + ", mSendQueueBlocked="
                        + mSendQueueBlocked);
            }
            Iterator<Message> it = mFwQueuedSendMessages.values().iterator();
            if (it.hasNext()) {
                /*
                 * Schedule timeout based on the first message in the queue (which is the earliest
                 * submitted message). Timeout = queuing time + timeout constant.
                 */
                Message msg = it.next();
                mSendMessageTimeoutMessage.schedule(
                        msg.getData().getLong(MESSAGE_BUNDLE_KEY_SEND_MESSAGE_ENQUEUE_TIME)
                        + NAN_SEND_MESSAGE_TIMEOUT);
            } else {
                mSendMessageTimeoutMessage.cancel();
            }
        }

        private void processSendMessageTimeout() {
            if (VDBG) {
                Log.v(TAG, "processSendMessageTimeout: mHostQueuedSendMessages.size()="
                        + mHostQueuedSendMessages.size() + ", mFwQueuedSendMessages.size()="
                        + mFwQueuedSendMessages.size() + ", mSendQueueBlocked="
                        + mSendQueueBlocked);

            }
            /*
             * Note: using 'first' to always time-out (remove) at least 1 notification (partially)
             * due to test code needs: there's no way to mock elapsedRealtime(). TODO: replace with
             * injected getClock() once moved off of mmwd.
             */
            boolean first = true;
            long currentTime = SystemClock.elapsedRealtime();
            Iterator<Map.Entry<Short, Message>> it = mFwQueuedSendMessages.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Short, Message> entry = it.next();
                short transactionId = entry.getKey();
                Message message = entry.getValue();
                long messageEnqueueTime = message.getData().getLong(
                        MESSAGE_BUNDLE_KEY_SEND_MESSAGE_ENQUEUE_TIME);
                if (first || messageEnqueueTime + NAN_SEND_MESSAGE_TIMEOUT <= currentTime) {
                    if (VDBG) {
                        Log.v(TAG, "processSendMessageTimeout: expiring - transactionId="
                                + transactionId + ", message=" + message
                                + ", due to messageEnqueueTime=" + messageEnqueueTime
                                + ", currentTime=" + currentTime);
                    }
                    onMessageSendFailLocal(message, WifiNanNative.NAN_STATUS_ERROR);
                    it.remove();
                    first = false;
                } else {
                    break;
                }
            }
            updateSendMessageTimeout();
            mSendQueueBlocked = false;
            transmitNextMessage();
        }

        @Override
        protected String getLogRecString(Message msg) {
            StringBuilder sb = new StringBuilder(WifiNanStateManager.messageToString(msg));

            if (msg.what == MESSAGE_TYPE_COMMAND
                    && mCurrentTransactionId != TRANSACTION_ID_IGNORE) {
                sb.append(" (Transaction ID=").append(mCurrentTransactionId).append(")");
            }

            return sb.toString();
        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            pw.println("WifiNanStateMachine:");
            pw.println("  mNextTransactionId: " + mNextTransactionId);
            pw.println("  mNextSessionId: " + mNextSessionId);
            pw.println("  mCurrentCommand: " + mCurrentCommand);
            pw.println("  mCurrentTransaction: " + mCurrentTransactionId);
            pw.println("  mSendQueueBlocked: " + mSendQueueBlocked);
            pw.println("  mSendArrivalSequenceCounter: " + mSendArrivalSequenceCounter);
            pw.println("  mHostQueuedSendMessages: [" + mHostQueuedSendMessages + "]");
            pw.println("  mFwQueuedSendMessages: [" + mFwQueuedSendMessages + "]");
            super.dump(fd, pw, args);
        }
    }

    private void sendNanStateChangedBroadcast(boolean enabled) {
        if (VDBG) {
            Log.v(TAG, "sendNanStateChangedBroadcast: enabled=" + enabled);
        }
        final Intent intent = new Intent(WifiNanManager.ACTION_WIFI_NAN_STATE_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        if (enabled) {
            intent.putExtra(WifiNanManager.EXTRA_WIFI_STATE, WifiNanManager.WIFI_NAN_STATE_ENABLED);
        } else {
            intent.putExtra(WifiNanManager.EXTRA_WIFI_STATE,
                    WifiNanManager.WIFI_NAN_STATE_DISABLED);
        }
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }

    /*
     * COMMANDS
     */

    private boolean connectLocal(short transactionId, int clientId, int uid, int pid,
            String callingPackage, IWifiNanEventCallback callback, ConfigRequest configRequest,
            boolean notifyIdentityChange) {
        if (VDBG) {
            Log.v(TAG, "connectLocal(): transactionId=" + transactionId + ", clientId=" + clientId
                    + ", uid=" + uid + ", pid=" + pid + ", callingPackage=" + callingPackage
                    + ", callback=" + callback + ", configRequest=" + configRequest
                    + ", notifyIdentityChange=" + notifyIdentityChange);
        }

        if (!mUsageEnabled) {
            Log.w(TAG, "connect(): called with mUsageEnabled=false");
            return false;
        }

        if (mClients.get(clientId) != null) {
            Log.e(TAG, "connectLocal: entry already exists for clientId=" + clientId);
        }

        if (mCurrentNanConfiguration != null
                && !mCurrentNanConfiguration.equals(configRequest)) {
            try {
                callback.onConnectFail(WifiNanNative.NAN_STATUS_ERROR);
            } catch (RemoteException e) {
                Log.w(TAG, "connectLocal onConnectFail(): RemoteException (FYI): " + e);
            }
            return false;
        }

        ConfigRequest merged = mergeConfigRequests(configRequest);
        if (mCurrentNanConfiguration != null && mCurrentNanConfiguration.equals(merged)) {
            try {
                callback.onConnectSuccess(clientId);
            } catch (RemoteException e) {
                Log.w(TAG, "connectLocal onConnectSuccess(): RemoteException (FYI): " + e);
            }
            WifiNanClientState client = new WifiNanClientState(mContext, clientId, uid, pid,
                    callingPackage, callback, configRequest, notifyIdentityChange);
            client.onInterfaceAddressChange(mCurrentDiscoveryInterfaceMac);
            mClients.append(clientId, client);
            return false;
        }

        return WifiNanNative.getInstance().enableAndConfigure(transactionId, merged,
                mCurrentNanConfiguration == null);
    }

    private boolean disconnectLocal(short transactionId, int clientId) {
        if (VDBG) {
            Log.v(TAG,
                    "disconnectLocal(): transactionId=" + transactionId + ", clientId=" + clientId);
        }

        WifiNanClientState client = mClients.get(clientId);
        if (client == null) {
            Log.e(TAG, "disconnectLocal: no entry for clientId=" + clientId);
            return false;
        }
        mClients.delete(clientId);
        client.destroy();

        if (mClients.size() == 0) {
            mCurrentNanConfiguration = null;
            WifiNanNative.getInstance().disable((short) 0);
            return false;
        }

        ConfigRequest merged = mergeConfigRequests(null);
        if (merged.equals(mCurrentNanConfiguration)) {
            return false;
        }

        return WifiNanNative.getInstance().enableAndConfigure(transactionId, merged, false);
    }

    private void terminateSessionLocal(int clientId, int sessionId) {
        if (VDBG) {
            Log.v(TAG,
                    "terminateSessionLocal(): clientId=" + clientId + ", sessionId=" + sessionId);
        }

        WifiNanClientState client = mClients.get(clientId);
        if (client == null) {
            Log.e(TAG, "terminateSession: no client exists for clientId=" + clientId);
            return;
        }

        client.terminateSession(sessionId);
    }

    private boolean publishLocal(short transactionId, int clientId, PublishConfig publishConfig,
            IWifiNanDiscoverySessionCallback callback) {
        if (VDBG) {
            Log.v(TAG, "publishLocal(): transactionId=" + transactionId + ", clientId=" + clientId
                    + ", publishConfig=" + publishConfig + ", callback=" + callback);
        }

        WifiNanClientState client = mClients.get(clientId);
        if (client == null) {
            Log.e(TAG, "publishLocal: no client exists for clientId=" + clientId);
            return false;
        }

        return WifiNanNative.getInstance().publish(transactionId, 0, publishConfig);
    }

    private boolean updatePublishLocal(short transactionId, int clientId, int sessionId,
            PublishConfig publishConfig) {
        if (VDBG) {
            Log.v(TAG, "updatePublishLocal(): transactionId=" + transactionId + ", clientId="
                    + clientId + ", sessionId=" + sessionId + ", publishConfig=" + publishConfig);
        }

        WifiNanClientState client = mClients.get(clientId);
        if (client == null) {
            Log.e(TAG, "updatePublishLocal: no client exists for clientId=" + clientId);
            return false;
        }

        WifiNanDiscoverySessionState session = client.getSession(sessionId);
        if (session == null) {
            Log.e(TAG, "updatePublishLocal: no session exists for clientId=" + clientId
                    + ", sessionId=" + sessionId);
            return false;
        }

        return session.updatePublish(transactionId, publishConfig);
    }

    private boolean subscribeLocal(short transactionId, int clientId,
            SubscribeConfig subscribeConfig, IWifiNanDiscoverySessionCallback callback) {
        if (VDBG) {
            Log.v(TAG, "subscribeLocal(): transactionId=" + transactionId + ", clientId=" + clientId
                    + ", subscribeConfig=" + subscribeConfig + ", callback=" + callback);
        }

        WifiNanClientState client = mClients.get(clientId);
        if (client == null) {
            Log.e(TAG, "subscribeLocal: no client exists for clientId=" + clientId);
            return false;
        }

        return WifiNanNative.getInstance().subscribe(transactionId, 0, subscribeConfig);
    }

    private boolean updateSubscribeLocal(short transactionId, int clientId, int sessionId,
            SubscribeConfig subscribeConfig) {
        if (VDBG) {
            Log.v(TAG,
                    "updateSubscribeLocal(): transactionId=" + transactionId + ", clientId="
                            + clientId + ", sessionId=" + sessionId + ", subscribeConfig="
                            + subscribeConfig);
        }

        WifiNanClientState client = mClients.get(clientId);
        if (client == null) {
            Log.e(TAG, "updateSubscribeLocal: no client exists for clientId=" + clientId);
            return false;
        }

        WifiNanDiscoverySessionState session = client.getSession(sessionId);
        if (session == null) {
            Log.e(TAG, "updateSubscribeLocal: no session exists for clientId=" + clientId
                    + ", sessionId=" + sessionId);
            return false;
        }

        return session.updateSubscribe(transactionId, subscribeConfig);
    }

    private boolean sendFollowonMessageLocal(short transactionId, int clientId, int sessionId,
            int peerId, byte[] message, int messageId) {
        if (VDBG) {
            Log.v(TAG,
                    "sendFollowonMessageLocal(): transactionId=" + transactionId + ", clientId="
                            + clientId + ", sessionId=" + sessionId + ", peerId=" + peerId
                            + ", messageId=" + messageId);
        }

        WifiNanClientState client = mClients.get(clientId);
        if (client == null) {
            Log.e(TAG, "sendFollowonMessageLocal: no client exists for clientId=" + clientId);
            return false;
        }

        WifiNanDiscoverySessionState session = client.getSession(sessionId);
        if (session == null) {
            Log.e(TAG, "sendFollowonMessageLocal: no session exists for clientId=" + clientId
                    + ", sessionId=" + sessionId);
            return false;
        }

        return session.sendMessage(transactionId, peerId, message, messageId);
    }

    private void enableUsageLocal() {
        if (VDBG) Log.v(TAG, "enableUsageLocal: mUsageEnabled=" + mUsageEnabled);

        if (mUsageEnabled) {
            return;
        }

        WifiNanNative.getInstance().deInitNan(); // force a re-init of NAN HAL

        mUsageEnabled = true;
        getCapabilities();
        createAllDataPathInterfaces();
        sendNanStateChangedBroadcast(true);
    }

    private void disableUsageLocal() {
        if (VDBG) Log.v(TAG, "disableUsageLocal: mUsageEnabled=" + mUsageEnabled);

        if (!mUsageEnabled) {
            return;
        }

        onNanDownLocal();
        deleteAllDataPathInterfaces();

        mUsageEnabled = false;
        WifiNanNative.getInstance().disable((short) 0);
        WifiNanNative.getInstance().deInitNan();

        sendNanStateChangedBroadcast(false);
    }

    private void startRangingLocal(int clientId, int sessionId, RttManager.RttParams[] params,
                                   int rangingId) {
        if (VDBG) {
            Log.v(TAG, "startRangingLocal: clientId=" + clientId + ", sessionId=" + sessionId
                    + ", parms=" + Arrays.toString(params) + ", rangingId=" + rangingId);
        }

        WifiNanClientState client = mClients.get(clientId);
        if (client == null) {
            Log.e(TAG, "startRangingLocal: no client exists for clientId=" + clientId);
            return;
        }

        WifiNanDiscoverySessionState session = client.getSession(sessionId);
        if (session == null) {
            Log.e(TAG, "startRangingLocal: no session exists for clientId=" + clientId
                    + ", sessionId=" + sessionId);
            client.onRangingFailure(rangingId, RttManager.REASON_INVALID_REQUEST,
                    "Invalid session ID");
            return;
        }

        for (RttManager.RttParams param : params) {
            String peerIdStr = param.bssid;
            try {
                param.bssid = session.getMac(Integer.parseInt(peerIdStr), ":");
                if (param.bssid == null) {
                    Log.d(TAG, "startRangingLocal: no MAC address for peer ID=" + peerIdStr);
                    param.bssid = "";
                }
            } catch (NumberFormatException e) {
                Log.e(TAG, "startRangingLocal: invalid peer ID specification (in bssid field): '"
                        + peerIdStr + "'");
                param.bssid = "";
            }
        }

        mRtt.startRanging(rangingId, client, params);
    }

    private boolean initiateDataPathSetupLocal(short transactionId, int peerId,
            int channelRequestType, int channel, byte[] peer, String interfaceName, byte[] token) {
        if (VDBG) {
            Log.v(TAG,
                    "initiateDataPathSetupLocal(): transactionId=" + transactionId + ", peerId="
                            + peerId + ", channelRequestType=" + channelRequestType + ", channel="
                            + channel + ", peer=" + String.valueOf(HexEncoding.encode(peer))
                            + ", interfaceName=" + interfaceName + ", token=" + token);
        }

        return WifiNanNative.getInstance().initiateDataPath(transactionId, peerId,
                channelRequestType, channel, peer, interfaceName, token);
    }

    private boolean respondToDataPathRequestLocal(short transactionId, boolean accept,
            int ndpId, String interfaceName, String token) {
        if (VDBG) {
            Log.v(TAG,
                    "respondToDataPathRequestLocal(): transactionId=" + transactionId + ", accept="
                            + accept + ", ndpId=" + ndpId + ", interfaceName=" + interfaceName
                            + ", token=" + token);
        }

        byte[] tokenBytes = token.getBytes();

        return WifiNanNative.getInstance().respondToDataPathRequest(transactionId, accept, ndpId,
                interfaceName, tokenBytes);
    }

    private boolean endDataPathLocal(short transactionId, int ndpId) {
        if (VDBG) {
            Log.v(TAG,
                    "endDataPathLocal: transactionId=" + transactionId + ", ndpId=" + ndpId);
        }

        return WifiNanNative.getInstance().endDataPath(transactionId, ndpId);
    }

    /*
     * RESPONSES
     */

    private void onConfigCompletedLocal(Message completedCommand) {
        if (VDBG) {
            Log.v(TAG, "onConfigCompleted: completedCommand=" + completedCommand);
        }

        if (completedCommand.arg1 == COMMAND_TYPE_CONNECT) {
            Bundle data = completedCommand.getData();

            int clientId = completedCommand.arg2;
            IWifiNanEventCallback callback = (IWifiNanEventCallback) completedCommand.obj;
            ConfigRequest configRequest = (ConfigRequest) data
                    .getParcelable(MESSAGE_BUNDLE_KEY_CONFIG);
            int uid = data.getInt(MESSAGE_BUNDLE_KEY_UID);
            int pid = data.getInt(MESSAGE_BUNDLE_KEY_PID);
            boolean notifyIdentityChange = data.getBoolean(
                    MESSAGE_BUNDLE_KEY_NOTIFY_IDENTITY_CHANGE);
            String callingPackage = data.getString(MESSAGE_BUNDLE_KEY_CALLING_PACKAGE);

            WifiNanClientState client = new WifiNanClientState(mContext, clientId, uid, pid,
                    callingPackage, callback, configRequest, notifyIdentityChange);
            mClients.put(clientId, client);
            try {
                callback.onConnectSuccess(clientId);
            } catch (RemoteException e) {
                Log.w(TAG,
                        "onConfigCompletedLocal onConnectSuccess(): RemoteException (FYI): " + e);
            }
            client.onInterfaceAddressChange(mCurrentDiscoveryInterfaceMac);
        } else if (completedCommand.arg1 == COMMAND_TYPE_DISCONNECT) {
            /*
             * NOP (i.e. updated configuration after disconnecting a client)
             */
        } else {
            Log.wtf(TAG, "onConfigCompletedLocal: unexpected completedCommand=" + completedCommand);
            return;
        }

        mCurrentNanConfiguration = mergeConfigRequests(null);
    }

    private void onConfigFailedLocal(Message failedCommand, int reason) {
        if (VDBG) {
            Log.v(TAG,
                    "onConfigFailedLocal: failedCommand=" + failedCommand + ", reason=" + reason);
        }

        if (failedCommand.arg1 == COMMAND_TYPE_CONNECT) {
            IWifiNanEventCallback callback = (IWifiNanEventCallback) failedCommand.obj;

            try {
                callback.onConnectFail(reason);
            } catch (RemoteException e) {
                Log.w(TAG, "onConfigFailedLocal onConnectFail(): RemoteException (FYI): " + e);
            }
        } else if (failedCommand.arg1 == COMMAND_TYPE_DISCONNECT) {
            /*
             * NOP (tried updating configuration after disconnecting a client -
             * shouldn't fail but there's nothing to do - the old configuration
             * is still up-and-running).
             */
        } else {
            Log.wtf(TAG, "onConfigFailedLocal: unexpected failedCommand=" + failedCommand);
            return;
        }

    }

    private void onSessionConfigSuccessLocal(Message completedCommand, int pubSubId,
            boolean isPublish) {
        if (VDBG) {
            Log.v(TAG, "onSessionConfigSuccessLocal: completedCommand=" + completedCommand
                    + ", pubSubId=" + pubSubId + ", isPublish=" + isPublish);
        }

        if (completedCommand.arg1 == COMMAND_TYPE_PUBLISH
                || completedCommand.arg1 == COMMAND_TYPE_SUBSCRIBE) {
            int clientId = completedCommand.arg2;
            IWifiNanDiscoverySessionCallback callback =
                    (IWifiNanDiscoverySessionCallback) completedCommand.obj;

            WifiNanClientState client = mClients.get(clientId);
            if (client == null) {
                Log.e(TAG,
                        "onSessionConfigSuccessLocal: no client exists for clientId=" + clientId);
                return;
            }

            int sessionId = mSm.mNextSessionId++;
            try {
                callback.onSessionStarted(sessionId);
            } catch (RemoteException e) {
                Log.e(TAG, "onSessionConfigSuccessLocal: onSessionStarted() RemoteException=" + e);
                return;
            }

            WifiNanDiscoverySessionState session = new WifiNanDiscoverySessionState(sessionId,
                    pubSubId, callback, isPublish);
            client.addSession(session);
        } else if (completedCommand.arg1 == COMMAND_TYPE_UPDATE_PUBLISH
                || completedCommand.arg1 == COMMAND_TYPE_UPDATE_SUBSCRIBE) {
            int clientId = completedCommand.arg2;
            int sessionId = completedCommand.getData().getInt(MESSAGE_BUNDLE_KEY_SESSION_ID);

            WifiNanClientState client = mClients.get(clientId);
            if (client == null) {
                Log.e(TAG,
                        "onSessionConfigSuccessLocal: no client exists for clientId=" + clientId);
                return;
            }

            WifiNanDiscoverySessionState session = client.getSession(sessionId);
            if (session == null) {
                Log.e(TAG, "onSessionConfigSuccessLocal: no session exists for clientId=" + clientId
                        + ", sessionId=" + sessionId);
                return;
            }

            try {
                session.getCallback().onSessionConfigSuccess();
            } catch (RemoteException e) {
                Log.e(TAG, "onSessionConfigSuccessLocal: onSessionConfigSuccess() RemoteException="
                        + e);
            }
        } else {
            Log.wtf(TAG,
                    "onSessionConfigSuccessLocal: unexpected completedCommand=" + completedCommand);
        }
    }

    private void onSessionConfigFailLocal(Message failedCommand, boolean isPublish, int reason) {
        if (VDBG) {
            Log.v(TAG, "onSessionConfigFailLocal: failedCommand=" + failedCommand + ", isPublish="
                    + isPublish + ", reason=" + reason);
        }

        if (failedCommand.arg1 == COMMAND_TYPE_PUBLISH
                || failedCommand.arg1 == COMMAND_TYPE_SUBSCRIBE) {
            IWifiNanDiscoverySessionCallback callback =
                    (IWifiNanDiscoverySessionCallback) failedCommand.obj;
            try {
                callback.onSessionConfigFail(reason);
            } catch (RemoteException e) {
                Log.w(TAG, "onSessionConfigFailLocal onSessionConfigFail(): RemoteException (FYI): "
                        + e);
            }
        } else if (failedCommand.arg1 == COMMAND_TYPE_UPDATE_PUBLISH
                || failedCommand.arg1 == COMMAND_TYPE_UPDATE_SUBSCRIBE) {
            int clientId = failedCommand.arg2;
            int sessionId = failedCommand.getData().getInt(MESSAGE_BUNDLE_KEY_SESSION_ID);

            WifiNanClientState client = mClients.get(clientId);
            if (client == null) {
                Log.e(TAG, "onSessionConfigFailLocal: no client exists for clientId=" + clientId);
                return;
            }

            WifiNanDiscoverySessionState session = client.getSession(sessionId);
            if (session == null) {
                Log.e(TAG, "onSessionConfigFailLocal: no session exists for clientId=" + clientId
                        + ", sessionId=" + sessionId);
                return;
            }

            try {
                session.getCallback().onSessionConfigFail(reason);
            } catch (RemoteException e) {
                Log.e(TAG, "onSessionConfigFailLocal: onSessionConfigFail() RemoteException=" + e);
            }
        } else {
            Log.wtf(TAG, "onSessionConfigFailLocal: unexpected failedCommand=" + failedCommand);
        }
    }

    private void onMessageSendSuccessLocal(Message completedCommand) {
        if (VDBG) {
            Log.v(TAG, "onMessageSendSuccess: completedCommand=" + completedCommand);
        }

        int clientId = completedCommand.arg2;
        int sessionId = completedCommand.getData().getInt(MESSAGE_BUNDLE_KEY_SESSION_ID);
        int messageId = completedCommand.getData().getInt(MESSAGE_BUNDLE_KEY_MESSAGE_ID);

        WifiNanClientState client = mClients.get(clientId);
        if (client == null) {
            Log.e(TAG, "onMessageSendSuccessLocal: no client exists for clientId=" + clientId);
            return;
        }

        WifiNanDiscoverySessionState session = client.getSession(sessionId);
        if (session == null) {
            Log.e(TAG, "onMessageSendSuccessLocal: no session exists for clientId=" + clientId
                    + ", sessionId=" + sessionId);
            return;
        }

        try {
            session.getCallback().onMessageSendSuccess(messageId);
        } catch (RemoteException e) {
            Log.w(TAG, "onMessageSendSuccessLocal: RemoteException (FYI): " + e);
        }
    }

    private void onMessageSendFailLocal(Message failedCommand, int reason) {
        if (VDBG) {
            Log.v(TAG, "onMessageSendFail: failedCommand=" + failedCommand + ", reason=" + reason);
        }

        int clientId = failedCommand.arg2;
        int sessionId = failedCommand.getData().getInt(MESSAGE_BUNDLE_KEY_SESSION_ID);
        int messageId = failedCommand.getData().getInt(MESSAGE_BUNDLE_KEY_MESSAGE_ID);

        WifiNanClientState client = mClients.get(clientId);
        if (client == null) {
            Log.e(TAG, "onMessageSendFailLocal: no client exists for clientId=" + clientId);
            return;
        }

        WifiNanDiscoverySessionState session = client.getSession(sessionId);
        if (session == null) {
            Log.e(TAG, "onMessageSendFailLocal: no session exists for clientId=" + clientId
                    + ", sessionId=" + sessionId);
            return;
        }

        try {
            session.getCallback().onMessageSendFail(messageId, reason);
        } catch (RemoteException e) {
            Log.e(TAG, "onMessageSendFailLocal: onMessageSendFail RemoteException=" + e);
        }
    }

    private void onCapabilitiesUpdatedResponseLocal(WifiNanNative.Capabilities capabilities) {
        if (VDBG) {
            Log.v(TAG, "onCapabilitiesUpdatedResponseLocal: capabilites=" + capabilities);
        }

        mCapabilities = capabilities;
    }

    private void onCreateDataPathInterfaceResponseLocal(Message command, boolean success,
            int reasonOnFailure) {
        if (VDBG) {
            Log.v(TAG, "onCreateDataPathInterfaceResponseLocal: command=" + command + ", success="
                    + success + ", reasonOnFailure=" + reasonOnFailure);
        }

        if (success) {
            if (DBG) {
                Log.d(TAG, "onCreateDataPathInterfaceResponseLocal: successfully created interface "
                        + command.obj);
            }
            mDataPathMgr.onInterfaceCreated((String) command.obj);
        } else {
            Log.e(TAG,
                    "onCreateDataPathInterfaceResponseLocal: failed when trying to create "
                            + "interface "
                            + command.obj + ". Reason code=" + reasonOnFailure);
        }
    }

    private void onDeleteDataPathInterfaceResponseLocal(Message command, boolean success,
            int reasonOnFailure) {
        if (VDBG) {
            Log.v(TAG, "onDeleteDataPathInterfaceResponseLocal: command=" + command + ", success="
                    + success + ", reasonOnFailure=" + reasonOnFailure);
        }

        if (success) {
            if (DBG) {
                Log.d(TAG, "onDeleteDataPathInterfaceResponseLocal: successfully deleted interface "
                        + command.obj);
            }
            mDataPathMgr.onInterfaceDeleted((String) command.obj);
        } else {
            Log.e(TAG,
                    "onDeleteDataPathInterfaceResponseLocal: failed when trying to delete "
                            + "interface "
                            + command.obj + ". Reason code=" + reasonOnFailure);
        }
    }

    private void onInitiateDataPathResponseSuccessLocal(Message command, int ndpId) {
        if (VDBG) {
            Log.v(TAG, "onInitiateDataPathResponseSuccessLocal: command=" + command + ", ndpId="
                    + ndpId);
        }

        mDataPathMgr.onDataPathInitiateSuccess((String) command.obj, ndpId);
    }

    private void onInitiateDataPathResponseFailLocal(Message command, int reason) {
        if (VDBG) {
            Log.v(TAG, "onInitiateDataPathResponseFailLocal: command=" + command + ", reason="
                    + reason);
        }

        mDataPathMgr.onDataPathInitiateFail((String) command.obj, reason);
    }

    private void onRespondToDataPathSetupRequestResponseLocal(Message command, boolean success,
            int reasonOnFailure) {
        if (VDBG) {
            Log.v(TAG, "onRespondToDataPathSetupRequestResponseLocal: command=" + command
                    + ", success=" + success + ", reasonOnFailure=" + reasonOnFailure);
        }

        // TODO: do something with this
    }

    private void onEndPathEndResponseLocal(Message command, boolean success, int reasonOnFailure) {
        if (VDBG) {
            Log.v(TAG, "onEndPathEndResponseLocal: command=" + command
                    + ", success=" + success + ", reasonOnFailure=" + reasonOnFailure);
        }

        // TODO: do something with this
    }

    /*
     * NOTIFICATIONS
     */

    private void onInterfaceAddressChangeLocal(byte[] mac) {
        if (VDBG) {
            Log.v(TAG, "onInterfaceAddressChange: mac=" + String.valueOf(HexEncoding.encode(mac)));
        }

        mCurrentDiscoveryInterfaceMac = mac;

        for (int i = 0; i < mClients.size(); ++i) {
            WifiNanClientState client = mClients.valueAt(i);
            client.onInterfaceAddressChange(mac);
        }
    }

    private void onClusterChangeLocal(int flag, byte[] clusterId) {
        if (VDBG) {
            Log.v(TAG, "onClusterChange: flag=" + flag + ", clusterId="
                    + String.valueOf(HexEncoding.encode(clusterId)));
        }

        for (int i = 0; i < mClients.size(); ++i) {
            WifiNanClientState client = mClients.valueAt(i);
            client.onClusterChange(flag, clusterId, mCurrentDiscoveryInterfaceMac);
        }
    }

    private void onMatchLocal(int pubSubId, int requestorInstanceId, byte[] peerMac,
            byte[] serviceSpecificInfo, byte[] matchFilter) {
        if (VDBG) {
            Log.v(TAG,
                    "onMatch: pubSubId=" + pubSubId + ", requestorInstanceId=" + requestorInstanceId
                            + ", peerDiscoveryMac=" + String.valueOf(HexEncoding.encode(peerMac))
                            + ", serviceSpecificInfo=" + Arrays.toString(serviceSpecificInfo)
                            + ", matchFilter=" + Arrays.toString(matchFilter));
        }

        Pair<WifiNanClientState, WifiNanDiscoverySessionState> data = getClientSessionForPubSubId(
                pubSubId);
        if (data == null) {
            Log.e(TAG, "onMatch: no session found for pubSubId=" + pubSubId);
            return;
        }

        data.second.onMatch(requestorInstanceId, peerMac, serviceSpecificInfo, matchFilter);
    }

    private void onSessionTerminatedLocal(int pubSubId, boolean isPublish, int reason) {
        if (VDBG) {
            Log.v(TAG, "onSessionTerminatedLocal: pubSubId=" + pubSubId + ", isPublish=" + isPublish
                    + ", reason=" + reason);
        }

        Pair<WifiNanClientState, WifiNanDiscoverySessionState> data = getClientSessionForPubSubId(
                pubSubId);
        if (data == null) {
            Log.e(TAG, "onSessionTerminatedLocal: no session found for pubSubId=" + pubSubId);
            return;
        }

        try {
            data.second.getCallback().onSessionTerminated(reason);
        } catch (RemoteException e) {
            Log.w(TAG,
                    "onSessionTerminatedLocal onSessionTerminated(): RemoteException (FYI): " + e);
        }
        data.first.removeSession(data.second.getSessionId());
    }

    private void onMessageReceivedLocal(int pubSubId, int requestorInstanceId, byte[] peerMac,
            byte[] message) {
        if (VDBG) {
            Log.v(TAG,
                    "onMessageReceivedLocal: pubSubId=" + pubSubId + ", requestorInstanceId="
                            + requestorInstanceId + ", peerDiscoveryMac="
                            + String.valueOf(HexEncoding.encode(peerMac)));
        }

        Pair<WifiNanClientState, WifiNanDiscoverySessionState> data = getClientSessionForPubSubId(
                pubSubId);
        if (data == null) {
            Log.e(TAG, "onMessageReceivedLocal: no session found for pubSubId=" + pubSubId);
            return;
        }

        data.second.onMessageReceived(requestorInstanceId, peerMac, message);
    }

    private void onNanDownLocal() {
        if (VDBG) {
            Log.v(TAG, "onNanDown");
        }

        mClients.clear();
        mCurrentNanConfiguration = null;
        mSm.onNanDownCleanupSendQueueState();
        mDataPathMgr.onNanDownCleanupDataPaths();
        mCurrentDiscoveryInterfaceMac = ALL_ZERO_MAC;
    }

    /*
     * Utilities
     */

    private Pair<WifiNanClientState, WifiNanDiscoverySessionState> getClientSessionForPubSubId(
            int pubSubId) {
        for (int i = 0; i < mClients.size(); ++i) {
            WifiNanClientState client = mClients.valueAt(i);
            WifiNanDiscoverySessionState session = client.getNanSessionStateForPubSubId(pubSubId);
            if (session != null) {
                return new Pair<>(client, session);
            }
        }

        return null;
    }

    private ConfigRequest mergeConfigRequests(ConfigRequest configRequest) {
        if (VDBG) {
            Log.v(TAG, "mergeConfigRequests(): mClients=[" + mClients + "], configRequest="
                    + configRequest);
        }

        if (mClients.size() == 0 && configRequest == null) {
            Log.e(TAG, "mergeConfigRequests: invalid state - called with 0 clients registered!");
            return null;
        }

        // TODO: continue working on merge algorithm:
        // - if any request 5g: enable
        // - maximal master preference
        // - cluster range covering all requests: assume that [0,max] is a
        // non-request
        // - if any request identity change: enable
        boolean support5gBand = false;
        int masterPreference = 0;
        boolean clusterIdValid = false;
        int clusterLow = 0;
        int clusterHigh = ConfigRequest.CLUSTER_ID_MAX;
        if (configRequest != null) {
            support5gBand = configRequest.mSupport5gBand;
            masterPreference = configRequest.mMasterPreference;
            clusterIdValid = true;
            clusterLow = configRequest.mClusterLow;
            clusterHigh = configRequest.mClusterHigh;
        }
        for (int i = 0; i < mClients.size(); ++i) {
            ConfigRequest cr = mClients.valueAt(i).getConfigRequest();

            if (cr.mSupport5gBand) {
                support5gBand = true;
            }

            masterPreference = Math.max(masterPreference, cr.mMasterPreference);

            if (cr.mClusterLow != 0 || cr.mClusterHigh != ConfigRequest.CLUSTER_ID_MAX) {
                if (!clusterIdValid) {
                    clusterLow = cr.mClusterLow;
                    clusterHigh = cr.mClusterHigh;
                } else {
                    clusterLow = Math.min(clusterLow, cr.mClusterLow);
                    clusterHigh = Math.max(clusterHigh, cr.mClusterHigh);
                }
                clusterIdValid = true;
            }
        }
        return new ConfigRequest.Builder().setSupport5gBand(support5gBand)
                .setMasterPreference(masterPreference).setClusterLow(clusterLow)
                .setClusterHigh(clusterHigh).build();
    }

    private static String messageToString(Message msg) {
        StringBuilder sb = new StringBuilder();

        String s = sSmToString.get(msg.what);
        if (s == null) {
            s = "<unknown>";
        }
        sb.append(s).append("/");

        if (msg.what == MESSAGE_TYPE_NOTIFICATION || msg.what == MESSAGE_TYPE_COMMAND
                || msg.what == MESSAGE_TYPE_RESPONSE) {
            s = sSmToString.get(msg.arg1);
            if (s == null) {
                s = "<unknown>";
            }
            sb.append(s);
        }

        if (msg.what == MESSAGE_TYPE_RESPONSE || msg.what == MESSAGE_TYPE_RESPONSE_TIMEOUT) {
            sb.append(" (Transaction ID=").append(msg.arg2).append(")");
        }

        return sb.toString();
    }

    /**
     * Dump the internal state of the class.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("NanStateManager:");
        pw.println("  mClients: [" + mClients + "]");
        pw.println("  mUsageEnabled: " + mUsageEnabled);
        pw.println("  mCapabilities: [" + mCapabilities + "]");
        pw.println("  mCurrentNanConfiguration: " + mCurrentNanConfiguration);
        for (int i = 0; i < mClients.size(); ++i) {
            mClients.valueAt(i).dump(fd, pw, args);
        }
        mSm.dump(fd, pw, args);
        mRtt.dump(fd, pw, args);
        mDataPathMgr.dump(fd, pw, args);
    }
}
