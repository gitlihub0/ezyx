package com.anizium

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AniziumPlugin : Plugin() {

    override fun load(context: Context) {
        registerMainAPI(Anizium())
        openSettings = { ctx -> showSettings(ctx) }
    }

    // ------------------------------------------------------------ Renkler
    private val cDialog = Color.parseColor("#14161C")
    private val cCard   = Color.parseColor("#1C1F27")
    private val cAmber  = Color.parseColor("#FFB300")
    private val cBrown  = Color.parseColor("#6F3F00")
    private val cGray   = Color.parseColor("#9AA0AE")
    private val cStroke = Color.parseColor("#3A3E4A")

    private fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun Context.shape(color: Int, radiusDp: Int, stroke: Int? = null) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun Context.label(
        text: CharSequence, sizeSp: Float, color: Int, bold: Boolean = false
    ) = TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    // ANDROID TV ODAKLANMA EFEKTİ
    private fun View.addTvFocus() {
        this.isFocusable = true
        this.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.alpha = 0.7f
                v.animate().scaleX(1.03f).scaleY(1.03f).setDuration(150).start()
            } else {
                v.alpha = 1.0f
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
            }
        }
    }

    // ------------------------------------------------------------ Ayar ekranı
    private fun showSettings(ctx: Context) {
        val dialog = Dialog(ctx)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val siteName = "Anizium"
        val initial = AniziumDomain.current()
        var useAuto = false

        // ---- Başlık satırı
        val title = SpannableStringBuilder().apply {
            append(siteName)
            setSpan(ForegroundColorSpan(cAmber), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val start = length
            append("  Alan Adı & Ayarlar")
            setSpan(ForegroundColorSpan(Color.WHITE), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val titleView = ctx.label(title, 20f, Color.WHITE).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = ctx.label("✕  Kapat", 14f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            setPadding(ctx.dp(16), ctx.dp(10), ctx.dp(16), ctx.dp(10))
            background = ctx.shape(Color.TRANSPARENT, 24, cStroke)
            addTvFocus() // Odaklanma eklendi
            setOnClickListener { dialog.dismiss() }
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(titleView)
            addView(closeBtn)
        }

        val subtitle = ctx.label(
            "Adres değiştiğinde 'Düzenle ✎' ile güncelleyebilir veya otomatik tarama yapabilirsiniz.",
            13f, cGray
        ).apply { setPadding(0, ctx.dp(8), 0, 0) }

        // ---- Kaydet ve Kapat
        val saveBtn = ctx.label("✓  Kaydet ve Kapat", 16f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.05f
            setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(14))
            background = ctx.shape(cBrown, 28)
            addTvFocus() // Odaklanma eklendi
        }

        // ---- Domain kartı
        val nameView = ctx.label(siteName, 18f, Color.WHITE, bold = true)
        val urlView  = ctx.label("$initial ✎", 14f, cGray).apply {
            setPadding(0, ctx.dp(4), 0, 0)
            addTvFocus() // Odaklanma eklendi
        }
        val apiView  = ctx.label("API: ${AniziumDomain.apiOf(initial)}", 12f, cGray).apply {
            setPadding(0, ctx.dp(2), 0, 0)
        }
        val editField = EditText(ctx).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            hint = AniziumDomain.DEFAULT
            setText(initial)
            setSingleLine()
            setTextColor(Color.WHITE)
            setHintTextColor(cGray)
            textSize = 14f
            visibility = View.GONE
            addTvFocus() // Odaklanma eklendi
        }
        val editLabel = ctx.label("Düzenle ✎", 15f, cAmber).apply {
            setPadding(ctx.dp(12), ctx.dp(8), 0, ctx.dp(8))
            addTvFocus() // Odaklanma eklendi
        }

        val leftCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(nameView)
            addView(urlView)
            addView(editField)
            addView(apiView)
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(14))
            background = ctx.shape(cCard, 16)
            addView(leftCol)
            addView(editLabel)
        }

        fun enterEditMode() {
            useAuto = false
            urlView.visibility = View.GONE
            editField.visibility = View.VISIBLE
            editField.requestFocus()
            editField.setSelection(editField.text.length)
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
            (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(editField, InputMethodManager.SHOW_IMPLICIT)
        }
        editLabel.setOnClickListener { enterEditMode() }
        urlView.setOnClickListener { enterEditMode() }

        // ---- Alt kısım
        val hint = ctx.label(
            "Alan adlarını test edip en güncel adreslere geçmek için tarayın.",
            13f, cGray
        ).apply { setPadding(0, ctx.dp(20), 0, ctx.dp(10)) }

        val autoBtn = ctx.label("⚡  Otomatik Tara ve Güncelle", 15f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.04f
            setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(14))
            background = ctx.shape(cCard, 28)
            addTvFocus() // Odaklanma eklendi
        }

        autoBtn.setOnClickListener {
            autoBtn.isEnabled = false
            autoBtn.text = "⏳  Taranıyor..."
            Thread {
                val result = AniziumDomain.fetchBlocking()
                Handler(Looper.getMainLooper()).post {
                    autoBtn.isEnabled = true
                    autoBtn.text = "⚡  Otomatik Tara ve Güncelle"
                    if (result.domain != null) {
                        useAuto = true
                        editField.setText(result.domain)
                        urlView.text = "${result.domain} ✎"
                        apiView.text = "API: ${AniziumDomain.apiOf(result.domain)}"
                        urlView.visibility = View.VISIBLE
                        editField.visibility = View.GONE
                        Toast.makeText(ctx, "Bulundu: ${result.domain}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(ctx, "Hata: ${result.error}", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }

        saveBtn.setOnClickListener {
            if (useAuto) {
                setKey(AniziumDomain.PREF_KEY, "") // manuel ayarı sil, GitHub'a bağlı kal
                Toast.makeText(ctx, "Otomatik domain kullanılacak", Toast.LENGTH_LONG).show()
            } else {
                val typed = AniziumDomain.normalize(editField.text.toString())
                if (typed != initial) {
                    setKey(AniziumDomain.PREF_KEY, typed)
                    if (typed.isEmpty()) {
                        Toast.makeText(ctx, "Manuel domain silindi, otomatik domain kullanılacak", Toast.LENGTH_LONG).show()
                    } else {
                        Thread {
                            val err = AniziumDomain.checkBlocking(AniziumDomain.apiOf(typed))
                            Handler(Looper.getMainLooper()).post {
                                val msg = if (err == null) "Kaydedildi, uygulamayı yeniden aç"
                                          else "Kaydedildi ama API'ye ulaşılamıyor ($err)"
                                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                            }
                        }.start()
                    }
                }
            }
            dialog.dismiss()
        }

        // ---- Birleştir
        fun LinearLayout.add(v: View, topDp: Int = 0) {
            addView(v, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = ctx.dp(topDp) })
        }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(20), ctx.dp(20), ctx.dp(20), ctx.dp(20))
            background = ctx.shape(cDialog, 24)
            add(header)
            add(subtitle)
            add(saveBtn, 16)
            add(card, 16)
            add(hint)
            add(autoBtn)
        }

        dialog.setContentView(ScrollView(ctx).apply { addView(root) })
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val w = (ctx.resources.displayMetrics.widthPixels * 0.92f).toInt()
                .coerceAtMost(ctx.dp(560))
            setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }
}
