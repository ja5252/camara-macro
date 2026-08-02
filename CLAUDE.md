# Cámara Macro — contexto del proyecto (para el consejo)

App Android (APK) que abre **directo una lente que funciona** en un Oppo cuya cámara
principal está dañada, y permite tomar fotos. **Ya funciona**; ahora se mejora.

## Hardware / realidad del dispositivo
- Modelo real de prueba: **Oppo CPH2765**, Android 16, ColorOS. Plegable:
  pantalla interior 2248x2480, exterior 1140x2616.
- 8 cámaras; IDs públicos: 0..7. Traseras reales: **ID2 (5 mm, 200 MP, MUERTA)**,
  **ID3 (2.3 mm gran angular, ISO 100-6400, exp. 83 µs–30 s)**,
  **ID6 (10.55 mm tele, ISO 100-12800)**, ID7 (profundidad, 1600x1200).
  Frontales: ID1, ID4, ID5.
- **ID 0 = cámara LÓGICA sobre las físicas [3, 2, 6]. NUNCA abrirla.** Medido con
  `dumpsys media.camera` con la app nativa abierta: se abre, se configura, queda
  `Device status: ACTIVE`... y **`Frames produced: 0` en TODOS sus streams**, tanto
  a 1x como a 0.6x. No es que enfoque mal: no entrega ni un fotograma. Por eso
  WhatsApp, Gemini y la cámara nativa salen en negro y se cuelgan: todos abren ID0.
- No se puede quitar la ID0 del sistema: `ro.boot.flash.locked=1`,
  `verifiedbootstate=green`, build `user`, sin root. La lista de cámaras la fija el
  HAL del fabricante en una partición de solo lectura con verified boot.
- ColorOS **quita el acceso a la cámara si la app no está visible** → abrir/cerrar
  la cámara con el ciclo de vida; probar con pantalla encendida.

## Stack y restricciones
- Kotlin + **Camera2** (NO CameraX: hay que abrir una lente física por su ID exacto).
- viewBinding, Material3, `minSdk 26`, `targetSdk 34`, `compileSdk 34`.
- Una sola lente trasera a la vez; pantalla **vertical**.
- Debe ser **rapidísima** para disparar (<1s de abrir a listo).

## Objetivo de la versión actual (lo que pidió el usuario)
1. Mejorar **muchísimo la interfaz** (premium, elegante, legible sobre cualquier escena).
2. **Selección de enfoque**: tap-to-focus (anillo) + enfoque manual (slider de distancia).
3. **Zoom**: pellizco + slider + indicador del nivel.
4. **Galería** de las últimas fotos (miniatura de la última + pantalla de recientes).
Mantener simple para el casual, con control para el entusiasta.

## Build y pruebas
- Build: **GitHub Actions → Releases (tag `latest`)** (repo `ja5252/camara-macro`).
- Pruebas: **ADB por USB** (`C:\Users\pepea\platform-tools\adb.exe`) sobre el CPH2765 conectado.
  Capturas con `adb shell screencap`, errores con `adb logcat`.

## Archivos actuales
- `app/src/main/java/com/pepe/camaramacro/`
  - `CameraActivity.kt` — pantalla de uso diario (preview + obturador).
  - `SetupActivity.kt` — elegir lente (primera vez).
  - `Camera2Controller.kt` — motor de cámara (preview + foto). Aquí van enfoque y zoom.
  - `CameraInfoUtil.kt` — lista lentes; `AutoFitTextureView.kt` — preview sin deformar.
- `app/src/main/res/` — layouts, tema, ícono, textos.

## Cómo opera el consejo
- El consejo (`.claude/agents/`) trabaja **por fases**, invocando solo el escuadrón
  pertinente (máx. 4-6 en paralelo), integrando y avanzando. Ver el catálogo y FASES.
- Para esta versión solo aplican los escuadrones 01 (diseño/UX), 02 (arquitectura, ligero),
  03 (captura: enfoque, zoom, multicam) y 08 (QA/rendimiento/entrega). El resto
  (RAW, HDR, noche, astro, XPan, video, ML, color science) es para fases futuras.
