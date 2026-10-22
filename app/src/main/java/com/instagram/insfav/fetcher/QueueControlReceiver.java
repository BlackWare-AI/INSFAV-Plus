package com.instagram.insfav.fetcher;

import android.content.*;

public final class QueueControlReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) { QueueControl.pause(c, "stop".equals(intent.getAction())); }
}
