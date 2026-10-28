package com.kimi.ai

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Environment
import android.util.Base64
import androidx.core.content.FileProvider
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Encrypted on-device storage for API keys and account notes. Passwords are never stored. */
object Vault {
    fun prefs(ctx: Context): SharedPreferences {
        val mk = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            ctx, "kimi_vault", mk,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}

object PayPal {
    private fun read(c: HttpURLConnection): String {
        val s = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        return s.bufferedReader().readText()
    }

    fun balance(id: String, secret: String, live: Boolean): String = try {
        val base = if (live) "https://api-m.paypal.com" else "https://api-m.sandbox.paypal.com"
        val t = URL("$base/v1/oauth2/token").openConnection() as HttpURLConnection
        t.requestMethod = "POST"; t.doOutput = true
        t.setRequestProperty("Authorization", "Basic " + Base64.encodeToString("$id:$secret".toByteArray(), Base64.NO_WRAP))
        t.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        t.outputStream.use { it.write("grant_type=client_credentials".toByteArray()) }
        val tok = JSONObject(read(t)).optString("access_token")
        if (tok.isEmpty()) "Login failed: check Client ID/Secret and Live/Sandbox"
        else {
            val b = URL("$base/v1/reporting/balances?currency_code=USD").openConnection() as HttpURLConnection
            b.setRequestProperty("Authorization", "Bearer $tok")
            val j = JSONObject(read(b))
            val arr = j.optJSONArray("balances")
            if (arr == null) j.optString("message", "No balance data returned")
            else (0 until arr.length()).joinToString("\n") {
                val tb = arr.getJSONObject(it).getJSONObject("total_balance")
                "${tb.optString("value")} ${tb.optString("currency_code")}"
            }
        }
    } catch (e: Exception) { "Error: ${e.message}" }
}

object Pdf {
    fun make(ctx: Context, title: String, body: String): File {
        val doc = PdfDocument()
        val letter = Prefs.int(ctx, "r_page", 0) == 1
        val w = if (letter) 612 else 595
        val h = if (letter) 792 else 842
        val m = listOf(36f, 50f, 72f)[Prefs.int(ctx, "r_margin", 1).coerceIn(0, 2)]
        val fs = listOf(10f, 12f, 14f)[Prefs.int(ctx, "r_font", 1).coerceIn(0, 2)]
        val footer = Prefs.bool(ctx, "r_footer", true)
        val author = Prefs.str(ctx, "r_author", "").trim()
        val tp = Paint().apply { textSize = fs; color = Color.BLACK; isAntiAlias = true }
        val hp = Paint(tp).apply { textSize = fs + 10f; isFakeBoldText = true }
        val gp = Paint(tp).apply { textSize = 9f; color = Color.GRAY }
        val lh = fs * 1.4f
        val lines = mutableListOf<String>()
        for (para in body.split("\n")) {
            if (para.isBlank()) { lines.add(""); continue }
            var cur = ""
            for (word in para.split(" ")) {
                val t = if (cur.isEmpty()) word else "$cur $word"
                if (tp.measureText(t) > w - 2 * m) { lines.add(cur); cur = word } else cur = t
            }
            lines.add(cur)
        }
        var i = 0; var n = 1
        do {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, n).create())
            var y = m + 10
            if (n == 1) {
                page.canvas.drawText(title, m, y, hp); y += fs + 16f
                if (author.isNotEmpty()) { page.canvas.drawText("By $author", m, y - 6f, gp); y += 14f }
            }
            while (i < lines.size && y < h - m - 8f) { page.canvas.drawText(lines[i], m, y, tp); y += lh; i++ }
            if (footer) page.canvas.drawText("Made with Kimi AI  •  Page $n", m, h - 22f, gp)
            doc.finishPage(page); n++
        } while (i < lines.size)
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)!!
        dir.mkdirs()
        val f = File(dir, "Kimi_${System.currentTimeMillis()}.pdf")
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }

    fun share(ctx: Context, f: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, "Send PDF to…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
