# Línea base de evidencia — CPH2765, 1 ago 2026, 20:49

Campaña automatizada por ADB sobre la versión **release con R8** (commit `e6dbd28`),
que ya incluye el arreglo de exposición (`92ab92f`). Escena: habitación con luz
artificial tenue. Todas las cifras son medidas, no impresiones.

## Fotos — barrido de zoom, sin flash

| zoom | lente | focal | exposición | ISO | nitidez (var. laplaciano) | ruido zona plana | energía alta frec. |
|---|---|---|---|---|---|---|---|
| 0.6x | ID3 nativo | 2.3 mm | 1/60 s | 2650 | 77.9 | 0.91 | 0.144 |
| 1x | ID3 recortado | 2.3 mm | 1/60 s | 9591 | **39.0** | 1.39 | 0.159 |
| 2x | ID3 recortado | 2.3 mm | 1/60 s | 13778 | 53.3 | **3.21** | 0.206 |
| 2.9x | **ID6 nativo** | 10.55 mm | 1/24 s | 12209 | **348.6** | 2.65 | **0.400** |
| 5x | ID6 recortado | 10.55 mm | 1/24 s | 12119 | 287.8 | 2.22 | 0.345 |

**El hallazgo grande: el teleobjetivo es ~9x más nítido que el gran angular recortado.**
El tramo 1x–2.9x es el punto débil de la app: se sirve recortando digitalmente una
lente de 2.3 mm, y ahí la nitidez se hunde (39 frente a 349). Es el hueco que tapan
los demás fabricantes con super-resolución multi-fotograma.

Todas las fotos salen a 3840x2160 (8.29 MP, 16:9 exacto). El programa de exposición
sale **AUTO** en todas: el piso de obturación ya no secuestra el AE, que era el bug
de la foto negra.

## Fotos — con flash

| zoom | exposición | ISO | flash disparó | ISO sin flash (comparación) |
|---|---|---|---|---|
| 1x | 1/120 s | 6056 | **SÍ** | 9591 |
| 2.9x | 1/24 s | 2419 | **SÍ** | 12209 |

El flash hace trabajo real: a 2.9x baja el ISO de 12209 a 2419 (2.3 stops menos de
grano). El EXIF confirma el destello en las dos.

## Modo noche

| medida | noche 0.6x | foto normal 0.6x |
|---|---|---|
| nitidez | **140.9** | 77.9 |
| ruido zona plana | **0.76** | 0.91 |
| p99.5 (blancos) | **242.9** | 232.3 |
| resolución | 3280x1856 (6.09 MP) | 3840x2160 (8.29 MP) |
| EXIF | **ninguno** | completo |

Tras el arreglo de exposición el modo noche **ya supera a una foto normal** en nitidez,
ruido y blancos — que era justo lo contrario de lo que midió el jurado de la ronda 6.
Quedan dos defectos: **pierde el 27% de los píxeles** (6.09 MP frente a 8.29) y **sale
sin nada de EXIF**, así que la galería no sabe ni cuándo se tomó.

## Cámara frontal

2560x1440 (3.69 MP), 1/20 s, ISO 8156. El sensor frontal tiene 5120x3840 en su
`pixelArraySize`, o sea **19.7 MP disponibles y se están usando 3.69**. Defecto claro.

## Vídeo

1920x1080, H.264 (`avc1`), 14.51 s, **16.6 Mbps**, con pista de audio AAC (`mp4a`)
de 14.34 s. El audio graba bien. Dos observaciones: se grabó a 1080p (el 4K existe
pero no es el valor por defecto) y la pista de audio es 170 ms más corta que la de
vídeo, que es sincronía justita al arrancar.

## Arranque y tamaño (release con R8 frente al debug que se publicaba antes)

| | debug (todas las versiones anteriores) | release con R8 |
|---|---|---|
| arranque en frío | 539–572 ms | **355–385 ms** |
| APK | 27.1 MB | **21.7 MB** |
