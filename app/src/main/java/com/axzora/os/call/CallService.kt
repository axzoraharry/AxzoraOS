package com.axzora.os.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.axzora.os.MainActivity
import com.axzora.os.chat.ChatClient
import com.axzora.os.data.SettingsStore
import com.axzora.os.wallet.AdminNotify
import com.axzora.os.wallet.WalletInbox
import com.axzora.os.wallet.WalletNet
import com.axzora.os.wallet.WalletStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps this device reachable while the app is closed: incoming calls, family
 * messages and Happy Paisa transfers all arrive through this one service.
 *
 * Incoming calls were previously detected by a coroutine owned by the Activity,
 * which meant the phone stopped listening the moment the app was backgrounded —
 * Android freezes cached processes, so the poll simply never ran and the caller
 * heard ringing that would never be answered. A foreground service is exempt
 * from that freeze.
 *
 * The proper long-term answer is a push notification, which wakes the phone
 * without any battery cost at all; this keeps calls working without requiring
 * a Firebase project.
 */
class CallService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIF_READY, readyNotification())
        if (!running) {
            running = true
            scope.launch { pollLoop() }
            scope.launch { watchCalls() }
        }
        return START_STICKY
    }

    private suspend fun pollLoop() {
        val settings = SettingsStore(applicationContext)
        val wallet = WalletStore(applicationContext)
        val uid = wallet.ensureUid()
        var lastMessageId = 0L
        var firstChatPass = true
        var lastNoticeId = 0L
        val chat = ChatClient()

        while (running) {
            val snapshot = runCatching { settings.settings.first() }.getOrNull()
            val server = snapshot?.serverUrl.orEmpty()

            // ---- family messages ----
            if (server.isNotBlank() && !snapshot?.familyCode.isNullOrBlank()) {
                val code = snapshot!!.familyCode
                val me = snapshot.displayName.ifBlank { "Me" }
                val fresh = chat.poll(server, code, lastMessageId, me)
                if (fresh.isNotEmpty()) {
                    lastMessageId = fresh.maxOf { it.id }
                    // The first pass only establishes where we are in the
                    // history; without this every old message would arrive as a
                    // notification the moment the service starts.
                    if (!firstChatPass) {
                        fresh.filter { !it.mine }.forEach { messageNotification(it.from, it.text) }
                    }
                }
                firstChatPass = false
            }

            // ---- admin announcements ----
            if (server.isNotBlank() && uid.isNotBlank()) {
                val notices = AdminNotify { server }.poll(uid, lastNoticeId)
                if (notices.isNotEmpty()) {
                    lastNoticeId = notices.maxOf { it.id }
                    notices.forEach { noticeNotification(it.title, it.text) }
                }
            }

            // ---- Happy Paisa arriving ----
            if (server.isNotBlank() && uid.isNotBlank()) {
                val net = WalletNet { server }
                runCatching { WalletInbox(applicationContext, wallet, net).poll(uid) }
            }

            // ---- device presence ----
            // Every few seconds this phone tells the hub it is alive, so it
            // shows up in the owner's device list; if the hub says someone
            // asked it to ring, it rings - the find-my-phone move.
            if (server.isNotBlank() && uid.isNotBlank()) {
                val hub = com.axzora.os.devices.DeviceHub(applicationContext) { server }
                hub.deviceId = snapshot!!.deviceId
                val name = snapshot.displayName.ifBlank { android.os.Build.MODEL ?: "My device" }
                val wantsRing = runCatching { hub.heartbeat(uid, name) }.getOrDefault(false)
                if (wantsRing && !findMyPhone.isRinging) {
                    scope.launch {
                        findMyPhone.start()
                        kotlinx.coroutines.delay(15_000)
                        findMyPhone.stop()
                    }
                }
            }

            delay(3000)
        }
    }

    /** Rings this phone loudly when another of the owner's devices asks. */
    private val findMyPhone by lazy { com.axzora.os.call.Ringer(applicationContext) }

    /**
     * Watches for calls on its own loop.
     *
     * Kept separate from the message loop on purpose: this request is *held
     * open* by the server until something happens, so folding it into the
     * same sequence would stall messages behind a call that never comes.
     */
    private suspend fun watchCalls() {
        val settings = SettingsStore(applicationContext)
        val wallet = WalletStore(applicationContext)
        val uid = wallet.ensureUid()
        var lastRung: String? = null
        var lastRoom: String? = null
        while (running) {
            val snapshot = runCatching { settings.settings.first() }.getOrNull()
            val server = snapshot?.serverUrl.orEmpty()
            if (server.isBlank() || uid.isBlank()) {
                delay(2000)
                continue
            }

            // Stand down while a call is live. The server hands each ICE
            // candidate out once, to the first request that drains it — if
            // this loop polled during a call it would swallow candidates the
            // call engine needs, and the call would connect slowly or not
            // at all.
            if (CallBus.callActive) {
                delay(1000)
                continue
            }

            val signaling = CallSignaling { server }
            // Held for up to 25s. Anything the server learns in that window —
            // a call, an answer, an ICE candidate — comes back at once.
            val result = signaling.poll(uid, waitMs = 25_000)

            // Bank any candidates this poll drained — draining is one-shot on
            // the server, and dropping them here is what made calls sit in
            // "connecting" after being answered.
            result.ice.forEach { (callId, list) -> CallBus.bankIce(callId, list) }

            /*
             * A group call has to reach a closed app too.
             *
             * Nobody has dialled yet - the invitation is a room waiting to be
             * joined - so there is no ringing call to notify about. Without
             * this, a group call only ever reached people who happened to
             * have Axzora open, which is most of the time nobody.
             *
             * The service cannot join on the user's behalf: joining opens the
             * microphone and dials everyone inside. So the notification opens
             * the app, which finds the same invitation on its own poll and
             * asks properly.
             */
            val invite = result.roomInvites.firstOrNull()
            if (invite != null && invite.roomId != lastRoom) {
                lastRoom = invite.roomId
                if (!CallBus.uiVisible) roomNotification(invite)
            } else if (invite == null && lastRoom != null) {
                // Host gave up, or someone else filled the last place.
                NotificationManagerCompat.from(this).cancel(NOTIF_ROOM)
                lastRoom = null
            }

            // Ring for the most recent caller. Taking the oldest means a
            // single unanswered call masks everyone who rings afterwards.
            // A call tagged with a room is one leg of a group, answered
            // silently by the call engine. Ringing for it would ask the user
            // to accept the same call once per person in the group.
            result.incoming.lastOrNull { it.roomId.isBlank() }?.let { call ->
                if (call.callId != lastRung) {
                    lastRung = call.callId
                    CallBus.offer(call)
                    // If an Activity is already on screen it renders the
                    // ringing UI itself; a notification as well would be
                    // duplicate noise.
                    if (!CallBus.uiVisible) ringNotification(call)
                }
            }

            // Caller hung up, or the call is simply no longer ringing:
            // clear so the same person can call straight back.
            val stillRinging = result.incoming.any {
                it.callId == lastRung && it.roomId.isBlank()
            }
            if (lastRung != null && (!stillRinging || lastRung in result.ended)) {
                NotificationManagerCompat.from(this).cancel(NOTIF_RINGING)
                if (CallBus.pending.value?.callId == lastRung) CallBus.clear()
                lastRung = null
            }

            // Only a guard against spinning if the server answers instantly.
            delay(250)
        }
    }

    // ---------------------------------------------------------------- notifications

    private fun channels(): NotificationManager {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CH_READY, "Call availability", NotificationManager.IMPORTANCE_MIN)
                    .apply { description = "Keeps you reachable for Axzora calls" }
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_MSG, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Family messages and Happy Paisa"
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    enableVibration(true)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_RING, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Rings when someone calls you"
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 800, 800, 800, 800)
                }
            )
        }
        return nm
    }

    private fun readyNotification(): Notification {
        channels()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CH_READY)
            // Ember accent, so the notification shade matches the theme.
            .setColor(0xFFF0A73C.toInt())
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle("Axzora is ready for calls")
            .setContentText("Calls, messages and payments arrive even when closed.")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun ringNotification(call: IncomingCall) {
        channels()

        fun answerIntent(video: Boolean, decline: Boolean) = PendingIntent.getActivity(
            this,
            call.callId.hashCode() + (if (video) 1 else 0) + (if (decline) 2 else 0),
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_CALL_ID, call.callId)
                putExtra(EXTRA_ANSWER_VIDEO, video)
                putExtra(EXTRA_DECLINE, decline)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val caller = androidx.core.app.Person.Builder()
            .setName(call.fromName)
            .setImportant(true)
            .build()

        val builder = NotificationCompat.Builder(this, CH_RING)
            // Ember accent, so the notification shade matches the theme.
            .setColor(0xFFF0A73C.toInt())
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle("${call.fromName} is calling")
            .setContentText("Axzora call")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(true)
            // Full-screen intent is what lets the call take over the screen
            // instead of appearing as a banner the user has to notice.
            .setFullScreenIntent(answerIntent(video = false, decline = false), true)
            .setContentIntent(answerIntent(video = false, decline = false))

        // CallStyle gives the system-rendered incoming-call layout with proper
        // Answer/Decline treatment; older versions fall back to plain actions.
        runCatching {
            builder.setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    caller,
                    answerIntent(video = false, decline = true),
                    answerIntent(video = false, decline = false),
                )
            )
        }.onFailure {
            builder
                .addAction(0, "Decline", answerIntent(video = false, decline = true))
                .addAction(0, "Audio", answerIntent(video = false, decline = false))
                .addAction(0, "Video", answerIntent(video = true, decline = false))
        }

        val n = builder.build()

        runCatching { NotificationManagerCompat.from(this).notify(NOTIF_RINGING, n) }
    }

    /** Someone has opened a group call and asked this phone into it. */
    private fun roomNotification(room: Room) {
        channels()
        val open = PendingIntent.getActivity(
            this, room.roomId.hashCode(),
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, CH_RING)
            .setColor(0xFFF0A73C.toInt())
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle("${room.hostName.ifBlank { room.host }} started ${room.title.lowercase()}")
            .setContentText("Tap to join")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(NOTIF_ROOM, n) }
    }

    /** An announcement from the Axzora admin. */
    private fun noticeNotification(title: String, text: String) {
        channels()
        val open = PendingIntent.getActivity(
            this, title.hashCode(),
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, CH_MSG)
            // Ember accent, so the notification shade matches the theme.
            .setColor(0xFFF0A73C.toInt())
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIF_NOTICE_BASE + (text.hashCode() % 1000), n)
        }
    }

    private fun messageNotification(from: String, text: String) {
        channels()
        val open = PendingIntent.getActivity(
            this, from.hashCode(),
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, CH_MSG)
            // Ember accent, so the notification shade matches the theme.
            .setColor(0xFFF0A73C.toInt())
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(from)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIF_MSG_BASE + from.hashCode() % 1000, n)
        }
    }

    override fun onDestroy() {
        running = false
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        runCatching { NotificationManagerCompat.from(this).cancel(NOTIF_RINGING) }
        runCatching { NotificationManagerCompat.from(this).cancel(NOTIF_ROOM) }
        super.onDestroy()
    }

    companion object {
        private const val CH_READY = "axzora_call_ready"
        private const val CH_RING = "axzora_call_ring"
        private const val CH_MSG = "axzora_messages"
        private const val NOTIF_READY = 91
        private const val NOTIF_RINGING = 92
        private const val NOTIF_ROOM = 93
        private const val NOTIF_MSG_BASE = 3000
        private const val NOTIF_NOTICE_BASE = 5000

        const val ACTION_STOP = "com.axzora.os.STOP_CALL_SERVICE"
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_ANSWER_VIDEO = "answer_video"
        const val EXTRA_DECLINE = "decline"

        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, CallService::class.java))
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, CallService::class.java).setAction(ACTION_STOP)
                )
            }
        }

        fun dismissRinging(context: Context) {
            runCatching { NotificationManagerCompat.from(context).cancel(NOTIF_RINGING) }
        }
    }
}
