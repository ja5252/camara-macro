# Reglas de R8 para la compilación de release.
#
# Las librerías (ML Kit, Coil, Material, AndroidX) ya incluyen sus propias reglas
# de consumo, así que aquí solo va lo que R8 no puede deducir por sí mismo.

# Las clases declaradas en el manifiesto (Activities, FileProvider) las conserva R8
# automáticamente, pero los nombres de los ficheros de traza sí ayudan a leer un
# crash real del teléfono en vez de un stack ofuscado e inútil.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Camera2 y ExifInterface se usan por API pública normal: no hace falta nada.
# ML Kit descarga sus modelos por reflexión desde Google Play Services.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-dontwarn com.google.mlkit.**
