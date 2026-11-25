package pk.cinejoy.tv

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import kotlin.system.exitProcess

class CineJoyApplication : Application() {

    companion object {
        const val HOME_URL = "https://cinejoy.pk"
        private const val PREFS_NAME = "cinejoy_recovery_state"
        private const val KEY_LAST_URL = "last_url"
        private const val KEY_LAST_CRASH_AT = "last_crash_at"
        private const val CRASH_COOLDOWN_MS = 4000L

        fun saveLastUrl(context: Context, url: String?) {
            if (url.isNullOrBlank() || url == "about:blank") {
                return
            }
            if (!url.startsWith("https://") && !url.startsWith("http://")) {
                return
            }
            try {
                context.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LAST_URL, url)
                    .apply()
            } catch (_: Throwable) {
            }
        }

        fun consumeRecoveryUrl(context: Context): String {
            return try {
                val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val saved = prefs.getString(KEY_LAST_URL, null)
                if (!saved.isNullOrBlank() && (saved.startsWith("https://") || saved.startsWith("http://"))) {
                    saved
                } else {
                    HOME_URL
                }
            } catch (_: Throwable) {
                HOME_URL
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        installUncaughtExceptionHandler()
        installMainLooperGuard()
    }

    private fun installUncaughtExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler { _, _ ->
            recoverSilently()
        }
    }

    private fun installMainLooperGuard() {
        Handler(Looper.getMainLooper()).post {
            while (true) {
                try {
                    Looper.loop()
                    return@post
                } catch (_: Throwable) {
                    if (!shouldRelaunchNow()) {
                        continue
                    }
                    relaunchMainActivity()
                }
            }
        }
    }

    private fun shouldRelaunchNow(): Boolean {
        return try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val lastCrash = prefs.getLong(KEY_LAST_CRASH_AT, 0L)
            if (now - lastCrash < CRASH_COOLDOWN_MS) {
                prefs.edit().remove(KEY_LAST_URL).apply()
                false
            } else {
                prefs.edit().putLong(KEY_LAST_CRASH_AT, now).apply()
                true
            }
        } catch (_: Throwable) {
            true
        }
    }

    private fun relaunchMainActivity() {
        try {
            val restartIntent = Intent(applicationContext, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
            startActivity(restartIntent)
        } catch (_: Throwable) {
        }
    }

    private fun recoverSilently() {
        if (shouldRelaunchNow()) {
            relaunchMainActivity()
        }
        try {
            Thread.sleep(150L)
        } catch (_: Throwable) {
        }
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }
}
