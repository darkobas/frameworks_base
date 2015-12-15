/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.om;

import static android.app.AppGlobals.getPackageManager;
import static android.content.Intent.ACTION_PACKAGE_ADDED;
import static android.content.Intent.ACTION_PACKAGE_CHANGED;
import static android.content.Intent.ACTION_PACKAGE_REMOVED;
import static android.content.Intent.ACTION_PACKAGE_REPLACED;
import static android.os.UserHandle.USER_OWNER;

import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.om.IOverlayManager;
import android.content.om.OverlayInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.UserInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Environment;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArray;

import com.android.server.IoThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.om.State.StateListener;
import com.android.server.pm.Installer;
import com.android.server.pm.UserManagerService;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/**
 * Service to manage asset overlays.
 *
 * Asset overlays are additional resources that come from apks loaded alongside
 * the system and app apks. This service, the OverlayManagerService or OMS for
 * short, tracks which installed overlays to use and provides methods to change
 * this. All changes propagate as onConfigurationChanged while the target app
 * is still running.
 *
 * The service will not by itself change what overlays should be active.
 * Instead, it is only responsible for making sure that overlays *can* be used
 * from a technical and security perspective. The responsibility to toggle
 * overlays on and off lies elsewhere within components that implement different
 * use-cases such as app themes or dynamic customization.
 *
 * OMS receives input from two sources:
 *
 *   1. Intents from PackageManagerService (PMS). Overlays are regular apks,
 *      and whenever a package is installed (or removed, or has a component
 *      enabled or disabled), the PMS broadcasts this as an intent. When the OMS
 *      receives one of these intents, it updates its internal representation of
 *      the available overlays and, if there was a change, i.e. an overlay was
 *      uninstalled, triggers an asset refresh in the affected apps.
 *
 *   2. External requests via the AIDL interface. The exposed interface allows
 *      clients to read information about the currently available overlays,
 *      change whether an overlay should be used or not, and change the relative
 *      order in which overlay packages are loaded. Read-access is granted if
 *      the request targets the same Android user, or if the caller holds the
 *      INTERACT_ACROSS_USERS_FULL permission. Write-access is granted if the
 *      caller holds the CHANGE_CONFIGURATION permission.
 *
 * The AIDL interface uses String package names and OverlayInfo objects.
 * OverlayInfo instances are used to track a specific pair of target and
 * overlay pair and the current state of the overlay. Once instantiated,
 * OverlayInfo instances are immutable.
 *
 * Internally, OverlayInfo objects are maintained by the State class. The OMS
 * and its helper classes are notified of changes to the internal state by the
 * State.StateListener callback interface. Since instances of OverlayInfo are
 * immutable and all collections returned from state are copies of the actual
 * collections there is no need to use locking when accessing the State from the
 * OMS and its helper classes. The file /data/system/overlays.xml is used to
 * persist State.
 *
 * Logic to calculate overlay states and verify the consistency of the internal
 * state is consolidated in the Rules class.
 *
 * Creation and deletion of idmap files is handled by the IdmapManager class.
 *
 * Finally, here is a list of keywords used in the code:
 *
 *   - target [package]: A regular apk that has its resource pool extended by
 *     zero or more overlay packages.
 *
 *   - overlay [package]: An apk that provides additional resources to another
 *     apk.
 *
 *   - OMS: The OverlayManagerService, i.e. this class.
 *
 *   - approved: An overlay is approved if the OMS has verified that it
 *     technically can be used (its target package is installed, and at
 *     least one resource name in both packages match) and that it is secure to
 *     do so.
 *
 *   - not approved: The opposite of approved.
 *
 *   - enabled: An overlay currently in active use and a part of the best match
 *     search of resource lookups. This requires the overlay to be approved.
 *
 *   - disabled: The opposite of enabled: requires the overlay to be approved.
 *
 *   - idmap: A mapping of resource IDs between target and overlay used during
 *     resource lookup. Generated on package installation. Also the name of the
 *     binary that creates the mapping.
 */
public class OverlayManagerService extends SystemService {

    static final String TAG = "OverlayManager";

    static final boolean DEBUG = false;

    private final IPackageManager mPm;

    private final UserManagerService mUserManager;

    // for remembering the overlay state during overlay package upgrades
    private final ArrayMap<String, OverlayInfo> mPendingUpgrades;

    private final IdmapManager mIdmapManager;

    private final State mState;

    private final Rules mRules;

    /**
     * File for backing up the state.
     */
    private final AtomicFile mStateFile;

    public OverlayManagerService(Context context, Installer installer) {
        super(context);
        mPm = getPackageManager();
        mUserManager = UserManagerService.getInstance();
        mPendingUpgrades = new ArrayMap<>();
        mIdmapManager = new IdmapManager(installer);
        mRules = new Rules(mPm, mIdmapManager);
        mState = new State(mRules);
        mStateFile = new AtomicFile(
                new File(Environment.getSystemSecureDirectory(), "overlays.xml"));
    }

    @Override
    public void onStart() {
    }

    @Override
    public void onBootPhase(int phase) {
        if (PHASE_SYSTEM_SERVICES_READY == phase) {
            IntentFilter packageFilter = new IntentFilter();
            packageFilter.addAction(ACTION_PACKAGE_ADDED);
            packageFilter.addAction(ACTION_PACKAGE_CHANGED);
            packageFilter.addAction(ACTION_PACKAGE_REPLACED);
            packageFilter.addAction(ACTION_PACKAGE_REMOVED);
            packageFilter.addDataScheme("package");
            getContext().registerReceiverAsUser(new PackageReceiver(), UserHandle.ALL,
                    packageFilter, null, null);

            restoreState();
            updateOverlayState(USER_OWNER);
            updateAssets(USER_OWNER, mState.getAllTargets(USER_OWNER));

            // Loading overlay packages from the package manager might update the
            // state of overlays. Persist any changes
            persistState();

            // The initial setup of our state is complete, we should be able to
            // handle all future changes in the callback
            mState.addChangeListener(new OverlayChangeCallback());

            publishBinderService(Context.OVERLAY_SERVICE, mService);
            LocalServices.addService(OverlayManagerService.class, this);
        }
    }

    public void onSwitchUser(int newUserId) {
        updateOverlayState(newUserId);
        updateAssets(newUserId, mState.getAllTargets(newUserId));
    }

    /**
     * Update the overlay state based on the current state of the packages for
     * the user. This is intended to be used when there is a possibility that we
     * have stale information because of missed/non existent PACKAGE_* events.
     * (At boot and user start etc.)
     */
    private void updateOverlayState(int userId) {
        if (DEBUG) {
            Slog.d(TAG, "Update state for user " + userId);
        }
        PackageManagerInternal pmLocal = LocalServices.getService(PackageManagerInternal.class);
        List<PackageInfo> overlayPackages = pmLocal.getOverlayPackages(userId);

        Set<String> overlayPackageNames = new HashSet<>(overlayPackages.size());
        for (PackageInfo pi : overlayPackages) {
            overlayPackageNames.add(pi.packageName);
            OverlayInfo overlay = mState.getOverlayInfo(pi.packageName, userId);
            int state;
            if (overlay == null) {
                // this is a new overlay package
                state = mRules.getInitialState(pi, userId);
            } else {
                state = mRules.getUpdatedState(overlay, pi, userId);
            }
            PackageInfo targetPi = getPackageInfo(overlay.targetPackageName, userId);
            if (targetPi != null) {
                mIdmapManager.createIdmap(targetPi, pi);
            }
            mState.insertOverlay(createOverlayInfo(pi, state, userId));
        }

        Map<String, List<OverlayInfo>> overlays = mState.getAllOverlays(userId);
        for (Entry<String, List<OverlayInfo>> entry : overlays.entrySet()) {
            for (OverlayInfo overlay : entry.getValue()) {
                if (!overlayPackageNames.contains(overlay.packageName)) {
                    // The overlay is no longer installed
                    removeOverlayInfo(overlay, false);
                    if (isRemovedForAllUsers(overlay)) {
                        mIdmapManager.removeIdmap(overlay);
                    }
                }
            }
        }
    }

    private OverlayInfo createOverlayInfo(PackageInfo pi, int state, int userId) {
        return new OverlayInfo(pi.packageName, pi.overlayTarget,
                pi.applicationInfo.getBaseCodePath(), state, userId);
    }

    private boolean isOverlayPackage(PackageInfo pi) {
        return pi != null && pi.overlayTarget != null;
    }

    private boolean isRemovedForAllUsers(OverlayInfo overlay) {
        int[] userIds = mUserManager.getUserIds();
        for (int userId : userIds) {
            if (mState.getOverlayInfo(overlay.packageName, userId) != null) {
                return false;
            }
        }
        return true;
    }

    private PackageInfo getPackageInfo(String packageName, int userId) {
        try {
            return mPm.getPackageInfo(packageName, 0, userId);
        } catch (RemoteException e) {
            // Intentionally left empty.
        }
        return null;
    }

    /**
     * Update mState with correct state based on a new or changed PackageInfo.
     *
     * @param overlayPackage the package that changed
     * @param userId
     */
    private void updateOverlayInfo(PackageInfo overlayPackage, int userId) {
        if (overlayPackage == null) {
            return;
        }
        int state;
        OverlayInfo staleOverlay;
        if (mPendingUpgrades.containsKey(overlayPackage.packageName)) {
            staleOverlay = mPendingUpgrades.remove(overlayPackage.packageName);
        } else {
            staleOverlay = mState.getOverlayInfo(overlayPackage.packageName, userId);
        }
        if (staleOverlay == null) {
            state = mRules.getInitialState(overlayPackage, userId);
        } else {
            state = mRules.getUpdatedState(staleOverlay, overlayPackage, userId);
        }
        OverlayInfo overlay = createOverlayInfo(overlayPackage, state, userId);
        mState.insertOverlay(overlay);
    }

    /**
     * Remove relevant overlay information for the overlay package.
     *
     * @param oi the overlay package that we should remove
     * @param replacing true if the overlay is removed in a step to replace the
     *            old package with a new version.
     */
    private void removeOverlayInfo(OverlayInfo oi, boolean replacing) {
        if (mState.removeOverlay(oi.packageName, oi.userId)) {
            if (replacing) {
                mPendingUpgrades.put(oi.packageName, oi);
            }
        }
        if (isRemovedForAllUsers(oi)) {
            mIdmapManager.removeIdmap(oi);
        }
    }

    private void updateOverlayInfos(String targetPackageName, int userId) {
        PackageInfo targetPackage = getPackageInfo(targetPackageName, userId);
        List<OverlayInfo> overlays = mState.getOverlays(targetPackageName, false, userId);
        for (OverlayInfo overlayInfo : overlays) {
            PackageInfo overlay = getPackageInfo(overlayInfo.packageName, userId);
            if (targetPackage != null) {
                mIdmapManager.createIdmap(targetPackage, overlay);
            }
            updateOverlayInfo(overlay, userId);
        }
    }

    private void updateAssets(int userId, String... targets) {
// TODO: Uncomment when when we integrate OMS properly
//        final long ident = Binder.clearCallingIdentity();
//        try {
//            Map<String, String[]> assetPaths = new ArrayMap<>();
//            for (String targetPackageName : targets) {
//                assetPaths.put(targetPackageName, getAssetPaths(targetPackageName, userId));
//            }
//            final IActivityManager am = ActivityManagerNative.getDefault();
//            am.updateAssets(userId, assetPaths);
//        } catch (PackageManager.NameNotFoundException e) {
//            Slog.e(TAG, "Cannot update assets for user " + userId, e);
//        } catch (RemoteException e) {
//            // Intentionally left blank
//        } finally {
//            Binder.restoreCallingIdentity(ident);
//        }
    }

    private void sendBroadcast(String action, String packageName, int userId) {
        final Intent intent = new Intent(action, Uri.fromParts("package", packageName, null));
        if (DEBUG) {
            Slog.d(TAG,
                    "Send broadcast " + action + " package: " + packageName + " userid: " + userId);
        }
        try {
            ActivityManagerNative.getDefault().broadcastIntent(null, intent, null, null, 0, null,
                    null, null, android.app.AppOpsManager.OP_NONE, null, false, false, userId);
        } catch (RemoteException e) {
            // Intentionally left empty.
        }
    }

    /**
     * Persist our current state to overlays.xml.
     */
    private void persistState() {
        IoThread.getHandler().post(new Runnable() {
            @Override
            public void run() {
                synchronized (mStateFile) {
                    try {
                        StateSerializer serializer = new StateSerializer(mStateFile);
                        serializer.startWrite();
                        mState.serialize(serializer);
                        serializer.finishWrite();
                    } catch (IOException e) {
                        Slog.e(TAG, "Failed to persist overlay state", e);
                    }
                }
            }
        });
    }

    /**
     * Restore the state from overlays.xml, this should be called once during boot.
     */
    private void restoreState() {
        if (DEBUG) {
            Slog.d(TAG, "Restore overlay state");
        }
        synchronized (mStateFile) {
            if (!mStateFile.getBaseFile().exists()) {
                return;
            }
            try {
                StateSerializer serializer = new StateSerializer(mStateFile);
                SparseArray<ArrayMap<String, ArrayList<OverlayInfo>>> overlays = serializer.read();
                // We might have data for dying users if the device is restarted
                // before we receive USER_REMOVED. Only restore the users that
                // will exist after system ready.
                for (UserInfo user : mUserManager.getUsers(true)) {
                    int userId = user.getUserHandle().getIdentifier();
                    mState.restore(overlays.get(userId), userId);
                }
            } catch (IOException e) {
                Slog.e(TAG, "Failed to restore overlay state", e);
            }
        }
    }

    public List<String[]> getAllAssetPaths(String packageName, int userId)
        throws PackageManager.NameNotFoundException {

        List<String[]> out = new ArrayList<>(2);
        for (String target : new String[]{"android", packageName}) {
            out.add(getAssetPaths(target, userId));
        }
        return out;
    }

    private String[] getAssetPaths(String packageName, int userId)
        throws PackageManager.NameNotFoundException {

        PackageInfo pi = getPackageInfo(packageName, userId);
        if (pi == null) {
            throw new PackageManager.NameNotFoundException(packageName);
        }
        List<OverlayInfo> overlays = mState.getOverlays(packageName, true, userId);
        String[] paths = new String[overlays.size() + 1];
        paths[0] = pi.applicationInfo.getBaseCodePath();
        int i = 1;
        for (OverlayInfo overlay : overlays) {
            paths[i] = overlay.baseCodePath;
            i++;
        }
        return paths;
    }

    // this class is used within the com.android.server.om package to listen
    // for changes to the state
    private final class OverlayChangeCallback implements StateListener {

        @Override
        public void onOverlayAdded(OverlayInfo overlay) {
            if (overlay.isEnabled()) {
                updateAssets(overlay.userId, overlay.targetPackageName);
            }
            persistState();
            sendBroadcast(Intent.ACTION_OVERLAY_ADDED, overlay.packageName, overlay.userId);
        }

        @Override
        public void onOverlayRemoved(OverlayInfo overlay) {
            if (overlay.isEnabled()) {
                updateAssets(overlay.userId, overlay.targetPackageName);
            }
            persistState();
            sendBroadcast(Intent.ACTION_OVERLAY_REMOVED, overlay.packageName, overlay.userId);
        }

        @Override
        public void onOverlayChanged(OverlayInfo overlay, OverlayInfo oldOverlay) {
            if (overlay.isEnabled() != oldOverlay.isEnabled()) {
                updateAssets(overlay.userId, overlay.targetPackageName);
            }
            persistState();
            sendBroadcast(Intent.ACTION_OVERLAY_CHANGED, overlay.packageName, overlay.userId);
        }

        @Override
        public void onOverlaysReordered(String targetPackage, int userId) {
            updateAssets(userId, targetPackage);
            persistState();
            sendBroadcast(Intent.ACTION_OVERLAYS_REORDERED, targetPackage, userId);
        }
    }

    private final class PackageReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            Uri data = intent.getData();
            String packageName = data != null ? data.getSchemeSpecificPart() : null;
            if (packageName == null) {
                Slog.e(TAG, "Cannot handle package broadcast for null package");
                return;
            }

            int[] userIds;
            switch (intent.getAction()) {
                case ACTION_PACKAGE_ADDED:
                case ACTION_PACKAGE_CHANGED:
                case ACTION_PACKAGE_REPLACED:
                    // PackageManger will send ACTION_* actions for USER_OWNER,
                    // when it really means all users. Update the OverlayInfo
                    // for all users for this package just in case.
                    userIds = mUserManager.getUserIds();
                    for (int userId : userIds) {
                        PackageInfo pi = getPackageInfo(packageName, userId);
                        if (isOverlayPackage(pi)) {
                            PackageInfo targetPackage = getPackageInfo(pi.overlayTarget, userId);
                            if (targetPackage != null) {
                                mIdmapManager.createIdmap(targetPackage, pi);
                            }
                            updateOverlayInfo(pi, userId);
                        } else {
                            updateOverlayInfos(pi.packageName, userId);
                        }
                    }
                    break;
                case ACTION_PACKAGE_REMOVED:
                    boolean allUsers = intent.getBooleanExtra(Intent.EXTRA_REMOVED_FOR_ALL_USERS,
                            false);
                    if (allUsers) {
                        userIds = mUserManager.getUserIds();
                    } else {
                        userIds = new int[] {
                                intent.getIntExtra(Intent.EXTRA_USER_HANDLE, USER_OWNER)
                        };
                    }
                    for (int userId : userIds) {
                        boolean replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
                        OverlayInfo oi = mState.getOverlayInfo(packageName, userId);
                        if (oi == null) {
                            updateOverlayInfos(packageName, userId);
                        } else {
                            removeOverlayInfo(oi, replacing);
                        }
                    }
                    break;
            }
        }
    }

    private final IBinder mService = new IOverlayManager.Stub() {
        @Override
        public Map<String, List<OverlayInfo>> getAllOverlays(int userId) throws RemoteException {
            enforceCrossUserPermission(Binder.getCallingUid(), userId, "getAllOverlays");
            return mState.getAllOverlays(userId);
        }

        @Override
        public List<OverlayInfo> getOverlayInfosForTarget(String targetPackageName, int userId)
                throws RemoteException {
            enforceCrossUserPermission(Binder.getCallingUid(), userId, "getOverlayInfosForTarget");
            return mState.getOverlays(targetPackageName, false, userId);
        }

        @Override
        public OverlayInfo getOverlayInfo(String packageName, int userId) throws RemoteException {
            enforceCrossUserPermission(Binder.getCallingUid(), userId, "getOverlayInfo");
            return mState.getOverlayInfo(packageName, userId);
        }

        @Override
        public boolean setEnabled(String packageName, boolean enable, int userId)
                throws RemoteException {
            enforceChangeConfigurationPermission("setEnabled");
            OverlayInfo oi = mState.getOverlayInfo(packageName, userId);
            if (oi == null) {
                return false;
            }
            int state = mRules.getUpdatedState(oi, enable);
            if (state == oi.state) {
                // return true if the new state is the requested state
                return oi.isEnabled() == enable;
            }

            // update OverlayInfo
            final long ident = Binder.clearCallingIdentity();
            try {
                mState.insertOverlay(new OverlayInfo(oi, state, userId));
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
            return true;
        }

        @Override
        public boolean setPriority(OverlayInfo overlay, String parentPackageName)
                throws RemoteException {
            enforceChangeConfigurationPermission("setPriority");
            if (overlay == null || parentPackageName == null) {
                return false;
            }
            final long ident = Binder.clearCallingIdentity();
            try {
                return mState.changePriority(overlay,
                        mState.getOverlayInfo(parentPackageName, overlay.userId));
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public boolean setHighestPriority(OverlayInfo overlay) throws RemoteException {
            enforceChangeConfigurationPermission("setHighestPriority");
            if (overlay == null) {
                return false;
            }
            final long ident = Binder.clearCallingIdentity();
            try {
                return mState.setHighestPriority(overlay);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public boolean setLowestPriority(OverlayInfo overlay) throws RemoteException {
            enforceChangeConfigurationPermission("setLowestPriority");
            if (overlay == null) {
                return false;
            }
            final long ident = Binder.clearCallingIdentity();
            try {
                return mState.setLowestPriority(overlay);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    };

    /**
     * Enforce that the caller holds the CHANGE_CONFIGURATION permission (or is
     * system or root).
     *
     * @param message used as message if SecurityException is thrown
     * @throws SecurityException if the caller is not system or root
     */
    private void enforceChangeConfigurationPermission(String message) {
        final int callingUid = Binder.getCallingUid();

        if (callingUid != Process.SYSTEM_UID && callingUid != 0) {
            getContext().enforceCallingOrSelfPermission(
                    android.Manifest.permission.CHANGE_CONFIGURATION, message);
        }
    }

    /**
     * Ensure that the caller has permission to interact with the given userId.
     * If the calling user is not the same as the provided user, the caller needs
     * to hold the INTERACT_ACROSS_USERS_FULL permission (or be system uid or
     * root).
     *
     * @param callingUid the calling user
     * @param userId the user to interact with
     * @param message message for any SecurityException
     */
    private void enforceCrossUserPermission(int callingUid, int userId, String message) {
        if (userId < 0) {
            throw new IllegalArgumentException("Invalid userId " + userId);
        }
        if (userId == UserHandle.getUserId(callingUid)) {
            return;
        }

        if (callingUid == Process.SHELL_UID) {
            if (mUserManager.hasUserRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES, userId)) {
                throw new SecurityException(
                        "Shell does not have permission to access user " + userId);
            }
        }

        if (callingUid != Process.SYSTEM_UID && callingUid != 0) {
            getContext().enforceCallingOrSelfPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, message);
        }
    }
}
