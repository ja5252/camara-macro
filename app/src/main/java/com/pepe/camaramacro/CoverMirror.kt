package com.pepe.camaramacro

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.TextureView
import android.view.View
import android.widget.ImageView
import android.widget.TextView

/**
 * Espejo del visor en la pantalla EXTERNA del plegable.
 *
 * Para qué: con el teléfono abierto y las lentes traseras apuntando a uno mismo, la
 * pantalla interior queda del otro lado y no se ve nada. Este espejo saca el encuadre
 * por la pantalla de cubierta, que es literalmente la razón de tener un plegable: poder
 * hacerse un autorretrato con la lente trasera BUENA en vez de con la frontal.
 *
 * Por qué copiando fotogramas y no compartiendo la superficie:
 * lo ideal sería añadir la Surface de la segunda pantalla como target adicional del
 * repeating request, pero eso vive dentro de Camera2Controller. La alternativa de
 * compartir el SurfaceTexture tampoco sirve: un SurfaceTexture no puede alimentar a dos
 * TextureView a la vez. Así que se copia el fotograma que ya está en el visor con
 * TextureView.getBitmap(bitmap), exactamente la misma técnica que ya usa el escáner de
 * códigos, a resolución baja y a ~15 fps. Es una lectura de GPU por fotograma: por eso
 * el bitmap se reutiliza (nunca se asigna uno nuevo por ciclo) y el bombeo solo corre
 * mientras el espejo está en pantalla.
 *
 * IMPORTANTE, y hay que decirlo claro: esto solo se puede encender si el sistema publica
 * la pantalla de cubierta como pantalla de presentación. Muchas ROM de plegables la
 * apagan mientras la interior está activa y entonces no aparece en DisplayManager. En
 * ese caso [isAvailable] devuelve false, el chip "Espejo" ni siquiera se muestra, y la
 * única vía real sería WindowAreaController de androidx.window (que exige tocar
 * build.gradle y que ColorOS implemente esa API). No se abre NUNCA la cámara ID0 por
 * esto: la lente sigue siendo la misma.
 */
class CoverMirror(
    private val context: Context,
    private val source: TextureView
) {

    /** Toque sobre la pantalla externa (disparo remoto). */
    var onShutter: (() -> Unit)? = null

    /** Cambió la disponibilidad de la pantalla externa: la UI muestra u oculta el chip. */
    var onAvailabilityChanged: ((Boolean) -> Unit)? = null

    /**
     * "Ahora mismo no copies": lo consulta el bombeo antes de cada lectura.
     * getBitmap() es una lectura SÍNCRONA GPU->CPU que vacía el pipeline de render, así que
     * hacerla justo mientras se dispara (dedo en el obturador, captura en vuelo, apilado de
     * noche) competía con lo único que de verdad importa en ese instante: la foto.
     */
    var isBusy: (() -> Boolean)? = null

    private val displayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val ui = Handler(Looper.getMainLooper())

    private var presentation: MirrorPresentation? = null
    private var frame: Bitmap? = null
    private var lastAvailable = false
    private var running = false

    /** Ancho del fotograma copiado. A 480 px se ve de sobra para encuadrar una cara y
     *  la lectura de GPU sigue siendo barata. */
    private val mirrorWidth = 480

    /**
     * ~6 fps (era 66 ms, ~15 fps).
     *
     * El coste de getBitmap() NO depende de estos 480 px de destino sino de la superficie
     * ORIGEN: el TextureView entero, hasta 2248x3998 px en la pantalla interior. Es una
     * lectura síncrona GPU->CPU que además fuerza un vaciado del pipeline de render, y a
     * 15 Hz eso se comía el visor justo con el espejo encendido: la misma técnica que se
     * acaba de quitar del escáner de códigos por ser "la causa más directa del se siente
     * lenta", pero quince veces por segundo en vez de una.
     *
     * A 6 fps se sigue encuadrando una cara de sobra (el espejo sirve para colocarse en el
     * cuadro, no para juzgar nitidez) y el coste baja a menos de la mitad.
     *
     * Lo que NO se puede hacer, y conviene dejarlo escrito para que nadie lo intente otra
     * vez: sacar la lectura a un HandlerThread con PixelCopy. PixelCopy.request() del SDK
     * 34 solo admite SurfaceView, Surface o Window; no hay ninguna sobrecarga para
     * TextureView, y la Surface que se construye sobre su SurfaceTexture es el lado
     * PRODUCTOR (donde escribe la cámara), del que PixelCopy no puede leer. La única API
     * para sacar píxeles de un TextureView es getBitmap(), y está atada al hilo de UI.
     */
    private val frameDelayMs = 160L

    val isAvailable: Boolean get() = externalDisplay() != null
    val isShowing: Boolean get() = presentation != null

    /** Empieza a vigilar si aparece o desaparece la pantalla externa. */
    fun start() {
        if (running) return
        running = true
        displayManager.registerDisplayListener(displayListener, ui)
        notifyAvailability()
    }

    /** Deja de vigilar y apaga el espejo. Se llama al pausar la Activity. */
    fun stop() {
        hide()
        if (!running) return
        running = false
        try {
            displayManager.unregisterDisplayListener(displayListener)
        } catch (e: Exception) {
        }
    }

    /** Enciende o apaga el espejo. Devuelve el estado en el que queda. */
    fun toggle(): Boolean = if (isShowing) {
        hide()
        false
    } else {
        show()
    }

    /** Devuelve true solo si el espejo llegó a mostrarse de verdad. */
    fun show(): Boolean {
        if (presentation != null) return true
        val d = externalDisplay() ?: return false
        val p = MirrorPresentation(context, d)
        try {
            p.show()
        } catch (e: Exception) {
            // WindowManager.InvalidDisplayException: la pantalla desapareció entre la
            // consulta y el show (pasa al plegar/desplegar justo en ese instante).
            return false
        }
        presentation = p
        ui.removeCallbacks(pump)
        ui.post(pump)
        return true
    }

    fun hide() {
        ui.removeCallbacks(pump)
        presentation?.let { p ->
            // Soltar el bitmap ANTES de cerrar. Y no se recicla: el ImageView de la
            // pantalla externa puede tener todavía un dibujado en vuelo con ese mismo
            // bitmap, y pintar un bitmap reciclado revienta el proceso entero.
            try {
                p.image.setImageDrawable(null)
            } catch (e: Exception) {
            }
            try {
                p.dismiss()
            } catch (e: Exception) {
            }
        }
        presentation = null
        frame = null
    }

    /** Número grande de la cuenta atrás en la pantalla externa (null = ocultar). */
    fun setCountdown(text: String?) {
        val p = presentation ?: return
        p.countdown.text = text ?: ""
        p.countdown.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    fun release() {
        stop()
    }

    // ---- Interior ----

    private fun externalDisplay(): Display? {
        val list = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        for (d in list) {
            if (d.isValid && d.displayId != Display.DEFAULT_DISPLAY) return d
        }
        return null
    }

    private fun notifyAvailability() {
        val now = isAvailable
        if (now == lastAvailable) return
        lastAvailable = now
        onAvailabilityChanged?.invoke(now)
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refresh()
        override fun onDisplayRemoved(displayId: Int) = refresh()
        override fun onDisplayChanged(displayId: Int) = refresh()
    }

    private fun refresh() {
        val d = externalDisplay()
        val shown = presentation
        if (shown != null && (d == null || shown.display.displayId != d.displayId)) {
            // La pantalla en la que estábamos ya no existe (o cambió): sin esto el
            // espejo se quedaba pintando en una pantalla muerta y fugaba el bitmap.
            hide()
            if (d != null) show()
        }
        notifyAvailability()
    }

    /** Bombeo de fotogramas: copia el visor y lo pinta en la pantalla externa. */
    private val pump = object : Runnable {
        override fun run() {
            val p = presentation ?: return
            val w = source.width
            val h = source.height
            // Mientras se dispara NO se copia nada: la lectura de GPU competía con la
            // captura y con el apilado de noche. Saltar el ciclo (y no parar el bombeo)
            // deja el último fotograma en la pantalla externa, que es lo correcto: quien
            // está posando ve congelada la imagen que se acaba de tomar.
            if (isBusy?.invoke() == true) {
                ui.postDelayed(this, frameDelayMs)
                return
            }
            if (w > 0 && h > 0 && source.isAvailable) {
                val th = (mirrorWidth.toFloat() * h / w).toInt().coerceAtLeast(1)
                var b = frame
                if (b == null || b.width != mirrorWidth || b.height != th) {
                    // Se suelta la referencia pero NO se recicla: el bitmap anterior
                    // sigue siendo el que el ImageView tiene puesto y reciclarlo aquí
                    // hacía que el siguiente dibujado matara el proceso.
                    p.image.setImageDrawable(null)
                    b = Bitmap.createBitmap(mirrorWidth, th, Bitmap.Config.ARGB_8888)
                    frame = b
                }
                try {
                    if (source.getBitmap(b) != null) {
                        p.image.setImageBitmap(b)
                        p.image.invalidate()
                    }
                } catch (e: Exception) {
                    // Una lectura fallida no puede matar el bombeo: el visor puede estar
                    // reconstruyendo la sesión justo en ese fotograma.
                }
            }
            ui.postDelayed(this, frameDelayMs)
        }
    }

    private inner class MirrorPresentation(ctx: Context, display: Display) :
        Presentation(ctx, display) {

        lateinit var image: ImageView
        lateinit var countdown: TextView

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContentView(R.layout.presentation_mirror)
            // Dialog.findViewById devuelve nullable: sin la aserción esto no compila
            // contra el SDK 34.
            image = findViewById<ImageView>(R.id.mirror_image)!!
            countdown = findViewById<TextView>(R.id.mirror_countdown)!!
            findViewById<View>(R.id.mirror_root)?.setOnClickListener {
                onShutter?.invoke()
            }
        }
    }
}
