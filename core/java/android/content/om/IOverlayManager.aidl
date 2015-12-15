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

package android.content.om;

import android.content.om.OverlayInfo;

/**
 *  Api for getting information about overlay packages.
 *  {@hide}
 */
interface IOverlayManager {

    /**
     * Returns information about all installed overlay packages for the
     * specified user. If there are no installed overlay packages for this user,
     * an empty map is returned (i.e. null is never returned). The returned map is a
     * mapping of target package names to lists of overlays. Each list for a
     * given target package is sorted in priority order, with the overlay with
     * the highest priority at the end of the list.
     *
     * @param userId the user to get OverlayInfos for
     * @return a Map<String, List<OverlayInfo>> with target package names mapped
     *         to lists of overlays
     */
    Map getAllOverlays(in int userId);

    /**
     * Returns information about all overlays for the given target package for
     * the specified user. The returned list is ordered according to the
     * overlay priority with the highest priority at the end of the list.
     *
     * @param targetPackageName the packageName of the target package
     * @param userId the user to get OverlayInfos for
     * @return an array of OverlayInfo objects; if no overlays exist for the
     *         requested package, an empty array is returned
     */
    List getOverlayInfosForTarget(in String targetPackageName, in int userId);

    /**
     * Returns information about the overlay with the given package name for the
     * specified user.
     *
     * @param packageName the name of the overlay package
     * @param userId the user to get OverlayInfo for
     * @return the OverlayInfo for the overlay package
     */
    OverlayInfo getOverlayInfo(in String packageName, in int userId);

    /**
     * Enable or disable an overlay package. An enabled overlay is a part of target
     * package's resources, ie. it will be part of search for best match on
     * configuration change. A disabled overlay will no longer affect the resources of
     * the target package. If the target is currently running, a configuration change
     * will allow the resources to be reloaded.
     *
     * @param packageName the name of the overlay package
     * @param enable true to enable the overlay, false to disable it
     * @param userId the user to get OverlayInfo for
     * @return true if the new enable state is according to the given enable
     *         parameter otherwise false
     */
    boolean setEnabled(in String packageName, in boolean enable, in int userId);

    /**
     * Change the priority of the given overlay to be just higher than the
     * overlay with package name parentPackageName. If the resulting overlay
     * order is not allowed, the priority change will not take effect and false
     * is returned.
     *
     * @param overlay the overlay info to change priority for
     * @param parentPackageName the package name of the new parent in the ovelray list
     * @return true if the priority change was successful
     */
    boolean setPriority(in OverlayInfo overlay, in String parentPackageName);

    /**
     * Change the priority of the given overlay to the highest priority relative to
     * the other overlays with the same target.
     *
     * @param overlay the overlay to get the highest priority
     * @return true if the priority change was successful
     */
    boolean setHighestPriority(in OverlayInfo overlay);

    /**
     * Change the priority of the overlay to the lowest priority relative to
     * the other overlays for the same target.
     *
     * @param overlay the overlay to get the lowest priority
     * @return true if the priority change was successful
     */
    boolean setLowestPriority(in OverlayInfo overlay);
}
