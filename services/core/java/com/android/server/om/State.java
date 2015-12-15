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

import static com.android.server.om.OverlayManagerService.DEBUG;

import android.content.om.OverlayInfo;
import android.util.ArrayMap;
import android.util.SparseArray;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A thread safe state for the OverlayManagerService. The purpose of this class
 * is to encapsulate the state of OverlayManagerService so we don't have to
 * worry about concurrency.
 */
final class State {

    /**
     * mStateLock must be held when accessing any of the members in this class.
     */
    private final Object mLock = new Object();

    /**
     * Mapping target package names to a collection of all the known overlays for that package.
     */
    private final SparseArray<ArrayMap<String, ArrayList<OverlayInfo>>> mOverlays = new SparseArray<>();

    /**
     * Listeners that will get notified of all changes to mOverlays.
     */
    private final CopyOnWriteArrayList<StateListener> mChangeListeners = new CopyOnWriteArrayList<>();

    /**
     * The rules to use when verifying the changes to this state
     */
    private final Rules mRules;


    State(Rules rules) {
        this.mRules = rules;
    }

    private ArrayMap<String, ArrayList<OverlayInfo>> getOverlays(int userId) {
        synchronized (mLock) {
            ArrayMap<String, ArrayList<OverlayInfo>> overlays = mOverlays.get(userId);
            if (overlays == null) {
                overlays = new ArrayMap<>();
                mOverlays.append(userId, overlays);
            }
            return overlays;
        }
    }

    /**
     * Get overlay information for the supplied target package.
     *
     * @param targetPackageName the package name of the target package
     * @param enabledOnly only include enabled overlays in the result
     * @return overlays sorted on priority
     */
    List<OverlayInfo> getOverlays(String targetPackageName, boolean enabledOnly, int userId) {
        synchronized (mLock) {
            ArrayList<OverlayInfo> overlays = getOverlays(userId).get(targetPackageName);
            if (overlays == null) {
                return Collections.emptyList();
            }

            if (enabledOnly) {
                LinkedList<OverlayInfo> enabledOverlays = new LinkedList<OverlayInfo>();
                for (OverlayInfo candidate : overlays) {
                    if (candidate.isEnabled()) {
                        enabledOverlays.add(candidate);
                    }
                }
                return enabledOverlays;
            }
            return new ArrayList<>(overlays);
        }
    }

    /**
     * Insert the overlay package to the set of known overlays. If an overlay
     * with the same package name already exists, the old OverlayInfo is
     * replaced.
     *
     * This will result in an onOverlayChange or onOverlayAdded
     * depending on if the overlay was new or updated.
     *
     * @param overlay the info about the overlay package
     * @throws IllegalArgumentException if the overlay is not allowed to be
     *             added
     */
    void insertOverlay(final OverlayInfo overlay) {
        final int userId = overlay.userId;
        OverlayInfo oldOverlay = null;
        synchronized (mLock) {
            ArrayList<OverlayInfo> overlays = getOverlays(userId).get(overlay.targetPackageName);
            if (overlays == null) {
                overlays = new ArrayList<>();
                getOverlays(userId).put(overlay.targetPackageName, overlays);
            }
            final int index = overlays.indexOf(overlay);
            if (index == -1) {
                overlays.add(mRules.getInsertIndex(overlay, overlays), overlay);
            } else {
                oldOverlay = overlays.set(index, overlay);
            }
        }
        if (oldOverlay == null) {
            notifyOverlayAdded(overlay);
        } else {
            notifyOverlayChange(overlay, oldOverlay, userId);
        }
    }

    /**
     * Move the specified overlay to the location directly after the parent
     * overlay. A null parent package will result in the overlay being moved to
     * the front of the list.
     *
     * @param overlay the OverlayInfo to move
     * @param parentOverlay the new parent overlay or null
     * @return true if the new priority change was valid
     */
    boolean changePriority(OverlayInfo overlay, OverlayInfo parentOverlay) {
        final boolean changed = reorder(overlay, parentOverlay);
        if (changed) {
            final int userId = overlay.userId;
            notifyOverlaysReordered(overlay.targetPackageName,
                    getOverlays(overlay.targetPackageName, false, userId), userId);
        }
        return changed;
    }

    boolean setHighestPriority(OverlayInfo overlay) {
        final int userId = overlay.userId;
        final boolean changed;
        ArrayList<OverlayInfo> overlays;
        synchronized (mLock) {
            overlays = getOverlays(userId).get(overlay.targetPackageName);
            if (overlays == null || overlays.size() == 0) {
                return false;
            }
            OverlayInfo parentPackage = overlays.get(overlays.size() - 1);
            if (parentPackage.equals(overlay)) {
                // We already have the highest priority
                return true;
            }
            changed = reorder(overlay, parentPackage);
            overlays = getOverlays(userId).get(overlay.targetPackageName);
        }
        if (changed) {
            notifyOverlaysReordered(overlay.targetPackageName, overlays, userId);
        }
        return changed;
    }

    boolean setLowestPriority(OverlayInfo overlay) {
        final boolean changed = reorder(overlay, null);
        if (changed) {
            int userId = overlay.userId;
            notifyOverlaysReordered(overlay.targetPackageName,
                    getOverlays(overlay.targetPackageName, false, userId), userId);
        }
        return changed;
    }

    /**
     * Helper to reorder the overlay packages. This will not call
     * notifyOverlaysReordered and is thus safe to use within a synchronized
     * block.
     *
     * @param overlay the OverlayInfo to move
     * @param parentOverlay the new parent overlay or null
     * @return true if the new priority change was valid
     */
    private boolean reorder(OverlayInfo overlay, OverlayInfo parentOverlay) {
        if (overlay == null) {
            return false;
        }
        if (overlay.equals(parentOverlay)) {
            return false;
        }
        boolean changed = false;
        final int userId = overlay.userId;
        synchronized (mLock) {
            ArrayList<OverlayInfo> overlays = getOverlays(userId).get(overlay.targetPackageName);
            if (overlays == null || !overlays.contains(overlay)) {
                return false;
            }
            if (parentOverlay != null && !overlays.contains(parentOverlay)) {
                return false;
            }
            if (parentOverlay == null && overlays.indexOf(overlay) == 0) {
                // Already at the front
                return true;
            }
            // Create a copy of the overlays list so we can verify with mRules
            // before committing any actual change.
            ArrayList<OverlayInfo> overlaysCandidate = new ArrayList<>(overlays);
            overlaysCandidate.remove(overlay);
            int index = 0;
            if (parentOverlay != null) {
                index = overlaysCandidate.indexOf(parentOverlay) + 1;
            }
            overlaysCandidate.add(index, overlay);
            if (index == getOverlays(userId).indexOfKey(overlay)) {
                // The change will not generate a new order
                return true;
            }
            if (mRules.verifyOverlayOrder(overlaysCandidate, userId)) {
                getOverlays(userId).put(overlay.targetPackageName, overlaysCandidate);
                changed = true;
            }
            return changed;
        }
    }

    /**
     * If an OverlayInfo exists with the given package name, it is removed from the OverlayState.
     *
     * @param packageName
     * @param userId
     * @return true if a removal was made, false if no OverlayInfo was found to be removed
     */
    boolean removeOverlay(final String packageName, int userId) {
        OverlayInfo overlay;
        synchronized (mLock) {
            overlay = getOverlayInfo(packageName, userId);
            if (overlay == null) {
                return false;
            }
            ArrayMap<String, ArrayList<OverlayInfo>> map = getOverlays(userId);
            map.get(overlay.targetPackageName).remove(overlay);
            if (map.get(overlay.targetPackageName).isEmpty()) {
                map.remove(overlay.targetPackageName);
            }
        }
        notifyOverlayRemoved(overlay);
        return true;
    }

    /**
     * Remove all overlay information for the user.
     * @param userId
     */
    void removeOverlays(int userId) {
        synchronized (mLock) {
            mOverlays.delete(userId);
        }
    }

    /**
     * Assert that the mLock is not held.
     */
    private void assertNotLocked() {
        if (Thread.holdsLock(mLock)) {
            throw new IllegalStateException("Assert: mLock is locked");
        }
    }

    private void notifyOverlayChange(final OverlayInfo overlay, final OverlayInfo oldOverlay,
            int userId) {
        if (DEBUG) {
            assertNotLocked();
        }
        for (StateListener listener : mChangeListeners) {
            listener.onOverlayChanged(overlay, oldOverlay);
        }
    }

    private void notifyOverlayAdded(final OverlayInfo overlay) {
        if (DEBUG) {
            assertNotLocked();
        }
        for (StateListener listener : mChangeListeners) {
            listener.onOverlayAdded(overlay);
        }
    }

    private void notifyOverlayRemoved(final OverlayInfo overlay) {
        if (DEBUG) {
            assertNotLocked();
        }
        for (StateListener listener : mChangeListeners) {
            listener.onOverlayRemoved(overlay);
        }
    }

    private void notifyOverlaysReordered(final String targetPackage,
                                         final List<OverlayInfo> overlays, int userId) {
        if (DEBUG) {
            assertNotLocked();
        }
        for (StateListener listener : mChangeListeners) {
            listener.onOverlaysReordered(targetPackage, userId);
        }
    }

    /**
     * A listener interface that will get callbacks for all changes to the mOverlay packages.
     */
    interface StateListener {
        void onOverlayAdded(OverlayInfo overlay);
        void onOverlayRemoved(OverlayInfo overlay);
        void onOverlayChanged(OverlayInfo overlay, OverlayInfo oldOverlay);
        void onOverlaysReordered(String targetPackage, int userId);
    }

    /**
     * Register the changeListener to get callbacks for overlay changes.
     *
     * @param changeListener
     */
    void addChangeListener(final StateListener changeListener) {
        mChangeListeners.add(changeListener);
    }

    /**
     * Get the OverlayInfo for overlay package with the given package name.
     *
     * @param packageName
     * @param userId
     * @return the found OverlayInfo or null if no OverlayInfo was found
     */
    OverlayInfo getOverlayInfo(final String packageName, int userId) {
        synchronized (mLock) {
            for (List<OverlayInfo> overlays : getOverlays(userId).values()) {
                for (OverlayInfo overlayInfo : overlays) {
                    if (overlayInfo.packageName.equals(packageName)) {
                        return overlayInfo;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Get all OverlayInfos for one user.
     *
     * @param userId
     * @return all OverlayInfos that exists for the user
     */
    Map<String, List<OverlayInfo>> getAllOverlays(int userId) {
        HashMap<String, List<OverlayInfo>> overlaysCopy = new HashMap<>();
        synchronized (mLock) {
            ArrayMap<String, ArrayList<OverlayInfo>> overlays = getOverlays(userId);
            for (String targetPackage : overlays.keySet()) {
                overlaysCopy.put(targetPackage, new ArrayList<>(overlays.get(targetPackage)));
            }
        }
        return overlaysCopy;
    }

    String[] getAllTargets(int userId) {
        synchronized (mLock) {
            Set<String> targets = getOverlays(userId).keySet();
            return targets.toArray(new String[targets.size()]);
        }
    }

    void serialize(StateSerializer serializer) throws IOException {
        synchronized (mLock) {
            int users = mOverlays.size();
            for (int i = 0; i < users; i++) {
                int userId = mOverlays.keyAt(i);
                ArrayMap<String, ArrayList<OverlayInfo>> overlays = mOverlays.valueAt(i);
                serializer.write(userId, overlays);
            }
        }
    }

    void restore(ArrayMap<String, ArrayList<OverlayInfo>> overlays, int userId) {
        if (overlays == null) {
            return;
        }
        synchronized (mLock) {
            mOverlays.put(userId, overlays);
        }
    }
}
