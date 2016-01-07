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

import static android.content.om.OverlayInfo.STATE_APPROVED_ALWAYS_ENABLED;
import static android.content.om.OverlayInfo.STATE_APPROVED_DISABLED;
import static android.content.om.OverlayInfo.STATE_APPROVED_ENABLED;
import static android.content.om.OverlayInfo.STATE_NOT_APPROVED_COMPONENT_DISABLED;
import static android.content.om.OverlayInfo.STATE_NOT_APPROVED_DANGEROUS_OVERLAY;
import static android.content.om.OverlayInfo.STATE_NOT_APPROVED_MISSING_TARGET;
import static android.content.om.OverlayInfo.STATE_NOT_APPROVED_NO_IDMAP;
import static android.content.pm.PackageManager.SIGNATURE_MATCH;
import static com.android.server.om.OverlayManagerService.TAG;

import android.annotation.NonNull;
import android.content.om.OverlayInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.os.RemoteException;
import android.util.Slog;

import java.util.List;

/**
 * This class contains the rules and logic to prevent the OverlayManagerService
 * from putting overlays in illegal states.
 */
class Rules {

    private final IPackageManager mPm;
    private final IdmapManager mIdmapManager;

    Rules(IPackageManager pm, IdmapManager idmapManager) {
        mPm = pm;
        mIdmapManager = idmapManager;
    }

    /**
     * When a new overlay package is installed, getInitialState should be used to determine
     * the state of the OverlayInfo that represents the overlay information for
     * that package.
     *
     * @param overlayPackage
     * @param userId
     * @return the state that should be used when creating new OverlayInfos for
     *         the PackageInfo overlay.
     */
    int getInitialState(PackageInfo overlayPackage, int userId) {
        return getUpdatedState(null, overlayPackage, userId);
    }

    /**
     * Return an updated state for the given overlay. If the state of the
     * PackageInfo is such that the state in the OverlayInfo is not valid
     * anymore, an updated state is returned. This state should be used to
     * generate a new OverlayInfo. If the current overlay.state is valid for
     * the given PackageInfo, the current overlay.state is returned.
     *
     * @param overlay the OverlayInfo to validate
     * @param overlayPackage a current PackageInfo for the overlay
     * @param userId
     * @return an update state for the OverlayInfo that reflects the state of
     *         the PackageInfo (this might be the same as the old state)
     * @throws IllegalArgumentException if the overlay and the overlayPackage is
     *             not representing the same apk
     */
    int getUpdatedState(OverlayInfo overlay, @NonNull PackageInfo overlayPackage, int userId) {
        if (overlay != null && !overlay.packageName.equals(overlayPackage.packageName)) {
            throw new IllegalArgumentException("Overlay package " + overlay.packageName
                    + " is not matching package " + overlayPackage.packageName);
        }
        if (overlay != null && overlay.userId != userId) {
            throw new IllegalArgumentException("User ID mismatch between overlay package "
                    + overlay.packageName + " and request user ID");
        }

        // The overlay is disabled by the Package Manager
        if (!overlayPackage.applicationInfo.enabled) {
            return STATE_NOT_APPROVED_COMPONENT_DISABLED;
        }

        // The target package is not installed
        if (getPackage(overlayPackage.overlayTarget, userId) == null) {
            return STATE_NOT_APPROVED_MISSING_TARGET;
        }

        // No idmap has been created. Perhaps there were no matching resources
        // between the two packages?
        if (!mIdmapManager.idmapExists(overlayPackage)) {
            return STATE_NOT_APPROVED_NO_IDMAP;
        }

        if (isSystem(overlayPackage)) {
            return STATE_APPROVED_ALWAYS_ENABLED;
        }

        // If the target and overlay have the same author, we approve it.
        if (isSignatureMatching(overlayPackage)) {
            return STATE_APPROVED_DISABLED;
        }

        // If the overlay only modifies resources explicitly granted by the
        // target, we approve it.
        if (!mIdmapManager.isDangerous(overlayPackage)) {
            return STATE_APPROVED_DISABLED;
        }

        // Technically, we could approve and use the overlay, but the target
        // hasn't granted every resource it touches. Let's not approve it.
        return STATE_NOT_APPROVED_DANGEROUS_OVERLAY;
    }

    /**
     * Get the updated state for the OverlayInfo overlay if we enable/disable it
     * according to the supplied enabled state. If the overlay is not allowed to
     * switch to the supplied enable state, the current state is returned.
     *
     * @param overlay
     * @param enable true if the overlay is to be enabled else false
     * @return the new state for the overlay (this might be the same as the old
     *         state)
     */
    int getUpdatedState(OverlayInfo overlay, boolean enable) {
        switch (overlay.state) {
            case STATE_APPROVED_DISABLED:
            case STATE_APPROVED_ENABLED:
                return enable ? STATE_APPROVED_ENABLED : STATE_APPROVED_DISABLED;
            default:
                return overlay.state;
        }
    }

    /**
     * Get the index where the newly added overlay should be inserted into the
     * overlays list. overlays.size() is returned when the overlay should be
     * appended to the end of the list.
     *
     * A list of overlays is partitioned in two slices: the first slice
     * contains pre-installed overlays, the other slice contains user
     * downloaded overlays.
     *
     * @param oi the overlay that will be inserted.
     * @param overlays a list that represents the current order of overlays for the same target.
     * @return the insertion index to use when adding this overlay to the list.
     */
    int getInsertIndex(OverlayInfo oi, List<OverlayInfo> overlays) {
        assertOverlaysAreConsistent(overlays);
        final int userId = oi.userId;
        final PackageInfo overlayPackage = getPackage(oi.packageName, userId);
        if (!isSystem(overlayPackage)) {
            // Non system overlays are appended to the end
            return overlays.size();
        }
        int index = 0;
        while (index < overlays.size()) {
            PackageInfo pi = getPackage(overlays.get(index).packageName, userId);
            if (pi != null && !isSystem(pi)) {
                // The new overlay package is a system package and must be
                // inserted before any non system overlays.
                return index;
            }
            if (pi != null && overlayPackage.requestedOverlayPriority < pi.requestedOverlayPriority) {
                return index;
            }
            index++;
        }
        return index;
    }

    private static boolean isSystem(PackageInfo pkg) {
        return (pkg.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    private PackageInfo getPackage(String packageName, int userId) {
        try {
            return mPm.getPackageInfo(packageName, 0, userId);
        } catch (RemoteException e) {
            // Intentionally left blank
        }
        return null;
    }

    /**
     * Check if the signature of the overlay package matches the signature of the target package.
     *
     * @param overlay
     * @return true if the signature match the target package
     */
    private boolean isSignatureMatching(PackageInfo overlay) {
        try {
            return mPm.checkSignatures(overlay.overlayTarget,
                    overlay.packageName) == SIGNATURE_MATCH;
        } catch (RemoteException e) {
            // Intentionally left blank
        }
        return true;
    }

    /**
     * Verify that the order of overlays is allowed.
     *
     * @param overlays the overlay list for one target package
     * @param userId
     * @return true if the order is allowed
     */
    boolean verifyOverlayOrder(List<OverlayInfo> overlays, int userId) {
        if (overlays.size() < 2) {
            return true;
        }
        assertOverlaysAreConsistent(overlays);
        int previousPrio = Integer.MIN_VALUE;
        boolean previousSystem = true;
        for (OverlayInfo overlay : overlays) {
            PackageInfo pi = getPackage(overlay.packageName, userId);
            final int prio = pi.requestedOverlayPriority;
            final boolean system = isSystem(pi);
            if (system && !previousSystem) {
                // System overlays must be in front of non system overlays.
                return false;
            }
            if (system && prio < previousPrio) {
                return false;
            }
            previousPrio = prio;
            previousSystem = system;
        }
        return true;
    }

    private void assertOverlaysAreConsistent(List<OverlayInfo> overlays) {
        if (overlays.size() < 2) {
            return;
        }
        final String targetPackageName = overlays.get(0).targetPackageName;
        final int userId = overlays.get(0).userId;
        for (OverlayInfo info : overlays) {
            if (!info.targetPackageName.equals(targetPackageName)) {
                throw new IllegalArgumentException("Overlay list is inconsistent: + different target "
                        + "packages: " + info.targetPackageName + " vs " + targetPackageName);
            }
            if (info.userId != userId) {
                throw new IllegalArgumentException("Overlay list is inconsistent: + different user IDs "
                        + info.userId + " vs " + userId);
            }
        }
    }
}
