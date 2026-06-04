# Cámara Macro

App de cámara para Android que **abre directo una lente que sí funciona** (evitando
la cámara principal dañada) y permite tomar fotos con un botón grande.

## ¿Qué problema resuelve?

En el celular la cámara de fábrica siempre abre la lente principal por defecto. Si esa
lente tiene una falla de enfoque, la app se traba. Esta app deja **elegir una sola vez**
la lente que funciona (por ejemplo, la del modo macro / gran angular) y desde entonces
abre siempre esa, sin pasar por la dañada.

- **Quién / cuándo:** proyecto personal, 2026.
- **Entradas:** ninguna; la app detecta las lentes del teléfono en tiempo real.
- **Salidas:** fotos JPEG guardadas en la galería, en `Pictures/CamaraMacro`.

## Cómo se usa

1. Instala el APK (ver abajo).
2. La **primera vez** te muestra todas las lentes con vista previa en vivo. Recorre con
   **Anterior / Siguiente**, identifica la que se ve como tu modo macro (la que funciona)
   y toca **"Usar esta lente"**.
3. A partir de ahí la app abre directo esa lente. Toca el botón redondo para tomar la foto.
4. Para cambiar de lente, usa **⚙ Cambiar lente** arriba a la derecha.

## Cómo se compila (automático, en la nube)

No necesitas instalar nada. Cada vez que se suben cambios a la rama `main`, GitHub Actions
compila el APK y lo publica en la sección **Releases** del repositorio (etiqueta `latest`).

- Workflow: [.github/workflows/build.yml](.github/workflows/build.yml)
- Resultado: archivo `camara-macro.apk` en *Releases → latest*.

## Cómo instalar el APK en el celular

1. Abre la página de *Releases* del repositorio **en el navegador del celular**.
2. Descarga `camara-macro.apk`.
3. Ábrelo. Si Android lo pide, activa **"permitir instalar apps de orígenes desconocidos"**
   para el navegador.
4. Instala y ábrela.

## Detalles técnicos

- Kotlin + API **Camera2** (control directo del ID de cada lente).
- Nunca dispara un enfoque bloqueante y tiene un *watchdog* de 5 s: si una lente no
  responde (p. ej. la dañada), avisa y deja probar otra.
- `minSdk 26`, `targetSdk 34`.

## Estructura

```
.
├─ app/                         app Android
│  └─ src/main/
│     ├─ java/com/pepe/camaramacro/
│     │   ├─ CameraActivity.kt      pantalla de uso diario
│     │   ├─ SetupActivity.kt       elegir lente (primera vez)
│     │   ├─ Camera2Controller.kt   motor de cámara (preview + foto)
│     │   ├─ CameraInfoUtil.kt      lista las lentes del teléfono
│     │   └─ AutoFitTextureView.kt  vista previa sin deformar
│     ├─ res/                       pantallas, ícono, textos
│     └─ AndroidManifest.xml
└─ .github/workflows/build.yml  compilación automática del APK
```
