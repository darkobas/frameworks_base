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

import android.content.om.OverlayInfo;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;

import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

final class StateSerializer {

    private static final String TAG_OVERLAYS = "overlays";
    private static final String TAG_OVERLAY = "overlay";
    private static final String TAG_TARGET = "target";
    private static final String TAG_USER = "user";

    private static final String ATTR_PATH = "path";
    private static final String ATTR_NAME = "name";
    private static final String ATTR_STATE = "state";
    private static final String ATTR_VERSION = "version";
    private static final String ATTR_ID = "id";

    private static final int CURRENT_VERSION = 1;

    private static final String TAG = "StateSerializer";

    private final AtomicFile mFile;
    private FastXmlSerializer mXml;
    private FileOutputStream mOut;

    public StateSerializer(AtomicFile file) {
        mFile = file;
    }

    void startWrite() throws IOException {
        mXml = new FastXmlSerializer();
        mOut = mFile.startWrite();
        mXml.setOutput(mOut, "utf-8");
        mXml.startDocument(null, true);
        mXml.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
        mXml.startTag(null, TAG_OVERLAYS);
        XmlUtils.writeIntAttribute(mXml, ATTR_VERSION, CURRENT_VERSION);
    }

    void finishWrite() throws IOException {
        mXml.endTag(null, TAG_OVERLAYS);
        mXml.endDocument();
        mFile.finishWrite(mOut);
        mOut = null;
        mXml = null;
    }

    void write(int userId, ArrayMap<String, ArrayList<OverlayInfo>> overlays) throws IOException {
        mXml.startTag(null, TAG_USER);
        XmlUtils.writeIntAttribute(mXml, ATTR_ID, userId);
        for (String targetPackage : overlays.keySet()) {
            write(targetPackage, overlays.get(targetPackage));
        }
        mXml.endTag(null, TAG_USER);
    }

    void write(String targetPackage, List<OverlayInfo> overlays) throws IOException {
        mXml.startTag(null, TAG_TARGET);
        XmlUtils.writeStringAttribute(mXml, ATTR_NAME, targetPackage);
        for (OverlayInfo overlay : overlays) {
            write(overlay);
        }
        mXml.endTag(null, TAG_TARGET);
    }

    void write(OverlayInfo overlay) throws IOException {
        mXml.startTag(null, TAG_OVERLAY);
        XmlUtils.writeStringAttribute(mXml, ATTR_NAME, overlay.packageName);
        XmlUtils.writeStringAttribute(mXml, ATTR_PATH, overlay.baseCodePath);
        XmlUtils.writeIntAttribute(mXml, ATTR_STATE, overlay.state);
        mXml.endTag(null, TAG_OVERLAY);
    }

    SparseArray<ArrayMap<String, ArrayList<OverlayInfo>>> read() throws IOException {
        try (FileInputStream in = mFile.openRead()) {
            return readOverlays(in);
        }
    }

    private SparseArray<ArrayMap<String, ArrayList<OverlayInfo>>> readOverlays(InputStream in)
            throws IOException {
        XmlPullParser parser = Xml.newPullParser();
        try {
            parser.setInput(in, "utf-8");
            XmlUtils.beginDocument(parser, TAG_OVERLAYS);
            int versionStr = XmlUtils.readIntAttribute(parser, ATTR_VERSION);
            switch (versionStr) {
                case CURRENT_VERSION:
                    break;
                default:
                    throw new XmlPullParserException("Unrecognized version " + versionStr);
            }
            SparseArray<ArrayMap<String, ArrayList<OverlayInfo>>> allOverlays = new SparseArray<>();

            int depth = parser.getDepth();
            while (XmlUtils.nextElementWithin(parser, depth)) {
                switch (parser.getName()) {
                    case TAG_USER:
                        int userId = XmlUtils.readIntAttribute(parser, ATTR_ID);
                        ArrayMap<String, ArrayList<OverlayInfo>> userOverlays = new ArrayMap<>();
                        readTargetOverlays(parser, userId, userOverlays);
                        allOverlays.put(userId, userOverlays);
                        break;
                }
            }

            return allOverlays;

        } catch (XmlPullParserException | ClassCastException e) {
            Slog.e(TAG, "Failed to parse Xml");
            throw new IOException(e);
        }
    }

    private void readTargetOverlays(XmlPullParser parser, int userId,
            ArrayMap<String, ArrayList<OverlayInfo>> overlays)
            throws IOException, XmlPullParserException {
        int depth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, depth)) {
            switch (parser.getName()) {
                case TAG_TARGET:
                    String targetPackage = XmlUtils.readStringAttribute(parser, ATTR_NAME);
                    overlays.put(targetPackage, readOverlays(targetPackage, parser, userId));
                    break;
            }
        }
    }

    private ArrayList<OverlayInfo> readOverlays(String targetPackage, XmlPullParser parser,
            int userId) throws IOException, XmlPullParserException {
        int depth = parser.getDepth();
        ArrayList<OverlayInfo> overlays = new ArrayList<>();
        while (XmlUtils.nextElementWithin(parser, depth)) {
            switch (parser.getName()) {
                case TAG_OVERLAY:
                    overlays.add(readOverlay(userId, targetPackage, parser));
            }
        }
        return overlays;
    }

    private OverlayInfo readOverlay(int userId, String targetPackage, XmlPullParser parser)
            throws IOException {
        String packageName = XmlUtils.readStringAttribute(parser, ATTR_NAME);
        String baseCodePath = XmlUtils.readStringAttribute(parser, ATTR_PATH);
        int state = XmlUtils.readIntAttribute(parser, ATTR_STATE);
        return new OverlayInfo(packageName, targetPackage, baseCodePath, state, userId);
    }
}
