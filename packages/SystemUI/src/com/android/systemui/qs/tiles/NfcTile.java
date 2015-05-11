/*
 * Copyright (C) 2015 The CyanogenMod Project
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

package com.android.systemui.qs.tiles;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.nfc.NfcAdapter;

import com.android.systemui.R;
import com.android.systemui.qs.QSTile;
import com.android.systemui.qs.UsageTracker;
import com.android.systemui.statusbar.policy.KeyguardMonitor;

public class NfcTile extends QSTile<QSTile.BooleanState> {
    private NfcAdapter mNfcAdapter;
    private boolean mListening;
    private final KeyguardMonitor mKeyguard;
    private final UsageTracker mUsageTracker;

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshState();
        }
    };

    public NfcTile(Host host) {
        super(host);
        mNfcAdapter = NfcAdapter.getDefaultAdapter(mContext);
        mKeyguard = host.getKeyguardMonitor();
	mUsageTracker = newUsageTracker(host.getContext());
        mUsageTracker.setListening(true);
    }

    @Override
    protected void handleDestroy() {
        super.handleDestroy();
        mUsageTracker.setListening(false);
    }

    @Override
    protected BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleClick() {
        toggleState();
    }

    @Override
    protected void handleLongClick() {
        if (mState.value) return;  // don't allow usage reset if inversion is active
        final String title = mContext.getString(R.string.quick_settings_reset_confirmation_title,
                mContext.getString(R.string.quick_settings_nfc));
        mUsageTracker.showResetConfirmation(title, new Runnable() {
            @Override
            public void run() {
                refreshState();
            }
        });
    }

    protected void toggleState() {
        int state = getNfcState();
        switch (state) {
            case NfcAdapter.STATE_TURNING_ON:
            case NfcAdapter.STATE_ON:
                mNfcAdapter.disable();
                break;
            case NfcAdapter.STATE_TURNING_OFF:
            case NfcAdapter.STATE_OFF:
                mNfcAdapter.enable();
                break;
        }
    }

    private boolean isEnabled() {
        int state = getNfcState();
        switch (state) {
            case NfcAdapter.STATE_TURNING_ON:
            case NfcAdapter.STATE_ON:
                return true;
            case NfcAdapter.STATE_TURNING_OFF:
            case NfcAdapter.STATE_OFF:
            default:
                return false;
        }
    }

    private int getNfcState() {
        return mNfcAdapter.getAdapterState();
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        if (mNfcAdapter == null) {
            mNfcAdapter = NfcAdapter.getDefaultAdapter(mContext);
        }
        state.visible = (mNfcAdapter != null) && !(mKeyguard.isSecure() && mKeyguard.isShowing()) && mUsageTracker.isRecentlyUsed();
        state.value = mNfcAdapter != null && isEnabled();
        state.icon = ResourceIcon.get(state.value ? R.drawable.ic_qs_nfc_on : R.drawable.ic_qs_nfc_off);
        state.label = mContext.getString(state.value
                ? R.string.quick_settings_nfc : R.string.quick_settings_nfc_off);
    }

    @Override
    public void setListening(boolean listening) {
        if (mListening == listening) return;
        mListening = listening;
        if (listening) {
            if (mNfcAdapter == null) {
                mNfcAdapter = NfcAdapter.getDefaultAdapter(mContext);
                refreshState();
            }
            mContext.registerReceiver(mReceiver,
                    new IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED));
        } else {
            mContext.unregisterReceiver(mReceiver);
        }
    }

    private static UsageTracker newUsageTracker(Context context) {
        return new UsageTracker(context, NfcTile.class, R.integer.days_to_show_nfc_tile);
    }

    public static class NfcTileChangedReceiver extends BroadcastReceiver {
        private UsageTracker mUsageTracker;

        @Override
        public void onReceive(Context context, Intent intent) {
            if (mUsageTracker == null) {
                mUsageTracker = newUsageTracker(context);
            }
            mUsageTracker.trackUsage();
        }
    }

}
