package com.pepe.camaramacro

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.pepe.camaramacro.databinding.ActivitySetupBinding

/**
 * Pantalla de configuración: deja recorrer todas las lentes con vista previa en
 * vivo para que el usuario elija la que funciona (su "modo macro").
 *
 * POR QUÉ COMPRUEBA SI LA LENTE DA IMAGEN: en el Oppo CPH2765 el asistente ofrecía
 * "Lente 2 de 4 · Trasera (normal) · 5.0 mm · ID 2", abría ese sensor y mostraba
 * NEGRO sin decir nada (la ID 2 es la principal de 200 MP averiada: el dumpsys de
 * sus streams dice "Frames produced: 0"). El vigilante del motor no salta porque la
 * sesión SÍ se configura: lo único que no llega son los fotogramas. Si el usuario
 * pulsaba "Usar esta lente" se quedaba con la app abriendo negro para siempre, que
 * es justo lo contrario de para lo que existe esta app.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var controller: Camera2Controller
    private var lenses: List<LensInfo> = emptyList()
    private var index = 0

    /** id de lente -> ¿llegó algún fotograma? Sin entrada = todavía sin comprobar. */
    private val daImagen = HashMap<String, Boolean>()

    private val uiHandler = Handler(Looper.getMainLooper())

    /** Generación de la comprobación en curso: invalida vigilantes de lentes ya abandonadas. */
    private var probeGen = 0
    private var probeWatch: Runnable? = null

    /**
     * ¿Está ya configurada la sesión de la lente ACTUAL? Sin esta puerta, un
     * fotograma de la lente anterior que llegue justo después de pulsar "Siguiente"
     * marcaría como buena la lente muerta a la que acabamos de saltar. Tanto onReady
     * como onFirstFrame llegan por runOnUiThread, o sea en orden FIFO: cualquier
     * fotograma rezagado de la lente vieja se procesa ANTES del onReady nuevo y se
     * descarta aquí.
     */
    private var sesionLista = false

    private val prefs by lazy { getSharedPreferences("camara", MODE_PRIVATE) }

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) ensureInit()
            else Toast.makeText(this, R.string.need_camera_permission, Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        controller = Camera2Controller(this, binding.texture)
        controller.onError = { msg ->
            runOnUiThread {
                // Si el motor falla, la lente tampoco sirve: se marca igual que si no
                // hubiese llegado ningún fotograma y el botón se queda bloqueado.
                cancelarPrueba()
                lenses.getOrNull(index)?.let { daImagen[it.cameraId] = false }
                mostrarLente(ESTADO_SIN_IMAGEN, msg)
            }
        }
        controller.onReady = {
            // La sesión ya está en marcha: a partir de aquí los fotogramas que lleguen
            // son de ESTA lente. Y el reloj de la comprobación se reinicia aquí para no
            // contar como "sin imagen" lo que en realidad es una apertura lenta.
            sesionLista = true
            if (lenses.isNotEmpty()) armarVigilante(SIN_IMAGEN_MS)
        }
        controller.onFirstFrame = {
            if (sesionLista) {
                cancelarPrueba()
                lenses.getOrNull(index)?.let { lens ->
                    daImagen[lens.cameraId] = true
                    mostrarLente(ESTADO_OK)
                }
            }
        }

        // Hasta que se compruebe que la lente entrega imagen, no se puede elegir.
        binding.btnUse.isEnabled = false
        binding.btnPrev.setOnClickListener { switch(-1) }
        binding.btnNext.setOnClickListener { switch(1) }
        binding.btnUse.setOnClickListener { useCurrent() }
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            ensureInit()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onPause() {
        cancelarPrueba()
        sesionLista = false
        controller.close()
        super.onPause()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /**
     * El asistente también tiene vista previa en vivo. Con los configChanges del
     * manifiesto ya no se recrea al plegar (antes, plegar en mitad de la elección
     * volvía a empezar por la primera lente); aquí solo se recoloca el visor, nunca
     * se cierra la lente, porque a mitad de elegir el parpadeo negro era el momento
     * más confuso de toda la app.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        binding.texture.requestLayout()
    }

    private fun ensureInit() {
        if (lenses.isEmpty()) init() else openCurrent()
    }

    private fun init() {
        val all = CameraInfoUtil.listLenses(this)
        // Se quitan dos familias de lentes ANTES de ofrecer nada:
        //  - La ID 0: es la cámara LÓGICA sobre las físicas averiadas y abrirla cuelga
        //    el HAL de ColorOS.
        //  - Las que no declaran BACKWARD_COMPATIBLE: no son cámaras. En este Oppo es
        //    la ID 7, un sensor de profundidad de 1600x1200 que el asistente ofrecía
        //    como si fuese una lente más y solo podía enseñar negro.
        val usables = all.filter { it.cameraId != "0" && esCamaraDeVerdad(it.cameraId) }
        // Los ifEmpty son la red de seguridad: en un teléfono distinto más vale ofrecer
        // una lente de más que dejar al usuario sin ninguna.
        lenses = usables.ifEmpty { all.filter { it.cameraId != "0" } }.ifEmpty { all }
        if (lenses.isEmpty()) {
            binding.lblLens.text = getString(R.string.no_cameras)
            binding.btnUse.isEnabled = false
            return
        }
        index = primeraLenteRazonable()
        openCurrent()
    }

    /**
     * Por dónde empezar. La lista viene en orden de ID, así que "la primera trasera"
     * era exactamente el sensor principal averiado (ID 2 aquí): el asistente arrancaba
     * en negro. Se empieza por la lente ya guardada si sigue existiendo y, si no, por
     * la trasera de focal más corta (el gran angular, la ID 3 en este teléfono, que es
     * la que funciona).
     */
    private fun primeraLenteRazonable(): Int {
        val guardada = prefs.getString("cameraId", null)
        if (guardada != null) {
            val i = lenses.indexOfFirst { it.cameraId == guardada }
            if (i >= 0) return i
        }
        val ancha = lenses.filter { it.facingBack && it.focalLengthMm > 0f }
            .minByOrNull { it.focalLengthMm }
        if (ancha != null) return lenses.indexOfFirst { it.cameraId == ancha.cameraId }
        return lenses.indexOfFirst { it.facingBack }.coerceAtLeast(0)
    }

    /**
     * Una lente sin REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE no puede dar
     * una vista previa normal: es un sensor auxiliar (profundidad / ToF).
     */
    private fun esCamaraDeVerdad(id: String): Boolean = try {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val caps = manager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        caps == null ||
            caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)
    } catch (e: Exception) {
        // Si no se puede consultar, se ofrece: no vamos a esconder una lente buena por
        // no haber podido leer sus características.
        true
    }

    private fun openCurrent() {
        cancelarPrueba()
        sesionLista = false
        controller.close()
        val lens = lenses.getOrNull(index) ?: return
        probeGen++
        // Si ya se comprobó antes en esta misma sesión, se enseña el veredicto conocido
        // mientras se vuelve a abrir: así al ir y volver con Anterior/Siguiente no
        // parpadea "Comprobando" en una lente que ya sabemos buena.
        mostrarLente(
            when (daImagen[lens.cameraId]) {
                true -> ESTADO_OK
                false -> ESTADO_SIN_IMAGEN
                else -> ESTADO_PROBANDO
            }
        )
        controller.open(lens.cameraId)
        // Vigilante largo por si la sesión no llega a configurarse nunca (o si el
        // TextureView todavía no tiene superficie y la apertura se queda esperando);
        // onReady lo vuelve a armar, ya corto, para contar desde que la sesión vive.
        armarVigilante(APERTURA_MS)
    }

    /**
     * Da `ms` a la lente para entregar un fotograma. Si no llega ninguno, se marca SIN
     * IMAGEN y se bloquea "Usar esta lente". El veredicto NO es definitivo a propósito:
     * si el fotograma llega tarde (apertura lenta, teléfono frío), onFirstFrame lo
     * corrige y el botón se desbloquea solo. Mejor un aviso que se retira que dejar
     * elegir un sensor muerto.
     */
    private fun armarVigilante(ms: Long) {
        cancelarPrueba()
        val gen = probeGen
        val lens = lenses.getOrNull(index) ?: return
        val vigilante = Runnable {
            if (gen != probeGen) return@Runnable
            daImagen[lens.cameraId] = false
            mostrarLente(ESTADO_SIN_IMAGEN)
            Log.i("CamMacro", "asistente: la lente ID${lens.cameraId} no entregó ningún fotograma")
        }
        probeWatch = vigilante
        uiHandler.postDelayed(vigilante, ms)
    }

    private fun cancelarPrueba() {
        probeWatch?.let { uiHandler.removeCallbacks(it) }
        probeWatch = null
    }

    private fun mostrarLente(estado: Int, motivo: String? = null) {
        val lens = lenses.getOrNull(index) ?: return
        val pie = when (estado) {
            ESTADO_OK -> "\n✔ Esta lente da imagen: puedes usarla"
            ESTADO_SIN_IMAGEN ->
                "\n⚠ SIN IMAGEN. Este sensor no entrega ningún fotograma (es de los averiados)." +
                    "\nPrueba con Siguiente." + (if (motivo != null) "\n$motivo" else "")
            else -> "\n⏳ Comprobando si da imagen…"
        }
        binding.lblLens.text = "Lente ${index + 1} de ${lenses.size}\n${lens.label}$pie"
        val usable = estado == ESTADO_OK
        binding.btnUse.isEnabled = usable
        // El gris del botón desactivado en el tema oscuro se lee poco sobre el panel;
        // bajando la opacidad se ve de un vistazo que ahora mismo no se puede pulsar.
        binding.btnUse.alpha = if (usable) 1f else 0.4f
    }

    private fun switch(dir: Int) {
        if (lenses.isEmpty()) return
        index = (index + dir + lenses.size) % lenses.size
        openCurrent()
    }

    private fun useCurrent() {
        val lens = lenses.getOrNull(index) ?: return
        if (daImagen[lens.cameraId] != true) {
            // El botón ya está bloqueado; esto es el segundo cerrojo. Guardar una lente
            // muerta deja la app arrancando en negro en todos los arranques siguientes.
            Toast.makeText(this, "Esta lente no da imagen: prueba otra", Toast.LENGTH_SHORT).show()
            return
        }
        // Se anota además como ID YA COMPROBADO. CameraActivity valida el ID guardado contra
        // cameraIdList en su onCreate, y esa llamada al servicio de cámara va por delante de
        // TODO el arranque en frío (inflado, motor y open) desde el hilo de UI. Aquí acabamos
        // de hacer una comprobación bastante más dura que esa —la lente se ha abierto y ha
        // ENTREGADO fotogramas—, así que repetirla en el arranque siguiente es tiempo regalado
        // en el bloque de VELOCIDAD. Si la lente fallara de verdad, CameraActivity borra la
        // anotación en onError y el arranque de después vuelve a validar.
        prefs.edit()
            .putString("cameraId", lens.cameraId)
            .putString("cameraIdValidado", lens.cameraId)
            .apply()
        Toast.makeText(this, R.string.lens_saved, Toast.LENGTH_SHORT).show()
        // CLEAR_TOP | SINGLE_TOP: REUTILIZA la CameraActivity que ya está en la pila en vez de
        // apilar una segunda. Al asistente ya no se llega solo en el arranque en frío: la fila
        // «Elegir otra lente» de Ajustes también trae aquí, y Ajustes se abrió DESDE
        // CameraActivity, que sigue viva debajo (se lanzó con settingsLauncher, no con
        // finish()). Sin estas banderas quedaban DOS CameraActivity apiladas: tras cambiar de
        // lente, el botón Atrás no salía de la app, devolvía a una cámara rancia que además
        // reabría la lente en su onResume.
        // Inocuo en el arranque en frío: ahí CameraActivity ya se cerró sola antes de venir y
        // no hay nada que limpiar. Y en la ruta nueva la instancia viva se entera por
        // onNewIntent, que hace disarmIntentCapture(); startCamera() relee prefs("cameraId")
        // en CADA onResume, así que la lente recién elegida sí se aplica.
        startActivity(
            Intent(this, CameraActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    companion object {
        private const val ESTADO_PROBANDO = 0
        private const val ESTADO_OK = 1
        private const val ESTADO_SIN_IMAGEN = 2

        /**
         * Margen para el primer fotograma desde que la sesión está configurada. La ID 2
         * averiada no entrega ninguno NUNCA; una lente sana entrega el primero en pocas
         * decenas de milisegundos, así que 1,5 s es holgadísimo sin hacerse esperar.
         */
        private const val SIN_IMAGEN_MS = 1500L

        /**
         * Margen desde que se pide abrir hasta que la sesión existe. Es más largo porque
         * incluye esperar a que el TextureView tenga superficie y a que el HAL abra la
         * lente: con 1,5 s aquí, un arranque en frío enseñaba "SIN IMAGEN" un segundo en
         * una lente perfectamente sana. Queda justo por debajo del vigilante de 5 s del
         * motor, que además avisa por onError si la sesión no llega a configurarse.
         */
        private const val APERTURA_MS = 4000L
    }
}
