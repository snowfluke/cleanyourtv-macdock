package com.gathin.tvhome;

import android.service.notification.NotificationListenerService;

/** Exists only so Android lets Home read the media sessions (NowPlaying). It reads no notifications. */
public final class MediaListener extends NotificationListenerService {
}
