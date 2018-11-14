/**
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.android.server.usage;

import android.annotation.UserIdInt;
import android.app.PendingIntent;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Monitors and informs of any app time limits exceeded. It must be informed when an app
 * enters the foreground and exits. Used by UsageStatsService. Manages multiple users.
 *
 * Test: atest FrameworksServicesTests:AppTimeLimitControllerTests
 * Test: manual: frameworks/base/tests/UsageStatsTest
 */
public class AppTimeLimitController {

    private static final String TAG = "AppTimeLimitController";

    private static final boolean DEBUG = false;

    /** Lock class for this object */
    private static class Lock {}

    /** Lock object for the data in this class. */
    private final Lock mLock = new Lock();

    private final MyHandler mHandler;

    private TimeLimitCallbackListener mListener;

    private static final long MAX_OBSERVER_PER_UID = 1000;

    private static final long ONE_MINUTE = 60_000L;

    /** Collection of data for each user that has reported usage */
    @GuardedBy("mLock")
    private final SparseArray<UserData> mUsers = new SparseArray<>();

    /**
     * Collection of data for each app that is registering observers
     * WARNING: Entries are currently not removed, based on the assumption there are a small
     *          fixed number of apps on device that can register observers.
     */
    @GuardedBy("mLock")
    private final SparseArray<ObserverAppData> mObserverApps = new SparseArray<>();

    private class UserData {
        /** userId of the user */
        private @UserIdInt
        int userId;

        /** Set of the currently active entities */
        private final ArraySet<String> currentlyActive = new ArraySet<>();

        /** Map from entity name for quick lookup */
        private final ArrayMap<String, ArrayList<UsageGroup>> observedMap = new ArrayMap<>();

        private UserData(@UserIdInt int userId) {
            this.userId = userId;
        }

        @GuardedBy("mLock")
        boolean isActive(String[] entities) {
            // TODO: Consider using a bloom filter here if number of actives becomes large
            final int size = entities.length;
            for (int i = 0; i < size; i++) {
                if (currentlyActive.contains(entities[i])) {
                    return true;
                }
            }
            return false;
        }

        @GuardedBy("mLock")
        void addUsageGroup(UsageGroup group) {
            final int size = group.mObserved.length;
            for (int i = 0; i < size; i++) {
                ArrayList<UsageGroup> list = observedMap.get(group.mObserved[i]);
                if (list == null) {
                    list = new ArrayList<>();
                    observedMap.put(group.mObserved[i], list);
                }
                list.add(group);
            }
        }

        @GuardedBy("mLock")
        void removeUsageGroup(UsageGroup group) {
            final int size = group.mObserved.length;
            for (int i = 0; i < size; i++) {
                final String observed = group.mObserved[i];
                final ArrayList<UsageGroup> list = observedMap.get(observed);
                if (list != null) {
                    list.remove(group);
                    if (list.isEmpty()) {
                        // No more observers for this observed entity, remove from map
                        observedMap.remove(observed);
                    }
                }
            }
        }

        @GuardedBy("mLock")
        void dump(PrintWriter pw) {
            pw.print(" userId=");
            pw.println(userId);
            pw.print(" Currently Active:");
            final int nActive = currentlyActive.size();
            for (int i = 0; i < nActive; i++) {
                pw.print(currentlyActive.valueAt(i));
                pw.print(", ");
            }
            pw.println();
            pw.print(" Observed Entities:");
            final int nEntities = observedMap.size();
            for (int i = 0; i < nEntities; i++) {
                pw.print(observedMap.keyAt(i));
                pw.print(", ");
            }
            pw.println();
        }
    }


    private class ObserverAppData {
        /** uid of the observing app */
        private int uid;

        /** Map of observerId to details of the time limit group */
        SparseArray<AppUsageGroup> appUsageGroups = new SparseArray<>();

        /** Map of observerId to details of the time limit group */
        SparseArray<SessionUsageGroup> sessionUsageGroups = new SparseArray<>();

        private ObserverAppData(int uid) {
            this.uid = uid;
        }

        @GuardedBy("mLock")
        void removeAppUsageGroup(int observerId) {
            appUsageGroups.remove(observerId);
        }

        @GuardedBy("mLock")
        void removeSessionUsageGroup(int observerId) {
            sessionUsageGroups.remove(observerId);
        }


        @GuardedBy("mLock")
        void dump(PrintWriter pw) {
            pw.print(" uid=");
            pw.println(uid);
            pw.println("    App Usage Groups:");
            final int nAppUsageGroups = appUsageGroups.size();
            for (int i = 0; i < nAppUsageGroups; i++) {
                appUsageGroups.valueAt(i).dump(pw);
                pw.println();
            }
            pw.println("    Session Usage Groups:");
            final int nSessionUsageGroups = sessionUsageGroups.size();
            for (int i = 0; i < nSessionUsageGroups; i++) {
                sessionUsageGroups.valueAt(i).dump(pw);
                pw.println();
            }
        }
    }

    /**
     * Listener interface for being informed when an app group's time limit is reached.
     */
    public interface TimeLimitCallbackListener {
        /**
         * Time limit for a group, keyed by the observerId, has been reached.
         *
         * @param observerId     The observerId of the group whose limit was reached
         * @param userId         The userId
         * @param timeLimit      The original time limit in milliseconds
         * @param timeElapsed    How much time was actually spent on apps in the group, in
         *                       milliseconds
         * @param callbackIntent The PendingIntent to send when the limit is reached
         */
        public void onLimitReached(int observerId, @UserIdInt int userId, long timeLimit,
                long timeElapsed, PendingIntent callbackIntent);

        /**
         * Session ended for a group, keyed by the observerId, after limit was reached.
         *
         * @param observerId     The observerId of the group whose limit was reached
         * @param userId         The userId
         * @param timeElapsed    How much time was actually spent on apps in the group, in
         *                       milliseconds
         * @param callbackIntent The PendingIntent to send when the limit is reached
         */
        public void onSessionEnd(int observerId, @UserIdInt int userId, long timeElapsed,
                PendingIntent callbackIntent);
    }

    abstract class UsageGroup {
        protected int mObserverId;
        protected String[] mObserved;
        protected long mTimeLimitMs;
        protected long mUsageTimeMs;
        protected int mActives;
        protected long mLastKnownUsageTimeMs;
        protected WeakReference<UserData> mUserRef;
        protected WeakReference<ObserverAppData> mObserverAppRef;
        protected PendingIntent mLimitReachedCallback;

        UsageGroup(UserData user, ObserverAppData observerApp, int observerId, String[] observed,
                long timeLimitMs, PendingIntent limitReachedCallback) {
            mUserRef = new WeakReference<>(user);
            mObserverAppRef = new WeakReference<>(observerApp);
            mObserverId = observerId;
            mObserved = observed;
            mTimeLimitMs = timeLimitMs;
            mLimitReachedCallback = limitReachedCallback;
        }

        @GuardedBy("mLock")
        public long getTimeLimitMs() { return mTimeLimitMs; }

        @GuardedBy("mLock")
        public long getUsageTimeMs() { return mUsageTimeMs; }

        @GuardedBy("mLock")
        public void remove() {
            UserData user = mUserRef.get();
            if (user != null) {
                user.removeUsageGroup(this);
            }
            // Clear the callback, so any racy inflight message will do nothing
            mLimitReachedCallback = null;
        }

        @GuardedBy("mLock")
        void noteUsageStart(long startTimeMs) {
            noteUsageStart(startTimeMs, startTimeMs);
        }

        @GuardedBy("mLock")
        void noteUsageStart(long startTimeMs, long currentTimeMs) {
            if (mActives++ == 0) {
                mLastKnownUsageTimeMs = startTimeMs;
                final long timeRemaining =
                        mTimeLimitMs - mUsageTimeMs + currentTimeMs - startTimeMs;
                if (timeRemaining > 0) {
                    if (DEBUG) {
                        Slog.d(TAG, "Posting timeout for " + mObserverId + " for "
                                + timeRemaining + "ms");
                    }
                    postCheckTimeoutLocked(this, timeRemaining);
                }
            } else {
                if (mActives > mObserved.length) {
                    // Try to get to a sane state and log the issue
                    mActives = mObserved.length;
                    final UserData user = mUserRef.get();
                    if (user == null) return;
                    final Object[] array = user.currentlyActive.toArray();
                    Slog.e(TAG,
                            "Too many noted usage starts! Observed entities: " + Arrays.toString(
                                    mObserved) + "   Active Entities: " + Arrays.toString(array));
                }
            }
        }

        @GuardedBy("mLock")
        void noteUsageStop(long stopTimeMs) {
            if (--mActives == 0) {
                final boolean limitNotCrossed = mUsageTimeMs < mTimeLimitMs;
                mUsageTimeMs += stopTimeMs - mLastKnownUsageTimeMs;
                if (limitNotCrossed && mUsageTimeMs >= mTimeLimitMs) {
                    // Crossed the limit
                    if (DEBUG) Slog.d(TAG, "MTB informing group obs=" + mObserverId);
                    postInformLimitReachedListenerLocked(this);
                }
                cancelCheckTimeoutLocked(this);
            } else {
                if (mActives < 0) {
                    // Try to get to a sane state and log the issue
                    mActives = 0;
                    final UserData user = mUserRef.get();
                    if (user == null) return;
                    final Object[] array = user.currentlyActive.toArray();
                    Slog.e(TAG,
                            "Too many noted usage stops! Observed entities: " + Arrays.toString(
                                    mObserved) + "   Active Entities: " + Arrays.toString(array));
                }
            }
        }

        @GuardedBy("mLock")
        void checkTimeout(long currentTimeMs) {
            final UserData user = mUserRef.get();
            if (user == null) return;

            long timeRemainingMs = mTimeLimitMs - mUsageTimeMs;

            if (DEBUG) Slog.d(TAG, "checkTimeout timeRemaining=" + timeRemainingMs);

            // Already reached the limit, no need to report again
            if (timeRemainingMs <= 0) return;

            if (DEBUG) {
                Slog.d(TAG, "checkTimeout");
            }

            // Double check that at least one entity in this group is currently active
            if (user.isActive(mObserved)) {
                if (DEBUG) {
                    Slog.d(TAG, "checkTimeout group is active");
                }
                final long timeUsedMs = currentTimeMs - mLastKnownUsageTimeMs;
                if (timeRemainingMs <= timeUsedMs) {
                    if (DEBUG) Slog.d(TAG, "checkTimeout : Time limit reached");
                    // Hit the limit, set timeRemaining to zero to avoid checking again
                    mUsageTimeMs += timeUsedMs;
                    mLastKnownUsageTimeMs = currentTimeMs;
                    AppTimeLimitController.this.postInformLimitReachedListenerLocked(this);
                } else {
                    if (DEBUG) Slog.d(TAG, "checkTimeout : Some more time remaining");
                    AppTimeLimitController.this.postCheckTimeoutLocked(this,
                            timeRemainingMs - timeUsedMs);
                }
            }
        }

        @GuardedBy("mLock")
        public void onLimitReached() {
            UserData user = mUserRef.get();
            if (user == null) return;
            if (mListener != null) {
                mListener.onLimitReached(mObserverId, user.userId, mTimeLimitMs, mUsageTimeMs,
                        mLimitReachedCallback);
            }
        }

        @GuardedBy("mLock")
        void dump(PrintWriter pw) {
            pw.print("        Group id=");
            pw.print(mObserverId);
            pw.print(" timeLimit=");
            pw.print(mTimeLimitMs);
            pw.print(" used=");
            pw.print(mUsageTimeMs);
            pw.print(" lastKnownUsage=");
            pw.print(mLastKnownUsageTimeMs);
            pw.print(" mActives=");
            pw.print(mActives);
            pw.print(" observed=");
            pw.print(Arrays.toString(mObserved));
        }
    }

    class AppUsageGroup extends UsageGroup {
        public AppUsageGroup(UserData user, ObserverAppData observerApp, int observerId,
                String[] observed, long timeLimitMs, PendingIntent limitReachedCallback) {
            super(user, observerApp, observerId, observed, timeLimitMs, limitReachedCallback);
        }

        @Override
        @GuardedBy("mLock")
        public void remove() {
            super.remove();
            ObserverAppData observerApp = mObserverAppRef.get();
            if (observerApp != null) {
                observerApp.removeAppUsageGroup(mObserverId);
            }
        }

        @Override
        @GuardedBy("mLock")
        public void onLimitReached() {
            super.onLimitReached();
            // Unregister since the limit has been met and observer was informed.
            remove();
        }
    }

    class SessionUsageGroup extends UsageGroup {
        private long mLastUsageEndTimeMs;
        private long mNewSessionThresholdMs;
        private PendingIntent mSessionEndCallback;

        public SessionUsageGroup(UserData user, ObserverAppData observerApp, int observerId,
                String[] observed, long timeLimitMs, PendingIntent limitReachedCallback,
                long newSessionThresholdMs, PendingIntent sessionEndCallback) {
            super(user, observerApp, observerId, observed, timeLimitMs, limitReachedCallback);
            this.mNewSessionThresholdMs = newSessionThresholdMs;
            this.mSessionEndCallback = sessionEndCallback;
        }

        @Override
        @GuardedBy("mLock")
        public void remove() {
            super.remove();
            ObserverAppData observerApp = mObserverAppRef.get();
            if (observerApp != null) {
                observerApp.removeSessionUsageGroup(mObserverId);
            }
            // Clear the callback, so any racy inflight messages will do nothing
            mSessionEndCallback = null;
        }

        @Override
        @GuardedBy("mLock")
        public void noteUsageStart(long startTimeMs, long currentTimeMs) {
            if (mActives == 0) {
                if (startTimeMs - mLastUsageEndTimeMs > mNewSessionThresholdMs) {
                    // New session has started, clear usage time.
                    mUsageTimeMs = 0;
                }
                AppTimeLimitController.this.cancelInformSessionEndListener(this);
            }
            super.noteUsageStart(startTimeMs, currentTimeMs);
        }

        @Override
        @GuardedBy("mLock")
        public void noteUsageStop(long stopTimeMs) {
            super.noteUsageStop(stopTimeMs);
            if (mActives == 0) {
                mLastUsageEndTimeMs = stopTimeMs;
                if (mUsageTimeMs >= mTimeLimitMs) {
                    // Usage has ended. Schedule the session end callback to be triggered once
                    // the new session threshold has been reached
                    AppTimeLimitController.this.postInformSessionEndListenerLocked(this,
                            mNewSessionThresholdMs);
                }

            }
        }

        @GuardedBy("mLock")
        public void onSessionEnd() {
            UserData user = mUserRef.get();
            if (user == null) return;
            if (mListener != null) {
                mListener.onSessionEnd(mObserverId, user.userId, mUsageTimeMs, mSessionEndCallback);
            }
        }

        @Override
        @GuardedBy("mLock")
        void dump(PrintWriter pw) {
            super.dump(pw);
            pw.print(" lastUsageEndTime=");
            pw.print(mLastUsageEndTimeMs);
            pw.print(" newSessionThreshold=");
            pw.print(mNewSessionThresholdMs);
        }
    }


    private class MyHandler extends Handler {
        static final int MSG_CHECK_TIMEOUT = 1;
        static final int MSG_INFORM_LIMIT_REACHED_LISTENER = 2;
        static final int MSG_INFORM_SESSION_END = 3;

        MyHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_CHECK_TIMEOUT:
                    synchronized (mLock) {
                        ((UsageGroup) msg.obj).checkTimeout(getUptimeMillis());
                    }
                    break;
                case MSG_INFORM_LIMIT_REACHED_LISTENER:
                    synchronized (mLock) {
                        ((UsageGroup) msg.obj).onLimitReached();
                    }
                    break;
                case MSG_INFORM_SESSION_END:
                    synchronized (mLock) {
                        ((SessionUsageGroup) msg.obj).onSessionEnd();
                    }
                    break;
                default:
                    super.handleMessage(msg);
                    break;
            }
        }
    }

    public AppTimeLimitController(TimeLimitCallbackListener listener, Looper looper) {
        mHandler = new MyHandler(looper);
        mListener = listener;
    }

    /** Overrideable by a test */
    @VisibleForTesting
    protected long getUptimeMillis() {
        return SystemClock.uptimeMillis();
    }

    /** Overrideable for testing purposes */
    @VisibleForTesting
    protected long getAppUsageObserverPerUidLimit() {
        return MAX_OBSERVER_PER_UID;
    }

    /** Overrideable for testing purposes */
    @VisibleForTesting
    protected long getUsageSessionObserverPerUidLimit() {
        return MAX_OBSERVER_PER_UID;
    }

    /** Overrideable for testing purposes */
    @VisibleForTesting
    protected long getMinTimeLimit() {
        return ONE_MINUTE;
    }

    @VisibleForTesting
    AppUsageGroup getAppUsageGroup(int observerAppUid, int observerId) {
        synchronized (mLock) {
            return getOrCreateObserverAppDataLocked(observerAppUid).appUsageGroups.get(observerId);
        }
    }

    @VisibleForTesting
    SessionUsageGroup getSessionUsageGroup(int observerAppUid, int observerId) {
        synchronized (mLock) {
            return getOrCreateObserverAppDataLocked(observerAppUid).sessionUsageGroups.get(
                    observerId);
        }
    }

    /** Returns an existing UserData object for the given userId, or creates one */
    @GuardedBy("mLock")
    private UserData getOrCreateUserDataLocked(int userId) {
        UserData userData = mUsers.get(userId);
        if (userData == null) {
            userData = new UserData(userId);
            mUsers.put(userId, userData);
        }
        return userData;
    }

    /** Returns an existing ObserverAppData object for the given uid, or creates one */
    @GuardedBy("mLock")
    private ObserverAppData getOrCreateObserverAppDataLocked(int uid) {
        ObserverAppData appData = mObserverApps.get(uid);
        if (appData == null) {
            appData = new ObserverAppData(uid);
            mObserverApps.put(uid, appData);
        }
        return appData;
    }

    /** Clean up data if user is removed */
    public void onUserRemoved(int userId) {
        synchronized (mLock) {
            // TODO: Remove any inflight delayed messages
            mUsers.remove(userId);
        }
    }

    /**
     * Check if group has any currently active entities.
     */
    @GuardedBy("mLock")
    private void noteActiveLocked(UserData user, UsageGroup group, long currentTimeMs) {
        // TODO: Consider using a bloom filter here if number of actives becomes large
        final int size = group.mObserved.length;
        for (int i = 0; i < size; i++) {
            if (user.currentlyActive.contains(group.mObserved[i])) {
                // Entity is currently active. Start group's usage.
                group.noteUsageStart(currentTimeMs);
            }
        }
    }

    /**
     * Registers an app usage observer with the given details.
     * Existing app usage observer with the same observerId will be removed.
     */
    public void addAppUsageObserver(int requestingUid, int observerId, String[] observed,
            long timeLimit, PendingIntent callbackIntent, @UserIdInt int userId) {
        if (timeLimit < getMinTimeLimit()) {
            throw new IllegalArgumentException("Time limit must be >= " + getMinTimeLimit());
        }
        synchronized (mLock) {
            UserData user = getOrCreateUserDataLocked(userId);
            ObserverAppData observerApp = getOrCreateObserverAppDataLocked(requestingUid);
            AppUsageGroup group = observerApp.appUsageGroups.get(observerId);
            if (group != null) {
                // Remove previous app usage group associated with observerId
                group.remove();
            }

            final int observerIdCount = observerApp.appUsageGroups.size();
            if (observerIdCount >= getAppUsageObserverPerUidLimit()) {
                throw new IllegalStateException(
                        "Too many app usage observers added by uid " + requestingUid);
            }
            group = new AppUsageGroup(user, observerApp, observerId, observed, timeLimit,
                    callbackIntent);
            observerApp.appUsageGroups.append(observerId, group);

            if (DEBUG) {
                Slog.d(TAG, "addObserver " + observed + " for " + timeLimit);
            }

            user.addUsageGroup(group);
            noteActiveLocked(user, group, getUptimeMillis());
        }
    }

    /**
     * Remove a registered observer by observerId and calling uid.
     *
     * @param requestingUid The calling uid
     * @param observerId    The unique observer id for this user
     * @param userId        The user id of the observer
     */
    public void removeAppUsageObserver(int requestingUid, int observerId, @UserIdInt int userId) {
        synchronized (mLock) {
            final ObserverAppData observerApp = getOrCreateObserverAppDataLocked(requestingUid);
            final AppUsageGroup group = observerApp.appUsageGroups.get(observerId);
            if (group != null) {
                // Remove previous app usage group associated with observerId
                group.remove();
            }
        }
    }


    /**
     * Registers a usage session observer with the given details.
     * Existing usage session observer with the same observerId will be removed.
     */
    public void addUsageSessionObserver(int requestingUid, int observerId, String[] observed,
            long timeLimit, long sessionThresholdTime,
            PendingIntent limitReachedCallbackIntent, PendingIntent sessionEndCallbackIntent,
            @UserIdInt int userId) {
        if (timeLimit < getMinTimeLimit()) {
            throw new IllegalArgumentException("Time limit must be >= " + getMinTimeLimit());
        }
        synchronized (mLock) {
            UserData user = getOrCreateUserDataLocked(userId);
            ObserverAppData observerApp = getOrCreateObserverAppDataLocked(requestingUid);
            SessionUsageGroup group = observerApp.sessionUsageGroups.get(observerId);
            if (group != null) {
                // Remove previous session usage group associated with observerId
                group.remove();
            }

            final int observerIdCount = observerApp.sessionUsageGroups.size();
            if (observerIdCount >= getUsageSessionObserverPerUidLimit()) {
                throw new IllegalStateException(
                        "Too many app usage observers added by uid " + requestingUid);
            }
            group = new SessionUsageGroup(user, observerApp, observerId, observed, timeLimit,
                    limitReachedCallbackIntent, sessionThresholdTime, sessionEndCallbackIntent);
            observerApp.sessionUsageGroups.append(observerId, group);

            user.addUsageGroup(group);
            noteActiveLocked(user, group, getUptimeMillis());
        }
    }

    /**
     * Remove a registered observer by observerId and calling uid.
     *
     * @param requestingUid The calling uid
     * @param observerId    The unique observer id for this user
     * @param userId        The user id of the observer
     */
    public void removeUsageSessionObserver(int requestingUid, int observerId,
            @UserIdInt int userId) {
        synchronized (mLock) {
            final ObserverAppData observerApp = getOrCreateObserverAppDataLocked(requestingUid);
            final SessionUsageGroup group = observerApp.sessionUsageGroups.get(observerId);
            if (group != null) {
                // Remove previous app usage group associated with observerId
                group.remove();
            }
        }
    }

    /**
     * Called when an entity becomes active.
     *
     * @param name   The entity that became active
     * @param userId The user
     */
    public void noteUsageStart(String name, int userId) throws IllegalArgumentException {
        synchronized (mLock) {
            UserData user = getOrCreateUserDataLocked(userId);
            if (DEBUG) Slog.d(TAG, "Usage entity " + name + " became active");
            if (user.currentlyActive.contains(name)) {
                throw new IllegalArgumentException(
                        "Unable to start usage for " + name + ", already in use");
            }
            final long currentTime = getUptimeMillis();

            // Add to the list of active entities
            user.currentlyActive.add(name);

            ArrayList<UsageGroup> groups = user.observedMap.get(name);
            if (groups == null) return;

            final int size = groups.size();
            for (int i = 0; i < size; i++) {
                UsageGroup group = groups.get(i);
                group.noteUsageStart(currentTime);
            }
        }
    }

    /**
     * Called when an entity becomes inactive.
     *
     * @param name   The entity that became inactive
     * @param userId The user
     */
    public void noteUsageStop(String name, int userId) throws IllegalArgumentException {
        synchronized (mLock) {
            UserData user = getOrCreateUserDataLocked(userId);
            if (DEBUG) Slog.d(TAG, "Usage entity " + name + " became inactive");
            if (!user.currentlyActive.remove(name)) {
                throw new IllegalArgumentException(
                        "Unable to stop usage for " + name + ", not in use");
            }
            final long currentTime = getUptimeMillis();

            // Check if any of the groups need to watch for this entity
            ArrayList<UsageGroup> groups = user.observedMap.get(name);
            if (groups == null) return;

            final int size = groups.size();
            for (int i = 0; i < size; i++) {
                UsageGroup group = groups.get(i);
                group.noteUsageStop(currentTime);
            }
        }
    }

    @GuardedBy("mLock")
    private void postInformLimitReachedListenerLocked(UsageGroup group) {
        mHandler.sendMessage(mHandler.obtainMessage(MyHandler.MSG_INFORM_LIMIT_REACHED_LISTENER,
                group));
    }

    @GuardedBy("mLock")
    private void postInformSessionEndListenerLocked(SessionUsageGroup group, long timeout) {
        mHandler.sendMessageDelayed(mHandler.obtainMessage(MyHandler.MSG_INFORM_SESSION_END, group),
                timeout);
    }

    @GuardedBy("mLock")
    private void cancelInformSessionEndListener(SessionUsageGroup group) {
        mHandler.removeMessages(MyHandler.MSG_INFORM_SESSION_END, group);
    }

    @GuardedBy("mLock")
    private void postCheckTimeoutLocked(UsageGroup group, long timeout) {
        mHandler.sendMessageDelayed(mHandler.obtainMessage(MyHandler.MSG_CHECK_TIMEOUT, group),
                timeout);
    }

    @GuardedBy("mLock")
    private void cancelCheckTimeoutLocked(UsageGroup group) {
        mHandler.removeMessages(MyHandler.MSG_CHECK_TIMEOUT, group);
    }

    void dump(PrintWriter pw) {
        synchronized (mLock) {
            pw.println("\n  App Time Limits");
            final int nUsers = mUsers.size();
            for (int i = 0; i < nUsers; i++) {
                pw.print("   User ");
                mUsers.valueAt(i).dump(pw);
            }
            pw.println();
            final int nObserverApps = mObserverApps.size();
            for (int i = 0; i < nObserverApps; i++) {
                pw.print("   Observer App ");
                mObserverApps.valueAt(i).dump(pw);
            }
        }
    }
}
