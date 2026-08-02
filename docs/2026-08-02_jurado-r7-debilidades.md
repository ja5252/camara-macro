# Ronda 7 — lo que el jurado dice que hay que arreglar

Marcador: **Hasselblad (ALFA) 75 — nuestra app (BETA) 0**, 5 empates. Medias 7.26 vs 4.64.

Ocho jueces nuevos, a ciegas. 243 debilidades senaladas, 242 unicas tras deduplicar.

| bloque | ALFA | BETA |
|---|---|---|
| 6. AUDIO | 7.1 | 1.6 |
| 5. VIDEO | 7.9 | 4.1 |
| 4. LAS DOS CAMARAS | 7.4 | 4.3 |
| 1. FOTO SIN FLASH | 7.9 | 5.2 |
| 2. FOTO CON FLASH | 5.5 | 2.8 |
| 9. VELOCIDAD | 6.8 | 4.6 |
| 10. AMPLITUD FUNCIONAL E INTEGRACION | 8.8 | 6.7 |
| 3. ZOOM | 7.1 | 5.2 |
| 7. BAJA LUZ Y MODO NOCHE | 7.0 | 5.9 |
| 8. INTERFAZ Y USO | 6.8 | 5.8 |

---

## Impacto CRITICO (35)

### 1. Fijar los parametros de audio en createRecorder: setAudioSamplingRate(48000), setAudioEncodingBitRate(192000-256000) y setAudioChannels(2) antes de prepare(). Hoy solo se llama a setAudioSource(MIC) y setAudioEncoder(AAC).

*1 juez(ces) lo senalaron.* El MP4 entregado lleva AAC-LC a 8000 Hz, 1 canal y 12,2 kbps: 4 kHz de ancho de banda, calidad de linea telefonica. Es el valor por defecto de MediaRecorder cuando no se le dice nada, y afecta a TODOS los videos que ha grabado la app.

### 2. No usar el LED con el teleobjetivo, o detectar el velo y descartar la toma: comparar el fotograma previo y el posterior al destello y, si el suelo de negros sube por encima de un umbral, repetir sin flash y avisar.

*1 juez(ces) lo senalaron.* foto_conflash_2.9x sale como niebla blanca: p1=121,8 y p99=209,6 (solo 88 de 255 niveles usados) y saturacion media 4,2, practicamente monocromo. La foto es inservible y la app la guarda igual.

### 3. Quitar el tope de 1920 px en la eleccion del tamano de grabacion (recSizes.filter{ it.width <= 1920 } en setUpOutputs) y subir videoTargetH al maximo razonable que soporte la lente, dejando 1080 como opcion y no como techo.

*1 juez(ces) lo senalaron.* El video medido es 1080p30 H.264 de 8 bits en 2026. La rama de 42 Mbps para altura >= 2000 ya existe en createRecorder pero el default nunca llega a ella, asi que la capacidad esta escrita y nunca se usa.

### 4. Corregir la medicion de exposicion con recorte digital: medir sobre el encuadre completo (o mezclar medicion global y recortada), poner techo de ISO y preferir bajar el obturador antes que subir la ganancia.

*1 juez(ces) lo senalaron.* Con la MISMA lente de 2.3 mm y el MISMO 1/60 s, el ISO va de 2650 (0.6x) a 9591 (1x) a 13778 (2x), 2,38 pasos, para solo 0,66 EV de escena mas oscura. El 2x es la peor imagen de todo el lote por culpa de esto.

### 5. Rehacer el balance del modo noche: o el apilado baja el ruido de forma medible, o no compensa el coste. Verificar sobre parche plano que la sigma de la salida apilada es MENOR que la de un disparo unico normalizando por resolucion, antes de aplicar cualquier realce.

*1 juez(ces) lo senalaron.* Medido: sigma 0,63 en la toma de noche frente a 0,50 en la normal de la misma escena, y ademas a menor resolucion (6,09 MP frente a 8,29 MP). Lo que sube es el laplaciano (140,9 frente a 77,9): eso es nitidez anadida, no relacion senal/ruido. Un apilado de 7 fotogramas deberia comprar ~1,4 pasos y no se mide ninguno.

### 6. Reparar el EXIF del camino de noche: escribir Orientation con un valor legal (1-8, no 0), y reponer Make, Model, DateTime, GPS, apertura, ISO, tiempo, balance y medicion; arreglar tambien el campo Software, que sale como 'Camara ? modo noche' con un caracter corrupto.

*1 juez(ces) lo senalaron.* El JPEG de noche solo trae 6 etiquetas de nivel superior y 4 en el IFD de EXIF, frente a las ~12 y ~35 del camino normal. Orientation=0 no es un valor valido de la norma y un lector estricto puede pintar la foto girada.

### 7. Audio de video a 8000 Hz, MONO y ~12 kbps. Leido en la AudioSampleEntry del propio mp4: channelcount=1, samplesize=16, samplerate=0x1f40=8000, esds maxBitrate ~12200. Causa exacta: createRecorder() en Camera2Controller.kt solo llama a setAudioSource(MIC) y setAudioEncoder(AAC), y nunca a setAudioSamplingRate, setAudioChannels ni setAudioEncodingBitRate, asi que MediaRecorder cae a sus valores por defecto. ARREGLO: setAudioSamplingRate(48000), setAudioChannels(2), setAudioEncodingBitRate(192000), y usar AAC-LC o HE-AAC segun el caso.

*1 juez(ces) lo senalaron.* 8 kHz deja el audio con 4 kHz de ancho de banda: calidad de llamada telefonica. Voces sordas, sin consonantes claras, sin brillo. Convierte cualquier video en inservible para publicar, y son tres lineas de codigo.

### 8. El flash sobre el TELEOBJETIVO destruye la foto con un velo de flare. foto_conflash_2.9x: p1=121.8, p99=209.6 (toda la imagen en 0.78 EV), saturacion 1.9, croma por fila 2.8 en el centro; visualmente un lavado lechoso blanco. La misma escena con el mismo tele SIN flash sale limpia y con color. ARREGLO: medir el velo (subida del percentil 1 respecto al fotograma de pre-flash) y, si supera umbral, no disparar el LED en esa lente; como minimo bloquear o advertir el flash cuando la lente activa es el tele.

*1 juez(ces) lo senalaron.* Es el peor fotograma de todo el lote y la app lo produce sin ningun aviso: el usuario pulsa flash en tele y se lleva una foto irrecuperable.

### 9. El paso de zoom 2x es un desastre de ruido de croma. ISO 13778 a 1/60 s, sigma 3.21, y al 100% la pared es un mosaico de manchas purpura, verdes y amarillas. Su nitidez nominal de 53.3 es RUIDO, no detalle: la energia de alta frecuencia 0.2055 cabalga sobre ese sigma. ARREGLO: denoise de croma agresivo y separado del de luma por encima de ISO ~6000 (el perfil actual elige NR por banda de ISO pero claramente no ataca el croma), y bajar la ganancia alargando la obturacion (ver punto siguiente).

*1 juez(ces) lo senalaron.* 2x es el paso de zoom mas usado en cualquier movil y es la peor imagen de las nueve. Rompe la escalera de calidad justo donde mas se nota.

### 10. El flash EMPEORA la foto a 1x en vez de mejorarla: la planta a medio metro sale quemada de verde y el salon a 3-4 m queda mas oscuro y sucio que sin flash. Solo aporta ~1.7 EV (BV -0.80 -> +0.97; misma luminancia con 3.17x menos exposicion). ARREGLO: sincronizacion lenta -- no acortar la obturacion al disparar (paso de 1/60 a 1/120, matando el ambiente); mantener el tiempo de la lectura sin flash y dejar que el LED solo rellene el primer plano. Y medir en la pre-captura si el sujeto esta dentro del alcance util (~2 m) antes de decidir disparar en modo AUTO.

*1 juez(ces) lo senalaron.* Un flash que hace peor la foto es peor que no tener flash. Hoy el modo AUTO no distingue 'hay sujeto cerca' de 'la escena esta lejos y oscura'.

### 11. Dominante VERDE del LED sin correccion. Con flash el balance pasa de calido (R/G 1.131, B/G 0.847) a verde dominante (R/G 0.964, B/G 0.950; G es el canal mas alto) y la saturacion media se hunde de 10.1 a 4.6; la dispersion de croma por fila cae de 25-29 a 11-15. El EXIF mantiene LightSource=21 (D65) con y sin flash. ARREGLO: perfil de balance de blancos especifico del flash -- capturar la referencia de croma del fotograma de pre-flash frente al de flash y aplicar COLOR_CORRECTION_TRANSFORM/COLOR_CORRECTION_GAINS para el iluminante del LED (o corregirlo como matriz de color en el post-procesado, que es lo que funciona cuando el HAL ignora la clave con AWB en AUTO).

*1 juez(ces) lo senalaron.* Es la firma del fosforo YAG del LED blanco y es exactamente lo que amarillea-verdea los tonos de piel. Cualquier retrato con flash saldra con la cara enfermiza.

### 12. Prohibir o compensar el flash en el teleobjetivo. Hoy foto_conflash_2.9x sale como niebla blanca: media 169,4, p1=121,8, recorrido tonal 88,8 y saturación 2,7%. Es el LED metiéndose en la óptica del tele (velo de reflexión). Mínimo: al pedir flash con la lente tele, o se cambia a gran angular avisando, o se baja la potencia con FLASH_MODE_SINGLE + compensación negativa, o se bloquea con un mensaje claro. Nunca disparar y entregar eso.

*1 juez(ces) lo senalaron.* Es la única toma del expediente literalmente inutilizable por decisión de la app, no por el hardware. Un usuario que use flash en tele pierde la foto siempre.

### 13. Configurar el audio en createRecorder (Camera2Controller.kt:4946). Faltan tres llamadas: recorder.setAudioSamplingRate(48000), recorder.setAudioChannels(2) y recorder.setAudioEncodingBitRate(192000). Hoy solo se llama a setAudioSource(MIC) y setAudioEncoder(AAC), así que Android impone sus valores por defecto y el clip sale a 8000 Hz MONO 12,2 kbps: ancho de banda de teléfono.

*1 juez(ces) lo senalaron.* Es el defecto de peor relación coste/daño del expediente: tres líneas de código convierten un audio de 1998 en un audio de 2026. Cualquier vídeo grabado hasta ahora es irrecuperable en audio.

### 14. Rehacer el punto de cruce entre lentes. setZoom cruza en 'gg >= zoomChain[i].second', o sea exactamente en 2.9x, el punto más tardío posible: todo el tramo 1,1x-2,9x lo sirve el recorte del gran angular. Medido: 2x da laplaciano 53,3 con ruido 3,21 a ISO 13778, mientras el tele a 2.9x da 348,6 con ruido 2,65 a ISO MÁS BAJO (12209). Opciones: recortar el zoom digital del gran angular a ~1,5x y que la siguiente pastilla salte directa al 2.9x óptico; o marcar el 2x como degradado; o eliminarlo.

*1 juez(ces) lo senalaron.* El sistema está entregando su peor imagen justo en el rango de zoom que más se usa, teniendo una lente muchísimo mejor disponible y sin usar.

### 15. Arreglar la cámara frontal antes de ofrecerla. El fichero entregado es un borrón marrón sin nada reconocible (2560x1440, 3,69 MP, ISO 8156, 1/20 s, EV -2,31) y la captura frontal.png confirma que el visor está igual. Hay que comprobar LENS_INFO_MINIMUM_FOCUS_DISTANCE y si hay AF disponible en la frontal; si es de foco fijo, avisar de la distancia hiperfocal; y si el visor no enfoca, no entregar la lente como usable.

*1 juez(ces) lo senalaron.* No hay una sola prueba de que la cámara frontal funcione en esta app. Es un tercio del bloque de cámaras y hoy es un cero.

### 16. Configurar el audio del MediaRecorder: setAudioSamplingRate(48000), setAudioChannels(2), setAudioEncodingBitRate(192000..256000) y cambiar AudioSource.MIC por AudioSource.CAMCORDER en createRecorder() (Camera2Controller.kt ~4946-4977).

*1 juez(ces) lo senalaron.* La pista entregada es AAC-LC a 8000 Hz, MONO, 12,2 kbps: ancho de banda tapado en 4 kHz, calidad de linea telefonica, exactamente los valores por defecto de MediaRecorder cuando no se configura nada. El codigo solo llama a setAudioSource(MIC) y setAudioEncoder(AAC). Ademas MIC es la ruta de llamada, con AGC y supresion de ruido de voz; CAMCORDER es la de video. Es el defecto mas grave de todo el expediente y se arregla con cuatro lineas.

### 17. Arreglar el flash: bajar el ISO y recalcular la exposicion cuando el flash dispara (secuencia de precaptura AE con AE_PRECAPTURE_TRIGGER_START y esperar a AE_STATE_FLASH_REQUIRED/CONVERGED antes de la captura), y probar con la lente y el LED limpios para descartar luz parasita mecanica.

*1 juez(ces) lo senalaron.* conflash_2.9x es inservible: p1=121,8 y p99=209,6, la imagen entera en 88 de 255 niveles, saturacion 1,9 (monocroma), nitidez 32,8, la mas baja del lote. conflash_1x tiene un velo sobre todo el encuadre y la saturacion cae de 10,1 a 4,6 respecto a la misma toma sin flash. El EXIF muestra que el AE mantuvo ISO 6056 y 2419 con el flash encendido en vez de bajarlo.

### 18. Etiquetar correctamente el color del video: BT.709 (primarias, matriz y transferencia) y rango limitado, en vez de smpte170m + bt470bg + color_range=pc.

*1 juez(ces) lo senalaron.* El archivo de 1920x1080 sale marcado como SD PAL con bandera de rango completo. Cualquier NLE o reproductor que respete las etiquetas vira el color y los niveles; es la clase de error que obliga a corregir clip por clip en montaje. Verificado con ffprobe sobre el video entregado.

### 19. Dejar activas las herramientas de analisis (histograma, cebras y focus peaking) MIENTRAS se graba, quitando 'controller.isRecording' de la guarda de analyzeFrame() (CameraActivity.kt ~3138).

*1 juez(ces) lo senalaron.* Hoy la linea 'if (!toolsOn || capturing || shutterHeld || controller.isRecording) return' apaga toda la monitorizacion justo al empezar a rodar. Un director de fotografia necesita el histograma y las cebras precisamente cuando esta grabando; apagarlas ahi invierte el proposito de la funcion.

### 20. Fijar el formato de audio en createRecorder(): recorder.setAudioSamplingRate(48000), recorder.setAudioChannels(2) y recorder.setAudioEncodingBitRate(192000..256000). Hoy no se llama a ninguna de las tres y la pista sale a 8000 Hz, MONO, 12.2 kbps.

*1 juez(ces) lo senalaron.* Es el defecto más grave de toda la app y se arregla en tres líneas. Medido con ffprobe sobre video_1x-VID_1785631966419.mp4: sample_rate=8000, channels=1, bit_rate=12200. El espectrograma que generé se corta a plomo en 4000 Hz: no existe nada por encima. Es ancho de banda de teléfono fijo — sin sibilancia, sin aire, sin transitorios, sin imagen estéreo, y con warbling de codec. El vídeo va a 16.6 Mbps y el audio a 12.2 kbps: 1360 a 1. Ninguna toma de esta app es utilizable en un montaje.

### 21. Cambiar MediaRecorder.AudioSource.MIC por AudioSource.CAMCORDER (con reserva a MIC si el dispositivo no lo expone).

*1 juez(ces) lo senalaron.* MIC es la fuente de VOZ: coge el micrófono inferior y le aplica el procesado de llamada del dispositivo. CAMCORDER es la fuente que Android define para vídeo: usa la matriz de micrófonos orientada a la cámara y no impone el mismo AGC/supresión. Con MIC, además, la imagen sonora no guarda ninguna relación con lo que se está encuadrando.

### 22. Avisar de forma inequívoca cuando se va a grabar SIN sonido: si RECORD_AUDIO está denegado, mostrar un icono de micrófono tachado permanente en el visor y un aviso antes de empezar, no grabar mudo en silencio.

*1 juez(ces) lo senalaron.* Verificado en CameraActivity.kt: el cuerpo del callback del permiso está vacío ('Permiso pre-concedido...') y startRec(withAudio=false) no muestra ningún mensaje. El usuario graba una toma entera y descubre en casa que no hay sonido. Es la clase de fallo que hace desinstalar una app de vídeo.

### 23. Desactivar el flash con el teleobjetivo, o rehacer el pre-flash de medición para esa lente. Hoy foto_conflash_2.9x es inservible.

*1 juez(ces) lo senalaron.* Medido: p1=121.8, p99=209.6 (todo el histograma comprimido en la mitad alta, sin un solo negro) y saturación media 1.9, prácticamente sin color. Visualmente es un velo lechoso uniforme sobre toda la escena: luz parásita del LED entrando en el objetivo tele. La foto no se puede usar para nada.

### 24. Añadir un vúmetro en vivo con pico y aviso de saturación en el HUD de grabación, y control de ganancia manual con AGC conmutable.

*1 juez(ces) lo senalaron.* Busqué 'micro', 'audio', 'vumetro', 'ganancia' y 'viento' en activity_camera.xml y en strings.xml: no hay ni una sola cadena. No existe absolutamente ningún control ni indicación de audio. La toma medida salió a -50.6 dBFS de media y -28.7 dBTP de pico, y el usuario no tenía forma alguna de saberlo mientras grababa.

### 25. Fijar el audio de vídeo a 48000 Hz, estéreo y 128-256 kbps: llamar a setAudioSamplingRate(48000), setAudioChannels(2) y setAudioEncodingBitRate(128000+) en createRecorder(), antes de setAudioEncoder.

*1 juez(ces) lo senalaron.* El vídeo entregado lleva AAC-LC a 8000 Hz MONO a 12,2 kbps — calidad de teléfono fijo, 4 kHz de ancho de banda — pegado a una imagen de 16,6 Mbps. Es la herencia de los valores por defecto de MediaRecorder porque el código nunca fija ninguno de los tres. Es el defecto más barato de arreglar y el más caro de dejar: descalifica todo el vídeo de la app. Agravante: la auditoría interna lo dio por bueno ('audio AAC correcto') sin mirar la cabecera.

### 26. Arreglar o desactivar el flash en el teleobjetivo: medir el velo (contraste p99-p1) tras el destello y, si cae por debajo de un umbral, no destellar en la lente tele y avisar al usuario.

*1 juez(ces) lo senalaron.* foto_conflash_2.9x es inutilizable: media 169.4 con p1 121.8 y p99 209.6 (88 niveles de 255 de recorrido) y saturación media 1.9 — prácticamente monocroma. A la vista es una niebla blanca. El destello está entrando directamente en la óptica del tele. Entregar esa foto es peor que no destellar.

### 27. Rehacer el paso 2x: o pasar a la lente tele con recorte hacia fuera imposible y por tanto limitar el zoom digital del angular a ~1,5x, o dejar de reescalar y entregar el recorte a su resolución real.

*1 juez(ces) lo senalaron.* foto_sinflash_2x es la peor imagen del lote: laplaciano 10.2, energía alta frecuencia 0.0023, 34,4% de píxeles sin ningún gradiente horizontal, ISO 13778 y manchas de croma a 100%. Es un recorte 3,33x del angular reescalado a 3840x2160 para fingir 8,29 MP. Un paso de zoom de primera fila que produce basura rompe la confianza en toda la tira.

### 28. Bloquear en SetupActivity las lentes que no entregan fotogramas: si en 1,5 s no llega ninguno, marcarla en rojo como 'sin imagen' y deshabilitar 'Usar esta lente'; no ofrecer de entrada ID2 ni ID7. Y validar el cameraId guardado contra cameraIdList en CameraActivity.onCreate.

*1 juez(ces) lo senalaron.* El propio informe de dispositivo documenta que el selector deja elegir el sensor de 200 MP AVERIADO (ID2) con visor en negro absoluto y sin aviso, dejando al usuario con una cámara sin imagen y sin salida evidente. El AndroidManifest documenta además, por escrito, que la validación del ID restaurado NO existe y que un ID heredado deja 'Esta lente no respondió' en cada arranque sin camino de vuelta al asistente. Son las dos formas de que el usuario se quede sin cámara: exactamente lo que esta app existe para evitar.

### 29. Configurar el audio del MediaRecorder: setAudioSamplingRate(48000), setAudioChannels(2) y setAudioEncodingBitRate(192000) en createRecorder() de Camera2Controller.kt (hoy solo se llama a setAudioSource y setAudioEncoder(AAC)).

*1 juez(ces) lo senalaron.* El vídeo entregado lleva AAC-LC a 8000 Hz, MONO, 12,2 kbps: calidad de llamada telefónica. Medí el espectro y la energía muere por encima de 3,9 kHz (banda 3000-3900 Hz = 0,02; 3900-4000 Hz = 0,00). Es un desequilibrio de 1360 a 1 frente a los 16,6 Mbps de vídeo, y se arregla con tres líneas.

### 30. Prohibir o rehacer el flash en el teleobjetivo: bloquear el disparo de flash cuando la lente activa es el tele (o forzar el cambio al gran angular), y si se mantiene, corregir el velo con secuencia y temporización del LED.

*1 juez(ces) lo senalaron.* foto_conflash_2.9x es una foto destruida: p0,1 = 114 y p1 = 121,8, es decir NO HAY UN SOLO PÍXEL por debajo de 114/255 en 8,29 MP; saturación 1,9 y nitidez desplomada de 348,6 (sin flash) a 32,8 (con flash). El usuario obtiene niebla blanca en vez de una foto.

### 31. Implementar medición con predestello real: pasada de preflash para AE y AWB específicos de flash antes de la captura, en lugar de disparar el LED sobre los parámetros de la escena ambiente.

*1 juez(ces) lo senalaron.* En gran angular el flash no aporta NADA de exposición (luminancia media 88,7 con flash frente a 89,8 sin flash) y arrasa el color: saturación 10,1 -> 4,6 y R/G 0,964 con B/G 0,950, o sea gris neutro. El flash empeora la foto en vez de salvarla.

### 32. Arreglar el flash con el teleobjetivo: hoy arruina la foto entera. En foto_conflash_2.9x el percentil 1 esta en 121,8/255 (no queda ni un negro), la saturacion cae a 1,9 y la nitidez a 32,8. Mientras no se resuelva, o se bloquea el flash en la lente tele con un aviso claro, o se detecta el velo (comparar el nivel de negro del fotograma con flash contra el de la precaptura) y se descarta la toma.

*1 juez(ces) lo senalaron.* Es el unico caso del expediente en que la app entrega una foto literalmente inservible, y el usuario no tiene forma de saber por que: aprieta el boton y sale niebla blanca.

### 33. Configurar el audio del video: hoy graba a 8000 Hz, mono, 12,2 kbps (verificado con ffprobe y en el descriptor esds del archivo). Fijar setAudioSamplingRate(48000), setAudioChannels(2) y setAudioEncodingBitRate(128000-192000), y usar AudioSource.CAMCORDER en vez de MIC.

*1 juez(ces) lo senalaron.* 8 kHz es calidad de llamada telefonica: se pierde todo por encima de 4 kHz. Cualquier video con voz o musica sale inutilizable y no hay ningun aviso ni ajuste que lo permita evitar.

### 34. Rehacer el zoom digital del gran angular (1x y 2x): laplaciano de 11-12 en el centro con moteado de color verde y magenta a ISO 13778. Recortar de un stream mas grande en vez de reescalar, limitar el zoom digital de esa lente y saltar antes al tele.

*1 juez(ces) lo senalaron.* Es el zoom que mas se usa (1x es la posicion por defecto de cualquiera) y es donde la app da su peor imagen: peor que la lente nativa a 0.6x y mucho peor que el tele.

### 35. Corregir el flash del gran angular: aporta luz (ISO de 9591 a 6056) pero deja la escena gris, con la saturacion hundida de 10,1 a 4,6 y un velo plano. Revisar el balance de blancos con destello y la componente ambiental.

*1 juez(ces) lo senalaron.* Una foto con flash que sale mas apagada de color que la misma foto sin flash desanima a usar el flash nunca mas.

---

## Impacto ALTO (89)

### 1. Aplicar la curva de tono propia con TONEMAP_MODE_CONTRAST_CURVE para anclar el pie de sombras y llevar el blanco a su sitio.

*1 juez(ces) lo senalaron.* El rango tonal esta aplastado: el tele usa p1=35 y p99=151 (116 de 255 niveles), el flash con tele 88 niveles y la frontal 73. Ninguna de esas fotos tiene negros ni blancos reales. El propio informe de aparato confirma que el HAL ofrece tonemap.availableToneMapModes [0 1 2] y 512 puntos de curva: la capacidad esta y no se usa.

### 2. Corregir la dominante calida del balance automatico, o al menos ofrecer una correccion; hoy la temperatura del tungsteno se traslada intacta al fichero.

*1 juez(ces) lo senalaron.* Medido en las cuatro tomas ambientales traseras: R/G entre 1,121 y 1,151 y B/G entre 0,853 y 0,903. Es un exceso de rojo del 12-15% y un defecto de azul del 10-15%, constante.

### 3. Revisar la dosis y el balance del flash en gran angular: hoy destella sin ganar exposicion y cambiando el color.

*1 juez(ces) lo senalaron.* Luminancia media 88,7 CON flash frente a 91,3 SIN flash en la misma escena; el ISO solo baja de 9591 a 6056 (0,66 pasos); la saturacion cae de 32,5 a 12,9 y el balance vira de R/G 1,137 a 0,964. Paga el destello y empeora el resultado.

### 4. Poner un piso de obturacion ligado a la focal en el teleobjetivo (regla reciproca: al menos 1/70-1/80 s con 70-77 mm equivalentes) aunque cueste ISO, o exigir apoyo.

*1 juez(ces) lo senalaron.* Las dos tomas de tele salen a 1/24 s (foto_sinflash_2.9x y 5x): tres pasos por debajo de lo que aguanta un pulso a esa focal. Con el movil apoyado no se nota; a mano saldran movidas.

### 5. Etiquetar el MP4 con colorimetria BT.709 en lugar de la que sale ahora.

*1 juez(ces) lo senalaron.* ffprobe da color_primaries=bt470bg y color_space/transfer=smpte170m, es decir contenido HD marcado como BT.601. Un reproductor que respete la etiqueta lo pinta con la matriz equivocada.

### 6. Cerrar el desfase de audio: arrancar el MediaRecorder y descartar los primeros fotogramas de video hasta que la pista de audio este dando muestras, o compensar el offset en el contenedor.

*1 juez(ces) lo senalaron.* Medido: audio 14,336 s frente a video 14,503 s, un deficit de 167 ms con ambos flujos arrancando en 0,000. Coincide con los 170 ms del JSON de medidas.

### 7. Atacar el ruido en manchas gruesas del zoom digital: hacer el recorte y el reescalado antes de la reduccion de ruido, no despues, para que la NR trabaje a la escala real del grano.

*1 juez(ces) lo senalaron.* En foto_sinflash_2x la sigma CRECE al reducir la imagen: 0,59 a 1:1, 1,07 a 1/2, 1,43 a 1/4 y 2,32 a 1/8. Eso es ruido de baja frecuencia que no se promedia nunca — el tipo mas visible y el mas dificil de arreglar despues.

### 8. Dar estado visible al chip de modo noche: cambiar color/relleno/etiqueta como ya hace el de flash, en vez de dejar la luna siempre amarilla con un mensaje pasajero.

*1 juez(ces) lo senalaron.* En noche-off.png y noche-on.png la luna es exactamente igual de amarilla. Y como el naranja/amarillo es el color de 'activo' en esta interfaz (el flash pasa de 'off' blanco a 'on' naranja), la luna siempre amarilla se lee como siempre encendida.

### 9. Poner fondo opaco (o desenfoque real) al panel de los tres puntos y ocultar lo que quede debajo mientras esta abierto.

*1 juez(ces) lo senalaron.* En panel-mas.png se leen a la vez la pastilla 'ID3 - 15 MM - 0.6X', los chips de flash y temporizador y la escena por debajo de las opciones HDR/RAW/Normal/PRO: tres capas de texto superpuestas en la misma zona.

### 10. Implementar disparo sin retardo por reprocesado (anillo de fotogramas + PRIVATE/YUV_REPROCESSING).

*1 juez(ces) lo senalaron.* El informe del aparato confirma que ID3 e ID6 declaran PRIVATE_REPROCESSING y YUV_REPROCESSING con maxNumInputStreams=1: el ZSL es viable en este telefono y hoy no existe. Ademas no hay NINGUNA cifra de latencia en el expediente con la que defender la velocidad.

### 11. Bloquear en el selector de lentes las camaras que no entregan imagen: si no llega un fotograma en ~1,5 s, marcarlas en rojo como 'sin imagen' y desactivar 'Usar esta lente'; y no ofrecer de entrada ni la ID2 ni la ID7.

*1 juez(ces) lo senalaron.* Documentado en el aparato: al llegar a 'Lente 2 de 4 - Trasera (normal) - 5.0 mm - ID 2' la vista previa sale en negro absoluto sin aviso, y esa es justamente la de 200 MP averiada. Si el usuario la elige se queda sin imagen y sin camino de vuelta evidente.

### 12. Validar en onCreate el ID guardado contra CameraInfoUtil.listLenses(this) y, si no existe, ponerlo a null para que el flujo mande al asistente.

*1 juez(ces) lo senalaron.* Lo documenta el propio AndroidManifest.xml: la validacion no existe, onCreate solo mira si el ID es null o '0', y con un ID restaurado por transferencia entre telefonos la apertura muere en el vigilante de 5 s y se ve 'Esta lente no respondio' en CADA arranque sin vuelta al asistente.

### 13. Las fotos de teleobjetivo no tienen blancos: p99=150.6 en 2.9x y p99=162.8 en 5x, o sea que el 40% superior del rango tonal se desperdicia y la imagen sale plana y lechosa. ARREGLO: aplicar la curva de tono propia (el HAL declara TONEMAP CONTRAST_CURVE con maxCurvePoints=512) anclando el percentil 99,5 cerca de 250, igual que ya hace NightStacker con su hombro.

*1 juez(ces) lo senalaron.* La lente tele es la MEJOR del telefono (348.6 de nitidez, textura real) y su rendicion tonal la desaprovecha. Es una perdida de calidad puramente de procesado.

### 14. Fuga de ISO por no usar la obturacion disponible: ISO 9591 a 1/60 en 1x, ISO 13778 a 1/60 en 2x, ISO 12209 a 1/24 en tele. El codigo detecta OIS (oisAvailable) y lo enciende, pero el suelo de obturacion del gran angular sigue en 1/60. ARREGLO: con OIS activo, bajar el piso a 1/25-1/30 en gran angular; son 1-1,3 EV menos de ganancia, que es justo lo que separa el 2x actual de uno usable.

*1 juez(ces) lo senalaron.* Casi todo el ruido de los pasos intermedios viene de ganancia electronica que no hacia falta pagar.

### 15. El modo noche pierde un 27% de pixeles: 3280x1856 = 6.09 MP frente a 8.29 MP de una foto normal. ARREGLO: apilar a la resolucion nativa de captura, o al menos avisar en la interfaz de que noche recorta.

*1 juez(ces) lo senalaron.* Se paga resolucion por el apilado sin que el usuario lo sepa ni pueda elegir.

### 16. El EXIF del modo noche esta destrozado: 10 etiquetas frente a las 47 de las demas fotos -- sin Make, Model, DateTimeOriginal, FNumber, Flash ni ExposureProgram -- Orientation se escribe como 0, que NO es un valor legal de EXIF (validos 1-8), y la etiqueta Software sale corrupta como 'Camara ? modo noche' (error de codificacion de caracteres). ARREGLO: escribir el mismo bloque EXIF completo que la ruta normal, Orientation legal y coherente con la de las otras fotos, y arreglar la codificacion del texto.

*1 juez(ces) lo senalaron.* Las fotos de noche no se ordenan por fecha en la galeria, no dicen con que camara se hicieron y dependen de que el visor trate el 0 como 1 para verse derechas.

### 17. No hay disparo sin retardo (ZSL) pese a que el aparato lo permite. No se fija CONTROL_ENABLE_ZSL en ninguna parte, y el documento de hallazgos confirma que el HAL declara PRIVATE_REPROCESSING + YUV_REPROCESSING con maxNumInputStreams=1. Ademas cada disparo con flash paga hasta 900 ms de espera de pre-captura AE antes de empezar. ARREGLO: anillo de fotogramas con reprocesado para la ruta normal.

*1 juez(ces) lo senalaron.* La latencia de obturador es estructuralmente no nula y con flash puede acercarse al segundo. En una app cuyo objetivo declarado es 'rapidisima para disparar' es la deuda tecnica mas cara.

### 18. La fila de zoom no indica cual es el paso ACTIVO. En la captura de la version actual se resaltan en blanco 0.6x y 2.9x (las posiciones opticas) y se apagan 1x/2x/5x: el estilo codifica optico-vs-digital, no la seleccion. La pastilla decia '77 MM', que no corresponde a ninguna pastilla. ARREGLO: estado activo distinto (relleno de acento) del estado 'optico', y un indicador continuo del factor real cuando el pellizco deja el zoom entre pastillas.

*1 juez(ces) lo senalaron.* El usuario no puede saber en que zoom esta mirando la fila de zoom. Es el fallo de estado mas basico de la interfaz.

### 19. Camara frontal a 2560x1440 = 3.69 MP fijos y sin ninguna luz de relleno. flashScreen() es solo una animacion blanca de 50 ms de obturador, no una iluminacion de pantalla; con BV -2.31 la foto frontal se hizo a ISO 8156 a 1/20 s sin ayuda. ARREGLO: escoger un tamano de captura mayor para la frontal si el HAL lo ofrece, y anadir flash de pantalla real (pantalla a blanco y brillo al maximo durante la exposicion) cuando el flash este en auto/on con la frontal.

*1 juez(ces) lo senalaron.* 3.69 MP es muy poco para 2026, y un selfie a oscuras sin relleno es exactamente el caso donde mas falta hace.

### 20. El selector de lentes deja elegir el sensor MUERTO (ID2, el de 200 MP averiado) dejando el visor en negro sin ningun aviso ni forma evidente de volver. Documentado en docs/2026-08-01_hallazgos-en-dispositivo.md. ARREGLO: si no llega ningun fotograma en ~1,5 s, marcar la lente en rojo como 'sin imagen' y bloquear 'Usar esta lente'; y no ofrecer de entrada ni ID2 ni ID7 (sensor de profundidad).

*1 juez(ces) lo senalaron.* Es el unico camino de la app que deja al usuario con una camara que no da imagen. En un telefono cuya camara principal esta rota, es la trampa mas facil de pisar.

### 21. Sin reduccion de ojos rojos. No aparece CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE ni ninguna clave RED_EYE en todo Camera2Controller.kt. ARREGLO: ofrecer el modo del HAL cuando este disponible, o al menos correccion de ojos rojos en el post-procesado del JPEG cuando se detecte cara y el flash haya disparado.

*1 juez(ces) lo senalaron.* Con el LED pegado al eje optico y sujetos a corta distancia, los ojos rojos son inevitables. Es la unica funcion de flash que falta por completo.

### 22. El boton verde de WhatsApp, del tamano del obturador y con el verde mas saturado de la pantalla, esta pegado al selector FOTO/VIDEO y es el elemento visualmente mas fuerte de la interfaz, mas que el propio obturador. ARREGLO: reducirlo a icono secundario monocromo, o moverlo al panel '...' junto al resto de acciones de compartir.

*1 juez(ces) lo senalaron.* Error de jerarquia: el ojo va primero al boton de compartir y no al de disparar.

### 23. Video por defecto en 1080p30 H.264. El motor SI soporta 4K a 42 Mbps, 60 fps y HEVC (setVideoTargetHeight/setVideoFps/setVideoHevc, expuestos como pastillas), pero lo que sale de fabrica es lo medido: 1920x1080, avc1 High/4.0, 30.1 fps, 16.6 Mbps. ARREGLO: 4K30 HEVC por defecto cuando supports4kVideo sea cierto, dejando 1080p como opcion.

*1 juez(ces) lo senalaron.* Es un ajuste por defecto de 2015. La capacidad ya esta pagada en codigo y no se entrega.

### 24. Reconstruir la tira de zoom al pasar a la cámara frontal. Hoy la frontal hereda la escalera de las traseras (0.6x/1x/2x/2.9x/5x) y se etiqueta 'ID1 · 20 MM · 0.6X', cuando 2.9x es la relación nativa del tele TRASERO y no significa nada en una lente frontal única.

*1 juez(ces) lo senalaron.* Ofrece al usuario paradas de zoom que no existen y etiqueta la única lente frontal como si fuera un gran angular secundario. Es información falsa en pantalla.

### 25. Igualar tono y nivel de negro entre gran angular y tele. En el cruce 2x->2.9x el negro salta de 18,7 a 34,0, la media de 94,4 a 104,6, el recorrido tonal cae de 130,1 a 118,7 y la saturación de 27,2% a 23,1%. El mismo salto se ve EN VIVO en el visor: luminancia 88,9->112,4 y p5 38->63 entre zoom-2x.png y zoom-2.9x.png. Ya existe lensEvSteps por lente: hace falta el equivalente para curva de tono y saturación, calibrado con una toma pareada.

*1 juez(ces) lo senalaron.* Es el defecto que define un sistema multicámara. Hoy el usuario ve el brillo, el contraste y el color cambiar de golpe al pellizcar, y esa es la sensación de 'son dos cámaras distintas' que un sistema bueno tiene que hacer desaparecer.

### 26. Frenar el bombeo de ISO al recortar. Sobre la MISMA lente y la misma escena, 0.6x sale a ISO 2650 y 1x a ISO 9591 (1,86 pasos más) para una diferencia de escena de solo 0,24 EV; a 2x son ya 13778. Con el piso de obturación fijando 1/60 s, el AE solo puede mover ISO. Hace falta una medición ponderada que no colapse sobre el recorte, o dejar que el obturador baje algo antes de disparar el ISO, o un techo de ISO por lente.

*1 juez(ces) lo senalaron.* Convierte un recorte —que debería costar solo resolución— en una pérdida de dos pasos de ruido. Es la causa directa de que el 1x y el 2x sean las peores fotos del lote.

### 27. Unificar la salida del modo noche con la del resto. Hoy sale a 3280x1856 (6,09 MP, relación 1,767) frente a 3840x2160 (8,29 MP, 1,778) de todas las demás; pierde la etiqueta EXIF de orientación (las otras llevan orient=3, esta ninguna), y pierde Make, Model y DateTime.

*1 juez(ces) lo senalaron.* Sin la etiqueta de orientación la foto nocturna se muestra girada 180 grados en cualquier visor estricto, y sin Make/Model/fecha no se ordena ni se filtra en la galería. Es la mejor foto de la app entregada como si fuera de otra app.

### 28. Bajar la reducción de ruido del gran angular. A ISO 2650 entrega ruido 0,91 con energía de alta frecuencia de solo 0,1442 y laplaciano 77,9: poco ruido Y poca textura a la vez es la firma del efecto acuarela, y se ve empastada la alfombra y las hojas del helecho. Probar NOISE_REDUCTION_MODE MINIMAL o FAST en vez de lo que decida ColorOS, y comprobar que HF sube por encima de 0,25.

*1 juez(ces) lo senalaron.* El gran angular de 2,3 mm es la lente que el usuario usa a diario. Hoy sus fotos parecen pintadas, y ese es el reproche de calidad de imagen más repetido de la prensa contra el procesado agresivo.

### 29. Atacar el velo del teleobjetivo. En 2.9x el pie de negros se queda en p1=34,0 y el recorrido tonal en 118,7 (frente a 191,9 del 0.6x nativo): la imagen sale lechosa aunque esté nítida. Curva de tono propia con el pie bajado y, si el velo es óptico, comprobar si viene de luz parásita del propio módulo.

*1 juez(ces) lo senalaron.* El tele es la mejor lente del aparato en detalle (laplaciano 348,6) y se está entregando sin contraste, que es lo primero que percibe el ojo. Se está tirando su ventaja.

### 30. Igualar la política de balance de blancos con y sin flash. Al disparar el flash el gran angular pasa de R/G 1,154 y 28,2% de saturación a R/G 0,959 y 10,5%: la escena cálida se vuelve gris. Hay que mezclar el WB de flash con el ambiente según cuánto aporte cada uno, o bloquear el WB del preview para la toma con flash.

*1 juez(ces) lo senalaron.* El usuario ve el visor cálido, dispara con flash y recibe una foto gris que no se parece a lo que estaba mirando. Es una ruptura de WYSIWYG completa.

### 31. Bloquear en el selector de lentes el sensor de 200 MP averiado: detectar que no llega ningún fotograma en ~1,5 s, marcar la lente en rojo como 'sin imagen' y deshabilitar 'Usar esta lente'; y no ofrecer de entrada ni la ID2 ni la ID7 (sensor de profundidad).

*1 juez(ces) lo senalaron.* Hoy el usuario puede elegir una cámara que no da imagen y quedarse sin salida evidente. Es el fallo que esta app existe precisamente para evitar.

### 32. Validar el cameraId guardado contra cameraIdList en CameraActivity.onCreate. El propio manifiesto documenta el hueco: un ID restaurado por transferencia directa desde otro teléfono se pasa tal cual a controller.open() y la apertura muere en el vigilante de 5 s con 'Esta lente no respondió' en CADA arranque, sin camino de vuelta al asistente.

*1 juez(ces) lo senalaron.* Es un fallo de arranque total, autoconfesado en el código y con la solución ya escrita en el comentario. Deja la app inservible sin manera de recuperarla.

### 33. Aplicar el zoom al vídeo, o corregir el etiquetado. El clip llamado 'video_1x' tiene el campo de visión del 0.6x nativo (encuadre idéntico al de foto_noche_0.6x), no el recorte de 1x.

*1 juez(ces) lo senalaron.* O el zoom no llega al camino de vídeo —y entonces el usuario graba siempre en gran angular sin saberlo— o la evidencia está mal nombrada. Las dos cosas hay que resolverlas.

### 34. Subir el vídeo al nivel de 2026: exponer 4K y 60 fps (el código ya tiene setVideoTargetHeight/setVideoFps y una escalera de bitrate hasta 42 Mbps, pero lo entregado es 1080p30 AVC), añadir 24 fps, HEVC por defecto y HLG10 si DynamicRangeProfiles lo lista en ID3/ID6, y demostrarlo con un clip.

*1 juez(ces) lo senalaron.* El CFR perfecto (dt 0,0333 s constante, desviación 0,0000 en 436 fotogramas) ya es una ventaja real sobre la competencia; entregarla solo a 1080p30 la desperdicia.

### 35. Anadir un vumetro de audio en vivo con pico y aviso de saturacion, y un conmutador visible de audio si/no antes y durante la grabacion.

*1 juez(ces) lo senalaron.* No existe ninguna indicacion de audio en toda la app. El usuario no sabe si hay pista, si esta saturando o si el microfono esta tapado hasta que abre el archivo. Solo se graba audio si el permiso RECORD_AUDIO esta concedido, en silencio.

### 36. Ofrecer 24 y 25 fps ademas de 30 y 60, como botones de primera clase, fijando CONTROL_AE_TARGET_FPS_RANGE cerrado y setVideoFrameRate coherente.

*1 juez(ces) lo senalaron.* Hoy toggleVfps() solo alterna 30 y 60 (CameraActivity.kt ~2810). El 24p es el estandar cinematografico y la ausencia de 24 fps es una de las criticas mas repetidas al rival: es una victoria gratuita que la app esta dejando pasar teniendo ya la infraestructura de rango cerrado montada.

### 37. Anadir salida de 10 bits: consultar DynamicRangeProfiles con getSupportedProfiles() y ofrecer al menos HLG10 (obligatorio si el aparato soporta 10 bits) con OutputConfiguration.setDynamicRangeProfile(), mas un perfil plano via TONEMAP_MODE_CONTRAST_CURVE.

*1 juez(ces) lo senalaron.* Todo lo entregado es 8 bits H.264 SDR. El aparato es Android 16 y el propio documento de hallazgos confirma tonemap.availableToneMapModes=[0 1 2] con maxCurvePoints=512, o sea que la curva propia esta disponible y no se usa en video. Sin 10 bits ni perfil plano no hay margen de etalonaje.

### 38. Marcar la parada de zoom activa SIEMPRE, aunque el zoom real no coincida exactamente con una parada (resaltar la mas cercana, o dibujar un indicador de posicion continuo sobre la tira).

*1 juez(ces) lo senalaron.* En la captura entregada de la version actual ninguna pastilla esta marcada: 0.6x y 2.9x salen en blanco (optico) y 1x/2x/5x en gris, mientras el chip dice que la lente activa es el tele a 77 mm, la pastilla mas apagada. La regla del codigo ('si best > 0.02f, active = -1') deja al usuario sin ninguna informacion de estado en todo el tramo de pellizco.

### 39. Igualar el procesado de tono entre las dos lentes traseras: el tele entrega p1=35,1 / p99=150,6 (115 de 255 niveles) frente a p1=24,1 / p99=214,5 del gran angular. Aplicar la curva de tono propia por lente para llevar el blanco a su sitio.

*1 juez(ces) lo senalaron.* Cambiar de lente cambia el aspecto de la foto: el tele sale lavado y sin punto de blanco ni de negro, con velo visible. Es el mismo defecto de 'inconsistencia al cambiar de camara' que el expediente le reprocha al rival, y aqui es peor porque solo hay dos lentes.

### 40. Bajar la reduccion de ruido del gran angular (NOISE_REDUCTION_MODE con perfil por ISO real) para recuperar textura.

*1 juez(ces) lo senalaron.* El 0.6x mide sigma de ruido 0,91 con laplaciano 77,9 y energia de alta frecuencia 0,1442: ruido casi nulo pagado con efecto acuarela. En el recorte al 100% la textura de la pared desaparece por completo y los bordes quedan plasticos. El tele demuestra que con grano real (sigma 2,65) se resuelve muchisimo mas (lap 348,6).

### 41. Estabilizar el AE entre disparos consecutivos de la misma escena.

*1 juez(ces) lo senalaron.* En 30 segundos sobre la misma habitacion el ISO paso de 2650 a 9591 y a 13778, el EV de -0,56 a -0,80 y a -1,22, y el p99 de 214 a 172 y a 148. Tres fotos de la misma escena con tres exposiciones distintas: el usuario no puede predecir lo que va a obtener.

### 42. Implementar ZSL de verdad: TEMPLATE_ZERO_SHUTTER_LAG en el repeating request y/o createReprocessableCaptureSession con buffer circular y seleccion por timestamp.

*1 juez(ces) lo senalaron.* No hay ni rastro de ZSL en el codigo (ninguna referencia a TEMPLATE_ZERO_SHUTTER_LAG, buffer circular ni reprocesado), mientras el propio documento de hallazgos del proyecto confirma PRIVATE_REPROCESSING e YUV_REPROCESSING presentes en ID3 e ID6 con maxNumInputStreams=1. Es la funcion que mas latencia de obturador elimina y el hardware la soporta.

### 43. Medir y publicar cifras de velocidad: arranque en frio hasta el primer fotograma, latencia de obturador, disparo a disparo y fps de visor, con adb sobre el CPH2765.

*1 juez(ces) lo senalaron.* El codigo tiene cronometros CamPerf en apertura, primer fotograma y obturador->buffer, pero en TODO el expediente no hay una sola cifra medida. La velocidad de la app es hoy, literalmente, sin evidencia, y es el terreno donde el proyecto declara querer ganar.

### 44. Sacar a la pantalla el estado de la grabacion en modo video: resolucion, fps, codec y estado de audio visibles en el HUD, no enterrados en el panel '...'.

*1 juez(ces) lo senalaron.* En la captura de modo video no hay absolutamente nada sobre lo que se va a grabar. El usuario pulsa REC sin saber si esta en 1080p30 H.264 o en 4K60 HEVC. Los chips existen en el layout (chip_vres, chip_vfps, chip_vcodec, chip_tl) pero viven dentro de video_panel.

### 45. Poner el panel '...' con fondo opaco y ocultar lo que quede debajo.

*1 juez(ces) lo senalaron.* En panel-mas.png el chip de lente 'ID3 . 15 MM . 0.6X' se lee POR DEBAJO del panel y la escena se transparenta a traves de el. Lo reconoce el propio documento de hallazgos del proyecto como el sintoma de 'paneles que se tapan'.

### 46. Bloquear la seleccion del sensor averiado en SetupActivity: detectar que no llega ningun fotograma en ~1,5 s, marcar la lente en rojo como 'sin imagen' y deshabilitar 'Usar esta lente'; y no ofrecer de entrada ni ID2 ni ID7.

*1 juez(ces) lo senalaron.* El documento de hallazgos del proyecto describe que al llegar a 'Lente 2 de 4 . ID 2' la vista previa sale en negro absoluto sin ningun aviso, y si el usuario la elige se queda con una camara que no da imagen y sin camino de vuelta. Es la unica cosa que esta app existe para evitar.

### 47. Validar el cameraId restaurado contra CameraInfoUtil.listLenses() en CameraActivity.onCreate y caer al asistente si no existe.

*1 juez(ces) lo senalaron.* El propio AndroidManifest.xml documenta el fallo abierto: con un ID restaurado por transferencia directa entre telefonos la apertura muere en el vigilante de 5 s y el usuario ve 'Esta lente no respondio' en CADA arranque, sin camino de vuelta.

### 48. Cerrar el archivo sin perder la cola de la pista de audio: el audio dura 14.336 s frente a 14.503 s de vídeo.

*1 juez(ces) lo senalaron.* 167 ms del final de CADA toma se quedan sin sonido. Y a 8000 Hz un fotograma AAC son 128 ms, así que la granularidad del recorte es seis veces más basta que a 48 kHz — corregir el sample rate ya reduce el problema, pero hay que dejar de cortar el rabo de la toma.

### 49. Reproducir el aviso sonoro de inicio ANTES de recorder.start(), no después.

*1 juez(ces) lo senalaron.* En CameraActivity.onRecordingChanged(true), playShutterSound(START_VIDEO_RECORDING) se ejecuta cuando la grabación ya ha arrancado, así que el propio pitido de la app entra en la toma por el micrófono. El arranque del espectrograma muestra un transitorio de banda ancha compatible con eso.

### 50. Arreglar la coherencia del AE al recortar digitalmente: 1x y 2x disparan a ISO 9591 y 13778 en la misma escena en la que 0.6x usa ISO 2650.

*1 juez(ces) lo senalaron.* El resultado es que 1x —la focal que más se usa— es la PEOR imagen de todo el juego: laplaciano 39.0 frente a 77.9 a 0.6x y 348.6 a 2.9x, con ruido 1.39 y visible manchado de luminancia. El usuario no entiende por qué acercarse un poco arruina la foto.

### 51. Dejar de reescalar los recortes digitales a 3840x2160: guardar el recorte a su resolución real o indicar los MP efectivos.

*1 juez(ces) lo senalaron.* 1x, 2x y 5x salen con los mismos 8.29 MP nominales que la toma nativa pero con una fracción del detalle (energía de alta frecuencia 0.1594 y 0.2055 frente a 0.3995 del tele óptico). Son megapíxeles que no contienen información: es exactamente el 'zoom que miente' que la propia app critica en el rival.

### 52. Ofrecer 24 y 25 fps como opciones de primera clase, además de 30 y 60.

*1 juez(ces) lo senalaron.* El código solo alterna entre 30 y 60 (toggleVfps). 24 fps es la cadencia de cine y 25 la de PAL/broadcast; sin ellas la app queda fuera de cualquier flujo de producción. Y es trivial: el motor ya fuerza un rango de AE cerrado, basta con (24,24).

### 53. Añadir captura de 10 bits: consultar DynamicRangeProfiles y ofrecer al menos HLG10, con selección explícita SDR/HLG10.

*1 juez(ces) lo senalaron.* DynamicRangeProfiles no aparece en ningún punto del fuente: la app graba 8 bits SDR y nada más. El aparato es Android 16, donde HLG10 es obligatorio si el equipo admite 10 bits. Sin esto, en 2026 el vídeo nace con dos generaciones de retraso en rango dinámico.

### 54. Exponer el bitrate de vídeo (y el modo CBR/VBR) al usuario en vez de la escalera fija 42/24/17/9 Mbps clavada en createRecorder().

*1 juez(ces) lo senalaron.* El rival tiene el bitrate bloqueado tras root y eso es un hueco señalado en el propio expediente; BETA lo tiene igualmente cerrado, solo que por código propio. Un deslizador de calidad es la victoria más barata que hay disponible.

### 55. Corregir el etiquetado de color del MP4: sale color_range=pc (rango completo) con primarios bt470bg y matriz smpte170m.

*1 juez(ces) lo senalaron.* Es una combinación inconsistente. Muchos montadores interpretan el H.264 en MP4 como rango limitado, así que ese archivo entra en la línea de tiempo con los niveles desplazados: negros aplastados y blancos recortados que no estaban en la toma.

### 56. Igualar el rendering entre el gran angular y el tele: hoy no parecen la misma cámara.

*1 juez(ces) lo senalaron.* Misma escena y mismo instante: el gran angular da luminancia 91.3 con p99=214.5, el tele da 104.6 con p99=150.6 — el tele entrega una curva mucho más plana, sin blancos reales, y un color cálido-beige casi monocromo. Saltar de una lente a otra cambia la foto entera.

### 57. Bajar la reducción de ruido del gran angular: efecto acuarela evidente al 100%.

*1 juez(ces) lo senalaron.* En el recorte 1:1 de foto_sinflash_0.6x la pared lisa queda plastificada, los bordes de las hojas se funden y la rejilla metálica del estante se convierte en papilla. Los números lo respaldan: laplaciano 77.9 con energía de alta frecuencia de solo 0.1442, cuatro veces menos que el tele.

### 58. Resolver la cámara frontal: solo 3.69 MP (2560x1440) y la única muestra es inutilizable.

*1 juez(ces) lo senalaron.* foto_frontal es una mancha desenfocada sin sujeto, a ISO 8156 y EV -2.31; su alta frecuencia de 0.7153 es ruido, no detalle. No existe ninguna evidencia de que la frontal produzca una foto aprovechable, y entrega menos de la mitad de píxeles que las traseras.

### 59. Bloquear en el selector de lentes el sensor de 200 MP averiado (ID2) y el de profundidad, con detección de 'no llega ningún fotograma' en 1,5 s.

*1 juez(ces) lo senalaron.* El documento de hallazgos del propio proyecto lo describe: SetupActivity ofrece 'Lente 2 de 4 · Trasera (normal) · 5.0 mm · ID 2', la vista previa sale en negro absoluto sin ningún aviso, y si el usuario la elige se queda con una cámara sin imagen y sin camino de vuelta. Es justo el fallo que esta app existe para evitar.

### 60. Validar el cameraId guardado contra getCameraIdList() en CameraActivity.onCreate y volver al asistente si no existe.

*1 juez(ces) lo senalaron.* El propio manifiesto documenta que el agujero SIGUE ABIERTO: con un ID restaurado por transferencia directa entre teléfonos, la apertura muere en el vigilante de 5 s y el usuario ve 'Esta lente no respondió' en CADA arranque, sin salida. Está escrita hasta la línea exacta que falta.

### 61. Añadir herramientas de monitorización para vídeo: cebras con umbral, histograma en vivo, focus peaking en grabación y medidor de audio.

*1 juez(ces) lo senalaron.* Ninguna existe. Es además el hueco que el propio expediente identifica como el más grande del rival ('no hay evidencia de cebras, waveform, false color ni medidores de audio en ninguna versión'). BETA denuncia el hueco y luego tampoco lo cubre.

### 62. Escribir en el EXIF de la foto de noche la exposición REALMENTE usada en la ráfaga (expNs e iso bloqueados en takeNightPhoto), no lastAeExpNs/lastAeIso del visor.

*1 juez(ces) lo senalaron.* fillStillExif escribe siempre el AE del visor. La foto de noche entregada declara 1/40 s a ISO 3684 cuando la ráfaga se bloquea a hasta 1/8 s con el ISO dividido por cinco. El archivo miente sobre lo que lo produjo y hace IMPOSIBLE verificar desde fuera que el camino de tiempo largo llegara a ejecutarse. Para un modo de apilado eso es fatal: es el dato que un juez comprueba primero.

### 63. Escribir el bloque XMP en todos los archivos entregados y, en la foto de noche, incluir cam:Frames con los fotogramas realmente apilados y los descartados.

*1 juez(ces) lo senalaron.* Ninguna de las nueve fotos entregadas lleva XMP. El número de fotogramas apilados es el único dato que distingue un apilado real de un disparo con curva. Sin él, el modo noche no es auditable ni por el usuario ni por nadie. (Puede ser procedencia — la nota dice que la tanda de EXIF completo es posterior — pero tal y como se entrega, no está.)

### 64. Recuperar el SNR del apilado: no dar por buena la salida si su suelo de ruido a igual luminancia no baja respecto a un solo fotograma; añadir un denoise espacial suave guiado por el mapa de pesos wY (donde hubo pocos fotogramas, más filtrado) después de la curva de tono.

*1 juez(ces) lo senalaron.* Medido por bandas de luminancia igualadas (percentil 10 de sigma en bloques 16x16), el apilado sale MÁS ruidoso que el disparo simple reescalado al mismo tamaño: sombras 0.70 → 1.27, 40-70 0.86 → 1.66, 70-100 0.60 → 0.95, 100-140 0.80 → 1.13. Siete fotogramas deberían dar ~2,6x menos ruido, no 1,4-1,9x más. El apilado está ganando detalle sólo porque se salta el denoiser del ISP, no porque gane señal — y eso es media victoria.

### 65. Eliminar el peaje de resolución del modo noche: apilar a la resolución completa usando acumuladores por bandas en disco/heap por franjas, o al menos advertir en la interfaz de que la foto de noche saldrá con menos píxeles.

*1 juez(ces) lo senalaron.* La foto de noche sale a 3280x1856 (6.09 MP) frente a 3840x2160 (8.29 MP) del modo normal: se pierde el 27% de los píxeles por usar el modo noche. El tamaño lo elige un presupuesto de heap (w*h*11 ≤ maxMemory/3) del que el usuario no sabe nada.

### 66. Conectar el parámetro 'ambience' (0-100) del NightStacker a un control real en la interfaz.

*1 juez(ces) lo senalaron.* El motor ya calcula k entre 1,2 y 2,4 y un techo entre 70 y 130 en función de 'ambience', y el propio comentario del código dice 'No lo toca nadie todavía'. Es la decisión más personal de una foto nocturna —cuánta noche quiero conservar— y está clavada a 50 sin que el usuario pueda intervenir.

### 67. Hacer que el modo noche levante de verdad el pie de sombras y controle las luces: revisar el objetivo de mediana y el hombro para que p1 suba respecto al disparo normal y el recorte de blancos no crezca.

*1 juez(ces) lo senalaron.* Comparado con el disparo normal de la misma escena, la foto de noche deja el p1 en 22.8 frente a 23.9 (las sombras salen IGUAL o más oscuras) y quema 7 veces más luces (0.283% frente a 0.039%) pese al hombro Reinhard que ancla el p99,5 en 251. 'Que la noche siga pareciendo noche' es correcto como principio, pero un modo noche que no hace legible lo oscuro no está cumpliendo su encargo.

### 68. Atacar el efecto acuarela del gran angular: aplicar ya la curva de tono propia por TONEMAP_MODE_CONTRAST_CURVE (512 puntos disponibles, confirmado en dumpsys) y pedir NOISE_REDUCTION_MODE menos agresivo o HIGH_QUALITY con perfil de detalle según ISO.

*1 juez(ces) lo senalaron.* En el 0.6x sólo queda el 0.0029 de la energía espectral entre 0,28 y 0,40 ciclos/px y el 0.0006 por encima de 0,40: la imagen está limitada en banda muy por debajo de Nyquist. A 100% las hojas de la planta son pasta y los lomos de los libros son ilegibles. Los 8,29 MP son nominales: la resolución útil es una fracción.

### 69. Corregir la incoherencia del AE entre pasos de zoom: la medición debe hacerse sobre la misma región y no dispararse al recortar.

*1 juez(ces) lo senalaron.* En la misma escena y con sólo 0,24 EV de diferencia de brillo, el 0.6x expone a ISO 2650 y el 1x a ISO 9591 — casi 1,9 EV de diferencia de ganancia sin motivo. El ruido sube en consecuencia de 0.91 a 1.39. Al 2x llega a ISO 13778. Se está quemando ruido gratis en cada recorte digital.

### 70. Dejar de reescalar los recortes digitales a 3840x2160: entregar el recorte a su resolución nativa (o, si se reescala, decirlo en el EXIF/XMP).

*1 juez(ces) lo senalaron.* El 1x (recorte 1,67x), el 2x (3,33x) y el 5x (1,72x) se entregan todos a 3840x2160 con pesos de 1,75 a 2,85 MB. Es resolución fingida: el archivo declara 8,29 MP y contiene una fracción de eso. Ocupa disco, engaña a quien lo edita y es la razón directa del espectro muerto del 2x.

### 71. Exponer la resolución de vídeo en la interfaz (4K/1080p/720p) y subir el objetivo por defecto a lo máximo que publique la lente; añadir 24 fps y HEVC 10 bits.

*1 juez(ces) lo senalaron.* El vídeo entregado es 1920x1080 H.264 8 bits a 30 fps. El motor ya tiene vresList 1080/2160/720 y conmutador HEVC, pero el informe de dispositivo dice literalmente que 'no hay forma de elegir la resolución de vídeo... la capacidad está en el motor y no se expone'. 1080p30 H.264 es el suelo de 2015, no el estándar de 2026.

### 72. Devolver el estado activo a la tira de zoom: marcar en naranja la pastilla en uso, como hacía la versión anterior.

*1 juez(ces) lo senalaron.* En interfaz-actual.png — la captura que se declara de la versión actual — ninguna pastilla está marcada mientras la pastilla de lente afirma 'TELEPHOTO · 77 MM · DIGITAL'. En flash-2.png y noche-on.png (versión anterior) la activa sí salía en naranja. Es una regresión que deja al usuario sin saber en qué zoom está justo en la app cuya mejor idea es decirle exactamente eso.

### 73. No dejar nunca la pantalla sin decir el zoom: mantener la píldora del nivel visible mientras el zoom no coincida con ninguna parada (en vez de ocultarla a los 1200 ms), o marcar la parada más cercana con un indicador de 'entre paradas'.

*1 juez(ces) lo senalaron.* highlightZoomStrip() pone active = -1 si la distancia relativa supera 0,02 y showZoom() programa hideZoom a 1200 ms. En interfaz-actual.png muestreé los cinco rellenos de las pastillas y son todos neutros (~#3A3A3A): ninguna marcada, píldora apagada. En ese estado la interfaz no dice en qué zoom está el usuario.

### 74. Arreglar el hundimiento del recorte digital: medir el AE sobre el fotograma completo (no sobre la ventana recortada) y generar el recorte desde la captura a resolución de sensor en vez de escalar el flujo ya recortado.

*1 juez(ces) lo senalaron.* Con la MISMA lente y el MISMO 1/60 s en la misma escena, el ISO pasa de 2650 (0.6x) a 9591 (1x) y a 13778 (2x), y el laplaciano cae de 77,9 a 39,0. El usuario pierde calidad al acercarse sin ganar encuadre útil.

### 75. Impedir que SetupActivity ofrezca los sensores que no entregan imagen: detectar la ausencia de fotogramas en ~1,5 s, marcar la lente en rojo como 'sin imagen' y bloquear 'Usar esta lente'; excluir de entrada la ID2 (200 MP averiado) y la ID7 (profundidad).

*1 juez(ces) lo senalaron.* El propio informe de dispositivo documenta que al llegar a 'Lente 2 de 4 · ID 2' la vista previa sale en negro absoluto sin ningún aviso y, si el usuario confirma, se queda con una cámara sin imagen y sin salida evidente. Es el fallo que puede dejar la app inservible para un usuario casual.

### 76. Implementar ZSL por reprocesado (PRIVATE/YUV_REPROCESSING) con anillo de fotogramas, y publicar mediciones de arranque, latencia de obturador y fps del visor.

*1 juez(ces) lo senalaron.* El informe de dispositivo confirma que el HAL del CPH2765 expone PRIVATE_REPROCESSING y YUV_REPROCESSING con maxNumInputStreams = 1 en ID3 e ID6, o sea que el disparo sin retardo es viable y sigue sin hacerse. Y en todo el expediente no hay NI UNA cifra de velocidad: sin medición no se puede puntuar el bloque más allá del aprobado.

### 77. Normalizar el tono por imagen (curva propia con punto de blanco real) para que los encuadres con zoom no salgan lavados.

*1 juez(ces) lo senalaron.* Los fotogramas ampliados no llegan nunca al blanco: p99,9 = 164,1 (2x), 173,0 (2.9x) y 173,3 (5x), frente a 244,5 del gran angular. El resultado es plano y lechoso comparado con la misma escena sin zoom.

### 78. Subir el listón del vídeo a 2026: 4K30 HEVC por defecto donde el HAL lo permita, más 24 fps, 10 bits y HDR; y aportar un clip CON MOVIMIENTO que demuestre la estabilización.

*1 juez(ces) lo senalaron.* Lo entregado es 1080p30 H.264 a 16,6 Mbps, sin 10 bits, sin HDR, sin LOG, sin 24 fps y sin cámara lenta. Además medí el desplazamiento global del clip de prueba: 0,00 px de media y 0,00 de desviación en 14,5 s — la cámara estaba fija, así que la estabilización queda SIN EVIDENCIA.

### 79. Resolver la cámara frontal: aportar una muestra válida, revisar el enfoque y elevar la resolución de salida por encima de 3,69 MP.

*1 juez(ces) lo senalaron.* La única foto frontal del expediente es un borrón completamente desenfocado de una pared, a 2560x1440 (3,69 MP) e ISO 8156. Con esa muestra no se puede acreditar nada de la frontal, y el techo de 3,69 MP ya es bajo de por sí.

### 80. Bajar la reduccion de ruido del gran angular. Con sigma 0,91 la textura de la pared desaparece y los lomos de los libros son ilegibles al 100% (laplaciano central 26,0). Preferir NOISE_REDUCTION_MODE minimo y algo mas de grano honesto.

*1 juez(ces) lo senalaron.* Es el efecto acuarela clasico: la foto parece limpia en la miniatura y se deshace en cuanto la amplias o la imprimes.

### 81. Igualar las dos lentes entre si: el tele sale mas calido (R/G 1,039 frente a 1,010), mas claro (luminancia 104,6 frente a 91,3) y mucho mas plano (p99 150,6 frente a 214,5) que el gran angular en la misma escena. Aplicar una correccion de color y una curva de tono por lente fisica.

*1 juez(ces) lo senalaron.* Al cruzar de 2x a 2.9x la foto cambia de caracter delante del usuario; parecen dos camaras de dos telefonos distintos.

### 82. Arreglar el tono plano y lechoso: el tele nunca llega al blanco (p99,9 de 172) y el 2x se queda en 159. Anclar el hombro de la curva de tono en un blanco real, como ya se hace en el modo noche.

*1 juez(ces) lo senalaron.* Las fotos se ven desvaidas y sin fuerza aunque tecnicamente esten bien expuestas; es lo primero que nota alguien que no sabe de camaras.

### 83. Bloquear el sensor averiado en el asistente de lentes: hoy se puede llegar a 'Lente 2 de 4 · 5.0 mm · ID 2', ver la vista previa en negro absoluto sin ningun aviso y pulsar 'Usar esta lente'. Detectar que no llega ningun fotograma en 1,5 s, marcarla en rojo como 'sin imagen' y desactivar el boton; y no ofrecer de entrada ni la ID2 ni la ID7.

*1 juez(ces) lo senalaron.* El usuario puede dejarse la app con una camara que no da imagen y sin camino evidente de vuelta. Es exactamente el fallo que esta app existe para evitar.

### 84. Validar el cameraId guardado contra la lista real de camaras en CameraActivity.onCreate (el propio manifiesto documenta que la validacion NO existe). Si el ID no esta en cameraIdList, ponerlo a null y mandar al asistente.

*1 juez(ces) lo senalaron.* Con una transferencia directa entre telefonos el ID restaurado apunta a una lente inexistente y la app muere en el vigilante de 5 s mostrando 'Esta lente no respondio' en CADA arranque, sin salida.

### 85. Medir y publicar la velocidad: tiempo de abrir a primer fotograma, latencia de obturador y disparo a disparo, con adb, y adjuntarlo como evidencia. Hoy no hay ni un numero en todo el expediente.

*1 juez(ces) lo senalaron.* La velocidad es un objetivo declarado del proyecto (<1 s) y es lo unico que no se puede juzgar; sin cifras no se puede ni defender ni mejorar.

### 86. Implementar disparo sin retardo con anillo de fotogramas: la captura usa TEMPLATE_STILL_CAPTURE y espera a que converjan enfoque y precaptura. El documento del aparato confirma que el telefono declara PRIVATE_REPROCESSING y YUV_REPROCESSING y maxNumInputStreams=1.

*1 juez(ces) lo senalaron.* Es la causa estructural del retardo de obturador, y el hardware ya ofrece la solucion: se esta dejando sin usar.

### 87. Aportar y verificar el video en 4K: solo existe un archivo de 1080p30 H.264. El motor tiene supports4kVideo y el layout tiene chip de resolucion, pero no hay ninguna prueba de que funcione.

*1 juez(ces) lo senalaron.* 1080p30 es el minimo de 2026; si el 4K funciona, no demostrarlo es regalar el bloque de video entero.

### 88. Corregir la distorsion de barril del gran angular de 2,3 mm: en foto_sinflash_0.6x y en el fotograma de video las lineas del techo, las baldas y la columna se curvan visiblemente.

*1 juez(ces) lo senalaron.* Es de los pocos defectos que una persona sin conocimientos tecnicos identifica al instante: 'las paredes salen torcidas'.

### 89. Dejar de recortar a 16:9 por defecto y de elegir el tamaño MEDIANO de la lista (pickJpegSize usa sorted[size/2]). Ofrecer la proporcion nativa del sensor a resolucion completa como opcion visible y explicada.

*1 juez(ces) lo senalaron.* Por defecto se estan tirando pixeles y campo de vision sin que el usuario lo sepa: 8,29 MP cuando el sensor puede dar mas.

---

## Impacto MEDIO (91)

### 1. Dar fondo opaco (o desenfoque real) al panel '⋯' y ocultar lo que quede debajo.

*2 juez(ces) lo senalaron.* Es translúcido y se solapa con la pastilla de lente, que se lee por debajo (panel-mas.png, y admitido en el documento de hallazgos del proyecto). Sobre una escena clara el panel deja de ser legible.

### 2. Comprobar y documentar si 3840x2160 (8,29 MP) es de verdad el maximo que ofrece el HAL para estas lentes, o si pickJpegSize esta descartando tamanos mayores por el filtro de relacion de aspecto 16:9.

*1 juez(ces) lo senalaron.* Todas las fotos traseras salen a 8,29 MP en 16:9. pickJpegSize ordena por area DENTRO del aspecto elegido, asi que un 4:3 mas grande quedaria fuera sin que el usuario lo sepa. Sin la lista de tamanos del aparato no puedo afirmar que se pierda resolucion, pero tampoco descartarlo.

### 3. Subir la resolucion de la camara frontal o justificar por que se queda en 2560x1440.

*1 juez(ces) lo senalaron.* La frontal entrega 3,69 MP frente a los 8,29 MP de las traseras: menos de la mitad. Ademas la unica muestra esta completamente desenfocada, con p1=111 y p99=184 (73 de 255 niveles), asi que su calidad real no se puede evaluar.

### 4. Aportar una toma de flash con caras para poder juzgar ojos rojos, sombras duras y tono de piel, y una serie a distancias conocidas (0,5 / 1 / 2 / 4 m) para el alcance util.

*1 juez(ces) lo senalaron.* En el expediente no hay ni una cara ni una referencia de distancia, asi que ojos rojos, sombras y alcance del flash quedan SIN EVIDENCIA y no se pueden puntuar a favor ni en contra.

### 5. Grabar un clip a pulso, caminando, para demostrar la estabilizacion electronica.

*1 juez(ces) lo senalaron.* El clip entregado tiene 0 px de desplazamiento entre fotogramas consecutivos (movil apoyado): la EIS esta en el codigo (CONTROL_VIDEO_STABILIZATION_MODE_ON con el OIS apagado al grabar) pero no hay forma de juzgarla.

### 6. Medir y publicar arranque en frio, latencia de obturador y cadencia del visor con el cronometro CamPerf que ya existe en el codigo.

*1 juez(ces) lo senalaron.* No hay UNA sola cifra de tiempo en el expediente. Con el rango de AE del visor fijado en [10,30] el visor puede caer a 10 fps con poca luz por diseno, y eso deberia estar medido y no supuesto.

### 7. Separar ajustes del selector de lentes: que el engranaje abra ajustes y quede una sola entrada a las lentes.

*1 juez(ces) lo senalaron.* Documentado en el aparato: el icono de engranaje abre SetupActivity ('Elige tu lente'), no unos ajustes, y en el mismo panel hay ademas un boton 'LENTES' que hace lo mismo. Dos entradas para lo mismo y un icono que promete otra cosa.

### 8. Exponer control de audio en video: ganancia o al menos silenciar la pista, filtro de viento y un medidor de nivel en pantalla mientras se graba.

*1 juez(ces) lo senalaron.* Hoy no hay ningun control: el motor recibe un booleano withAudio y el unico camino que lo apaga es el time-lapse. El usuario no puede saber si esta entrando sonido hasta que reproduce el fichero.

### 9. Anadir 24 fps y una salida de 10 bits/HDR al video, y exponer el codec HEVC por defecto en aparatos que lo soporten.

*1 juez(ces) lo senalaron.* setVideoHevc, setVideoFps y setVideoTargetHeight ya existen en el motor y hay chips en el layout (chip_vcodec, chip_vfps, chip_vres), pero lo entregado es H.264 de 8 bits a 30 fps: la capacidad esta escrita y el valor por defecto no la aprovecha.

### 10. Aflojar la reduccion de ruido en zonas planas o hacerla dependiente del ISO real de la toma, para no dejar la firma de acuarela.

*1 juez(ces) lo senalaron.* En foto_sinflash_2x, a ISO 13778, mi sigma en los bloques mas planos da 0,57-0,65: eso solo se consigue borrando la zona plana, mientras la textura conserva grano. Es exactamente el contraste que produce el efecto acuarela.

### 11. El estado del flash se muestra solo con un icono tachado, sin palabra. La version anterior escribia '⚡ auto' y '⚡ on' junto al icono; la actual perdio el texto. ARREGLO: recuperar la etiqueta de texto en el chip de flash (apagado/auto/encendido/linterna), que es el unico control cuyo estado cambia radicalmente la foto.

*1 juez(ces) lo senalaron.* Un rayo tachado es ambiguo entre 'flash apagado' y 'esta lente no tiene flash'. Regresion de legibilidad.

### 12. El audio arranca ~170 ms tarde (pista de video 14.51 s frente a 14.34 s de audio). Es el calentamiento del microfono de MediaRecorder. ARREGLO: abrir la fuente de audio antes de arrancar la grabacion de video, o compensar el desfase en el muxado.

*1 juez(ces) lo senalaron.* 170 ms de desfase es perceptible en sincronia labial y deja los primeros instantes mudos.

### 13. Las pastillas de zoom pierden la etiqueta sobre escena brillante: en panel-mas.png y noche-on.png el '1x' y el '0.6x' quedan encima de la imagen del televisor y apenas se leen. ARREGLO: fondo opaco o desenfoque real detras de las pastillas, o borde/sombra de contraste garantizado, en vez de translucidez fija.

*1 juez(ces) lo senalaron.* El encargo pide 'legible sobre cualquier escena' y sobre pantallas encendidas no se cumple.

### 14. El aviso 'Modo noche ON' es una pastilla blanca que TAPA la fila FOTO/VIDEO mientras esta visible. ARREGLO: colocarla donde no oculte controles (p. ej. bajo la pastilla de lente) y reducir su contraste para que no rompa el HUD oscuro.

*1 juez(ces) lo senalaron.* Un aviso no debe ocultar un control activo.

### 15. Salida fija de 8.29 MP en 16:9 (3840x2160) en las dos traseras. Existe una opcion '16:9 / FULL' en el panel, pero todo el lote medido salio en 16:9. ARREGLO: dejar 4:3 completo por defecto (mas pixeles y todo el campo del sensor) y 16:9 como recorte opcional.

*1 juez(ces) lo senalaron.* 16:9 tira la parte superior e inferior del sensor. 8.3 MP es poco para 2026 cuando lo normal binado son 12.5 MP.

### 16. El icono ⚙ abre el selector de lentes, no unos ajustes, y en el mismo panel hay ademas un boton 'LENTES' que hace lo mismo: dos entradas para lo mismo con un icono que promete otra cosa. ARREGLO: que ⚙ abra SettingsActivity y dejar solo 'LENTES' para el selector.

*1 juez(ces) lo senalaron.* Un engranaje que no lleva a ajustes rompe la convencion mas universal de las interfaces.

### 17. No hay ningun control de audio: ni ganancia, ni filtro de viento, ni silenciar, ni medidor de nivel, ni eleccion de mono/estereo. ARREGLO: al menos silenciar/grabar, y un medidor de nivel durante la grabacion.

*1 juez(ces) lo senalaron.* Ni siquiera se puede saber si el microfono esta captando algo hasta reproducir el clip.

### 18. Sin camara lenta, sin HDR/10 bits/Dolby Vision en video, sin 24 fps y sin controles de video pro (bloqueo de exposicion, WB manual, focus peaking en video). ARREGLO: priorizar 24 fps y bloqueo de exposicion/WB durante la grabacion, que son los que mas cambian el resultado con menos trabajo.

*1 juez(ces) lo senalaron.* El catalogo de video queda muy por debajo de lo esperable en 2026 aunque el motor ya soporte 4K60.

### 19. Sin compensacion de exposicion de flash ni control de potencia del LED. El flash es un todo o nada. ARREGLO: exponer un ajuste de potencia (o al menos +/- 1 EV de flash) usando la pre-captura para calibrar.

*1 juez(ces) lo senalaron.* Sin ello no hay forma de rescatar un primer plano quemado como el de la planta en la foto de 1x.

### 20. HDR, RAW y PRO aparecen como botones en el panel '...' pero NO hay ninguna evidencia de que funcionen: no se aporto ningun DNG, ninguna muestra HDR ni ninguna captura del modo PRO. ARREGLO: aportar (y verificar) un DNG que abra en un revelador, una muestra HDR con su ganancia, y confirmar que PRO cubre las dos lentes.

*1 juez(ces) lo senalaron.* Tres de las funciones mas vendibles de la app estan sin verificar y por tanto no cuentan.

### 21. Modo noche solo evidenciado en 0.6x: nada en teleobjetivo ni en la camara frontal. ARREGLO: habilitar y verificar el apilado en las tres camaras.

*1 juez(ces) lo senalaron.* El tele es la lente que mas necesita el apilado (ISO 12209 en la escena medida) y es donde no hay prueba de que exista.

### 22. El apilado nocturno no alarga la exposicion: 1/40 s a ISO 3684 por fotograma, apenas 1 EV mas de luz que un disparo normal (3684/40 frente a 2650/60). Toda la ganancia viene del promediado. ARREGLO: bajar la obturacion por fotograma con OIS activo (1/10-1/15) y bajar el ISO en la misma proporcion, y aumentar el numero de fotogramas cuando el analisis de alineacion detecte pulso estable.

*1 juez(ces) lo senalaron.* La nota de procedencia afirma 'tiempo largo e ISO bajo' y el EXIF dice lo contrario. Recoger fotones siempre gana a promediar ruido.

### 23. No hay ninguna medicion de velocidad en el expediente: ni arranque, ni latencia de obturador, ni fluidez del visor, pese a que el codigo ya tiene los cronometros CamPerf de apertura, de obturador->buffer y de cambio de rango de fps. ARREGLO: aportar las lecturas de logcat -s CamPerf de una sesion real.

*1 juez(ces) lo senalaron.* Un bloque entero queda sin puntuar por evidencia y no por la app. Los numeros ya se estan generando; solo hay que recogerlos.

### 24. No hay slider de zoom, ni de enfoque manual, ni de exposicion en ninguna captura aportada, pese a que el encargo del proyecto los pide explicitamente (tap-to-focus con anillo, slider de distancia, pellizco mas slider de zoom con indicador de nivel). ARREGLO: aportar capturas que los muestren, o implementarlos.

*1 juez(ces) lo senalaron.* Son requisitos explicitos de esta version y no hay forma de comprobarlos.

### 25. Documentar y limitar el alcance del flash. A 1x el flash ilumina solo el helecho a ~30 cm y deja la habitación a ISO 6056 de luz ambiente, además de subir el pie de negros de 23,0 a 32,9 (velo). Indicar en pantalla el alcance útil o avisar cuando el sujeto medido esté fuera de él.

*1 juez(ces) lo senalaron.* Un flash que solo llega a 30 cm y encima mete velo en el resto del encuadre empeora la foto más de lo que la mejora en la mayoría de las escenas.

### 26. Dar estado persistente al chip de modo noche. Medido: el chip de la luna es idéntico píxel a píxel en noche-on.png y noche-off.png (mismos 640 y 654 píxeles amarillos, mismo fondo). El único aviso es un mensaje que desaparece.

*1 juez(ces) lo senalaron.* El modo noche cambia el tiempo de captura de fracciones de segundo a varios segundos. Que no se pueda saber si está armado es la peor clase de estado invisible.

### 27. Sacar el botón verde de WhatsApp (btn_whatsapp) de la fila principal, junto a FOTO/VIDEO. Es un círculo verde saturado con icono de auricular telefónico: es el elemento más llamativo de toda la pantalla, parece un botón de llamada y pesa visualmente más que el obturador. Llevarlo al panel de compartir o a la galería.

*1 juez(ces) lo senalaron.* Rompe la jerarquía en el único sitio donde la jerarquía no se negocia: en una cámara el elemento dominante tiene que ser el obturador.

### 28. Marcar la pastilla de zoom activa. En la captura de la versión actual (interfaz-actual.png) ninguna pastilla está resaltada, aunque el chip dice que está en tele digital; la versión anterior sí ponía la activa en naranja (2.9x en zoom-2.9x.png). Es una regresión.

*1 juez(ces) lo senalaron.* En un sistema de dos lentes con cinco paradas, no saber en cuál estás es perder el control principal de la app.

### 29. Devolver el estado visible a los chips de la barra superior. La versión actual quitó las etiquetas 'off' del flash y del temporizador; ahora solo el flash muestra estado (tachado) y temporizador, cuadrícula y noche no muestran ninguno.

*1 juez(ces) lo senalaron.* Cuatro controles conmutables de los que solo uno dice en qué estado está. La barra pierde su función principal, que es decirte cómo va a salir la foto.

### 30. Poner fondo opaco (o desenfoque real) al panel '⋯' y ocultar lo que quede debajo. Hoy es translúcido: en panel-mas.png se leen a la vez el chip 'ID3 · 15 MM · 0.6X' y el helecho de la escena por detrás de HDR/RAW/Normal/PRO.

*1 juez(ces) lo senalaron.* Es un panel de decisiones (formato de salida, relación de aspecto) ilegible sobre escena clara. Ya estaba documentado en los hallazgos del aparato y sigue sin arreglarse en la evidencia entregada.

### 31. Implementar ZSL. El documento del aparato confirma PRIVATE_REPROCESSING y YUV_REPROCESSING presentes e idénticos en ID3 e ID6, con maxNumInputStreams=1, y el código no los usa: solo hay TEMPLATE_STILL_CAPTURE y ni un createReprocessableCaptureSession. Hoy se puede esperar hasta AF_WAIT_MAX_MS 600 + AE_PRECAPTURE_MAX_MS 900 = 1,5 s antes de capturar.

*1 juez(ces) lo senalaron.* La foto siempre se toma DESPUÉS de la pulsación, cuando el propio HAL permite tomarla del instante exacto. Es la ventaja de velocidad más grande que está sobre la mesa sin recoger.

### 32. Arreglar la metadata de color del vídeo: sale con primarias bt470bg (PAL) y matriz/transferencia smpte170m sobre yuvj420p marcado como rango completo. Para 1080p debe ser bt709 con rango limitado, o rango completo declarado coherentemente.

*1 juez(ces) lo senalaron.* Dos reproductores distintos van a mostrar colores y contraste distintos del mismo fichero, y un editor va a interpretarlo mal al importarlo.

### 33. Cerrar los 167 ms que le faltan a la pista de audio (14,336 s frente a 14,503 s de vídeo): arrancar el AudioRecord/MediaRecorder antes del primer fotograma y no cortar la pista al parar.

*1 juez(ces) lo senalaron.* El final de cada clip se queda mudo y, según cómo lo alinee el reproductor, arrastra desincronía perceptible en el resto.

### 34. Capturar al fotograma nativo del sensor por defecto, no a 8,29 MP 16:9. Las nueve fotos del expediente son 3840x2160; el panel '⋯' ya tiene un conmutador 16:9 / FULL que no se está usando.

*1 juez(ces) lo senalaron.* Se está tirando campo de visión vertical y resolución en todas las tomas, incluidas las del tele, que es la lente con detalle real que aprovechar.

### 35. Reducir la deriva de color a lo largo del recorrido de zoom: B/G pasa de 0,818 en 0.6x a 0,879 en el tele (7,5%, del orden de 200-300 K), y la saturación cae de 28,9% en 0.6x a 21,8% en 5x (-25%).

*1 juez(ces) lo senalaron.* La coincidencia de color justo en el cruce ya es buena (R/G 1,159 vs 1,164, B/G 0,879 en las dos) — sería una pena perder esa virtud por la deriva en los extremos del recorrido.

### 36. Medir y publicar los números de velocidad: tiempo de 'am start' hasta el primer fotograma del visor, latencia de obturador, shot-to-shot con 10 disparos y fps reales del visor, contra la app de ColorOS en el mismo CPH2765.

*1 juez(ces) lo senalaron.* No hay ni un dato de velocidad en todo el expediente, así que ese bloque no se puede puntuar por lo que la app hace sino por lo que el código sugiere. Sin medición no hay defensa posible.

### 37. Aportar evidencia de estabilización de vídeo (mismo recorrido a pulso con EIS on/off, y el recorte que aplica marcado en el visor) y de cámara lenta si el HAL la soporta (REQUEST_AVAILABLE_CAPABILITIES = CONSTRAINED_HIGH_SPEED_VIDEO).

*1 juez(ces) lo senalaron.* El código pide CONTROL_VIDEO_STABILIZATION_MODE_ON en vídeo pero no hay ni un fotograma que demuestre que funciona ni cuánto recorta.

### 38. Dejar apagar el EIS para tripode y gimbal, y activar PREVIEW_STABILIZATION (Android 13+) para que el visor y el archivo se estabilicen igual.

*1 juez(ces) lo senalaron.* Hoy 'useEis = videoSessionActive && eisAvailable' enciende el EIS sin opcion de apagarlo, con su recorte permanente, que es exactamente la critica que el expediente le hace al rival. Y sin PREVIEW_STABILIZATION lo que se ve al encuadrar no es lo que se graba. El aparato es Android 16.

### 39. Subir la resolucion de salida del modo noche a la del modo normal, o avisar del recorte antes de disparar.

*1 juez(ces) lo senalaron.* El modo noche entrega 3280x1856 = 6,09 MP frente a 8,29 MP del disparo normal (-27% de pixeles) y cambia de relacion de 1,778 a 1,767: el encuadre entregado no es el que se compuso. Ademas recorta un 0,198% de blancos, el maximo de todo el lote.

### 40. Poner la relacion FULL (sensor completo) como valor por defecto en vez de 16:9, o al menos explicar en el chip cuanta area de sensor se esta tirando.

*1 juez(ces) lo senalaron.* Todas las fotos entregadas salen a 3840x2160 = 8,29 MP en 16:9. En fotografia eso es descartar area de sensor de arranque, y en un telefono con la principal muerta cada pixel del gran angular cuenta.

### 41. Anadir bloqueo manual de obturador, ISO y balance de blancos DURANTE la grabacion, con regla de 180 grados automatica (obturador = 1/(2*fps)).

*1 juez(ces) lo senalaron.* El modo PRO existe para foto pero no hay evidencia de control manual sostenido en video. Sin bloqueo, un clip 'respira' en exposicion y color; con SENSOR_EXPOSURE_TIME y SENSOR_SENSITIVITY fijos el problema desaparece por construccion, y es la ventaja natural de una app Camera2.

### 42. Publicar en el HUD el ISO y la velocidad de obturacion reales del AE.

*1 juez(ces) lo senalaron.* El propio codigo lo admite: 'la lectura de ISO/velocidad reales del AE se queda fuera porque el motor todavia no publica aeIso/aeExposureNs'. Hoy el histograma solo muestra 'LUZ %' y la compensacion pedida. Sin ISO ni obturador en pantalla el modo pro esta incompleto.

### 43. Hacer que las cebras tengan umbral ajustable (por ejemplo 70% para piel y 95% para recorte) en vez del 250/2 fijo.

*1 juez(ces) lo senalaron.* En analyzeFrame() el umbral esta clavado en 'y >= 250 || y <= 2': es solo un aviso de recorte ya consumado. Una cebra util avisa ANTES de quemar, y el umbral de piel es la herramienta de exposicion mas usada en rodaje.

### 44. Corregir la cola de audio truncada: el archivo entregado tiene 14,503 s de video y 14,336 s de audio.

*1 juez(ces) lo senalaron.* Los ultimos 167 ms del clip se quedan mudos. No es un desfase de sincronia (ambas pistas arrancan en pts 0,000), pero al encadenar tomas en montaje produce huecos de sonido en cada corte.

### 45. Elevar la calidad del clip por defecto: HEVC en vez de H.264 con el mismo bitrate, o 4K30 por defecto donde el sensor lo permita, y exponer un selector de bitrate.

*1 juez(ces) lo senalaron.* Los 16,6 Mbps medidos son holgados para 1080p30 y no limitan nada, pero el conjunto (1080p30 H.264 8 bits) es el minimo de 2018. La escalera del codigo (42 Mbps en 4K, 24 en 1080p60, 17 en 1080p30) es razonable y esta desaprovechada porque el valor por defecto es el mas bajo.

### 46. Anadir camara lenta con createConstrainedHighSpeedCaptureSession() consultando getHighSpeedVideoSizes() de ID3 e ID6.

*1 juez(ces) lo senalaron.* No hay ningun modo de alta velocidad. Es la funcion de video mas pedida por usuarios normales y la unica del catalogo del rival que se puede replicar integramente con Camera2 sin depender del ISP.

### 47. Resolver la orientacion: rotar iconos y cifras 90 grados con OrientationEventListener manteniendo el layout fijo, y comprobar que el clip no depende de rotation=-180 en la matriz de pantalla.

*1 juez(ces) lo senalaron.* screenOrientation='portrait' en el manifiesto deja el HUD de lado al grabar en horizontal, que es como se graba video. Y el archivo entregado lleva rotation=-180: cualquier herramienta que ignore la matriz lo reproduce del reves.

### 48. Corregir la desaturación con flash también a 1x: saturación media 4.6 frente a 10.1 sin flash en la misma escena.

*1 juez(ces) lo senalaron.* El flash ya dispara de verdad (EXIF flash_disparo='SI'), que era el problema anterior; pero ahora lava el color y deja un velo gris-verdoso general. Se dispara y no quema (blancos recortados 0.006%), pero el color queda destruido.

### 49. Implementar el ZSL por reprocesado que el HAL ya declara.

*1 juez(ces) lo senalaron.* El documento de hallazgos confirma que ID3 e ID6 exponen PRIVATE_REPROCESSING y YUV_REPROCESSING con maxNumInputStreams=1, y concluye que el disparo sin retardo 'deja de ser depende del HAL y pasa a ser trabajo pendiente'. Mientras no esté, el retardo de obturador está sin mitigar y no hay ninguna medición que diga cuánto es.

### 50. Recuperar la resolución completa en modo noche: hoy baja a 3280x1856 (6.09 MP) frente a los 8.29 MP normales.

*1 juez(ces) lo senalaron.* Se pierde un 27% de píxeles justo en el modo que el usuario elige cuando la escena es difícil. Y los blancos recortados suben de 0.034% a 0.198%.

### 51. Hacer que el modo noche recoja MÁS LUZ, no solo más nitidez.

*1 juez(ces) lo senalaron.* Medido: la luminancia media apenas se mueve, 91.3 sin noche frente a 91.9 con noche. El apilado mejora nitidez (+81%) y ruido (-16%), que es un logro real, pero para el usuario que quiere ver donde no ve, hoy no aporta nada. Falta una toma larga de verdad, no solo apilado.

### 52. Publicar mediciones de arranque, latencia de obturador y disparo a disparo, tomadas con los cronómetros CamPerf que ya están instrumentados.

*1 juez(ces) lo senalaron.* No hay ni una cifra de velocidad en todo el expediente, así que ese bloque no se puede puntuar por encima del aprobado por pura falta de datos. La instrumentación ya existe en el código; solo falta ejecutarla y aportar los números.

### 53. Aportar evidencia en oscuridad real: todas las muestras están entre EV -0,56 y EV -2,31, interiores iluminados.

*1 juez(ces) lo senalaron.* El bloque de baja luz se juzga hoy con una escena que no es de noche. Cualquier nota sobre el modo noche está limitada por la procedencia de la evidencia, no por la app.

### 54. Mostrar en el visor de vídeo el formato en curso (resolución, fps, códec) y el estado del micrófono.

*1 juez(ces) lo senalaron.* En modo-video.png la fila superior sigue siendo la de foto y no hay ningún indicador de qué se está a punto de grabar. En vídeo, saber si vas a 1080p30 H.264 con o sin sonido es lo primero que hay que ver, antes que cualquier otra cosa.

### 55. Separar el icono ⚙ de la selección de lente: hoy abre el selector, duplicando además un botón 'LENTES' del mismo panel.

*1 juez(ces) lo senalaron.* Un engranaje promete ajustes y lleva a otra cosa, y hay dos entradas para la misma función. Está documentado como hallazgo 3 en el propio documento del proyecto.

### 56. Ofrecer bloqueo explícito de AE y AWB durante la grabación, con indicador en pantalla.

*1 juez(ces) lo senalaron.* Sin bloqueo, un clip 'respira': la exposición y el color se mueven solos a mitad de plano y no hay forma de casarlo con la toma siguiente en el montaje. El motor ya sabe hacerlo (CONTROL_AE_LOCK y CONTROL_AWB_LOCK aparecen en el controlador), pero no está expuesto para vídeo.

### 57. Añadir filtro pasa-altos conmutable contra el viento y el ruido de manejo (80-150 Hz) sobre el audio.

*1 juez(ces) lo senalaron.* Para grabar en exterior con el teléfono en la mano, el rumble de manejo y el viento son el enemigo número uno, y hoy no hay ninguna defensa ni ningún control. El rival tiene Audio Windscreen; BETA no tiene nada.

### 58. Detectar micrófono externo (AudioDeviceInfo.TYPE_USB_DEVICE / TYPE_USB_HEADSET) y permitir elegirlo con setPreferredDevice(), mostrando su nombre en el visor.

*1 juez(ces) lo senalaron.* Es una portería vacía: el expediente del rival documenta que la app de fábrica NO soporta micrófono USB-C (RØDE mantiene un artículo de soporte específico para Oppo/OnePlus) ni auriculares Bluetooth. Convertirse en la única app nativa del teléfono que sí lo hace vale más que cualquier filtro de color.

### 59. Etiquetar el vídeo con BT.709 y rango limitado (o full range correctamente declarado y coherente).

*1 juez(ces) lo senalaron.* El archivo sale como yuvj420p con color_range=pc y espacio smpte170m (BT.601) en material 1920x1080. HD debe ser BT.709. La combinación actual hace que el vídeo se vea lavado o con negros aplastados según el reproductor, sin que nadie toque nada.

### 60. Filtrar la tira de zoom por cámara activa y renombrar los pasos de la frontal.

*1 juez(ces) lo senalaron.* En frontal.png, con la cámara ID1 seleccionada, la tira sigue ofreciendo 2.9x y 5x, que son el teleobjetivo TRASERO, y la pastilla llama '0.6X' a la frontal. Ofrecer una lente que no existe en la cámara en uso es el fallo de coherencia más visible de la interfaz.

### 61. Igualar la rendición tonal entre el gran angular y el tele: la misma curva y el mismo objetivo de contraste en las dos lentes.

*1 juez(ces) lo senalaron.* El angular entrega p1 24.1 → p99 214.5 (recorrido de 190 niveles) y el tele p1 35.1 → p99 150.6 (116 niveles) en la misma escena. El tele sale lechoso, sin negros ni blancos reales, y el salto al cambiar de lente es brutal. La media de luminancia también salta de 91.3 a 104.6.

### 62. Corregir la dominante cálida del balance de blancos por defecto.

*1 juez(ces) lo senalaron.* En el gran angular sin flash los canales quedan en R 99.3 / G 89.9 / B 77.1 (22 niveles de diferencia entre rojo y azul) y en el tele en R 115.6 / B 89.8. Es una dominante ámbar sistemática que ninguna de las dos lentes corrige.

### 63. Implementar ZSL por reprocesado (PRIVATE_REPROCESSING / YUV_REPROCESSING) y publicar mediciones de arranque, latencia de obturador y fluidez del visor por adb.

*1 juez(ces) lo senalaron.* El propio informe de dispositivo confirma que PRIVATE_REPROCESSING y YUV_REPROCESSING están presentes en ID3 e ID6 con maxNumInputStreams=1 y que el disparo sin retardo 'pasa a ser trabajo pendiente'. Hoy cada disparo con flash/AE arrastra hasta 900 ms de precaptura. Y no existe UNA SOLA medición de tiempo en todo el expediente: el bloque de velocidad se juzga a ciegas.

### 64. Reducir la latencia del modo noche y mostrar cuenta atrás: el vigilante actual llega a 18 s.

*1 juez(ces) lo senalaron.* NIGHT_WATCHDOG_BASE_MS 4000 + 2000 x 7 fotogramas = 18 s de plazo. El apilado es Kotlin puro sobre CPU con 6,09 MP x 7 fotogramas. Es exactamente la queja documentada contra la app rival ('Night mode taking 18 sec... nadie puede sostener el teléfono 18-20 s'). Sin cuenta atrás visible, el usuario no sabe si soltar el teléfono.

### 65. Apilar en modo noche desde RAW (o al menos desde YUV con NOISE_REDUCTION_MODE OFF/MINIMAL y TONEMAP en CONTRAST_CURVE), no desde YUV_420_888 ya procesado.

*1 juez(ces) lo senalaron.* El NightStacker recibe fotogramas de 8 bits a los que el ISP ya aplicó su reducción de ruido y su curva. De-gamma a 12 bits recupera precisión aritmética pero no información: lo que el denoiser borró no vuelve. Es el techo estructural del modo noche actual y la razón de que gane detalle sólo a costa de grano.

### 66. Cerrar los 167 ms que le faltan a la pista de audio respecto al vídeo y comprobar la sincronía de arranque real.

*1 juez(ces) lo senalaron.* Medido: vídeo 14.503 s, audio 14.336 s, ambos con start_time 0.000. La diferencia es del orden del umbral de percepción de desincronía. Con MediaRecorder el micro arranca después del codificador; conviene medirlo con claqueta y compensar.

### 67. Que el icono ⚙ abra los ajustes y no el selector de lentes, y eliminar la duplicidad con el botón 'LENTES'.

*1 juez(ces) lo senalaron.* Del propio informe de dispositivo: 'un engranaje que no lleva a ajustes... dos entradas para lo mismo, con un icono que promete otra cosa'. Un icono universalmente entendido que hace otra cosa es peor que no tener icono.

### 68. Sacar el botón de WhatsApp de la franja de control principal (o al menos darle un icono que se entienda) y no darle la jerarquía del conmutador FOTO/VIDEO.

*1 juez(ces) lo senalaron.* En interfaz-actual.png un círculo verde con auricular telefónico comparte fila con FOTO|VIDEO. En una cámara, un auricular verde se lee como 'llamar'. Un atajo de compartir no puede tener el mismo peso visual que el selector de modo.

### 69. Investigar por qué interfaz-actual.png sale con el visor completamente en negro y asegurar que la captura del visor funciona (o documentar que es limitación de screencap).

*1 juez(ces) lo senalaron.* La ÚNICA captura de la versión actual muestra la aplicación abierta, con la pastilla 'TELEPHOTO · 77 MM · DIGITAL' y todos los controles pintados, y ni un píxel de imagen. O el visor no estaba entregando fotogramas o la captura no lo recoge; en ambos casos la versión actual queda sin una sola prueba visual de su visor.

### 70. Auditar la rotación de las dos rutas de guardado: la ruta directa del HAL y la de noche (rotateNv21) no coinciden en el material entregado.

*1 juez(ces) lo senalaron.* foto_sinflash_0.6x y foto_noche_0.6x son la misma escena con la misma lente separadas por dos minutos, y correlacionan al 0.911 sólo tras girar una de ellas 180°. Puede ser que el probador girara el teléfono, pero conviene descartar que currentJpegOrientation() se aplique con distinto signo en las dos rutas: el propio código documenta que ya hubo un fallo así ('Antes la de noche salía con Orientation=6 y el buffer sin girar').

### 71. Mejorar la cámara frontal: subir la resolución por encima de 2560x1440, controlar el ISO (dispara a 8156) y dar enfoque o al menos avisar de que es de foco fijo.

*1 juez(ces) lo senalaron.* La frontal entrega 3.69 MP —menos de la mitad que las traseras—, ruido entre 3.0 y 5.5 por banda de luminancia, un histograma de sólo 77 niveles útiles (p1 106.8 → p99 186.2) y la muestra entregada está completamente desenfocada. Parte es encuadre del probador, pero la resolución, el ruido y la planitud son de la app.

### 72. Aportar evidencia de baja luz REAL (EV por debajo de -3) para el modo noche.

*1 juez(ces) lo senalaron.* La única toma 'de noche' del expediente es un salón con el televisor encendido a EV -0.56. Un modo noche no se juzga en un salón: se juzga en una calle sin farolas. Sin esa prueba, la parte de 'cuánta luz recoge de verdad' queda sin evidencia y el modo no puede puntuar por encima de 5.

### 73. Modular la reducción de ruido por ISO real y conservar micro-textura: perfil de detalle por ISO en vez de una NR agresiva y uniforme.

*1 juez(ces) lo senalaron.* A ISO 2650 el ruido en zona plana es 0,91 y la pared queda sin un solo grano: efecto acuarela puro. La energía de alta frecuencia del gran angular se queda en 0,1442 frente a 0,3995 del tele en la misma sesión.

### 74. Conservar la resolución y el EXIF en el modo noche: salir a 8,29 MP como el resto y escribir Make, Model, Orientation, exposición e ISO; y arreglar la etiqueta Software, que sale corrompida.

*1 juez(ces) lo senalaron.* foto_noche baja a 3280x1856 (6,09 MP) frente a 8,29 MP del resto, no tiene Make ni Model ni Orientation, y el campo Software dice literalmente 'Camara ? modo noche' (carácter no ASCII destrozado). La mejor foto del lote es la peor documentada.

### 75. Sacar a strings.xml todos los literales de interfaz que siguen en el código en castellano: 'ANÁLISIS', 'MF', 'AEB', los wbLabels ('WB Incand.', 'WB Fluor.', 'WB Sol', 'WB Nube', 'WB Sombra'), 'Repetir', 'Usar esta', 'Herramientas de análisis: ', 'Lector de códigos activado/desactivado' y los cuatro hint() de error.

*1 juez(ces) lo senalaron.* La app tiene locale en inglés (values-en) y la captura actual está en inglés ('TELEPHOTO', 'PHOTO', 'VIDEO'), pero esos chips y avisos aparecerán en castellano en el mismo panel. Mezcla de idiomas visible en pantalla.

### 76. Unificar el margen izquierdo del HUD en un solo valor para las cuatro filas.

*1 juez(ces) lo senalaron.* Medí los bordes izquierdos sobre interfaz-actual.png (1140 px de ancho): chip de lente 46 px, fila de zoom 52 px, barra superior de iconos 63 px y fila del obturador 85 px. Son unos 16, 18, 22 y 26dp: cuatro márgenes distintos apilados en vertical, y el ojo lee el borde izquierdo como dentado.

### 77. Rebajar el botón de WhatsApp y cambiarle el glifo: quitarle el auricular de teléfono (usar un icono de compartir) y no dejarlo compitiendo en peso visual con el obturador.

*1 juez(ces) lo senalaron.* En la única captura del HUD actual es un círculo verde saturado y opaco, el segundo elemento más llamativo de la pantalla, y su glifo es un AURICULAR: lee 'llamar', no 'compartir a WhatsApp'. Un atajo a una app de terceros no puede ser el segundo foco de una interfaz de cámara. (El código ya lo baja al 35% con aro en wa_circle_bg; la captura aportada todavía muestra la versión opaca.)

### 78. Revisar el valor por defecto de 16:9 y la resolución de salida: ofrecer 4:3 a resolución completa por defecto en vez de migrar a capRatio = 2.

*1 juez(ces) lo senalaron.* Las dos lentes traseras entregan 8,29 MP en 16:9 (3840x2160). En una app cuyo argumento es la calidad de imagen, arrancar recortando la relación nativa del sensor tira superficie y píxeles sin que el usuario lo pida.

### 79. Etiquetar el estado en la barra superior de iconos: al menos un rótulo corto o un punto de estado para cuadrícula y modo noche, como ya se hace con el temporizador ('3s').

*1 juez(ces) lo senalaron.* Los cinco chips superiores son solo icono. Únicamente el flash codifica sus cuatro estados con iconos distintos y el temporizador escribe los segundos; para cuadrícula, noche y '⋯' el usuario tiene que memorizar el color del trazo. En un producto que quiere ser 'simple para el casual' eso es discoverability perdida.

### 80. Restaurar el EXIF completo en el modo noche: hoy pierde marca, modelo, fecha, GPS, apertura y equivalente de 35 mm, y escribe Orientation=0, que no es un valor valido (el rango legal es 1-8). El campo Software ademas sale con un caracter roto: 'Camara ? modo noche'.

*1 juez(ces) lo senalaron.* Esas fotos quedan sin fecha ni lugar en la galeria y ordenadas de forma distinta al resto; y un tag de orientacion invalido puede hacer que algun visor las gire mal.

### 81. Que el modo noche no baje la resolucion ni cambie la proporcion: entrega 3280x1856 (6,09 MP, ratio 1,767) frente a 3840x2160 (8,29 MP, 1,778) del disparo normal.

*1 juez(ces) lo senalaron.* Rompe la coherencia del carrete y el usuario pierde un 27% de pixeles justo cuando mas detalle necesita.

### 82. Hacer opaco el panel '...' y ordenarlo. Hoy es traslucido, deja ver por debajo el chip de lente, y mezcla sin agrupar HDR, RAW, Normal, PRO, 16:9, FULL, LENTES, atras, WhatsApp y engranaje.

*1 juez(ces) lo senalaron.* Parece un menu de desarrollador. Un usuario no tecnico no sabe que hace 'RAW' ni por que 'FULL' esta al lado de 'WhatsApp'.

### 83. Separar ajustes de selector de lentes: el engranaje abre el selector de lentes, no unos ajustes, y en el mismo panel hay ademas un boton 'LENTES' que hace exactamente lo mismo.

*1 juez(ces) lo senalaron.* Un icono universalmente conocido que lleva a otro sitio, y dos entradas para la misma pantalla, es desorientacion pura.

### 84. Dar realimentacion continua del zoom cuando no se esta en una parada exacta: hoy, si el pellizco deja el zoom entre paradas, no se resalta ninguna pastilla (highlightZoomStrip anula la seleccion si la distancia relativa supera 0,02). Añadir un indicador de posicion continua o resaltar la parada mas cercana de forma atenuada.

*1 juez(ces) lo senalaron.* En la captura de la version actual el chip dice 'TELEPHOTO · 77 MM · DIGITAL' y las cinco pastillas estan apagadas: el usuario no ve donde esta.

### 85. Cerrar la doble fuente de verdad del encuadre: el propio comentario de Camera2Controller.setPreviewFill documenta que syncPreviewGravity y el motor aplican preferencias distintas y que el resultado es 'un parpadeo del encuadre en cada toque de chip'. La solucion esta escrita en el codigo: que syncPreviewGravity llame a controller.setPreviewFill(cover).

*1 juez(ces) lo senalaron.* Es un defecto conocido, con arreglo de una linea ya identificado, que ensucia cada interaccion.

### 86. Adaptar la tira de zoom a la camara frontal: en la captura de la frontal se siguen ofreciendo 2.9x y 5x, paradas que corresponden al teleobjetivo trasero, y el chip dice 'ID1 · 20 MM · 0.6X'.

*1 juez(ces) lo senalaron.* Ofrece un zoom que no existe y llama '0.6x' a una camara de selfie, que para el usuario no significa nada.

### 87. Mejorar la camara frontal o al menos documentarla: solo entrega 2560x1440 (3,69 MP), menos de la mitad que las traseras, y la unica muestra aportada es un plano desenfocado de un techo liso donde no se ve nada.

*1 juez(ces) lo senalaron.* La frontal es la camara mas usada del dia a dia y en este expediente esta sin defender.

### 88. Mostrar progreso y cuenta atras en el modo noche: el vigilante permite 4 s + 2 s por fotograma (hasta 18 s con los 7 fotogramas) y no hay ninguna captura que demuestre indicador de progreso, aviso de 'no te muevas' ni posibilidad de cancelar.

*1 juez(ces) lo senalaron.* Es la queja numero uno documentada contra el modo noche del rival; repetirla seria desaprovechar una ventaja regalada.

### 89. Probar y aportar el modo noche con el teleobjetivo: solo hay muestra en gran angular a 0.6x, y es justo con el tele (ISO 12209 en la toma normal) donde mas falta hace.

*1 juez(ces) lo senalaron.* Sin esa prueba, la mejor funcion de la app queda demostrada solo en la mitad del sistema optico.

### 90. Suavizar la caida del visor a 10 fps con poca luz (rango [10,30]) o avisar en pantalla cuando ocurre.

*1 juez(ces) lo senalaron.* Encuadrar a 10 fps se siente roto justo en las escenas dificiles, que son las que exigen encuadrar con calma.

### 91. Completar la evidencia prometida que no llego: no existen ni 00-inicio.png ni grabando.png en el material entregado, asi que la pantalla de arranque y el estado de grabacion quedan sin juzgar; tampoco hay ni un DNG, ni una foto en Ultra HDR, ni una rafaga, pese a que los tres interruptores existen.

*1 juez(ces) lo senalaron.* Funciones declaradas y no demostradas no puntuan; y en el caso del RAW y el Ultra HDR seria justo lo que mas distinguiria a esta app.

---

## Impacto BAJO (27)

### 1. Unificar el camino de orientacion: el JPEG normal la entrega por etiqueta EXIF (Orientation=3) y el de noche gira el BUFFER y deja la etiqueta a 0. Elegir uno de los dos y aplicarlo igual en foto, noche y video.

*1 juez(ces) lo senalaron.* Dos mecanismos distintos para lo mismo multiplican las formas de fallar, y de hecho ya ha fallado en el camino de noche, que es el que gira el buffer y escribe un valor de orientacion ilegal.

### 2. Volver a capturar las pruebas de interfaz con una escena visible y aportar las capturas que faltan (00-inicio.png y grabando.png, prometidas y no entregadas).

*1 juez(ces) lo senalaron.* La unica captura de la version actual (interfaz-actual.png) tiene el visor completamente en negro, de modo que la legibilidad del HUD sobre escena solo se puede juzgar sobre la version anterior. Es una limitacion de la EVIDENCIA, no de la app, y lo hago constar como tal.

### 3. Saturacion global baja en todo el lote (maximo 10.4 de media, y eso en una escena con planta verde, alfombra estampada y televisor encendido), y efecto acuarela visible a 1x en las hojas de la planta y la pared. ARREGLO: subir ligeramente la saturacion en la curva propia y elegir el perfil de detalle con el ISO REAL de la foto (ya previsto en codigo) para no aplastar la microtextura en los pasos intermedios.

*1 juez(ces) lo senalaron.* Las fotos salen apagadas frente a cualquier camara de fabrica, y el 1x es el encuadre por defecto.

### 4. Corregir el campo Software del EXIF del modo noche: hoy contiene 'Camara ? modo noche', con el carácter roto por codificación. Escribirlo en ASCII o en UTF-8 correctamente marcado.

*1 juez(ces) lo senalaron.* Es la firma de la app en cada fichero nocturno y sale con basura. Barato de arreglar y visible en cualquier inspector de metadatos.

### 5. Completar la integración con el sistema: TileService de Ajustes Rápidos, accesos directos de lanzador y STILL_IMAGE_CAMERA_SECURE para disparar desde la pantalla de bloqueo.

*1 juez(ces) lo senalaron.* El manifiesto ya declara STILL_IMAGE_CAMERA, IMAGE_CAPTURE, GET_CONTENT y PICK —integración más ancha que la app de fábrica— y le faltan justo los caminos de arranque rápido, que es lo que decide si el usuario la usa o abre la de siempre.

### 6. Entregar la evidencia completa y coherente: faltan los ficheros 00-inicio.png y grabando.png que se anuncian, y la única captura de la versión actual (interfaz-actual.png) tiene el visor completamente en negro.

*1 juez(ces) lo senalaron.* El acabado visual de la versión actual no se puede juzgar con un visor negro, y eso obliga a puntuar la interfaz con capturas de una versión anterior. Se está evaluando a ciegas la parte que más ha cambiado.

### 7. Separar el engranaje del selector de lentes: el icono de ajustes debe abrir ajustes, y quitar la duplicidad con el boton 'LENTES' del mismo panel.

*1 juez(ces) lo senalaron.* Lo documenta el propio proyecto: 'un engranaje que no lleva a ajustes' y dos entradas para lo mismo en el mismo panel, con un icono que promete otra cosa.

### 8. Sustituir 'ID3' por lenguaje humano en el chip principal (por ejemplo 'GRAN ANGULAR . 15 MM'), dejando el ID como dato secundario o en ajustes.

*1 juez(ces) lo senalaron.* 'ID3' es jerga de desarrollador en un HUD de consumo. El resto del chip (focal en mm + DIGITAL) es excelente y no necesita el numero de camara delante para funcionar.

### 9. Recuperar altura de encuadre: la banda inferior de HUD ocupa cerca de un cuarto de la pantalla entre pastillas de zoom, conmutador FOTO/VIDEO y fila del obturador.

*1 juez(ces) lo senalaron.* En un visor vertical esa banda es exactamente el espacio que el usuario necesita para componer. Fusionar la fila de pastillas con la del conmutador, o dejarla flotar sobre la imagen con degradado, devuelve pantalla sin perder controles.

### 10. Anadir STILL_IMAGE_CAMERA_SECURE (disparo desde la pantalla de bloqueo con showWhenLocked), un TileService de Ajustes rapidos y accesos directos de lanzador.

*1 juez(ces) lo senalaron.* La integracion por intents ya es buena (STILL_IMAGE_CAMERA, VIDEO_CAMERA, IMAGE_CAPTURE, VIDEO_CAPTURE, GET_CONTENT, PICK, FileProvider), pero faltan las tres vias de arranque rapido mas usadas, y en este telefono la app deberia ser el unico camino a una camara que funcione.

### 11. Documentar o rehacer la evidencia de camara frontal: la muestra entregada esta completamente desenfocada y solo tiene 3,69 MP.

*1 juez(ces) lo senalaron.* foto_frontal sale a 2560x1440 con ISO 8156 a 1/20 s y una energia de alta frecuencia de 0,7153 que es ruido, no detalle. Con esa unica muestra no se puede juzgar la frontal, y lo que se ve es una camara de baja resolucion sin enfocar.

### 12. Corregir el documento interno que afirma que el video sale con 'audio AAC correcto'.

*1 juez(ces) lo senalaron.* El documento docs/2026-08-01_hallazgos-en-dispositivo.md, punto 4, da por bueno el audio del clip. El mismo clip mide 8000 Hz mono a 12,2 kbps. Un expediente que valida un defecto critico hace que el defecto sobreviva otra ronda.

### 13. Añadir cámara lenta con createConstrainedHighSpeedCaptureSession si getHighSpeedVideoSizes lo permite en ID3/ID6.

*1 juez(ces) lo senalaron.* No existe ninguna referencia a sesiones de alta velocidad en el código. Es una ausencia visible frente a cualquier cámara de 2026, y el motor ya enumera el StreamConfigurationMap para lo demás.

### 14. Demostrar el 4K: el menú lo ofrece (vresList incluye 2160 con la comprobación supports4kVideo) pero no hay ni una muestra grabada.

*1 juez(ces) lo senalaron.* Una capacidad que no se aporta grabada no se puede puntuar. La única muestra de vídeo del expediente es 1080p30.

### 15. Aportar los ficheros de evidencia que faltan: 00-inicio.png y grabando.png.

*1 juez(ces) lo senalaron.* El encargo los prometía y no están en la carpeta. Sin ellos no se puede verificar ni la pantalla de arranque ni el HUD de grabación en uso, y ambos son justamente donde se juzga si la app estorba al encuadre.

### 16. Corregir la afirmación 'audio AAC correcto' del documento de hallazgos del proyecto.

*1 juez(ces) lo senalaron.* Se refiere a este mismo archivo, cuya pista es de 8 kHz mono a 12.2 kbps. Que esa frase esté escrita significa que nadie del equipo ha abierto nunca la pista de audio, y ese es un problema de proceso, no solo de código.

### 17. Cuantificar y publicar la estabilización de vídeo (EIS) y añadir cámara lenta y 24 fps.

*1 juez(ces) lo senalaron.* El código activa CONTROL_VIDEO_STABILIZATION_MODE_ON si la lente lo publica, pero en el archivo entregado no hay forma de medir si actuó. Y no hay cámara lenta, ni 24 fps, ni ningún modo de vídeo más allá del normal y el time-lapse.

### 18. Dejar de mostrar identificadores internos de cámara ('ID3', 'ID6') en la pastilla de lente.

*1 juez(ces) lo senalaron.* Las capturas de la versión anterior muestran 'ID3 · 15 MM · 0.6X' e 'ID6 · 70 MM · 2.9X'. La versión actual ya lo corrige con 'TELEPHOTO · 77 MM · DIGITAL', que es la solución correcta: conviene asegurarse de que no queda ningún camino que revele el ID crudo.

### 19. Revisar la exclusión mutua entre RAW, Ultra HDR, noche y QR, y explicarla en la interfaz cuando el usuario intente combinarlas.

*1 juez(ces) lo senalaron.* El código las hace excluyentes por el límite de streams del HAL (keepOnlyExtra). Es una restricción honesta, pero hoy el usuario descubre que activar una apaga otra sin entender por qué.

### 20. Ampliar el catálogo hacia lo que hoy se da por hecho: panorama, retrato/bokeh, ráfaga con contador, larga exposición y foto en movimiento.

*1 juez(ces) lo senalaron.* El inventario actual es amplio para una app de terceros (RAW, Ultra HDR, QR, PRO completo, time-lapse, filtros, marca de agua, lupa de enfoque) y la integración por intents es sobresaliente (IMAGE_CAPTURE, VIDEO_CAPTURE, GET_CONTENT, PICK, STILL_IMAGE_CAMERA), pero faltan modos que cualquier usuario espera encontrar y que no dependen de hardware especial.

### 21. Aportar capturas del HUD actual sobre escena CLARA y sobre escena oscura, y del panel '⋯' ya opaco.

*1 juez(ces) lo senalaron.* La única captura de la interfaz actual está sobre una escena negra (medí la zona del visor: máximo 12/255), así que la legibilidad sobre escena clara solo se puede deducir de los tokens de color. Las capturas que sí muestran escena clara son de la versión anterior, donde los chips son casi transparentes y el panel '⋯' deja ver el chip de lente por debajo. La corrección está afirmada pero no mostrada.

### 22. Reducir la huella de la banda de controles: la fila de zoom de cinco botones de 56x48dp a todo el ancho duplica lo que ya hace el pellizco y empuja hacia abajo el selector de modo y el obturador.

*1 juez(ces) lo senalaron.* Entre panel, fila de zoom, fila de modo y fila de obturador, la banda inferior ocupa cerca de un tercio de la pantalla y crece más al abrir cualquier panel — justo el espacio que el usuario necesita para encuadrar.

### 23. Contemplar el uso en horizontal: rotar los glifos y los rótulos del HUD aunque la Activity siga bloqueada en vertical.

*1 juez(ces) lo senalaron.* La app está bloqueada en vertical y no hay ninguna adaptación al giro. Es exactamente la queja que la prensa le hace al rival ('la interfaz no rota en horizontal'), así que hoy no es una ventaja competitiva sino un empate en un defecto conocido.

### 24. Revisar el destello parasito de la lente tele: en la captura del visor a 5x se ve un reflejo violeta y azul cruzando la imagen desde una luz del techo.

*1 juez(ces) lo senalaron.* Aparece incluso sin flash, asi que apunta a un problema de la propia optica o de la ventana, y agrava el desastre del flash con el tele.

### 25. Quitar la jerga interna que aun asoma en la interfaz ('ID3', 'ID6', 'ID1') donde no haga falta; la version actual ya lo hace bien con 'TELEPHOTO', hay que rematarlo en el asistente y en el resto de pantallas.

*1 juez(ces) lo senalaron.* Para un usuario normal 'ID6' no significa nada; 'Teleobjetivo · 70 mm' si.

### 26. Añadir los modos ausentes que la gente busca por costumbre: retrato, panoramica y camara lenta; y unificar QR, texto y documento en un solo modo 'Escanear'.

*1 juez(ces) lo senalaron.* Son los huecos visibles frente a cualquier camara de fabrica, y el lector de QR ya esta hecho, asi que el camino esta medio andado.

### 27. Documentar y verificar el sonido de obturador y el haptico de captura: no hay ninguna evidencia de que exista realimentacion al disparar.

*1 juez(ces) lo senalaron.* Sin confirmacion de disparo el usuario duda de si la foto se hizo y aprieta dos veces.
