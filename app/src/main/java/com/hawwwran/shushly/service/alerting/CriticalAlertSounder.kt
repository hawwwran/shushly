package com.hawwwran.shushly.service.alerting

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays Shushly's important-notification alert. Sound-only: no notification is posted, so there is
 * no id, content intent, or dismissal to track.
 *
 * THE constraint: Smart Quiet Mode's zen policy mutes every audio lane except the alarm lane
 * (`ZenRuleQuietModeController.buildZenPolicy` is `disallowAllSounds` + `allowAlarms`). So both the
 * tone and the haptic MUST be issued on the alarm usage, or our own DND rule would silence them.
 */
interface CriticalAlertSounder {
    /**
     * Plays the alert tone on the alarm lane; also vibrates (alarm usage) when [vibrate] is true.
     * [soundUri] is the chosen tone (a parseable content/resource URI string); null — or anything
     * that fails to resolve — falls back to the system default. Always plays on USAGE_ALARM, at the
     * device's alarm-stream volume (which the Home slider controls directly).
     */
    fun playAlert(vibrate: Boolean, soundUri: String?)
}

@Singleton
class CriticalAlertSounderImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : CriticalAlertSounder {

    // USAGE_ALARM is the only lane Smart Quiet Mode leaves open; CONTENT_TYPE_SONIFICATION marks the
    // tone as a short alert sound rather than music. Volume therefore rides the alarm stream.
    private val alarmAudioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val vibrator: Vibrator? by lazy {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    }

    // Reused across alerts and held as a field so playback isn't cut short by GC mid-play. Cached
    // only while the requested URI is unchanged; [cachedKey] is the soundUri it was built for (null
    // = default), so a newly-picked sound rebuilds on the next alert.
    @Volatile private var ringtone: Ringtone? = null
    @Volatile private var cachedKey: String? = null

    @Synchronized
    override fun playAlert(vibrate: Boolean, soundUri: String?) {
        playTone(soundUri)
        if (vibrate) vibrate()
    }

    private fun playTone(soundUri: String?) {
        try {
            val rt = ensureRingtone(soundUri)
            if (rt == null) {
                Log.w(TAG, "no alert tone available; sound skipped")
                return
            }
            rt.audioAttributes = alarmAudioAttributes
            // Plays at the device alarm-stream volume by default (the Home slider sets that directly).
            if (rt.isPlaying) rt.stop()
            rt.play()
        } catch (t: Throwable) {
            // Fail-safe: a playback failure must never crash the listener.
            Log.w(TAG, "alert tone failed", t)
        }
    }

    private fun ensureRingtone(soundUri: String?): Ringtone? {
        val existing = ringtone
        if (existing != null && cachedKey == soundUri) return existing
        val rt = buildRingtone(soundUri)
        ringtone = rt
        cachedKey = soundUri
        return rt
    }

    /** The chosen tone, or the system default (notification → alarm) when null/unresolvable. */
    private fun buildRingtone(soundUri: String?): Ringtone? {
        if (soundUri != null) {
            runCatching { RingtoneManager.getRingtone(context, Uri.parse(soundUri)) }
                .getOrNull()
                ?.let { return it }
            Log.w(TAG, "alert sound URI did not resolve; using default")
        }
        val defaultUri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: return null
        return runCatching { RingtoneManager.getRingtone(context, defaultUri) }.getOrNull()
    }

    private fun vibrate() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            val effect = VibrationEffect.createWaveform(DOUBLE_PULSE_MS, NO_REPEAT)
            if (Build.VERSION.SDK_INT >= 33) {
                val attrs = VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_ALARM)
                    .build()
                v.vibrate(effect, attrs)
            } else {
                // 31..32: the VibrationAttributes overload doesn't exist yet; the alarm
                // AudioAttributes overload keeps the haptic on the alarm usage so it survives DND.
                @Suppress("DEPRECATION")
                v.vibrate(effect, alarmAudioAttributes)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "alert vibration failed", t)
        }
    }

    private companion object {
        const val TAG = "ShushlySounder"
        // wait, buzz, pause, buzz: a notification-like double pulse.
        val DOUBLE_PULSE_MS = longArrayOf(0, 60, 80, 60)
        const val NO_REPEAT = -1
    }
}
