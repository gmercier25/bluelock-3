package ca.gmercier.bluelock

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** Enregistre les plantages (sans aucune donnée personnelle) pour pouvoir les diagnostiquer. */
object CrashLog {
    @Volatile private var appCtx: Context? = null
    private val steps = ArrayDeque<String>()

    private fun file(ctx: Context) = File(ctx.filesDir, "crash.txt")

    /** Journal des dernières étapes (aucune donnée personnelle), joint au rapport de plantage. */
    fun step(msg: String) {
        synchronized(steps) {
            val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            steps.addLast("$t [${Thread.currentThread().name}] $msg")
            while (steps.size > 60) steps.removeFirst()
        }
    }

    fun isCrashProcess(): Boolean = try {
        val name = if (android.os.Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName()
        else File("/proc/self/cmdline").readText().trim { it <= ' ' || it == '\u0000' }
        name?.endsWith(":crash") == true
    } catch (_: Throwable) {
        false
    }

    private fun stepsText(): String = synchronized(steps) { steps.joinToString("\n") }

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        // Le processus de l'écran d'erreur ne doit pas s'intercepter lui-même.
        if (isCrashProcess()) return
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val info = "BlueLock ${BuildInfo.version(app)} · Android ${android.os.Build.VERSION.RELEASE} " +
                    "(API ${android.os.Build.VERSION.SDK_INT}) · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n" +
                    "Fil : ${thread.name}\n\n"
                file(app).writeText(
                    info + sw.toString().take(10_000) + "\n\n--- Dernières étapes ---\n" + stepsText()
                )
            } catch (_: Throwable) {
            }
            try {
                app.startActivity(
                    Intent(app, CrashActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
            } catch (_: Throwable) {
            }
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(10)
        }
    }

    fun read(ctx: Context): String? = file(ctx).takeIf { it.exists() }?.readText()
    fun clear(ctx: Context) {
        file(ctx).delete()
    }
}

object BuildInfo {
    fun version(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }
}

/** Affiche le dernier plantage avec un bouton pour le copier. */
class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = CrashLog.read(this) ?: "Aucun plantage enregistré."
        val pad = (16 * resources.displayMetrics.density).toInt()

        val title = TextView(this).apply {
            this.text = "BlueLock a planté"
            textSize = 24f
            setPadding(0, 0, 0, pad / 2)
        }
        val hint = TextView(this).apply {
            this.text = "Touche « Copier » puis colle le texte dans la conversation avec Claude."
            setPadding(0, 0, 0, pad)
        }
        val copy = Button(this).apply {
            this.text = "Copier l'erreur"
            setOnClickListener {
                val cm = getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("BlueLock crash", text))
                Toast.makeText(this@CrashActivity, "Copié", Toast.LENGTH_SHORT).show()
            }
        }
        val share = Button(this).apply {
            this.text = "Partager"
            setOnClickListener {
                startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                        "Partager l'erreur"
                    )
                )
            }
        }
        val cont = Button(this).apply {
            this.text = "Continuer"
            setOnClickListener {
                CrashLog.clear(this@CrashActivity)
                startActivity(Intent(this@CrashActivity, MainActivity::class.java))
                finish()
            }
        }
        val body = TextView(this).apply {
            this.text = text
            textSize = 11f
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, pad, 0, 0)
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            addView(title); addView(hint); addView(copy); addView(share); addView(cont); addView(body)
        }
        setContentView(ScrollView(this).apply { addView(col) })
    }
}
