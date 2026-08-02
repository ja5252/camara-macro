# Hallazgos encontrados usando la app en el teléfono — 1 ago 2026

Cosas que no salen del código ni del plan: salen de manejar la app en el CPH2765.

## 1. El selector de lentes abre el sensor MUERTO sin avisar (grave)

El engranaje del panel "⋯" abre `SetupActivity` ("Elige tu lente"). Al llegar a
**"Lente 2 de 4 · Trasera (normal) · 5.0 mm · ID 2"** la vista previa sale **en negro
absoluto** y no hay ni un aviso. La ID2 es justamente el sensor de 200 MP averiado
—el mismo que mata a la cámara lógica ID0— y la app deja elegirlo tan tranquila.

Si el usuario pulsa "Usar esta lente" se queda con una cámara que no da imagen y sin
forma evidente de volver atrás.

**Qué hacer:** detectar que no llega ningún fotograma en un tiempo razonable (1,5 s) y
marcar la lente como *sin imagen* en rojo, bloqueando "Usar esta lente"; y no ofrecer
de entrada ni la ID2 ni la ID7 (sensor de profundidad, 1600x1200, no sirve de cámara).

## 2. El panel "⋯" es translúcido y se lee mal

El panel deja pasar la escena y se solapa con la pastilla "ID3 · 15 MM · 0.6X", que se
ve por debajo. Es el síntoma exacto que describió el jurado como "paneles que se tapan".
Necesita fondo opaco (o un desenfoque real) y ocultar lo que quede por debajo.

## 3. Un engranaje que no lleva a ajustes

El icono ⚙ abre el selector de lentes, no unos ajustes. Y en el mismo panel hay además
un botón "LENTES" que hace lo mismo. Dos entradas para lo mismo, con un icono que
promete otra cosa.

## 4. No hay forma de elegir la resolución de vídeo

El vídeo grabó a **1920x1080 a 16,6 Mbps con audio AAC correcto**, pero en la interfaz
no existe ningún control para pedir 4K. La capacidad está en el motor y no se expone.

## 5. Capacidades del HAL que estaban "sin verificar" — ya comprobadas

Los diseñadores descartaron varios arreglos por no saber si el CPH2765 los soporta.
Comprobado en `dumpsys media.camera` para **ID3 e ID6, idéntico en las dos**:

| capacidad | valor | qué desbloquea |
|---|---|---|
| `PRIVATE_REPROCESSING` + `YUV_REPROCESSING` | presentes | **ZSL por reprocesado es viable** |
| `maxNumInputStreams` | 1 | suficiente para el anillo de fotogramas del ZSL |
| `colorCorrection.availableAberrationModes` | `[0 1 2]` | corrección de aberración cromática en HIGH_QUALITY |
| `tonemap.availableToneMapModes` | `[0 1 2]` | **`CONTRAST_CURVE` disponible** |
| `tonemap.maxCurvePoints` | 512 | curva de tono propia con holgura de sobra |

O sea: la curva de tono propia (para levantar el pie de sombras y llevar el blanco a
su sitio) y el disparo sin retardo **sí se pueden hacer** en este teléfono. Dejan de
ser "depende del HAL" y pasan a ser trabajo pendiente.
