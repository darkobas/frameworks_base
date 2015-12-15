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

import android.annotation.NonNull;
import android.content.om.OverlayInfo;
import android.content.pm.PackageInfo;
import android.os.UserHandle;
import android.util.Slog;

import com.android.server.pm.Installer;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

class IdmapManager {

    private static final String TAG = "IdmapManager";
    private static final boolean DEBUG = false;

    private final Installer mInstaller;

    public IdmapManager(Installer installer) {
        mInstaller = installer;
    }

    boolean createIdmap(PackageInfo target, PackageInfo overlay) {
        if (DEBUG) {
            Slog.d(TAG, "Create idmap for " + target.packageName + " and " + overlay.packageName);
        }
        final int sharedGid = UserHandle.getSharedAppGid(target.applicationInfo.uid);
        // TODO: generate idmap for split APKs
        final String targetBaseCodePath = target.applicationInfo.getBaseCodePath();
        final String overlayBaseCodePath = overlay.applicationInfo.getBaseCodePath();
        if (mInstaller.idmap(targetBaseCodePath, overlayBaseCodePath, sharedGid) != 0) {
            Slog.w(TAG, "Failed to generate idmap for " + targetBaseCodePath + " and "
                    + overlayBaseCodePath);
            return false;
        }

        return true;
    }

    void removeIdmap(OverlayInfo overlay) {
        if (DEBUG) {
            Slog.d(TAG, "remove idmap for " + overlay.baseCodePath);
        }
        if (mInstaller.removeIdmap(overlay.baseCodePath) != 0) {
            Slog.w(TAG, "Failed to remove idmap for " + overlay.baseCodePath);
        }
    }

    private String getIdmapPath(String baseCodePath) {
        StringBuilder sb = new StringBuilder("/data/resource-cache/");
        sb.append(baseCodePath.substring(1).replace('/', '@'));
        sb.append("@idmap");
        return sb.toString();
    }

    private boolean isDangerous(String idmapPath) {
        try (DataInputStream dis = new DataInputStream(new FileInputStream(idmapPath))) {
            int magic = dis.readInt();
            int version = dis.readInt();
            int dangerous = dis.readInt();
            return dangerous != 0;
        } catch (IOException e) {
            return true;
        }
    }

    boolean isDangerous(PackageInfo overlay) {
        return isDangerous(getIdmapPath(overlay.applicationInfo.getBaseCodePath()));
    }

    boolean idmapExists(@NonNull PackageInfo pi) {
        return new File(getIdmapPath(pi.applicationInfo.getBaseCodePath())).isFile();
    }
}
