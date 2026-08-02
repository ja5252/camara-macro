# Plan completo de arreglos — auditoria y jurado, ronda 6

Generado por el consejo tras el jurado ciego de la ronda 6.
Marcador: **Hasselblad (ALFA) 90 — nuestra app (BETA) 23**, 8 empates.

## Notas por juez

| Juez | Hasselblad | Nosotros |
|---|---|---|
| Perito en calidad de imagen (nitidez, resolucion efectiva, color, exposicion, rango dinami | 6.5 | 5.4 |
| Director de diseño de cámaras | 6.3 | 4.3 |
| Ingeniero de rendimiento (arranque, latencia de obturador, fluidez del visor, coste del es | 6.8 | 4.4 |
| Product Manager | 7.2 | 4.2 |
| Ingeniero de captura (enfoque, zoom, cambio de lentes, congelación del movimiento, estabil | 6.7 | 5.7 |
| Jurado ciego #P | 6.8 | 5.2 |

## Veredicto del presidente del jurado

La app funciona y su idea central (abrir directo una lente que sirve en un telefono con la principal rota) sigue siendo su mejor activo, pero la version actual pierde en las tres dimensiones que se han juzgado: 5,4 en imagen, 4,3 en diseno y 4,4 en rendimiento frente a un ALFA que ronda el 6,5-7. El plan tiene 152 arreglos y, leidos juntos, cuentan una historia clara: hay cuatro causas raiz y casi todo lo demas es sintoma. (1) La maquina de estados de captura esta rota: el obturador puede quedarse muerto para siempre, la espera de AF se resuelve ANTES de que empiece el barrido -por eso hay fotos blandas- y la pre-captura del AE se da por buena al instante, que es el origen de la historia de bugs de flash. Son dos criticos y dos altos que se arreglan en horas y que valen mas que cualquier funcion nueva. (2) El HUD esta anclado a la PANTALLA en vez de al VISOR: un FrameLayout de 28 hijos con margenes literales que produce cinco elementos en la misma cota, paneles que se tapan, cuadricula desalineada hasta 289 px, cronometro mordido y pastillas cortadas. Reestructurarlo en preview_frame / top_bar / control_band elimina de golpe una docena de hallazgos visuales. (3) El plegable no esta soportado: la Activity se recrea al abrir el telefono, no hay layout-sw600dp, el visor recorta 42% SOLO por abajo y la foto no recorta nada -lo que se encuadra no es lo que se guarda- y no existe el espejo en la pantalla externa, que es literalmente lo que el usuario pidio. (4) El modo noche no es un modo noche: nunca alarga la exposicion (1/120 s a ISO 1503 por fotograma, heredado del fps range del visor), apila en 8 bits ya gamma-codificados y alinea con +-6 px enteros, asi que MIDE PEOR ruido que un disparo normal de la propia app. A eso se suma que todo lo que se ha medido esta hecho sobre un APK de DEBUG, porque el workflow publica assembleDebug: los 539/572 ms de arranque son del peor binario posible. Orden recomendado: primero los seis bugs de captura y concurrencia (dias, no semanas, y devuelven fiabilidad), en paralelo el cambio a assembleRelease con R8 y baseline profile; despues la reestructuracion del layout con los iconos vectoriales y el contraste, que es donde esta la mayor subida de nota por esfuerzo; luego el plegable (configChanges, gravity del cover, recorte coherente del JPEG y layout-sw600dp); y solo entonces el modo noche reconstruido y las funciones que faltan (enfoque manual en la UI, histograma/cebras/peaking, ajustes reales, geoetiquetado, sonido de obturador). He marcado 14 puntos como no realistas o dependientes del HAL: el AF de la ID0 es hardware muerto, y el ZSL por reprocesado, la camara lenta, Ultra HDR+RAW simultaneos y los 60 fps de visor hay que comprobarlos en el CPH2765 antes de prometerlos. 88 de los 152 arreglos necesitan el telefono conectado por ADB para verificarse: sin dispositivo se puede avanzar en layout, metadatos, higiene de build e i18n, pero nada de imagen ni de captura debe darse por bueno sin medirlo.

## Indice — 172 arreglos

- **IMAGEN** — 24
- **ENFOQUE Y CAPTURA** — 18
- **RENDIMIENTO** — 21
- **INTERFAZ** — 35
- **PLEGABLE** — 13
- **FUNCIONES QUE FALTAN** — 17
- **INTEGRACION CON EL SISTEMA** — 13
- **ROBUSTEZ Y BUGS** — 24
- **ACCESIBILIDAD** — 7

---

## IMAGEN

### 1. El modo noche no recoge mas luz que una foto normal

`Camera2Controller.kt` · coste: horas · riesgo: medio · necesita el telefono

El EXIF de una foto nocturna da 1/120 s a ISO 1503 por fotograma: se hereda lastAeExpNs, que viene acotado por el CONTROL_AE_TARGET_FPS_RANGE [30,30] del visor, asi que el tope de 125.000.000 ns (1/8 s) del codigo nunca se alcanza. Antes de lanzar la rafaga, medir la escena con una peticion de sondeo SIN el fps range del preview (o calcular el EV objetivo desde lastAeIso y lastAeExpNs) y fijar manualmente el par exposicion/ISO que lleve cada fotograma al tope: 1/8 s a ISO 190 recoge la misma luz que 1/120 s a ISO 1503 con ~8x menos ruido de lectura. Elegir el tiempo por giroscopio y exponer un presupuesto visible (1 s / 2 s / 4 s / tripode).

### 2. Efecto acuarela: resolucion efectiva de ~3 MP sobre 12,58 MP nominales

`Camera2Controller.kt` · coste: dias · riesgo: alto · necesita el telefono

El espectro se aplana en un suelo de ruido por encima de 0,25 cyc/px y la acutancia es 1,12-1,38 uniforme en todo el encuadre (alfombra sin fibras, sofa con manchones). 1) Registrar en cada foto result.get(NOISE_REDUCTION_MODE) y EDGE_MODE del CaptureResult y avisar si el HAL devuelve HIGH_QUALITY pese a pedir MINIMAL. 2) Si ColorOS lo ignora, dejar de depender del JPEG del HAL: capturar YUV_420_888 o RAW_SENSOR y aplicar denoise propio conservador. 3) Subir el umbral de MINIMAL de ISO 800 a ISO 1600. 4) Anadir un modo 'Detalle maximo' con NOISE_REDUCTION_MODE_OFF.

### 3. El apilado nocturno promedia en 8 bits ya gamma-codificados

`NightStacker.kt` · coste: horas · riesgo: medio · necesita el telefono

accY es un ShortArray de luma comprimida 0-255: promediar en gamma sesga la media hacia las luces y el suelo de cuantizacion limita la ganancia de SNR. Medido: sigma de ruido 9,52 en pared plana, PEOR que un solo disparo de la propia app (1,28 a ISO 306). Aplicar una LUT de de-gamma (sRGB inversa) de 256 entradas al entrar en el acumulador y la gamma directa al salir en result(); acumular y dividir en 16 bits y aplicar la curva de tono ANTES de cuantizar a 8 bits.

### 4. Alineacion nocturna: solo traslacion global ENTERA de +-6 px

`NightStacker.kt` · coste: dias · riesgo: alto · necesita el telefono

SEARCH=6 sin rotacion, sin sub-pixel y sin movimiento local. A pulso con 12,6 MP y 7 fotogramas el temblor supera esos 6 px, asi que el apilado emborrona (caida espectral x12 entre 0,05 y 0,10 cyc/px frente a x2,3 en diurna). Sustituir estimateShift por busqueda piramidal grueso-a-fino (niveles 1/8, 1/4, 1/2; equivale a +-48 px al coste actual) con refinamiento sub-pixel por interpolacion parabolica del SAD, y anadir alineacion por bloques de 256x256 px con muestreo bilineal para absorber rotacion y paralaje. Descartar el fotograma cuyo SAD residual supere un umbral en vez de apilarlo.

### 5. Rechazo de fantasmas destructivo y sin croma

`NightStacker.kt` · coste: horas · riesgo: medio · necesita el telefono

GHOST_THRESH=30 compara siempre contra el PRIMER fotograma y DESCARTA el pixel (continue), de modo que sobre un sujeto movil cntY puede quedarse en 1 y esa zona no recibe ningun promediado. Ademas accU/accV no tienen rechazo alguno: siempre acumulan, produciendo arrastre de color. 1) Comparar contra la media acumulada, no contra refY. 2) Sigma-clip ponderado (peso decreciente con la desviacion) en vez de umbral duro. 3) Guardar la mascara de luma y aplicarla submuestreada /2 a U y V. 4) Si un pixel acaba con cuenta < 3, marcarlo y suavizarlo con sus vecinos.

### 6. TARGET_MEDIAN = 118 fijo: la noche deja de parecer noche

`NightStacker.kt` · coste: horas · riesgo: bajo · necesita el telefono

Cualquier escena se normaliza a gris medio con ganancia de hasta 3,5x, amplificando ruido y cuantizacion; ademas la curva y = g*x/(1+(g-1)*x) comprime brutalmente el tramo alto (p99 = 202, p99,9 por canal 226/236/243: imagen plana y lechosa sin blancos reales). Hacer el objetivo dependiente de la escena: target = clamp(medianaOriginal * k, minimo, 118) con k ~2,0-2,5, o derivarlo del BrightnessValue medido. Anadir un hombro en S y exponer un control 'ambiente nocturno' 0-100%.

### 7. Halo de realce de +40 niveles al borde de los objetos oscuros

`Camera2Controller.kt` · coste: horas · riesgo: medio · necesita el telefono

Medido +40 niveles (+35% sobre la base local) en 1-2 px al borde de los cables negros y +24 al salir. Se pide EDGE_MODE_FAST sin comprobar nunca que el HAL lo aplique, y el halo convive con el empastado: patron 'emborrono y luego sobre-realzo'. Probar EDGE_MODE_OFF y EDGE_MODE_ZERO_SHUTTER_LAG (si estan en edgeAvailable), medir el perfil de borde en las tres opciones sobre la misma escena de tripode y quedarse con la que deje overshoot < 10 niveles. Si el HAL solo ofrece FAST/HIGH_QUALITY, aplicar unsharp mask propio con radio 1,0 y cantidad <= 30%.

### 8. Las luces nunca llegan al blanco: imagenes lavadas

`NightStacker.kt` · coste: horas · riesgo: bajo · necesita el telefono

Maximo absoluto 250/250/248 y p99 = 201 en una foto diurna; la nocturna se queda en p99,9 de 226/236/243. Normalizar el punto blanco al final del pipeline: llevar el percentil 99,5 a 250-252 con un estiramiento lineal antes de codificar. En el modo noche aplicarlo DESPUES de la curva de Reinhard, no antes. Test: exigir p99,5 >= 240 en escenas con un blanco presente.

### 9. Recorte de negros: 0,527% de pixeles en Y<=1

`Camera2Controller.kt` · coste: horas · riesgo: medio · necesita el telefono

~646.000 pixeles en negro absoluto y 0,814% en Y<=3: el pie de la curva de tono aplasta las sombras a cero. Fijar una TonemapCurve propia con TONEMAP_MODE_CONTRAST_CURVE (comprobando antes TONEMAP_AVAILABLE_TONE_MAP_MODES y TONEMAP_MAX_CURVE_POINTS) con el pie levantado, la MISMA curva en los tres canales y anclada en (0,0) y (1,1). Si el HAL la ignora, aplicarla sobre el buffer.

### 10. applyDetailModes decide con el ISO del VISOR, no con el de la foto

`Camera2Controller.kt` · coste: minutos · riesgo: bajo · sin dispositivo

Se eligen NOISE_REDUCTION y EDGE_MODE segun lastAeIso (ISO del preview) mientras applyShutterFloor puede subir el ISO de esa misma captura hasta 3200: se pide reduccion de ruido MINIMAL para fotos que acabaran a ISO alto y al reves. Calcular primero el ISO efectivo que devuelve applyShutterFloor y decidir NR/EDGE con ese valor.

### 11. El visor y la foto usan procesado distinto (no hay WYSIWYG)

`Camera2Controller.kt` · coste: minutos · riesgo: bajo · necesita el telefono

applyDetailModes() se aplica a la peticion de foto (linea 544) pero NO a la del preview (linea 1984): la reduccion de ruido y el realce que se ven no son los que se guardan. Aplicarlo tambien al previewRequestBuilder con la variante FAST (barata) y refrescar la peticion repetida cuando lastAeIso cruce los umbrales de 800 y 2000. Alternativa minima: marca discreta en la interfaz cuando el modo de la foto difiera del del visor.

### 12. Exposicion desigual entre lentes fisicas (2 EV de diferencia)

`Camera2Controller.kt` · coste: horas · riesgo: bajo · necesita el telefono

Con BrightnessValue casi identico (6,85 gran angular frente a 6,19 tele, 0,66 EV) el tele expone ~2 EV mas y usa ISO 628 donde el gran angular uso ISO 100; su salida queda con mediana Y 170 y histograma aplastado (p1=52, p99=211) frente a 131 / (5, 234). Calibrar un offset de exposicion por ID fisico: fotografiar una carta gris al 18% con cada lente en la misma luz, almacenar la diferencia en EV y aplicarla via CONTROL_AE_EXPOSURE_COMPENSATION al cambiar de lente. Test: disparar las dos lentes seguidas y fallar si la mediana difiere mas del 10%.

### 13. Doble y triple compresion JPEG en las rutas de noche, filtro y recorte

`Camera2Controller.kt` · coste: horas · riesgo: medio · necesita el telefono

YuvImage comprime a 95, rotateJpeg decodifica y recomprime a 95, applyColorFilter y cropFullJpeg vuelven a decodificar y recomprimir. Perdida generacional sobre una imagen que ya venia de un apilado, mas segundos de CPU y ~50 MB de ARGB por decode. Aplicar rotacion (copia con indices permutados sobre el plano YUV) y matriz de color ANTES de la unica llamada a compressToJpeg, y encadenar recorte+filtro sobre UN solo Bitmap con una sola compresion final a calidad >= 97.

### 14. La calidad JPEG no se controla: se hereda la del HAL

`Camera2Controller.kt` · coste: minutos · riesgo: bajo · sin dispositivo

Nunca se fija CaptureRequest.JPEG_QUALITY. En este dispositivo salio calidad IJG 98 (suma de tabla de luma 151), excelente pero no determinista: otro HAL podria dar 90 y perder detalle sin que nadie se entere. Fijarlo explicitamente (95-98), registrar en el log la tabla de cuantizacion real del archivo guardado como comprobacion y ofrecer un ajuste 'calidad maxima / equilibrada' (a q98 los archivos pesan 7-11 MB).

### 15. Los JPEG no llevan perfil ICC ni XMP

`Camera2Controller.kt` · coste: horas · riesgo: bajo · sin dispositivo

Comprobado: no hay APP2 ICC_PROFILE en ninguno de los cuatro archivos analizados, solo EXIF ColorSpace=1, y tampoco hay XMP. El color queda a merced del visor y se cierra la puerta a cualquier salida en gama amplia. Insertar un APP2 con el perfil ICC sRGB v4 (~3 KB) justo despues del APP1 de EXIF antes de guardar, y un bloque XMP con modelo, lente fisica usada (ID + focal real) y modo de captura, que ademas resuelve la trazabilidad.

### 16. Los JPEG arrastran 264 KB de basura del HAL

`Camera2Controller.kt` · coste: horas · riesgo: bajo · sin dispositivo

Una de las fotos lleva CINCO segmentos APP4 'QTI Debug Metadata' de ~264 KB en total copiados tal cual al archivo del usuario (~3% del peso, cero utilidad); la foto de noche no los tiene, lo que confirma que solo pasan por la ruta directa del HAL. Antes de guardar, recorrer los marcadores del JPEG y eliminar todos los APP3-APP15 desconocidos conservando APP0 (JFIF), APP1 (EXIF/XMP) y APP2 (ICC/MPF). Filtro de ~30 lineas sin recodificar, asi que no hay perdida de calidad.

### 17. Metadatos EXIF que mienten o faltan

`Camera2Controller.kt` · coste: horas · riesgo: bajo · sin dispositivo

La foto de noche declara Flash=9 ('flash disparado, modo obligatorio') en un apilado de 7 fotogramas, y las otras declaran Flash=16. Faltan Software, LensModel, SubjectDistance, DigitalZoomRatio y ExposureProgram. En writeNightExif escribir el estado real del flash (0 o 16), Software con nombre+version, LensModel con el ID fisico y la focal, SubjectDistance desde LENS_FOCUS_DISTANCE del CaptureResult, DigitalZoomRatio, y TAG_USER_COMMENT con el numero de fotogramas y la exposicion total equivalente para que el archivo no mienta sobre como se hizo.

### 18. Orientacion EXIF incoherente entre las cuatro rutas de guardado

`Camera2Controller.kt` · coste: horas · riesgo: medio · necesita el telefono

Las fotos normales salen con Orientation=1 y pixeles rotados; el modo noche sale con Orientation=6 y buffer sin rotar, asi que se ve tumbado en cualquier visor que ignore el EXIF. Elegir UNA politica y aplicarla igual en las cuatro rutas. Recomendado: escribir siempre la etiqueta EXIF y no rotar pixeles nunca (evita la recompresion de rotateJpeg). Anadir un test de humo que compare el EXIF de una foto normal y una de noche.

### 19. La deteccion de Ultra HDR exige un tamano identico en JPEG_R

`Camera2Controller.kt` · coste: horas · riesgo: medio · necesita el telefono

hdrSupported exige que JPEG_R ofrezca EXACTAMENTE el mismo tamano que el JPEG elegido por pickJpegSize; como este depende de aspect y fullRes, al pasar a MED o a 1:1/16:9 ese tamano casi nunca esta en la lista JPEG_R: Ultra HDR se declara no soportado o, peor, hdrEnabled sigue true mientras stillFormat cae a JPEG y la foto se guarda sin mapa de ganancia con el chip HDR encendido. Aplicar pickJpegSize sobre map.getOutputSizes(ImageFormat.JPEG_R) cuando HDR esta activo, y llamar a onHdrUnavailable si tras el calculo queda en false.

### 20. Ultra HDR no es auditable y es excluyente con RAW

`Camera2Controller.kt` · coste: horas · riesgo: medio · necesita el telefono

Ninguna de las cuatro muestras entregadas prueba salida Ultra HDR: no hay gain map, ni segmento MPF, ni XMP. Comprobar con StreamConfigurationMap si el HAL admite JPEG_R y RAW_SENSOR en la misma sesion y, si lo admite, anadir ambos targets a la misma CaptureRequest. Adjuntar a la entrega una foto Ultra HDR verificada (comprobando el APP2 MPF y el gain map con libultrahdr).

### 21. El modo noche desatura: la ganancia solo toca la luma

`NightStacker.kt` · coste: horas · riesgo: bajo · necesita el telefono

La curva LUT se aplica a out[luma] pero el croma se copia sin escalar, asi que al levantar el brillo 3,5x el color se lava. Escalar el croma con un factor de saturacion acoplado a la ganancia de luma, o convertir a RGB lineal, ganar y volver a YUV.

### 22. La resolucion del modo noche depende en silencio de la memoria

`Camera2Controller.kt` · coste: minutos · riesgo: bajo · sin dispositivo

nightSize se elige con el filtro width*height*11 <= budget y cae a previewSize (1920x1080 por defecto) si no cabe. En el dispositivo de prueba salio 4096x3072, pero el usuario nunca sabe a que resolucion dispara. Mostrar la resolucion efectiva al activar el modo ('Noche - 12,6 MP' / 'Noche - 2,1 MP'), registrarla en el EXIF y, si cae por debajo de la mitad de la nominal, avisar y ofrecer apilar menos fotogramas.

### 23. El balance de blancos en Kelvin es una interpolacion inventada

`Camera2Controller.kt` · coste: horas · riesgo: medio · necesita el telefono

kelvinToRggb interpola linealmente r de 1,0 a 2,4 y b de 2,2 a 1,0: los valores mostrados no corresponden a temperaturas reales, asi que 5000 K no da gris neutro. Calcular las ganancias desde el locus planckiano y COLOR_CORRECTION_TRANSFORM, o calibrar interpolando entre las ganancias que el propio AWB reporta en tres escenas de referencia. Anadir eje de tinte verde-magenta y un cuentagotas de gris.

### 24. No hay tests de regresion de calidad de imagen

`Camera2Controller.kt` · coste: horas · riesgo: bajo · necesita el telefono

Publicar como suite de regresion las medidas que uso el jurado: energia espectral > 1e-8 a 0,45 cyc/px, overshoot de borde < 10 niveles, p99,5 >= 240 con blanco presente, pixeles en Y<=1 por debajo del 0,1%, y diferencia de mediana entre lentes < 10%. Un script que corra sobre las fotos descargadas por ADB tras cada release.

---

## ENFOQUE Y CAPTURA

### 25. CRITICO: el obturador se queda muerto para siempre

`Camera2Controller.kt` · coste: horas · riesgo: medio · necesita el telefono

Durante la espera de AF/AE (hasta 400 + 900 ms) el callback onResult vive SOLO dentro de la lambda 'go' y de afWaitTimeout; pendingResult todavia es null. Si en esa ventana entra abortPendingCapture() -- lo llaman close() (onPause), onDisconnected (la ruta MAS comun en ColorOS), onError, fail() y switchToLens -- se lee pendingResult (null, no invoca nada) y clearAfAeWaits borra la espera: onResult NUNCA se llama, 'capturing' nunca vuelve a false y el boton de disparo queda inservible hasta recrear la Activity. Anadir '@Volatile private var pendingShutter: ((Boolean)->Unit)? = null', asignarlo al principio de takePhoto/takeNightPhoto, consumirlo en captureStillNow al asignar pendingResult, e invocarlo con false en abortPendingCapture y en clearAfAeWaits.

### 26. CRITICO: la espera de AF se resuelve antes de que empiece el barrido

`Camera2Controller.kt` · coste: horas · riesgo: medio · necesita el telefono

El mapeo colapsa PASSIVE_FOCUSED->FOCUSED y PASSIVE_UNFOCUSED->NOT_FOCUSED, y la espera acepta cualquiera de los dos. Tras AF_TRIGGER_START el HAL tarda 1-3 fotogramas en pasar a ACTIVE_SCAN, asi que el primer resultado con frameNumber >= afTriggerFrame trae aun el estado PASIVO previo y se dispara sin haber enfocado: es la causa viva de las fotos blandas, y la puerta por numero de fotograma no lo evita. Separar el estado crudo del mapeado para la UI: en la espera aceptar SOLO CONTROL_AF_STATE_FOCUSED_LOCKED o NOT_FOCUSED_LOCKED (que solo aparecen tras un trigger activo), exigiendo opcionalmente haber visto antes ACTIVE_SCAN.

### 27. La pre-captura del AE se da por buena al instante

`Camera2Controller.kt` · coste: horas · riesgo: medio · necesita el telefono

Justo despues de AE_PRECAPTURE_TRIGGER_START el HAL sigue reportando CONVERGED durante 1-2 fotogramas antes de entrar en PRECAPTURE; la condicion acepta CONVERGED (y hasta ae == null) en el primer resultado valido, asi que se captura con la exposicion vieja y sin flash cargado. Es el origen de la historia de bugs de flash. Maquina de dos fases: marcar seenPrecapture cuando llegue CONTROL_AE_STATE_PRECAPTURE y solo entonces aceptar CONVERGED/FLASH_REQUIRED. Quitar 'ae == null' como condicion de exito y dejar que ahi mande el timeout de 900 ms.

### 28. El enfoque se queda clavado tras un timeout o una excepcion

`Camera2Controller.kt` · coste: horas · riesgo: medio · necesita el telefono

unlockFocusAfterShot() solo se llama en la ruta feliz y en onCaptureFailed. Si salta CAPTURE_TIMEOUT_MS o si acquireNextImage/saveImage lanzan, se libera el obturador pero NO se manda AF_TRIGGER_CANCEL: el AF queda en FOCUSED_LOCKED, lastFocusState sigue valiendo FOCUSED y TODAS las fotos siguientes salen a la distancia de aquella. Extraer un finishShot(ok: Boolean) que centralice cancelar el watchdog + desbloquear AF + invocar el callback, y usarlo en las cuatro salidas (exito, fallo, timeout y catch).

### 29. La medicion con flash da ISO 21280

`Camera2Controller.kt` · coste: horas · riesgo: medio · necesita el telefono

El EXIF de dos capturas con destello da ISO 21280 a 1/20 s: el AE mide el ambiente y luego se dispara el flash, con ruido extremo y primer plano quemado. Tras la pre-captura, bloquear CONTROL_AE_LOCK con la ganancia medida durante el pre-flash, o imponer un techo de ISO (1600) y un obturador de sincronizacion (1/60-1/125) cuando fireFlash sea true. Anadir reduccion de ojos rojos y control de intensidad con turnOnTorchWithStrengthLevel (API 33).

### 30. Tocar para enfocar y disparar acto seguido captura a medio barrido

`Camera2Controller.kt` · coste: minutos · riesgo: medio · necesita el telefono

setFocusPoint pone afLocked = true y lanza AF_TRIGGER_START, pero la condicion needsAf de takePhoto excluye explicitamente !afLocked: el gesto natural (tocar y disparar) no espera nada y captura en pleno ACTIVE_SCAN. Cambiar la condicion a que dependa del estado real: esperar cuando afAvailable && !manualFocus && lastFocusState != FOCUSED, y en el caso afLocked suscribirse a la convergencia SIN reenviar AF_TRIGGER_START para no cancelar el barrido dirigido al punto tocado.

### 31. La puerta por numero de fotograma se arma al reves y nunca se rearma

`Camera2Controller.kt` · coste: minutos · riesgo: bajo · sin dispositivo

Se asigna afWaitAction = go ANTES de afTriggerFrame = Long.MAX_VALUE, asi que en esa ventana la puerta guarda todavia el numero de fotograma del disparo ANTERIOR y un resultado del visor puede atravesarla. Ademas 'go' no devuelve afTriggerFrame/aeTriggerFrame a Long.MAX_VALUE al ejecutarse, por lo que el valor viejo persiste hasta el siguiente disparo. Invertir el orden en ambos metodos y reponer los dos campos a Long.MAX_VALUE dentro de las lambdas.

### 32. La rafaga no es una rafaga: 2-3 fps con caza de foco entre tomas

`CameraActivity.kt` · coste: dias · riesgo: medio · necesita el telefono

burstNext() encadena takePhoto() de uno en uno con 60 ms de espera, y como unlockFocusAfterShot pone lastFocusState=null tras cada foto, cada fotograma vuelve a lanzar y esperar un barrido de AF; ademas se llama a refreshThumbnail (consulta a MediaStore en hilo principal) por cada frame y el imageReader tiene maxImages=2. Usar session.captureBurst() con una lista de CaptureRequest, bloqueando AE/AWB/AF durante toda la secuencia (un solo enfoque al principio), subir maxImages a 5-8, drenar los buffers en un Executor de E-S, actualizar la miniatura UNA vez al soltar desde el bitmap en memoria y mostrar contador de disparos. Objetivo medible: >= 10 fps sostenidos.

### 33. El enfoque manual existe en el motor pero no en la interfaz

`CameraActivity.kt` · coste: horas · riesgo: bajo · necesita el telefono

setManualFocusDistance() y hasManualFocus estan implementados pero ningun control los llama; el panel PRO solo tiene EV/ISO/VEL/WB/K/AUTO. En una app cuyo proposito declarado es macro es la carencia mas grave. Anadir un chip 'MF' que abra un slider de dioptrias de 0 a minFocusDiopters, con lectura de distancia en cm, activacion automatica de la lupa mientras se arrastra y doble toque para volver a AF continuo. Recordar el valor entre sesiones.

### 34. No hay zero shutter lag

`Camera2Controller.kt` · coste: dias · riesgo: alto · necesita el telefono

Cada disparo encadena espera de AF (400 ms) + pre-captura de AE (900 ms) + captura still; con flash se superan 1,3 s desde la pulsacion y se pierde el momento. Mantener un ImageReader YUV/PRIVATE en anillo alimentado por el stream de preview con timestamps y, al pulsar, elegir el fotograma mas cercano a la pulsacion (o el mas nitido por varianza del laplaciano). Si REQUEST_AVAILABLE_CAPABILITIES declara PRIVATE/YUV_REPROCESSING, usar createReprocessableCaptureSession. Mitigacion barata: precalentar el AF en el ACTION_DOWN del obturador. Anadir un conmutador 'Rapido / Calidad'.

### 35. No hay deteccion de caras ni de ojos

`Camera2Controller.kt` · coste: horas · riesgo: bajo · necesita el telefono

STATISTICS_FACE_DETECT_MODE no se usa en ningun punto del controlador, asi que para fotos de personas -el caso mas comun- el AF/AE va a ciegas. Activarlo (FULL o SIMPLE segun lo que declare el HAL), dibujar los recuadros en el visor y priorizar AF_REGIONS/AE_REGIONS sobre la cara mas grande cuando el usuario no ha tocado la pantalla.

### 36. El watchdog de noche aborta antes de terminar a resolucion completa

`Camera2Controller.kt` · coste: horas · riesgo: medio · necesita el telefono

El watchdog es de 8 s fijos, pero desde el cambio a nightSize maximo estimateShift recorre 13x13 desplazamientos sobre una rejilla de paso 8 (~30 M operaciones por fotograma) mas el bucle de acumulacion, x7 fotogramas, todo en Kotlin y en el backgroundHandler de la camara: se pasa facil de 8 s y se pierde la foto. Subir el watchdog a algo proporcional (4 s + 2 s por fotograma), sacar el apilado a un HandlerThread propio distinto del de la camara y mostrar progreso real (fotograma k de 7) con cancelacion.

### 37. El toque fija AE y AF a la misma region

`Camera2Controller.kt` · coste: horas · riesgo: bajo · necesita el telefono

setFocusPoint aplica el mismo MeteringRectangle a CONTROL_AE_REGIONS y CONTROL_AF_REGIONS: no se puede enfocar en un punto y medir en otro, que es justo lo que el rival solo ofrece en su modo Master. Dos marcadores arrastrables independientes (circulo = enfoque, cuadrado = medicion) con candados separados en TODOS los modos, y tamano de AE_REGIONS conmutable para medicion puntual / ponderada / matricial.

### 38. El piso de obturacion esta fijo en codigo y no se expone

`CameraActivity.kt` · coste: horas · riesgo: bajo · necesita el telefono

El piso de 1/60 s esta hardcodeado, setShutterFloorNs nunca se llama desde la UI y se desactiva por completo con flash auto/on. Anadir un chip 'ACCION' con pisos seleccionables (1/60, 1/125, 1/250, 1/500) y techo de ISO configurable, elevar el piso automaticamente si el giroscopio detecta movimiento y mantener un piso de sincronizacion razonable tambien con flash.

### 39. Voltear a la camara frontal produce un negro largo

`CameraActivity.kt` · coste: horas · riesgo: medio · necesita el telefono

flipCamera() hace controller.close() + open() completo -que ademas detiene el hilo de fondo y el listener de orientacion- sin el fotograma congelado que si se usa al cambiar de lente trasera. Reutilizar onLensSwitching/freezeForLensSwitch en flipCamera() y sustituir close() por un cierre parcial que conserve el HandlerThread y el OrientationEventListener entre lentes.

### 40. El fotograma congelado no escala al nuevo campo de vision

`CameraActivity.kt` · coste: horas · riesgo: medio · necesita el telefono

La transicion al cambiar de lente da un salto de encuadre porque el congelado se muestra al tamano de la lente anterior. Animar la escala del ImageView congelado con la razon de focales entre lente origen y destino durante el crossfade, y si getConcurrentCameraIds lo permite, abrir la lente destino antes de cruzar el umbral de zoom. Ademas, retirar el overlay con el PRIMER onCaptureCompleted real de la nueva sesion en vez del postDelayed ciego de 120 ms.

### 41. El zoom digital esta limitado a 4x por una constante inventada

`Camera2Controller.kt` · coste: minutos · riesgo: bajo · necesita el telefono

maxZoomRatio = zoomChain.last * 4f en vez de leer SCALER_AVAILABLE_MAX_DIGITAL_ZOOM real del HAL. Leer el maximo real por lente y ofrecerlo, marcando visualmente a partir de que punto es recorte digital (pastillas por encima del optico en gris) para no enganar sobre la calidad.

### 42. El juego de paradas de zoom cambia entre sesiones

`CameraActivity.kt` · coste: horas · riesgo: bajo · necesita el telefono

En una captura las paradas son 0.6/1/2/2.9/5 y en otra 1/4.6: se regeneran con reglas distintas segun el modo de entrada. Fijar el juego de paradas de forma determinista a partir de la cadena de lentes (una sola funcion, un solo criterio) y no regenerarlo nunca segun el estado transitorio.

---

## RENDIMIENTO

### 43. El APK que se publica y que el usuario mide es el de DEBUG

`build.yml` · coste: horas · riesgo: medio · necesita el telefono

El workflow ejecuta 'gradle assembleDebug' y sube app-debug.apk a Releases; ademas el buildType release tiene minifyEnabled false. Un binario debuggable no recibe compilacion AOT por perfil, no pasa por R8 y arrastra dex sin optimizar: los 539/572 ms de arranque y toda la sensacion de lentitud estan medidos sobre el peor binario posible. Cambiar a assembleRelease, activar minifyEnabled + shrinkResources con reglas proguard para ML Kit y Coil, anadir androidx.profileinstaller y generar un Baseline Profile del arranque (MAIN -> primer frame de visor). Volver a medir y publicar la comparativa.

### 44. El escaneo de codigos bloquea el hilo principal una vez por segundo

`CameraActivity.kt` · coste: horas · riesgo: medio · necesita el telefono

autoScanTick llama a TextureView.getBitmap() cada 1100 ms en el hilo de UI: una lectura sincrona GPU->CPU cuyo coste escala con la superficie ORIGEN (no con el destino de 360 px) y que fuerza flush del pipeline de render. Es la causa tecnica mas directa del 'se siente lenta' en la pantalla grande. Corre siempre: en PRO, en video parado y durante una captura pedida por otra app. Eliminarlo y usar el pipeline YUV que ya existe en el controlador (qrReader + InputImage.fromMediaImage, sin copia ni readback), procesando 1 de cada N frames en un Executor propio, con chip QR persistido y apagado por defecto.

### 45. El guardado de la foto corre en el mismo hilo que sirve la camara

`Camera2Controller.kt` · coste: dias · riesgo: alto · necesita el telefono

saveImage() se ejecuta en backgroundHandler y encadena recorte, filtro (decodifica a ARGB_8888 de 12,6 MP y recomprime a q95), miniatura, Exif e insert+write+update en MediaStore; unlockFocusAfterShot solo se libera al terminar todo, asi que el visor queda con el foco clavado durante el guardado y el pipeline se llena en el segundo disparo. Separar hilos: un HandlerThread exclusivo para la camara y un Executor aparte para codificacion y E-S; devolver el control en cuanto se lee el buffer.

### 46. Cada ajuste reconstruye la sesion Camera2 entera

`Camera2Controller.kt` · coste: dias · riesgo: alto · necesita el telefono

HDR, RAW, noche, QR, relacion de aspecto y resolucion llaman a postRebuildSession(), que cierra sesion e ImageReaders, vuelve a leer CameraCharacteristics y recrea todo: un apagon de visor por cada toque de chip. Separar los ajustes que solo son CaptureRequest (filtro, EV, WB, cuadricula, temporizador, flash) de los que cambian streams (RAW, noche, ratio, resolucion); para estos, crear la sesion una vez con todos los targets posibles y elegir el target por request, y en API 28+ usar SessionConfiguration con setSessionParameters.

### 47. El crossfade de lente asigna 36 MB en el hilo principal

`CameraActivity.kt` · coste: minutos · riesgo: bajo · necesita el telefono

freezeForLensSwitch() hace t.getBitmap(t.width, t.height) justo en el instante que la animacion pretendia suavizar; en el plegable desplegado el TextureView se mide a 2248x3998 px = 35,9 MB de ARGB_8888, y se dispara en cada cruce de parada optica durante un pellizco. Congelar a un cuarto de resolucion (o ~720 px de ancho) sobre un Bitmap reutilizado, o pasar a SurfaceView + PixelCopy asincrono.

### 48. La lupa de enfoque pide un bitmap completo en cada toque

`CameraActivity.kt` · coste: minutos · riesgo: bajo · necesita el telefono

showMagnifier hace binding.texture.getBitmap(tw, th) a resolucion COMPLETA (hasta 35,9 MB en la pantalla interior) solo para recortar un 12%, en el hilo de UI: tiron visible justo cuando el usuario toca para enfocar. Ademas, si getBitmap o createBitmap lanzan, el catch deja el bitmap grande sin reciclar. Pedir getBitmap(tw/3, th/3) sobre un bitmap de campo reutilizado, ajustar el recorte por el factor, y mover el recycle a un finally.

### 49. El visor va a 30 fps y capado a 1920x1080

`Camera2Controller.kt` · coste: horas · riesgo: medio · necesita el telefono

aeFpsRange fuerza el rango 30/30 y MAX_PREVIEW_WIDTH/HEIGHT limitan a 1920x1080 sobre un panel interior de 2248x2480: la imagen se escala hacia arriba (se ve blanda, con moire arcoiris sobre superficies finas) y 30 fps en un panel de alta tasa parece que la app se arrastra. Elegir el rango de fps segun la luz (30/30 solo cuando la exposicion lo exija, 60 fijo o [30,60] con luz suficiente) y elegir el tamano de SurfaceTexture por area real de la ventana en vez de por un tope constante, recalculandolo en el callback de plegado.

### 50. La miniatura consulta la carpeta equivocada, en el hilo principal y sin LIMIT

`CameraActivity.kt` · coste: minutos · riesgo: bajo · necesita el telefono

latestMediaUri() filtra por '%Pictures/CamaraMacro%' mientras saveImage() guarda en DCIM/Camera (por eso la miniatura muestra una foto antigua); el LIKE con comodin inicial fuerza escaneo completo de la tabla y se ejecuta en onResume, por delante del arranque de la camara. Corregir la ruta a DCIM/Camera con el filtro por nombre 'MACRO_%' que ya usa GalleryActivity, anadir LIMIT 1, moverlo a un Executor y, mejor aun, guardar la Uri de la ultima foto en SharedPreferences para que el arranque no toque MediaStore.

### 51. El apilado nocturno es monohilo y congela el visor

`NightStacker.kt` · coste: horas · riesgo: medio · necesita el telefono

NightStacker apila 7 frames de 12,58 MP con bucles Kotlin monohilo en el hilo de camara (~130 millones de iteraciones mas estimateShift y planeToDense por frame), sin paralelizar y sin GPU: durante el apilado se congelan la vista previa y cualquier otro callback. Paralelizar por bandas con un pool del tamano de los nucleos (el bucle por filas es trivialmente divisible) o moverlo a un shader/RenderEffect, y sustituir la etiqueta 'Apilando...' por progreso real con opcion de cancelar.

### 52. close() bloquea el hilo de UI hasta 1,5 s (riesgo de ANR)

`Camera2Controller.kt` · coste: horas · riesgo: medio · necesita el telefono

stopBackgroundThread hace join(1500) sobre el hilo de camara, y CameraActivity lo llama en onPause y, peor, en flipCamera (close() seguido de open() en el mismo click). Si la lente danada cuelga el HAL, la interfaz se congela al cambiar de camara y Android puede lanzar un ANR; contradice ademas el requisito de <1 s de abrir a listo. Hacer quitSafely() sin join (readers y device ya se cerraron antes) o mover el cierre a una corrutina y encadenar open() en la finalizacion del close.

### 53. El ImageReader de la foto solo tiene 2 buffers

`Camera2Controller.kt` · coste: minutos · riesgo: medio · necesita el telefono

maxImages=2 a resolucion completa: con el guardado ocupando el mismo hilo, el pipeline se llena en el segundo disparo y el HAL empieza a descartar o a esperar. Subir a 4-6 buffers (calculando memoria con SENSOR_INFO_ACTIVE_ARRAY y el heap disponible) y drenar en un Executor de E-S para que acquireNextImage no compita con la escritura a disco.

### 54. refreshThumbnail() duplicado en cada foto y en cada frame de rafaga

`CameraActivity.kt` · coste: minutos · riesgo: bajo · sin dispositivo

Se llama por CADA foto de la rafaga (7 en ~1 s) y tras cada foto normal aunque onPhotoThumb ya pinte la miniatura al instante desde el JPEG en memoria; cada llamada lanza una decodificacion coil de un JPEG completo y, si ultimoGuardado es null, una query a MediaStore en el hilo principal, justo en el momento de mayor presion de memoria. Quitarlo de burstNext y del callback de takePhoto; conservarlo solo en onResume y fuera del hilo principal.

### 55. Se paga el modelo de ML Kit empaquetado por codigo que no se ejecuta

`build.gradle` · coste: horas · riesgo: medio · necesita el telefono

Hay DOS pipelines de codigos y el bueno esta muerto: el controlador tiene qrReader + BarcodeScanning completos pero setQrEnabled no se llama desde ningun sitio (grep: cero coincidencias). Se empaqueta barcode-scanning:17.2.0 (buena parte de los 27 MB del APK) por codigo inalcanzable. Consolidar en el pipeline YUV del controlador, borrar autoScanner/autoScanTick/scanBitmap de la Activity y evaluar sustituir la dependencia por 'com.google.android.gms:play-services-mlkit-barcode-scanning' para sacar el modelo del APK. Restringir formatos con BarcodeScannerOptions (hoy se buscan TODOS, la configuracion mas lenta).

### 56. El visor usa TextureView en vez de SurfaceView

`AutoFitTextureView.kt` · coste: dias · riesgo: alto · necesita el telefono

TextureView anade una copia hacia la jerarquia de vistas, un frame extra de latencia y coste de composicion proporcional al tamano del panel: exactamente donde el usuario nota la lentitud. Migrar a SurfaceView (o SurfaceControl) con la logica de recorte y relacion de aspecto en el layout, y usar PixelCopy para los casos puntuales que hoy necesitan getBitmap (congelado de lente y lupa).

### 57. saveImage encadena hasta cinco bitmaps de 12,6 MP

`Camera2Controller.kt` · coste: horas · riesgo: medio · necesita el telefono

Con aspecto FULL y filtro activo: decode a Bitmap (~50 MB ARGB_8888), copia rotada, recorte, recompresion, segundo decode completo para el filtro, tercer bitmap de salida, otra recompresion y un cuarto decode para la miniatura. Todo en el hilo del ImageReader, que asi no entrega el siguiente fotograma. Encadenar las transformaciones sobre UN solo Bitmap (rotar+recortar+filtrar con un unico Canvas, una sola compresion) y usar inPreferredConfig = RGB_565 para la miniatura.

### 58. Siete paneles inflados en frio sin un solo ViewStub

`activity_camera.xml` · coste: horas · riesgo: bajo · sin dispositivo

Se infla un FrameLayout con 64 vistas identificadas, incluidos more_panel, pro_panel, video_panel, lens_panel, qr_card, ev_quick y magnifier_card, todos con visibility=gone. Es coste puro en la ruta critica de arranque. Convertirlos en ViewStub e inflarlos la primera vez que se abren; con el APK release y baseline profile es el siguiente recorte mas barato del arranque en frio.

### 59. APIs obsoletas de sesion y de pantalla

`Camera2Controller.kt` · coste: horas · riesgo: medio · necesita el telefono

Se usa createCaptureSession(List<Surface>,...) obsoleta y windowManager.defaultDisplay.getSize()/getRotation() en tres puntos, que en plegables y multiventana devuelven la pantalla fisica y no la ventana de la app. Pasar a SessionConfiguration + OutputConfiguration con Executor y setSessionParameters (evita una reconfiguracion en el primer frame), y sustituir defaultDisplay por activity.display y WindowMetricsCalculator.computeCurrentWindowMetrics().

### 60. Fuga de Surface en cada apertura y cada reconstruccion

`Camera2Controller.kt` · coste: minutos · riesgo: bajo · sin dispositivo

startPreview y startVideo crean Surface(texture) y nunca la liberan; startPreview se invoca en cada apertura, cada switchToLens, cada postRebuildSession, cada fallback de configuracion y al terminar un video, asi que con el uso normal se acumulan decenas de Surface con su buffer nativo. Guardar la Surface en un campo, liberar la anterior con release() antes de crear la nueva y liberarla tambien en close().

### 61. El sensor de vector de rotacion se registra siempre

`CameraActivity.kt` · coste: minutos · riesgo: bajo · sin dispositivo

TYPE_ROTATION_VECTOR se registra en cada onResume a SENSOR_DELAY_UI aunque el nivel de horizonte este apagado: despierta el hilo principal ~16 veces por segundo sin efecto visible. Extraer startRollSensor()/stopRollSensor() y registrarlo solo cuando gridOn/showLevel esten activos, tambien desde toggleGrid().

### 62. No existe ninguna instrumentacion de rendimiento

`build.gradle` · coste: dias · riesgo: bajo · necesita el telefono

Solo hay Log.i sueltos: sin Macrobenchmark de arranque, sin JankStats, sin trazas de frames perdidos, sin medicion del tiempo de guardado, del apilado nocturno ni de la latencia disparo-a-disparo. Sin numeros, cada arreglo es una apuesta. Anadir un modulo de Macrobenchmark con StartupTimingMetric y FrameTimingMetric, instrumentar con Trace.beginSection las cuatro rutas clave (apertura, captura, guardado, apilado) y publicar la tabla de tiempos junto al APK.

### 63. Video 4K/60 sin evidencia de fps sostenidos ni comportamiento termico

`Camera2Controller.kt` · coste: horas · riesgo: bajo · necesita el telefono

No hay medicion de cadencia real ni de throttling, y el time-lapse fija setCaptureRate(2.0) sin exponerlo. El rival tiene documentado justamente el fallo de frame rate variable, asi que hay una victoria facil sin reclamar. Medir y publicar fps reales y frames perdidos en 4K/60 durante 5 minutos, fijar CONTROL_AE_TARGET_FPS_RANGE al valor de grabacion durante el video para garantizar cadencia constante, y exponer el intervalo del time-lapse.

---

## INTERFAZ

### 64. El HUD esta anclado a la PANTALLA y no al VISOR (causa raiz)

`activity_camera.xml` · coste: dias · riesgo: alto · necesita el telefono

activity_camera.xml es un FrameLayout plano con 28-30 hijos colocados por margenes absolutos (24/30/40/54/64/96/102/110/116/120/184/190/244dp). Es el origen comun del clipping, de la desalineacion y de casi todos los solapes de esta auditoria. Reestructurar en tres capas: (1) FrameLayout preview_frame con texture, lens_fade, grid_overlay, focus_ring, magnifier y un center_slot; (2) LinearLayout vertical top_bar que APILE chips, lens_chip y more_panel en vez de superponerlos; (3) LinearLayout vertical control_band abajo con panel_slot > zoom_pill > zoom_strip > mode_toggle > shutter_row. Ningun margen del HUD debe volver a medirse desde el borde de la pantalla.

### 65. Cinco elementos comparten exactamente marginBottom=244dp

`activity_camera.xml` · coste: horas · riesgo: medio · necesita el telefono

zoom_pill, pro_panel, lens_panel, video_panel y ev_quick estan en la misma cota. El codigo solo excluye pro/lens/video con ifs a mano ('if (proOn) togglePro() // no solapar paneles'): ev_quick aparece automaticamente en CADA toque de enfoque durante 4 s y se dibuja sobre el slider del panel PRO (dos sliders superpuestos), y zoom_pill queda por debajo de los tres paneles, asi que al pellizcar con un panel abierto el indicador de zoom es invisible. Meter los cuatro paneles en un unico FrameLayout panel_slot con helper showPanel(v) que haga GONE a los demas, y sacar zoom_pill como ultimo hijo. La exclusion debe ser estructural, no una lista de ifs.

### 66. El panel 'mas' tapa el chip de lente, el aviso de bloqueo y la lupa

`activity_camera.xml` · coste: horas · riesgo: medio · necesita el telefono

more_panel mide 176dp y ocupa y=96..272dp a todo el ancho; dentro de esa banda caen lens_chip (102..128), ae_lock_badge (120..152) y magnifier_card (120..240), y como more_panel es el ultimo en el XML se dibuja ENCIMA de los tres. Con el panel abierto desaparece el chip de lente, no se ve el aviso AE/AF BLOQUEADO y la lupa de confirmacion de nitidez queda oculta (el usuario cree que la lupa no funciona). Apilar la cabecera en un top_bar vertical para que more_panel empuje en vez de superponerse, y colocar la lupa en el cuadrante OPUESTO al toque.

### 67. Las pastillas de zoom se cortan por el borde inferior del preview

`activity_camera.xml` · coste: minutos · riesgo: bajo · necesita el telefono

En el plegable quedan a caballo del limite y se ve medio circulo asomando con '1x' y '4.6x' colgando sobre el negro. Constrenir zoom_strip a bottom_toBottomOf=@id/texture con marginBottom en dp y clipChildren=false en el contenedor, o moverla dentro de un contenedor hijo que ocupe exactamente el rectangulo del visor.

### 68. El chip de lente se sale del visor en el plegable

`activity_camera.xml` · coste: minutos · riesgo: bajo · necesita el telefono

'ID3 - 15 MM - 0.6X' queda cortado por el borde de la imagen y el texto flota sobre la franja negra, por usar layout_gravity=top|start + marginTop=102dp contra la pantalla. Anclarlo al top|start DEL VISOR con margen de 16dp y darle maxWidth con ellipsize.

### 69. El dato optico en pantalla se contradice a si mismo

`CameraActivity.kt` · coste: horas · riesgo: medio · necesita el telefono

En una captura la pastilla dice '5x' y el rotulo 'ID6 - 70 MM - 6.6X'; en otra la pastilla dice '4.6x' y el rotulo 'ID6 - 70 MM - 10.5X'. Los milimetros quedan congelados en la focal fisica mientras el recorte digital crece hasta 3,6x. Una unica fuente de verdad: zoomTotal y zoomLente; mostrar SIEMPRE la focal EFECTIVA = focal35mmEq * zoomLente; que pastilla y rotulo lean el mismo valor; y cuando el recorte supere la resolucion nativa del stream, marcar el numero en ambar con la etiqueta 'digital' y anotarlo en DigitalZoomRatio del EXIF.

### 70. Emoji del sistema usados como iconografia

`activity_camera.xml` · coste: horas · riesgo: bajo · necesita el telefono

chip_flash='rayo off', chip_night='luna', chip_vid='claqueta HD', chip_tl='reloj TL', chip_filter='estrella Normal', mezclados con glifos Unicode y texto plano ('QR', 'WhatsApp', 'LENTES'). Se pintan con la fuente de emoji del movil: no se pueden tenir con el ambar, tienen baseline propio, el amarillo de la luna pelea con el acento y cambian entre dispositivos. Sustituir los 10 chips por VectorDrawables monocromos de 24dp con tint=?attr/colorControlNormal y tint ambar en estado activo: un solo set, un grosor de trazo, una caja optica. Es la correccion de mayor impacto visual por linea de codigo.

### 71. Contraste insuficiente: los controles son ilegibles sobre escena clara

`chip_bg.xml` · coste: minutos · riesgo: bajo · necesita el telefono

chip_bg es #66000000 (40% de negro) con texto #CCFFFFFF: ~1,8:1 sobre blanco, muy por debajo del 4,5:1 exigible. En la captura de la cocina la fila superior casi desaparece. Subir el fondo a #99000000 con borde de 1dp #33FFFFFF (o RenderEffect.createBlurEffect en API 31+), texto a #FFFFFFFF y sombra (shadowColor #CC000000, shadowRadius 4). Verificar midiendo sobre una captura ADB a contraluz del propio CPH2765.

### 72. Los scrims no protegen nada

`activity_camera.xml` · coste: horas · riesgo: bajo · necesita el telefono

scrim_bottom mide 190dp anclado al fondo de la PANTALLA (cae integro sobre la franja negra en las dos configuraciones y, en FIT, pinta 56dp de degradado sobre negro dejando una costura gris visible), mientras zoom_pill y los paneles estan a 244dp y la tira de zoom llega a 194dp: texto y sliders ambar sobre imagen viva sin proteccion. scrim_top mide 110dp pero lens_chip ocupa 102..128dp. Anclar ambos al rectangulo del visor y dimensionarlos por contenido: que el scrim inferior sea el FONDO de la banda de controles (crece y encoge con ella) y el superior cubra options_bar + lens_chip completos (~150dp). Ocultar el inferior en modo FIT.

### 73. La cuadricula y el nivel se dibujan sobre toda la pantalla, no sobre el encuadre

`GridOverlayView.kt` · coste: horas · riesgo: medio · necesita el telefono

grid_overlay es match_parent de la raiz. En la cubierta (FIT 16:9) la imagen ocupa 351x624dp de una caja de 351x758dp, asi que las lineas de tercios se pintan en y=253 y y=505 cuando los tercios reales estan en y=208 y y=416 (error de hasta 289 px), y el nivel de horizonte queda 67dp por debajo del centro real. Con 1:1 la segunda linea cae fuera de la foto. Meter texture + lens_fade + grid_overlay + focus_ring en un preview_frame comun, o anadir a GridOverlayView un setPreviewRect(RectF).

### 74. Cuenta atras, tarjeta QR y aviso 'Apilando' centrados en la pantalla, no en la imagen

`activity_camera.xml` · coste: horas · riesgo: bajo · necesita el telefono

Los tres usan layout_gravity=center sobre la raiz. En la cubierta el centro de la raiz esta 67dp por debajo del centro de la imagen; con relacion 1:1 los tres aparecen ENTEROS sobre la franja negra, fuera de la foto. Ademas comparten el mismo centro: si salta un QR durante la cuenta atras, qr_card tapa el numero. Crear un unico center_slot dentro de preview_frame y mostrar solo uno a la vez con un helper showCenterSlot(view), posponiendo qr_card mientras countdown este visible.

### 75. El cronometro de grabacion queda debajo de los chips

`activity_camera.xml` · coste: minutos · riesgo: bajo · necesita el telefono

rec_indicator ocupa y=54..85dp centrado horizontalmente y options_bar ocupa y=40..88dp con chip_grid justo en el centro; como options_bar se declara despues, se dibuja ENCIMA y durante la grabacion el tiempo 0:00 se ve mordido. Darle a rec_indicator su propia fila en el top_bar vertical, o intercambiarlos (ocultar options_bar mientras isRecording, que ya se detecta en onRecordingChanged).

### 76. El punto/cuadro de video no esta centrado en el obturador

`activity_camera.xml` · coste: minutos · riesgo: bajo · sin dispositivo

btn_shutter ocupa 24..102dp desde abajo (centro en 63dp) y shutter_icon 64..94dp (centro en 79dp): 16dp por encima, mordiendo el anillo blanco superior. Se ve torcido a simple vista en ambas pantallas y en los dos estados. Envolver ambos en un FrameLayout shutter_wrap de 78dp con layout_gravity=center para los dos hijos (arreglo minimo equivalente: marginBottom=48dp en shutter_icon).

### 77. Desalineacion medible en la fila inferior

`activity_camera.xml` · coste: minutos · riesgo: bajo · sin dispositivo

Miniatura marginStart=24dp frente a flip marginEnd=22dp, y centros verticales en 57dp, 58dp y 63dp para miniatura, flip y obturador: 6dp de desnivel visible en la fila que mas se mira de la app. Igualar los margenes a 24dp y alinear los tres por centro vertical con una cadena horizontal (o la shutter_row propuesta con Space weight=1).

### 78. La tira de zoom se recorta y las pastillas son dispares

`activity_camera.xml` · coste: minutos · riesgo: bajo · necesita el telefono

Se construye por codigo con minWidth 52dp + marginEnd 8dp dentro de un LinearLayout wrap_content sin scroll: con 6 paradas pide 360dp > 351dp de la pantalla plegada y se recorta por los dos lados, escondiendo justo el gran angular y el tele. Ademas las pastillas 0.6x y 2.9x se ven mayores que 1x/2x/5x (queja literal del usuario: 'botones de zoom disparejos'). Envolver zoom_strip en un HorizontalScrollView con scrollbars=none y paddingHorizontal=16dp, y dar a todas las pastillas el mismo minWidth/minHeight (48dp) y tipografia tabular.

### 79. La parada de zoom activa solo se distingue por el color del texto

`CameraActivity.kt` · coste: minutos · riesgo: bajo · necesita el telefono

El fondo de la pastilla seleccionada es identico al de las demas. Darle fondo propio (ambar al 18% con borde ambar de 1,5dp) ademas del texto ambar y animar la transicion en 150 ms. El estado seleccionado debe leerse de reojo sin buscar el color de la cifra. Ademas, resaltar por proximidad al zoom REAL: si el zoom global es 6,6x nunca debe quedar resaltada la de 5x.

### 80. La barra de opciones no tiene scroll y ya se ha cortado un chip

`activity_camera.xml` · coste: minutos · riesgo: bajo · necesita el telefono

options_bar es un LinearLayout match_parent sin HorizontalScrollView: ya se corto un chip por el borde derecho en una captura. El ritmo tambien falla: chips de 48dp minimo junto a 'rayo off' de ~72dp con separacion fija de 10dp. Envolver en HorizontalScrollView con degradado de desvanecimiento en los extremos, o fijar todos los chips a 56dp con solo icono y un punto ambar de estado (el texto 'off' sobra si el icono ya lo dice).

### 81. La tercera fila del panel 'mas' se desborda en la pantalla de cubierta

`activity_camera.xml` · coste: minutos · riesgo: bajo · necesita el telefono

LENTES (~76dp) + 'atras' (~80dp) + WhatsApp (~90dp) + ImageButton 48dp + margenes = ~354dp > 351dp disponibles, sin pesos ni scroll: al ultimo hijo (btn_change_lens, el acceso a cambiar de lente) le llega ~0 y se recorta. Tras pulsar voltear, el chip pasa a 'frontal 1' (~114dp) y la fila sube a ~388dp: el boton desaparece con certeza. Envolver cada fila en HorizontalScrollView (fillViewport=true) o pasar a FlexboxLayout con flexWrap.

### 82. El slider rapido de EV cruza toda la pantalla y roba toques de enfoque

`activity_camera.xml` · coste: minutos · riesgo: bajo · necesita el telefono

ev_quick es match_parent con un SeekBar de 48dp que aparece solo en cada toque de enfoque durante 4 s, en plena imagen (y=466..514 en la cubierta, 424..472 en la interior): mientras esta visible, cualquier toque para enfocar dentro de esa franja mueve la exposicion en vez de enfocar. En la interior llega a 692dp (~165 mm) para un rango de +-2 EV. Compactarlo a una pastilla wrap_content con maxWidth=@dimen/panel_max_width, fondo zoom_pill_bg y layout_gravity=bottom|center_horizontal dentro del panel_slot.

### 83. Anchos de panel fijos que se ven distintos en cada pantalla

`activity_camera.xml` · coste: minutos · riesgo: bajo · sin dispositivo

pro_slider es de 288dp: el 82% del ancho util en la cubierta y solo el 42% en la interior (apretado plegado, perdido abierto). qr_card es match_parent: 303dp plegado pero 644dp abierto para mostrar una URL. lens_panel y video_panel son wrap_content y quedan como islas diminutas. Definir @dimen/panel_max_width (340dp en values/, 440dp en sw600dp), poner pro_slider a 0dp+weight=1 dentro de un panel con ese maxWidth, y qr_card a wrap_content con el mismo tope.

### 84. La tarjeta QR salta al centro del visor y tapa el sujeto

`CameraActivity.kt` · coste: horas · riesgo: bajo · necesita el telefono

qr_card aparece centrada en cuanto entra cualquier codigo de barras en cuadro, mientras el usuario compone; qrDismissed solo recuerda UN valor, asi que vuelve a aparecer con el siguiente. Mover el aviso a una pastilla discreta abajo ('Codigo detectado - tocar para ver') que solo se despliegue al tocarla, recordar los codigos descartados en una lista y no escanear mientras el dedo esta sobre el obturador ni durante la cuenta atras.

### 85. El feedback principal son mas de 20 Toast

`CameraActivity.kt` · coste: horas · riesgo: bajo · necesita el telefono

'Modo noche ON', 'Rafaga', 'RAW + JPEG', 'Copiado', 'Ultra HDR no disponible en esta lente'... En la pantalla grande el Toast sale abajo del todo, lejisimos del chip que se acaba de tocar arriba, y tapa los controles. Reemplazarlos por el propio estado del chip (color y texto) mas una pastilla efimera junto al control pulsado; reservar el mensaje largo solo para errores reales y darlo como Snackbar con accion ('RAW no disponible aqui - Cambiar lente').

### 86. Sistema tipografico incoherente

`themes.xml` · coste: horas · riesgo: bajo · sin dispositivo

Monospace en zoom_pill/ev_label/rec_indicator/pro_value, sans-serif-medium en lens_chip/qr_text, bold por defecto en ProChip, tamanos 12/13/14/15/16/96sp y tres reglas de caja simultaneas ('off' en minusculas, 'FOTO' en mayusculas, 'ID3 - 15 MM' en versalitas). Definir tres estilos (etiqueta 11sp versalitas, valor 13sp, dato 15sp), una unica familia, y cifras tabulares reales con fontFeatureSettings='tnum' en lugar de usar monospace como parche. Regla de caja unica: versalitas para etiquetas de estado, sin excepciones.

### 87. Dos sistemas de superficie conviviendo

`zoom_pill_bg.xml` · coste: horas · riesgo: bajo · sin dispositivo

chip_bg (#66000000, radio 8dp) para la barra superior y el chip de lente, frente a zoom_pill_bg (#B3000000, radio 18dp) para pastillas, paneles, tarjeta QR y selector de modo; radios totales 8/12/18/60dp sin escala. Un unico token de superficie con dos niveles de elevacion (chip y panel), misma opacidad, y una escala de radios de 3 pasos (8 / 16 / 28dp) aplicada por tamano de superficie: el radio de 18dp no puede ser el mismo en una pastilla de 48dp y en la tarjeta QR a ancho completo.

### 88. Los chips son TextView clickables, no botones

`themes.xml` · coste: horas · riesgo: bajo · necesita el telefono

TalkBack los anuncia como texto y el fondo es un shape plano, asi que no hay ripple ni estado pulsado. Migrar ProChip a MaterialButton, o al menos anadir foreground=?attr/selectableItemBackground, un selector de fondo con state_selected y un accessibilityDelegate con className Button.

### 89. ProChip se usa para tres jerarquias distintas

`themes.xml` · coste: horas · riesgo: bajo · sin dispositivo

El mismo componente sirve para conmutadores de estado ('HDR', 'RAW', 'FLASH'), para navegacion ('PRO', 'LENTES') y para acciones de la tarjeta QR ('Copiar', 'Abrir', 'Cerrar'): sin jerarquia, todo pesa igual. Tres variantes: ChipEstado (con punto o icono on/off), ChipAccion (contorno) y BotonPrimario (relleno ambar) para la accion principal de un panel. El usuario debe distinguir 'esto cambia un ajuste' de 'esto ejecuta algo'.

### 90. Objetivo tactil de FOTO/VIDEO por debajo del minimo

`activity_camera.xml` · coste: minutos · riesgo: bajo · sin dispositivo

tab_photo y tab_video son TextView con paddingVertical 6dp y texto 13sp: ~29dp de alto en el control mas usado despues del obturador, frente a los 48dp que el propio proyecto ya fijo en @style/ProChip. Aplicar minHeight=48dp, minWidth=72dp y gravity=center (o darles style=@style/ProChip y dejar el color como unico estado activo). El fondo zoom_pill_bg absorbe el cambio sin tocar el diseno.

### 91. El destello de captura cubre toda la ventana, incluida la franja negra

`activity_camera.xml` · coste: minutos · riesgo: bajo · sin dispositivo

screen_flash es match_parent de la raiz: en la cubierta con 16:9 parpadean tambien 134dp de barra negra (407dp con 1:1), lo que se ve como un fogonazo de pantalla completa y ademas revela donde termina el encuadre. En la interior (COVER) el efecto si es correcto, asi que las dos pantallas dan una sensacion distinta al disparar. Moverlo dentro de preview_frame.

### 92. Aire muerto en la banda inferior de la pantalla cerrada

`activity_camera.xml` · coste: horas · riesgo: bajo · necesita el telefono

38% de la pantalla en negro con ~200px de vacio entre el visor y la tira de zoom, ~150px hasta FOTO/VIDEO y ~170px mas hasta el obturador. Compactar a tres filas con un ritmo unico (12/20/12dp) y usar el espacio recuperado para subir el visor o sacar de '...' lo que hoy esta escondido: ratio, HDR y RAW como chips de estado siempre visibles.

### 93. Jerga de programador en la pantalla principal

`CameraActivity.kt` · coste: minutos · riesgo: bajo · sin dispositivo

'ID3 - 15 MM - 0.6X' y 'ID6' aparecen en el HUD de uso diario. Sustituir por lenguaje humano: 'Gran angular - 15 mm', 'Teleobjetivo - 70 mm', dejando el ID solo en la pantalla de eleccion de lente para quien lo necesite.

### 94. Colores de estado escritos a mano en Kotlin y fuera del sistema

`colors.xml` · coste: minutos · riesgo: bajo · sin dispositivo

Verde #4CD964 y rojo #FF3B30 (paleta de iOS) para el anillo de enfoque, mas #CCFFFFFF y #8CFFFFFF repetidos por todo CameraActivity. Llevarlos a colors.xml como focus_ok / focus_fail / text_primary / text_dim, reemplazar cada Color.parseColor, y reelegir el verde y el rojo para que convivan con el ambar en vez de importarlos de otra marca.

### 95. El obturador no tiene ningun gesto visual memorable

`shutter_button.xml` · coste: minutos · riesgo: bajo · sin dispositivo

Es blanco puro y no participa del sistema de acento; la app no tiene firma propia. Anillo exterior ambar de 3dp sobre relleno blanco (mantiene la legibilidad), escala de rebote al pulsar y transformacion a cuadrado rojo en video. Sale gratis y es lo unico que el usuario mira mientras dispara.

### 96. El chip de relacion de aspecto puede mentir en el arranque

`activity_camera.xml` · coste: minutos · riesgo: bajo · necesita el telefono

chip_ratio nace con '16:9' en el XML y el briefing afirma 16:9 por defecto, pero las cuatro fotos entregadas son 4096x3072 (4:3). Verificar que refleja el AspectRatio efectivo al arrancar y no un placeholder. En una app cuyo mayor valor es decir la verdad sobre la optica, el HUD no puede equivocarse sobre el encuadre.

### 97. El chip de lente puede partirse en dos lineas

`activity_camera.xml` · coste: minutos · riesgo: bajo · sin dispositivo

Es wrap_content sin singleLine ni ellipsize y su texto es dinamico: 'MACRO - 1.0x - 2 LENTES OFF' son ~254dp; si la etiqueta crece, el chip se parte y crece hacia abajo invadiendo la zona de la lupa y del panel '...'. Anadir maxLines=1, ellipsize=end y maxWidth=@dimen/lens_chip_max_width (240dp en values/, 420dp en sw600dp).

### 98. El panel 'mas' es una rejilla de chips cripticos sin ninguna explicacion

`activity_camera.xml` · coste: dias · riesgo: medio · necesita el telefono

HDR, RAW, FULL, LENTES, 'atras', 'Normal', PRO sin una sola etiqueta que diga que hacen, anclado ARRIBA (marginTop 96dp), inalcanzable con el pulgar en 2480 px y tapando el visor. Convertirlo en un bottom-sheet a media altura con secciones y nombres completos ('Calidad de imagen', 'Formato', 'Lentes'), cada opcion con una linea de descripcion.

---

## PLEGABLE

### 99. Al abrir o cerrar el telefono la app se REINICIA

`AndroidManifest.xml` · coste: horas · riesgo: medio · necesita el telefono

El manifiesto declara configChanges='orientation|screenSize|keyboardHidden' y omite smallestScreenSize y screenLayout, justo los que cambian al plegar (GalleryActivity si los declara; CameraActivity no). La Activity se destruye, la camara se cierra y se reabre (539-572 ms en negro) y se pierden zoom, modo y ajustes: es la causa directa del 'se siente lenta' en la pantalla grande. Poner configChanges='orientation|screenSize|smallestScreenSize|screenLayout|density|uiMode|keyboardHidden' en CameraActivity y SetupActivity, declarar resizeableActivity=true e implementar onConfigurationChanged que releea preview_fills_screen, recoloque el HUD y llame solo a configureTransform + reajuste de aspecto, SIN cerrar el CameraDevice.

### 100. El recorte del visor se come SOLO la parte de abajo

`Camera2Controller.kt` · coste: minutos · riesgo: medio · necesita el telefono

El texture tiene layout_gravity='top|center_horizontal'. En la interior con preview_fills_screen=true (coverMode) y 16:9, el TextureView se mide a 692x1230dp dentro de un padre de 716dp: los 514dp sobrantes (41,8% del encuadre) se pierden POR ABAJO y 0dp por arriba, asi que el centro de la composicion del usuario cae al 29% de altura del fotograma real (en 4:3 se pierden 207dp). Ademas el comentario de CameraActivity ('en modo LLENA t.left/t.top son negativos') asume centrado: t.top NUNCA es negativo con gravity=top. Fijar la gravedad junto a coverMode: CENTER en COVER, TOP|CENTER_HORIZONTAL en FIT, replicandolo identico en lens_fade.

### 101. En la pantalla grande el visor recorta pero la foto no (WYSIWYG roto)

`Camera2Controller.kt` · coste: horas · riesgo: medio · necesita el telefono

coverMode se fuerza con el bool preview_fills_screen aunque la relacion sea 16:9, pero cropFullJpeg SOLO se aplica cuando aspect==FULL: el visor recorta el 42% del fotograma y el archivo guardado conserva el 16:9 completo, asi que el sujeto centrado en pantalla aparece en el tercio superior de la foto. Exponer un flag 'previewCropped' y recortar el JPEG siempre que el visor este en coverMode; alternativa honesta: dibujar guias tenues que marquen lo que NO se va a guardar, o forzar ratioIndex=LLENA por defecto en sw600dp.

### 102. cropFullJpeg recorta con la proporcion equivocada

`Camera2Controller.kt` · coste: minutos · riesgo: medio · necesita el telefono

Usa displayMetrics (2248/2480 = 0,9065) en vez de la caja visible real del visor (692/716 = 0,9665, ya sin barras): 6,6% de error incluso cuando SI recorta. Sustituir por el ratio de la caja visible (box.width/box.height del contenedor del texture) o guardar ese ratio al medir.

### 103. No existe ningun layout-sw600dp

`activity_camera.xml` · coste: dias · riesgo: alto · necesita el telefono

Un unico activity_camera.xml de telefono con margenes absolutos estirado a 2248x2480: todos los controles se apelotonan en la sexta parte inferior con un vacio enorme en el centro, la cabecera deja ~196dp vacios a cada lado de los 5 chips y el panel '...' se convierte en una banda de 176dp a lo ancho de 692dp para 10 chips que caben en dos filas. Crear res/layout-sw600dp/activity_camera.xml con ConstraintLayout y guidelines porcentuales, rail vertical lateral (configurable diestro/zurdo) para obturador/miniatura/flip, tira de zoom vertical junto al borde y paneles como bottom-sheet a media altura.

### 104. La fila inferior se estira 592dp: imposible de alcanzar con el pulgar

`activity_camera.xml` · coste: horas · riesgo: bajo · necesita el telefono

En la interior (692dp de ancho) la miniatura queda a marginStart=24dp y el flip a marginEnd=22dp, a ~592dp de distancia (unos 118 mm reales a 423 dpi), con el obturador en el centro: toda la interaccion desperdigada en 135 mm. Sustituir los tres elementos sueltos por una shutter_row (miniatura | Space weight=1 | shutter_wrap | Space weight=1 | flip) con maxWidth=@dimen/action_row_max_width (400dp en values/, 420dp en sw600dp) para que queden agrupados y centrados en la zona alcanzable.

### 105. No hay espejo ni visor en la pantalla EXTERNA del plegable

`CameraActivity.kt` · coste: dias · riesgo: alto · necesita el telefono

Cero referencias a Presentation, DisplayManager o FoldingFeature en todo el proyecto, y androidx.window no esta en build.gradle. Es lo que el usuario pidio por su nombre y es LA razon de tener un plegable: con el telefono cerrado, usar la camara trasera buena para autorretratos mostrando el encuadre fuera. Anadir androidx.window, registrar un DisplayManager.DisplayListener y, cuando getDisplays(DISPLAY_CATEGORY_PRESENTATION) devuelva la pantalla de cubierta, lanzar una Presentation con un segundo TextureView alimentado por el MISMO SurfaceTexture (o anadir su Surface como segundo target del repeating request), con espejado horizontal opcional, temporizador grande y obturador tactil. Boton 'Espejo' activo solo cuando hay pantalla externa.

### 106. La app esta bloqueada en vertical por manifiesto

`AndroidManifest.xml` · coste: dias · riesgo: medio · necesita el telefono

screenOrientation='portrait' en CameraActivity y SetupActivity, en un dispositivo cuya pantalla interior es casi cuadrada y se usa en horizontal muy a menudo. Es la misma critica que el dossier del rival documenta como fallo. Quitar el bloqueo al menos en sw600dp ('user' o 'fullSensor') con un layout-sw600dp-land, o al menos rotar iconos y cifras 90 grados con OrientationEventListener y animacion de 200 ms manteniendo el layout, colocando los controles en columna lateral cuando el ancho supere al alto.

### 107. preview_bottom_inset es un recurso muerto que ademas miente

`dimens.xml` · coste: minutos · riesgo: bajo · necesita el telefono

values-sw600dp/dimens.xml declara EXACTAMENTE el mismo 0dp que values/dimens.xml, con un comentario que afirma 'Reservamos la banda inferior para que los controles nunca tapen el encuadre'. No se reserva nada, y quien lea el recurso creera que el problema esta resuelto. O darle valor real en sw600dp (p.ej. 200dp, dejando el visor en 692x516dp y una banda limpia) con preview_fills_screen=false para recuperar WYSIWYG, o borrar la variante, el atributo y el comentario. Lo que no puede quedarse es un override identico al default con un comentario falso.

### 108. Sin soporte de postura: no hay FoldingFeature ni modo tabletop

`build.gradle` · coste: dias · riesgo: medio · necesita el telefono

Anadir androidx.window:window y observar FoldingFeature para reaccionar a la postura sin recrear nada: con HALF_OPENED colocar el visor arriba del pliegue y los controles abajo (modo tabletop, el caso de tripode natural del plegable), y evitar colocar controles sobre la bisagra.

### 109. La galeria no se adapta y sus miniaturas quedan aplastadas al plegar

`GalleryActivity.kt` · coste: minutos · riesgo: bajo · necesita el telefono

GalleryActivity declara smallestScreenSize|screenLayout en configChanges (asi que NO se recrea) pero cachea el tamano de celda una sola vez con resources.displayMetrics.widthPixels / 3 y lo aplica como altura fija: al abrir el plegable el ancho pasa de 1140 a 2248 px, las columnas se ensanchan a 749 px y la altura de fila sigue en 380 px (miniaturas 2:1). Ademas 3 columnas fijas en 692dp son celdas de 231dp. Calcular la celda en cada onCreateViewHolder desde parent.width y el numero de columnas por ancho objetivo (una por cada ~120-180dp) leyendo WindowMetricsCalculator, recalculando en onConfigurationChanged.

### 110. El tamano del visor se calcula con la pantalla fisica, no con la ventana

`Camera2Controller.kt` · coste: minutos · riesgo: bajo · necesita el telefono

windowManager.defaultDisplay (obsoleto desde API 30) se usa para rotacion y tamano en tres puntos del controlador: en un plegable y en multiventana devuelve la pantalla fisica y no la ventana de la app, justo en los casos que mas importan. Migrar a activity.display para la rotacion y a WindowMetricsCalculator.computeCurrentWindowMetrics() para el tamano, recalculando en onConfigurationChanged.

### 111. Sin interruptor Ajustar/Llenar visible

`bools.xml` · coste: horas · riesgo: bajo · necesita el telefono

values-sw600dp/bools.xml fuerza preview_fills_screen=true, asi que el visor recorta una foto 16:9 sobre una pantalla de proporcion 0,91 sin avisar ni ofrecer alternativa. Exponer un interruptor Ajustar/Llenar en la barra (persistido en prefs) y, en modo Llenar, dibujar guias tenues del encuadre real sobre el GridOverlayView.

---

## FUNCIONES QUE FALTAN

### 112. No hay ninguna herramienta de exposicion (histograma, cebras, aviso de recorte)

`CameraActivity.kt` · coste: dias · riesgo: medio · necesita el telefono

Sin histograma en vivo, sin cebras, sin aviso de recorte de altas luces o sombras y sin lectura de ISO/velocidad en automatico, en una app que pelea por calidad de imagen. El coste es casi nulo porque YA existe un stream YUV de analisis. Reutilizar ese ImageReader (o abrir uno de 320x240) para calcular en el hilo de fondo el histograma de luma y una mascara de recorte; pintar histograma como overlay opcional, cebras diagonales sobre pixeles > 250 y sombras bajo umbral, y una linea de estado permanente con ISO, velocidad y EV leidos del CaptureResult.

### 113. No hay focus peaking, que es la herramienta clave en macro

`CameraActivity.kt` · coste: dias · riesgo: alto · necesita el telefono

Sin peaking el enfoque manual se juzga a ojo sobre un visor pequeno. Un unico shader sobre el preview (RenderEffect en API 31+ o GLSurfaceView) puede resolver peaking, cebras e histograma a la vez: gradiente Sobel con color y umbral configurables, disponible TAMBIEN en modo auto.

### 114. No hay pantalla de Ajustes

`SetupActivity.kt` · coste: dias · riesgo: bajo · necesita el telefono

SetupActivity es solo un selector de lente; toda la configuracion vive como chips en un panel anclado arriba. Crear un Ajustes real (PreferenceFragmentCompat o BottomSheetDialogFragment) con: sonido de obturador, tipo de cuadricula, geoetiquetado, calidad JPEG, accion de los botones de volumen, modo y lente de arranque, proporcion por defecto, ubicacion de guardado, escanear codigos si/no y color de acento.

### 115. Sin geoetiquetado

`AndroidManifest.xml` · coste: horas · riesgo: bajo · necesita el telefono

Se escribe un IFD de GPS VACIO (puntero en offset 808 en todas las fotos analizadas) pero el manifiesto no pide ninguna permission de ubicacion y no hay codigo de localizacion. Anadir ACCESS_COARSE/FINE_LOCATION como opcionales, FusedLocationProviderClient con getLastLocation cacheada y rellenar TAG_GPS_LATITUDE/LONGITUDE/ALTITUDE/TIMESTAMP. Interruptor en Ajustes, por defecto APAGADO, sin bloquear nunca el disparo esperando al fix.

### 116. Sin sonido de captura

`CameraActivity.kt` · coste: horas · riesgo: bajo · necesita el telefono

No hay ni una referencia a SoundPool ni a MediaActionSound en todo el proyecto: solo queda el haptico, asi que al disparar no hay confirmacion audible. Es justo el detalle teatral que hace que una camara se sienta cara. Precargar un WAV corto con SoundPool y reproducirlo en onCaptureStarted (latencia minima), respetando el modo silencio y con interruptor en Ajustes; opcionalmente dos o tres sonidos elegibles.

### 117. Sin marca de agua opcional, teniendo el dato diferencial en mano

`Camera2Controller.kt` · coste: horas · riesgo: bajo · sin dispositivo

El EXIF confirma que la app conoce la focal fisica real (2,3 mm gran angular / 10,55 mm tele) y el ID de lente: es el dato que ninguna otra camara muestra. Componer un Bitmap con franja BAJO la imagen (sin alterar proporcion ni pixeles del encuadre) con 'ID3 - 15 mm eq - f/x - 1/145 s - ISO 100', tres estilos (ninguno / datos / personalizado) y guardar SIEMPRE tambien la version sin marca.

### 118. Sin modo documento / escaner

`CameraActivity.kt` · coste: dias · riesgo: medio · necesita el telefono

Reutilizar el SDK de ML Kit ya empaquetado con la Document Scanner API: deteccion de bordes, correccion de perspectiva y exportacion a PDF multipagina. Es la funcion de mayor relacion valor/esfuerzo que piden los usuarios normales antes que RAW, y unifica el modo 'Escanear' con el lector de codigos.

### 119. Sin modo de alta resolucion nativa del sensor

`Camera2Controller.kt` · coste: horas · riesgo: medio · necesita el telefono

La app se queda en 12,6 MP fijos. Leer el tamano maximo de SCALER_STREAM_CONFIGURATION_MAP para ID3/ID6 y ofrecerlo como modo, con aviso de que con poca luz gana el binning. Es barato y de alto retorno.

### 120. Sin bracketing de exposicion

`Camera2Controller.kt` · coste: horas · riesgo: medio · necesita el telefono

Una de las dos herramientas que mas valen y que el rival no tiene. Implementar con captureBurst: 3/5/7 tomas a +-1/+-2 EV usando CONTROL_AE_EXPOSURE_COMPENSATION o SENSOR_EXPOSURE_TIME, guardando la serie completa.

### 121. Sin focus stacking (apilado de enfoque)

`Camera2Controller.kt` · coste: dias · riesgo: medio · necesita el telefono

La otra herramienta clave en macro que el rival no tiene: barrer LENS_FOCUS_DISTANCE entre dos limites marcados por el usuario con captureBurst y guardar la serie completa. La fusion puede quedar fuera de la app (el usuario apila en el PC) sin perder valor.

### 122. Sin larga exposicion

`NightStacker.kt` · coste: dias · riesgo: medio · necesita el telefono

Ni exposicion larga real ni por apilado. Con el motor de noche ya arreglado (exposicion medida + apilado en lineal), la larga exposicion por apilado es el siguiente paso natural: N fotogramas al maximo de SENSOR_INFO_EXPOSURE_TIME_RANGE sumados en vez de promediados, con presupuesto de tiempo visible.

### 123. Sin deteccion automatica de escena nocturna

`CameraActivity.kt` · coste: horas · riesgo: bajo · necesita el telefono

El modo noche es un chip manual y es excluyente con RAW y con el escaneo de codigos, lo que obliga al usuario a saber que combinacion es valida. Sugerirlo cuando lastAeIso y lastAeExpNs superen un umbral (pastilla 'Modo noche?' de un toque) y explicar en la interfaz por que se desactivan RAW o QR al activarlo, en vez de apagarlos en silencio.

### 124. Personalizacion de la interfaz inexistente

`CameraActivity.kt` · coste: dias · riesgo: bajo · necesita el telefono

Acento fijo (#FFFF9E00), sin modo zurdo, sin reordenar ni ocultar chips, sin modo de arranque configurable y con los botones de volumen fijados a disparo. Anadir tres acentos elegibles via tema, obturador reubicable izquierda/derecha, arrastrar para ordenar y ocultar los chips (persistido en prefs) y selector de accion para el volumen (disparo / zoom / exposicion).

### 125. El panel PRO no compite

`CameraActivity.kt` · coste: dias · riesgo: bajo · necesita el telefono

Seis parametros (EV/ISO/VEL/WB/K/AUTO) comparten un unico SeekBar y hay un solo boton AUTO global. Dar un boton 'A' independiente por parametro, un slider por parametro seleccionado con lectura numerica clara, y presets guardables (incluida la distancia de enfoque manual).

### 126. El intervalo del time-lapse esta fijo y no se expone

`Camera2Controller.kt` · coste: minutos · riesgo: bajo · necesita el telefono

setCaptureRate(2.0) hardcodeado. Exponer el intervalo al usuario (1s / 2s / 5s / 10s / 30s) con estimacion de duracion final del clip.

### 127. Solo espanol, con cadenas incrustadas fuera de strings.xml

`strings.xml` · coste: horas · riesgo: bajo · sin dispositivo

'Copiar', 'Abrir', 'Cerrar', 'LENTES', 'atras', 'WhatsApp', 'HD' en el XML y 'Modo noche ON', 'Rafaga', 'Copiado', 'RAW + JPEG', 'Esta lente no soporta RAW', 'WB manual no disponible', 'Ultra HDR no disponible en esta lente' en Kotlin. No hay values-en. Extraer TODOS los literales a strings.xml y anadir values-en como minimo; sin esto la app no es distribuible y la mitad de los mensajes no se pueden traducir.

### 128. Faltan modos con demanda real: retrato, panoramica, camara lenta

`Camera2Controller.kt` · coste: dias · riesgo: alto · necesita el telefono

Son los que un usuario normal pide antes que RAW. Camara lenta: comprobar antes si el HAL expone CONSTRAINED_HIGH_SPEED_VIDEO en ID3/ID6. Retrato y panoramica se pueden dejar fuera si se dice con honestidad en la lista de limitaciones, en vez de anunciarlos.

---

## INTEGRACION CON EL SISTEMA

### 129. CRITICO: se declara VIDEO_CAPTURE pero nunca se devuelve el video

`CameraActivity.kt` · coste: horas · riesgo: medio · necesita el telefono

El manifiesto declara android.media.action.VIDEO_CAPTURE, pero armIntentCapture solo instala jpegSink (fotos): al parar la grabacion el video se guarda en DCIM/Camera y la app que llamo recibe siempre RESULT_CANCELED; EXTRA_OUTPUT se ignora por completo en video. Cualquier app que pida grabar un video se queda sin nada. En onRecordingChanged(false), si captureIntent && captureVideo, pasar el Uri destino a openVideoOutput (o copiar el mp4 a esa Uri) y hacer setResult(RESULT_OK, Intent().setData(uri).addFlags(FLAG_GRANT_READ_URI_PERMISSION)) + finish(). Mitigacion inmediata si no se implementa: QUITAR esa linea del manifiesto para no anunciar algo que no se cumple.

### 130. La foto devuelta a otra app sale con el filtro de color puesto

`CameraActivity.kt` · coste: minutos · riesgo: bajo · necesita el telefono

armIntentCapture() se ejecuta ANTES de restoreSettings(), que reaplica el filtro guardado; ese matrix se aplica al JPEG antes de entregarlo al jpegSink. Si el usuario dejo puesto Vintage o B/N, la foto que se le devuelve al banco, al formulario o a WhatsApp sale filtrada sin que nadie lo pida y sin forma de verlo. Lo mismo con Ultra HDR para receptores que no lo esperan. Forzar entrega neutra en armIntentCapture: filterIndex=0, setCaptureColorMatrix(null), ocultar chipFilter y considerar setHdrEnabled(false).

### 131. No hay confirmacion antes de devolver la foto a otra app

`CameraActivity.kt` · coste: horas · riesgo: bajo · necesita el telefono

Se hace setResult(RESULT_OK) y finish() en cuanto se escribe el archivo: un toque accidental envia una foto movida al formulario del banco sin posibilidad de repetirla. Insertar una pantalla de revision con la foto a tamano completo y dos botones grandes, 'Usar esta' y 'Repetir'; solo al confirmar llamar a setResult y finish. Es el comportamiento que espera cualquiera que haya usado la camara de fabrica.

### 132. No hay onNewIntent: los intents reutilizados se ignoran en silencio

`CameraActivity.kt` · coste: horas · riesgo: medio · necesita el telefono

Toda la deteccion de intent (action, EXTRA_OUTPUT, pickContent) se hace UNICAMENTE en onCreate y no hay override de onNewIntent. Si el sistema reusa la instancia, el intent nuevo se ignora: la app llamante espera un resultado que nunca llega; y al reves, una instancia que venia de un IMAGE_CAPTURE puede quedar con jpegSink activo mandando la siguiente foto del usuario a un llamador inexistente. Anadir onNewIntent que haga setIntent y re-evalue action/EXTRA_OUTPUT, llamando a armIntentCapture() o restableciendo jpegSink=null y la visibilidad de miniatura/modeToggle/chipWa.

### 133. La miniatura devuelta en el extra 'data' puede reventar el Binder

`CameraActivity.kt` · coste: minutos · riesgo: bajo · sin dispositivo

Se escala a 400 px de lado en ARGB_8888 = hasta 640 KB parcelados en una sola transaccion Binder, contra un limite duro de ~1 MB compartido: en llamadores con payload propio puede saltar TransactionTooLargeException y la app que pidio la foto se queda sin resultado o crashea. El propio comentario del codigo lo intuye. Bajar a 256 px y/o usar RGB_565 (256*256*2 = 128 KB), que es el orden que usa AOSP.

### 134. writeSharedJpeg borra toda la cache y rompe adjuntos pendientes

`CameraActivity.kt` · coste: minutos · riesgo: bajo · sin dispositivo

Antes de escribir la foto nueva se borra TODO el contenido de cacheDir/compartir, asi que la Uri de FileProvider entregada a una app anterior queda apuntando a un fichero inexistente: se adjunta la foto en un correo que se envia mas tarde, el usuario vuelve a la camara, captura para otra app y el adjunto pendiente se rompe con FileNotFoundException. Borrar solo lo caducado (mas de 6 h) o conservar siempre los 3 ultimos ficheros.

### 135. La app puede compartir por WhatsApp una foto de OTRA aplicacion

`CameraActivity.kt` · coste: minutos · riesgo: bajo · necesita el telefono

El fallback de latestMediaUri busca RELATIVE_PATH LIKE '%DCIM/Camera%', la carpeta COMPARTIDA con la camara de fabrica. Si ultimoGuardado es null (arranque en frio, tras onPause, o si el guardado fallo), la miniatura y sobre todo shootAndShareWhatsApp pueden coger y ENVIAR una foto tomada por otra app. GalleryActivity ya evita justo esto filtrando por nombre. Anadir 'AND DISPLAY_NAME LIKE MACRO_%' y un LIMIT 1.

### 136. El escaner de codigos sigue activo durante una captura pedida por otra app

`CameraActivity.kt` · coste: minutos · riesgo: bajo · sin dispositivo

En modo intent se ocultan miniatura, modeToggle y chipWa, pero el escaneo automatico sigue y showQrResult puede desplegar la tarjeta QR con botones Abrir/Copiar encima del flujo de captura de otra app. Aparte de la confusion, es CPU gastada en un flujo que solo tiene que sacar una foto y volver. En armIntentCapture ocultar qr_card y no programar el escaner, con el mismo guard al principio de scanViewfinderForCodes.

### 137. No se puede disparar desde la pantalla de bloqueo ni hay atajos del sistema

`AndroidManifest.xml` · coste: dias · riesgo: medio · necesita el telefono

Solo se declaran STILL_IMAGE_CAMERA y VIDEO_CAMERA; cero coincidencias de shortcut, TileService y AppWidget en todo el proyecto. Declarar INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE con showWhenLocked y una Activity en modo seguro que solo permita disparar y no abrir la galeria; anadir res/xml/shortcuts.xml (Selfie, Video, Macro, Ultimo QR) y un TileService de Ajustes rapidos.

### 138. La miniatura queda rota tras grabar un video

`build.gradle` · coste: minutos · riesgo: bajo · necesita el telefono

openVideoOutput asigna lastSavedUri con la Uri del VIDEO, y refreshThumbnail se la pasa a coil, pero en build.gradle solo esta 'io.coil-kt:coil:2.6.0' SIN coil-video (no hay VideoFrameDecoder): no se puede decodificar el mp4 y la miniatura se queda con la imagen anterior. Peor: shareLatestToWhatsApp usaria esa Uri de video con setType('image/jpeg'). Anadir coil-video y registrar VideoFrameDecoder (o usar contentResolver.loadThumbnail), y fijar el mime segun el tipo real al compartir.

### 139. allowBackup=true sin reglas: se restaura un ID de lente inexistente

`AndroidManifest.xml` · coste: minutos · riesgo: bajo · necesita el telefono

Las preferencias, incluido 'cameraId', se restauran en otro telefono y pueden apuntar a un ID de lente inexistente o DANADO en ese hardware -exactamente lo que este proyecto debe evitar. Anadir fullBackupContent/dataExtractionRules excluyendo la clave cameraId, y validar en el arranque que el ID guardado sigue existiendo en getCameraIdList() antes de abrirlo, cayendo a SetupActivity si no.

### 140. Higiene de release: version congelada y contrasenas del keystore en claro

`build.gradle` · coste: horas · riesgo: medio · sin dispositivo

versionCode 1 / versionName 1.0 congelados desde el inicio, y storePassword/keyPassword como 'camara123' en claro dentro de app/build.gradle (repetida tres veces, y el fichero esta en el repo). Versionado automatico desde el tag de GitHub Actions, mover las contrasenas a gradle.properties fuera del repo o a secretos de CI y ROTAR la clave, y activar R8 en release con el modelo de barcode de Play Services bajo demanda para bajar de los 27 MB.

### 141. Basura de trabajo en la raiz del repositorio

`.gitignore` · coste: minutos · riesgo: bajo · sin dispositivo

Hay ~12 ficheros scratch_*.xml y varios scratch_*.jpg sueltos en la raiz del proyecto (dos de ellos sin seguimiento en git). Mover lo que sirva a una carpeta scratch/ ignorada por .gitignore y borrar el resto: hoy compiten visualmente con los ficheros reales y ensucian cualquier busqueda.

---

## ROBUSTEZ Y BUGS

### 142. close() no invalida cameraGen: la lente queda retenida

`Camera2Controller.kt` · coste: horas · riesgo: medio · necesita el telefono

Si openCamera() esta en vuelo (el HAL aun no entrego onOpened) y llega close() -caso real en onPause y en flipCamera, que hace close() seguido de open()- la comprobacion 'gen != cameraGen' de onOpened NO detecta nada: o se asigna cameraDevice y se llama a startPreview sobre un controlador ya desmontado (backgroundHandler ya es null), o el callback ni se entrega y el CameraDevice queda abierto sin que nadie lo cierre. En ColorOS eso deja la lente ocupada por el propio proceso y el open siguiente falla con CAMERA_IN_USE. Incrementar cameraGen al principio de close() y de switchToLens, y registrar el StateCallback con uiHandler en vez de backgroundHandler para que los callbacks tardios se sigan entregando.

### 143. Carrera de datos sobre previewRequestBuilder

`Camera2Controller.kt` · coste: horas · riesgo: alto · necesita el telefono

CaptureRequest.Builder no es thread-safe y se muta desde el hilo de fondo (unlockFocusAfterShot, startPreview/onConfigured) y desde el de UI (setZoom, setFocusPoint, applyAndUpdate, setEv, setWhiteBalance, setFlashMode) a la vez: ajustes que se pierden, peticiones inconsistentes o IllegalStateException al construir mientras el usuario pellizca el zoom. Ademas es lateinit y nunca se invalida, asi que isInitialized sigue siendo true apuntando al builder de un CameraDevice ya cerrado. Serializar TODA mutacion en el backgroundHandler y sustituir el lateinit por 'private var previewRequestBuilder: CaptureRequest.Builder? = null', puesto a null en close() y switchToLens.

### 144. setUpOutputs se ejecuta desde dos hilos sin sincronizacion

`Camera2Controller.kt` · coste: horas · riesgo: alto · necesita el telefono

Se llama desde openCamera (hilo de UI) y desde postRebuildSession/onConfigureFailed (backgroundHandler), y reasigna imageReader/rawReader/nightReader/qrReader. Dos ejecuciones solapadas pueden cerrar dos veces el mismo ImageReader, dejar huerfano el que acaba de crear la otra, o registrar un listener sobre un reader que ya no esta en la sesion (fotos que no llegan nunca -> watchdog). Ejecutar SIEMPRE setUpOutputs/openCamera/startPreview en el backgroundHandler (open() ya arranca el hilo antes) y comprobar un token de generacion dentro del post.

### 145. Fuga de ImageReader a resolucion completa en el fallback de Ultra HDR

`Camera2Controller.kt` · coste: minutos · riesgo: bajo · sin dispositivo

setUpOutputs hace 'imageReader = ImageReader.newInstance(...)' SIN cerrar el anterior (a diferencia de rawReader/nightReader/qrReader, que si se cierran), asi que cada intento fallido de JPEG_R filtra un reader de 2 buffers a resolucion maxima (decenas de MB de memoria nativa). Ademas la CameraCaptureSession fallida que llega como parametro nunca se cierra, ni aqui ni en el fallback de RAW. Cerrar imageReader al principio de setUpOutputs y llamar a session.close() al entrar en onConfigureFailed.

### 146. Un fallo al iniciar video deja el visor muerto y fuga el MediaRecorder

`Camera2Controller.kt` · coste: horas · riesgo: medio · necesita el telefono

startVideo cierra y anula captureSession ANTES de crear la sesion de grabacion. Si onConfigureFailed dispara (o salta el catch), solo se llama a fail(): 'recording' sigue false, videoSessionActive queda en true, el MediaRecorder queda preparado reteniendo mic y encoder sin liberarse nunca, el ParcelFileDescriptor sigue abierto y la fila de MediaStore queda con IS_PENDING=1 (archivo fantasma). Como isRecording es false, stopVideo sale de inmediato: no hay forma de recuperar, la vista previa no vuelve. En ambas rutas: reset+release del MediaRecorder, videoSessionActive=false, finalizeVideo (o borrar la fila pendiente) y volver a startPreview antes de reportar el error.

### 147. El ajuste de Ultra HDR nunca se restaura al arrancar

`Camera2Controller.kt` · coste: minutos · riesgo: bajo · necesita el telefono

setHdrEnabled hace 'val target = enabled && hdrSupported', pero hdrSupported solo se conoce despues del primer setUpOutputs (es decir, despues de open()), y restoreSettings corre al final de onCreate, antes de onResume->startCamera: la llamada devuelve false SIEMPRE. El usuario activa Ultra HDR, cierra la app y al volver esta apagado en silencio, mientras la preferencia sigue en true (asi que el primer toque tampoco cuadra). Separar deseo de capacidad: anadir hdrRequested y un presetHdr(on) analogo a presetCaptureSettings, y en setUpOutputs hacer hdrEnabled = hdrRequested && hdrSupported notificando el valor real a la UI.

### 148. El chip de camara frontal miente tras bloquear la pantalla

`CameraActivity.kt` · coste: minutos · riesgo: bajo · necesita el telefono

startCamera() fuerza camCycleIndex=0, facing='back' y abre siempre la trasera, pero el texto y color de chipFlip solo se tocan dentro de flipCamera(). Si el usuario esta en la frontal y bloquea/desbloquea (o cambia de app y vuelve), el visor vuelve a la trasera mientras el chip sigue diciendo 'frontal' en ambar, y el siguiente toque lleva a la frontal 1 en vez de volver atras. Extraer updateFlipChip() y llamarlo desde startCamera() y flipCamera().

### 149. La insignia AE/AF LOCK se queda encendida con el bloqueo deshecho

`CameraActivity.kt` · coste: minutos · riesgo: bajo · necesita el telefono

Camera2Controller.open() resetea aeLocked y afLocked a false, pero la Activity conserva aeAfLocked=true y deja aeLockBadge VISIBLE. Tras un onPause/onResume, un flip o un cambio de lente, la insignia sigue en pantalla sin bloqueo real, y el siguiente toque largo lo pone a false: hacen falta dos toques largos para volver a bloquear. Anadir aeAfLocked=false y aeLockBadge.visibility=GONE en startCamera() y flipCamera().

### 150. El temporizador no se cancela al salir ni al cambiar de modo

`CameraActivity.kt` · coste: minutos · riesgo: bajo · necesita el telefono

El runnable de la cuenta atras solo se cancela al volver a pulsar el obturador y en onDestroy: onPause no lo cancela (solo quita autoScanTick) y setMode/toggleRecord tampoco. Consecuencias reales: (a) temporizador de 10 s, el usuario sale de la app y a los 10 s se dispara takePhoto con la camara cerrada -> Toast de error en segundo plano; (b) si cambia a VIDEO o empieza a grabar durante la cuenta atras, salta una foto fija sobre una sesion de video sin el surface del ImageReader; (c) el numero puede quedarse visible. Extraer cancelCountdown() y llamarlo en onPause, setMode y toggleRecord.

### 151. Doble apertura de la lente en el primer arranque

`CameraActivity.kt` · coste: minutos · riesgo: medio · necesita el telefono

El resultado del ActivityResult del permiso se entrega ANTES de onResume, asi que se ejecuta startCamera() en el callback y, acto seguido, onResume vuelve a ver el permiso concedido y llama startCamera() otra vez: dos manager.openCamera() del mismo ID en vuelo. El token cameraGen cierra el primero, pero en este HAL de ColorOS (con la ID 0 danada) abrir dos veces es exactamente el escenario que cuelga el HAL; ademas se resetean currentZoom y zoomRestored dos veces. No llamar a startCamera desde el callback del permiso, o anadir una guarda de reentrada 'abriendo' que se ponga a false en onReady/onError y en onPause.

### 152. El stream RAW se atasca hasta reabrir la camara

`Camera2Controller.kt` · coste: minutos · riesgo: medio · necesita el telefono

onRawAvailable sobrescribe pendingRawImage sin cerrar la anterior y onCaptureFailed no limpia pendingRawImage/pendingRawResult; con maxImages=2 basta un par de fallos para que el reader se quede sin buffers y RAW deje de funcionar. Ademas captureStillNow limpia pendingRawImage desde el hilo de UI mientras onRawAvailable la escribe desde el hilo de camara: carrera con riesgo de doble close. Cerrar la anterior antes de asignar, limpiar en onCaptureFailed y mover toda la manipulacion de pendingRaw* al backgroundHandler.

### 153. Visor negro silencioso tras una reconstruccion de sesion

`Camera2Controller.kt` · coste: horas · riesgo: medio · necesita el telefono

El Runnable de postRebuildSession cierra y anula captureSession e imageReader y luego llama a startPreview, que sale sin hacer nada si cameraDevice o surfaceTexture son null. Si entre el post y su ejecucion hubo un switchToLens, un onDisconnected o un close(), la sesion queda destruida y nadie la reconstruye: pantalla negra, obturador inutil y NINGUN mensaje de error. Capturar un token de generacion al encolar y salir si cambio; y en startPreview, cuando device/surface sean null, registrar el estado y programar un reintento o llamar a fail() para que la UI avise.

### 154. fail() deja la lente danada abierta y retenida

`Camera2Controller.kt` · coste: minutos · riesgo: medio · necesita el telefono

fail() no cierra ni el CameraDevice ni la CameraCaptureSession: justo el caso para el que existe el watchdog ('esta lente no respondio, puede ser la danada') deja la lente ID0 ABIERTA y retenida por el proceso, que es exactamente lo que cuelga el HAL de ColorOS. Tampoco invalida cameraGen, asi que un onOpened tardio la vuelve a asignar. Anadir cameraGen++, cerrar sesion y device con try/catch y ponerlos a null antes de notificar onError.

### 155. Modo noche: doble invocacion del callback

`Camera2Controller.kt` · coste: horas · riesgo: medio · necesita el telefono

finishNightStack corre en el hilo de camara y abortNight en el de UI (watchdog de 8 s); ambos hacen 'if (!nightCapturing) return; nightCapturing = false; ...' con un campo que no es @Volatile y una secuencia comprobar-y-asignar no atomica: el usuario puede recibir onResult(false) y onResult(true) para la misma foto (toast de error mas miniatura), o ninguno. Ademas abortNight hace nightStacker?.release() mientras addFrame lo esta usando. Cerrar con un AtomicBoolean compartido (compareAndSet) y ejecutar abortNight en el backgroundHandler para que toda la maquina de estado viva en un hilo.

### 156. Cinco campos compartidos entre hilos sin @Volatile

`Camera2Controller.kt` · coste: minutos · riesgo: medio · sin dispositivo

pendingResult, afWaitAction, aeWaitAction, nightCapturing y lastFocusState se leen y escriben desde UI y desde el hilo de camara sin ninguna sincronizacion, a diferencia de cameraDevice/captureSession/afTriggerFrame/qrBusy que si son volatiles. Consecuencias reales: el hilo de camara puede no ver a tiempo el afWaitAction recien armado (la foto siempre tarda los 400 ms del timeout) o el watchdog y el listener invocan el callback los dos. Marcar los cinco como @Volatile y convertir los pares comprobar-y-anular en operaciones atomicas con AtomicReference.getAndSet(null).

### 157. Exclusion mutua incompleta entre RAW, HDR, noche y QR

`Camera2Controller.kt` · coste: horas · riesgo: medio · necesita el telefono

setRawEnabled(true) apaga hdr, noche y QR, pero setHdrEnabled(true) solo apaga RAW, y setNightEnabled/setQrEnabled no apagan HDR. Con noche + Ultra HDR se configura preview + JPEG_R a resolucion completa + YUV a resolucion completa, combinacion que el HAL rechaza casi seguro -> onConfigureFailed -> Ultra HDR se apaga solo sin que el usuario entienda por que. Peor: si el modo noche sigue ON con HDR, el obturador seguira llamando a takeNightPhoto mientras la sesion esta en JPEG_R (dos rutas compitiendo). Centralizar en un selectMode() que garantice como maximo un stream extra y repintar los chips.

### 158. Un fallo de captura solo produce un toast generico

`Camera2Controller.kt` · coste: horas · riesgo: medio · necesita el telefono

No hay reintento ni reapertura automatica tras onError/onDisconnected del CameraDevice, y el usuario solo ve R.string.photo_error. Dar mensajes con causa (sesion reconstruyendose, sin espacio, HAL sin respuesta, camara tomada por otra app) y reabrir automaticamente con backoff cuando la Activity vuelva a primer plano.

### 159. onDestroy recicla el bitmap del escaner con una deteccion en vuelo

`CameraActivity.kt` · coste: minutos · riesgo: bajo · sin dispositivo

scanBitmap?.recycle() sin comprobar autoScanBusy: si hay una deteccion de ML Kit en curso sobre ese mismo bitmap (se reutiliza a proposito y el InputImage no lo copia), el worker puede tocar un bitmap reciclado -> IllegalStateException en un hilo de fondo. La ventana es pequena pero real (salir justo despues de encuadrar un codigo). Cerrar autoScanner primero y reciclar solo si !autoScanBusy; si esta ocupado, basta con poner scanBitmap = null.

### 160. Posible crash al salir con el QR por YUV activo

`Camera2Controller.kt` · coste: minutos · riesgo: bajo · sin dispositivo

El addOnCompleteListener de ML Kit corre en el hilo principal y hace image.close(); si entretanto close() (onPause) o setUpOutputs cerraron el qrReader, se cierra una Image de un reader ya destruido, lo que en varios HAL lanza IllegalStateException sin capturar en el hilo principal. Tampoco hay guarda de generacion para descartar detecciones de una sesion vieja. Envolver el close en try/catch, comprobar un token de sesion antes de emitir onQrDetected y retrasar el cierre del qrReader hasta que qrBusy vuelva a false.

### 161. Contabilidad del modo noche y sesion huerfana

`Camera2Controller.kt` · coste: minutos · riesgo: bajo · sin dispositivo

(a) onNightImage incrementa nightCount aunque acquireNextImage devuelva null o lance, de modo que la rafaga puede darse por terminada habiendo apilado menos fotogramas de los pedidos (menos reduccion de ruido, en silencio): mover el ++ dentro del bloque de exito y dejar que el watchdog cubra los perdidos. (b) En onConfigured, cuando cameraDevice ya es null se hace return sin cerrar la CameraCaptureSession recien configurada, que queda huerfana: sustituir por session.close() antes del return.

### 162. El slider del panel PRO se queda mudo tras tocar WB

`CameraActivity.kt` · coste: minutos · riesgo: bajo · sin dispositivo

cycleWb() pone proParam = 'wb', pero applyParam() no tiene rama 'wb': a partir de ese momento mover el deslizador no hace absolutamente nada y no hay ninguna senal de por que; solo se recupera tocando EV/ISO/VEL/K. O no cambiar proParam en cycleWb, o deshabilitar visualmente el slider mientras proParam=='wb' (isEnabled=false y alpha 0.4f), reactivandolo en selectParam/selectKelvin.

### 163. El EV del panel PRO no se sincroniza con el slider rapido

`CameraActivity.kt` · coste: minutos · riesgo: bajo · sin dispositivo

applyParam rama 'ev' solo pinta el texto y no actualiza el campo evSteps, que si usan evSlider y showEvQuick: tras cambiar el EV en PRO, la etiqueta y la posicion del slider rapido muestran el valor ANTIGUO y el primer roce pisa lo puesto en PRO. Asignar evSteps = steps antes de controller.setEv(steps), y anadir evSteps = 0 en resetAuto().

### 164. El boton de cambiar lente queda tintado para siempre tras un error

`CameraActivity.kt` · coste: minutos · riesgo: bajo · sin dispositivo

Ante cualquier error se tine btnChangeLens con el color de acento como aviso permanente, pero NADA lo limpia nunca: tras un error transitorio ('Otra app tomo la camara', normal en ColorOS al volver de otra app) el boton queda marcado para siempre aunque la lente siguiente abra perfectamente. Limpiarlo en controller.onReady al confirmar que la sesion arranco.

### 165. Codigo muerto: toda la ruta de QR por YUV del controlador

`Camera2Controller.kt` · coste: horas · riesgo: bajo · necesita el telefono

La Activity cablea controller.onQrDetected, pero setQrEnabled no se llama desde ningun sitio, asi que qrEnabled es siempre false y qrReader, onQrImage, setQrEnabledInternal y las ramas de qrEnabled en setUpOutputs/startPreview son inalcanzables: se mantiene y se toca en refactors codigo que nunca se ejecuta, y ademas es la implementacion BUENA (sin readback de GPU). O borrarla, o usarla en lugar del escaneo por getBitmap documentando el coste del tercer stream.

---

## ACCESIBILIDAD

### 166. Solo 6 contentDescription para ~30 controles

`activity_camera.xml` · coste: horas · riesgo: bajo · necesita el telefono

Los chips de opciones son TextView cuyo texto accesible es el emoji, asi que un lector de pantalla lee 'alto voltaje off' donde deberia decir 'flash apagado'. Poner contentDescription y stateDescription en los 20+ chips (flash, temporizador, cuadricula, noche, mas, HDR, RAW, filtro, PRO, proporcion, resolucion, lentes, voltear, WhatsApp, EV/ISO/VEL/WB/K/AUTO y los de video) describiendo funcion Y estado actual.

### 167. El area de gestos no es alcanzable sin vista

`activity_camera.xml` · coste: minutos · riesgo: bajo · necesita el telefono

gesture_area concentra enfoque por toque, pellizco de zoom, doble toque y pulsacion larga para bloquear AE/AF, y no tiene ni contentDescription ni acciones accesibles; en la pantalla interior ocupa 692x716dp. Anadir contentDescription ('Visor: toca para enfocar, manten para bloquear AE/AF') y ViewCompat.addAccessibilityAction para enfocar al centro y para el bloqueo AE/AF.

### 168. Los cambios de estado no se anuncian

`CameraActivity.kt` · coste: horas · riesgo: bajo · necesita el telefono

Al activar noche, RAW, HDR o cambiar de lente no hay ningun anuncio: el usuario con TalkBack no sabe que ha pasado (y el feedback visual son Toast que quedan lejos del control). Llamar a announceForAccessibility en cada cambio de estado y actualizar stateDescription del chip correspondiente.

### 169. Overlays decorativos anunciados como la miniatura de la ultima foto

`activity_camera.xml` · coste: minutos · riesgo: bajo · sin dispositivo

lens_fade (el fotograma congelado del cambio de lente) y magnifier (la lupa de enfoque) reutilizan contentDescription=@string/cd_thumbnail, asi que TalkBack los anuncia como si fueran la miniatura. Poner importantForAccessibility='no' en ambos y quitar el contentDescription reciclado.

### 170. Los chips no se anuncian como botones ni tienen estado pulsado

`themes.xml` · coste: horas · riesgo: bajo · necesita el telefono

Son TextView clickables con shape plano: TalkBack los lee como texto, no hay ripple ni feedback tactil visual. Migrarlos a MaterialButton o anadir un accessibilityDelegate con className Button, foreground=?attr/selectableItemBackground y un selector con state_selected.

### 171. El panel de ajustes esta fuera del alcance del pulgar

`activity_camera.xml` · coste: dias · riesgo: medio · necesita el telefono

El panel 'Mas' esta anclado ARRIBA (marginTop 96dp): en una pantalla de 2480 px es inalcanzable con una mano y ademas tapa el visor. Moverlo a un bottom-sheet a media altura, que ademas resuelve el alcance y el solape con lens_chip, ae_lock_badge y la lupa.

### 172. Verificacion sistematica de contraste y objetivos tactiles

`activity_camera.xml` · coste: horas · riesgo: bajo · necesita el telefono

Establecer como criterio de aceptacion 4,5:1 de contraste en todo el HUD medido sobre capturas ADB de escenas quemadas y a contraluz del propio CPH2765, y 48dp minimos en todos los objetivos tactiles (hoy fallan tab_photo/tab_video con ~29dp). Anadir esa comprobacion a la lista de QA de cada release en las dos pantallas del plegable.

---

## Descartado por no realista o dependiente del HAL

- Arreglar el enfoque de la camara principal (ID 0): es un fallo fisico de fabrica. No hay nada que hacer por software y NUNCA debe abrirse esa lente porque cuelga el HAL de ColorOS.
- Zero shutter lag por reprocesado (createReprocessableCaptureSession): depende de que REQUEST_AVAILABLE_CAPABILITIES declare PRIVATE_REPROCESSING o YUV_REPROCESSING en la lente ID3/ID6, cosa poco habitual en lentes secundarias de ColorOS. Hay que comprobarlo en el dispositivo ANTES de planificarlo; si no esta, lo unico realista es un anillo de fotogramas del stream de preview (menor resolucion que la foto) o precalentar el AF en el ACTION_DOWN.
- Fusion nocturna en dominio RAW al nivel de la competencia: apilar RAW_SENSOR de 12-16 bits con alineacion piramidal y por bloques en Kotlin puro sobre 12,6 MP es inviable en tiempos aceptables. Requeriria NDK (C++/NEON) o GPU (RenderEffect/GLES compute), que es un proyecto en si mismo, no un arreglo.
- Retrato con desenfoque comparable al de la camara de fabrica: necesita segmentacion o mapa de profundidad multi-camara. Sin APIs del fabricante y con una sola lente activa a la vez, el resultado seria claramente inferior. Mas honesto declararlo fuera de alcance que entregarlo mediocre.
- Panoramica: exige stitching propio (deteccion de puntos, homografias, blending). Son semanas de trabajo para una funcion que el usuario objetivo casi no usara.
- Camara lenta real (120/240 fps): solo posible si el HAL expone CONSTRAINED_HIGH_SPEED_VIDEO para ID3/ID6; en muchos dispositivos solo esta disponible en la camara principal, que aqui es justamente la danada. Verificar antes de prometerlo.
- Ultra HDR (JPEG_R) y RAW_SENSOR en la misma sesion: depende enteramente de lo que declare StreamConfigurationMap. Puede ser sencillamente imposible en este HAL; el codigo debe consultarlo y degradar con un mensaje claro, no asumirlo.
- Visor a 60 fps: solo si CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES ofrece [60,60] o [30,60] para esa lente y ese tamano de preview. En lentes secundarias suele estar limitado a 30. Es una comprobacion en dispositivo, no una decision de diseno.
- Gama amplia real (Display P3): el HAL entrega sRGB. Se puede (y se debe) embeber el perfil ICC sRGB, pero anunciar gama amplia sin que el pipeline la produzca seria mentir en los metadatos.
- Rear Display Mode via WindowAreaController (transferir la Activity a la pantalla de cubierta): requiere que ColorOS implemente esa API de androidx.window. Si no esta, hay que caer al respaldo con Presentation sobre el display secundario, que da menos control y no todas las ROMs lo exponen igual.
- Baseline Profile y Macrobenchmark dentro de GitHub Actions: la generacion del perfil y las mediciones necesitan un dispositivo o emulador; en el runner actual no hay ninguno. El perfil hay que generarlo localmente por ADB con el CPH2765 conectado y luego comitearlo.
- minifyEnabled true con ML Kit y Coil: tecnicamente hace falta, pero es un cambio con riesgo real de romper la app en runtime por reflexion. No se puede dar por bueno sin una tanda de pruebas completa en el dispositivo.
- Calibracion de exposicion entre lentes y validacion de la curva de tono: no se pueden hacer sin el telefono, una carta gris al 18% y luz controlada. Cualquier valor elegido a ojo desde el codigo sera otro numero inventado, como el que ya tiene el balance de blancos en Kelvin.
- Zoom optico continuo: no existe en este hardware. Todo lo que pase de la focal nativa de cada lente es recorte digital, y la unica mejora honesta es decirlo en pantalla (marcar el tramo digital) en vez de seguir mostrando milimetros congelados.