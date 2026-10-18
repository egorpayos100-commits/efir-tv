package plus.efir.tv.ui.home

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import plus.efir.tv.CrashReport
import plus.efir.tv.R

class CrashActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crash)

        val text = CrashReport.read(this) ?: "Пусто: файл падения не найден."
        val view = findViewById<TextView>(R.id.crash_text)
        view.text = text

        findViewById<TextView>(R.id.crash_hint).text = buildString {
            append("Полный текст также в файле: ")
            append(CrashReport.file(this@CrashActivity).absolutePath)
            append("\n«Назад» вернёт в приложение и очистит отчёт.")
        }
    }

    override fun onBackPressed() {
        CrashReport.clear(this)
        super.onBackPressed()
    }

    companion object {
        fun startIfCrashed(context: Context) {
            if (CrashReport.read(context) != null) {
                context.startActivity(Intent(context, CrashActivity::class.java))
            }
        }
    }
}
