package com.pepe.camaramacro

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat

/**
 * Ajustes de verdad.
 *
 * Hasta ahora la única "configuración" era SetupActivity —que es solo el selector de
 * lente— y una fila de chips sobre el visor: no había dónde poner nada que no cupiera en
 * un emoji, y cosas como el sonido de captura, las cebras o el piso de obturación no
 * tenían interruptor posible. Además el botón del engranaje llevaba al selector de lente,
 * que no es lo que promete el icono, y las teclas de volumen estaban clavadas al disparo
 * sin alternativa.
 *
 * Escribe en las MISMAS preferencias ("camara") que CameraActivity, que las relee entera
 * al volver (applyPrefs). Aquí no se abre NINGUNA lente: entrar en Ajustes no debe tocar
 * la cámara, y menos en un ColorOS que la quita en cuanto la app deja de estar visible.
 *
 * La pantalla se construye por CÓDIGO a propósito: res/layout y res/values son de otros
 * integradores en esta ronda, y añadir ahí un fichero o una cadena rompería su
 * compilación. Por eso los textos van literales.
 */
class SettingsActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("camara", MODE_PRIVATE) }

    private val cAccent by lazy { ContextCompat.getColor(this, R.color.accent) }
    private val cText by lazy { ContextCompat.getColor(this, R.color.warm_white) }
    private val cDim by lazy { ContextCompat.getColor(this, R.color.text_off) }

    /** Chips de "qué hacen las teclas de volumen": se repintan al elegir. */
    private val volChips = ArrayList<TextView>(3)

    private val floorLabels = arrayOf("Automático", "1/60", "1/125", "1/250", "1/500")
    private var chipFloor: TextView? = null

    private val zebraLabels = arrayOf("70 %", "95 %", "Recorte")
    private var chipZebra: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            fitsSystemWindows = true
        }

        // ---- Cabecera ----
        val cabecera = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        cabecera.addView(
            TextView(this).apply {
                text = "‹"
                textSize = 28f
                setTextColor(cText)
                gravity = Gravity.CENTER
                contentDescription = getString(R.string.close)
                minWidth = dp(48)
                minHeight = dp(48)
                setOnClickListener { finish() }
            },
            LinearLayout.LayoutParams(dp(48), dp(48))
        )
        cabecera.addView(
            TextView(this).apply {
                text = "Ajustes"
                textSize = 20f
                setTextColor(cText)
                setPadding(dp(8), 0, 0, 0)
            }
        )
        raiz.addView(
            cabecera,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64))
        )

        // ---- Cuerpo desplazable ----
        val cuerpo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), dp(32))
        }

        seccion(cuerpo, "CAPTURA")
        interruptor(cuerpo, "Sonido de captura", "shutterSound", true)
        // El lector de códigos NO se pone aquí a propósito, aunque comparta preferencia:
        // tiene su propio chip QR en el panel «Más» y duplicar el mando en dos sitios era
        // pedir que el chip y el interruptor acabaran diciendo cosas distintas (el motor
        // puede apagarlo solo al encender RAW, Ultra HDR o el modo noche).
        filaObturacion(cuerpo)

        // Exposición a la cara: setFaceMetering() llevaba implementado en el motor desde
        // hacía rondas y NADIE lo llamaba. La detección de caras corría igual y su resultado
        // no se usaba nunca para medir la luz. Va apagado por defecto porque mueve la
        // exposición de toda la escena en cuanto entra alguien en cuadro, que es justo lo
        // contrario de lo que quiere una foto macro.
        interruptor(
            cuerpo, "Exponer para la cara", "faceMetering", false,
            "Cuando hay una cara en cuadro, la luz se mide sobre ella y no sobre el fondo."
        )

        seccion(cuerpo, "HERRAMIENTAS DE ANÁLISIS")
        interruptor(cuerpo, "Histograma", "toolHist", true)
        interruptor(
            cuerpo, "Cebras de recorte", "toolZebra", false,
            "Raya lo que ya está quemado o pegado al negro: eso no se recupera después."
        )
        filaCebras(cuerpo)
        interruptor(
            cuerpo, "Resaltar el enfoque", "toolPeak", false,
            "Marca en ámbar los bordes nítidos. En macro la profundidad de campo son milímetros."
        )
        etiqueta(
            cuerpo,
            "Se encienden y se apagan con el chip ANÁLISIS del panel «Más», sobre el visor. " +
                "Ahora siguen funcionando MIENTRAS se graba vídeo, que es cuando de verdad " +
                "hacen falta."
        )

        seccion(cuerpo, "BOTONES DE VOLUMEN")
        filaVolumen(cuerpo)

        seccion(cuerpo, "CÁMARA")
        cuerpo.addView(
            TextView(this).apply {
                text = getString(R.string.pick_another_lens)
                textSize = 16f
                setTextColor(cAccent)
                minHeight = dp(56)
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    startActivity(Intent(this@SettingsActivity, SetupActivity::class.java))
                    finish()
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val scroll = ScrollView(this)
        scroll.addView(
            cuerpo,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        raiz.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        setContentView(
            raiz,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    // ---------------------------------------------------------------- Constructores de fila

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun seccion(padre: LinearLayout, titulo: String) {
        padre.addView(
            TextView(this).apply {
                text = titulo
                textSize = 12f
                setTextColor(cAccent)
                letterSpacing = 0.1f
                setPadding(0, dp(24), 0, dp(4))
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun etiqueta(padre: LinearLayout, texto: String) {
        padre.addView(
            TextView(this).apply {
                text = texto
                textSize = 13f
                setTextColor(cDim)
                setPadding(0, dp(4), 0, dp(4))
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    /** Fila con título, explicación opcional y un interruptor atado a una preferencia. */
    private fun interruptor(
        padre: LinearLayout,
        titulo: String,
        clave: String,
        porDefecto: Boolean,
        detalle: String? = null
    ) {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
        }
        val textos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textos.addView(
            TextView(this).apply {
                text = titulo
                textSize = 16f
                setTextColor(cText)
            }
        )
        if (detalle != null) {
            textos.addView(
                TextView(this).apply {
                    text = detalle
                    textSize = 12f
                    setTextColor(cDim)
                }
            )
        }
        fila.addView(
            textos,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val sw = SwitchCompat(this).apply {
            isChecked = prefs.getBoolean(clave, porDefecto)
            contentDescription = titulo
            setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean(clave, v).apply() }
        }
        fila.addView(sw)
        padre.addView(
            fila,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        )
    }

    /**
     * Piso de velocidad de obturación. Estaba escrito a fuego en el motor (1/60) y no había
     * forma de congelar el movimiento cuando hacía falta ni de relajarlo cuando sobraba.
     */
    private fun filaObturacion(padre: LinearLayout) {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
        }
        val textos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textos.addView(
            TextView(this).apply {
                text = "Velocidad mínima"
                textSize = 16f
                setTextColor(cText)
            }
        )
        textos.addView(
            TextView(this).apply {
                text = "Cuanto más rápida, menos movida sale la foto y más ISO se gasta."
                textSize = 12f
                setTextColor(cDim)
            }
        )
        fila.addView(
            textos,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val chip = TextView(this, null, 0, R.style.ProChip)
        chipFloor = chip
        pintarObturacion()
        chip.setOnClickListener {
            val i = (prefs.getInt("shutterFloor", 1) + 1) % floorLabels.size
            prefs.edit().putInt("shutterFloor", i).apply()
            pintarObturacion()
        }
        fila.addView(chip)
        padre.addView(
            fila,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        )
    }

    /**
     * Umbral de las cebras. Estaba clavado a fuego en "y >= 250": eso solo raya lo que YA se
     * ha quemado, o sea que avisa cuando el píxel ya está perdido. Una cebra útil avisa
     * ANTES, y el 70 % es la referencia de exposición de piel de cualquier rodaje.
     */
    private fun filaCebras(padre: LinearLayout) {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
        }
        val textos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textos.addView(
            TextView(this).apply {
                text = "Umbral de las cebras"
                textSize = 16f
                setTextColor(cText)
            }
        )
        textos.addView(
            TextView(this).apply {
                text = "70 % es el tono de piel; 95 % avisa antes de quemar; recorte, cuando ya está."
                textSize = 12f
                setTextColor(cDim)
            }
        )
        fila.addView(
            textos,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val chip = TextView(this, null, 0, R.style.ProChip)
        chipZebra = chip
        pintarCebras()
        chip.setOnClickListener {
            val i = (prefs.getInt("zebraLevel", 2) + 1) % zebraLabels.size
            prefs.edit().putInt("zebraLevel", i).apply()
            pintarCebras()
        }
        fila.addView(chip)
        padre.addView(
            fila,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        )
    }

    private fun pintarCebras() {
        val i = prefs.getInt("zebraLevel", 2).coerceIn(0, zebraLabels.size - 1)
        chipZebra?.let {
            it.text = zebraLabels[i]
            it.setTextColor(if (i < 2) cAccent else cDim)
            it.contentDescription = "Umbral de las cebras: ${zebraLabels[i]}"
        }
    }

    private fun pintarObturacion() {
        val i = prefs.getInt("shutterFloor", 1).coerceIn(0, floorLabels.size - 1)
        chipFloor?.let {
            it.text = floorLabels[i]
            it.setTextColor(if (i > 1) cAccent else cDim)
            it.contentDescription = "Velocidad mínima: ${floorLabels[i]}"
        }
    }

    private fun filaVolumen(padre: LinearLayout) {
        val fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        volChips.clear()
        arrayOf("DISPARO", "ZOOM", "EXPOSICIÓN").forEachIndexed { i, rotulo ->
            val chip = TextView(this, null, 0, R.style.ProChip)
            chip.text = rotulo
            chip.setOnClickListener {
                prefs.edit().putInt("volAction", i).apply()
                pintarVolumen()
            }
            volChips.add(chip)
            fila.addView(chip)
        }
        pintarVolumen()
        padre.addView(
            fila,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun pintarVolumen() {
        val a = prefs.getInt("volAction", 0)
        volChips.forEachIndexed { i, chip ->
            chip.setTextColor(if (i == a) cAccent else cDim)
            chip.isSelected = i == a
            chip.contentDescription =
                "${chip.text}. ${if (i == a) "Seleccionado" else "No seleccionado"}"
        }
    }

}
