// SPDX-License-Identifier: Apache-2.0
// Luma ContentProvider endpoint implementing the public Shizuku Binder handoff protocol.
package rikka.shizuku;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import io.github.modao7067.luma.ShizukuBridge;
import moe.shizuku.api.BinderContainer;

public class ShizukuProvider extends ContentProvider {
    public static final String METHOD_SEND_BINDER = "sendBinder";
    public static final String METHOD_GET_BINDER = "getBinder";
    public static final String EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (extras == null) return null;
        extras.setClassLoader(BinderContainer.class.getClassLoader());
        Bundle reply = new Bundle();
        if (METHOD_SEND_BINDER.equals(method)) {
            BinderContainer container = extras.getParcelable(EXTRA_BINDER);
            if (container != null && container.binder != null) {
                ShizukuBridge.onBinderReceived(container.binder, getContext());
            }
        } else if (METHOD_GET_BINDER.equals(method)) {
            IBinder binder = ShizukuBridge.peekBinder();
            if (binder != null && binder.pingBinder()) {
                reply.putParcelable(EXTRA_BINDER, new BinderContainer(binder));
            }
        }
        return reply;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
