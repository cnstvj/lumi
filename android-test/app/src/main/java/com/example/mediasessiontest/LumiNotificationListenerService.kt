package com.example.mediasessiontest

import android.service.notification.NotificationListenerService

class LumiNotificationListenerService : NotificationListenerService() {
    // This service needs to be registered in the manifest 
    // to grant the app BIND_NOTIFICATION_LISTENER_SERVICE permission, 
    // which allows MediaSessionManager to query active sessions.
}
