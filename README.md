# Trashed (APK)

App Android que envuelve `app/src/main/assets/index.html` en un WebView y lee la galería
mediante un puente nativo (`AndroidStorage` → `window.StorageBridge`).

## Requisitos

- Android Studio reciente (Ladybug 2024.2 o posterior). Trae JDK 17.
- Teléfono con Android 11 o superior (`minSdk = 30`).

## Abrir y probar

1. Abre la carpeta del repositorio `trashed` en Android Studio.
2. Espera la sincronización de Gradle. Si pide generar el wrapper, acepta.
3. Activa la depuración USB en el teléfono y pulsa **Run**.

## Generar el APK

- Depuración: **Build > Build APK(s)**. Sale en `app/build/outputs/apk/debug/app-debug.apk`.
- Línea de comandos (requiere `ANDROID_HOME` con `platforms;android-35` y `build-tools;35.0.0`):
  `./gradlew assembleDebug`. El wrapper usa Gradle 8.9.
- Para instalarla fuera de Play, genera una firmada: **Build > Generate Signed App > APK**.
  Necesitas un keystore; guárdalo, porque sin él no podrás actualizar la app.

## Qué revisar en el teléfono

1. Pulsa **Analizar galería** y concede el permiso de fotos y videos.
2. Revisa las categorías: duplicados, similares, borrosas, capturas, grandes.
3. Marca elementos y confirma. Deben ir a la papelera del sistema (Fotos > Papelera).
4. Si eliges solo algunas fotos (Android 14+), la app solo verá esas.

## Limitaciones conocidas

- Solo fotos y videos. Los archivos generales (PDF, APK, ZIP) no se leen en esta versión.
- Miniaturas: el nativo sirve `/thumb/<id>` con `ContentResolver.loadThumbnail` (256 px). Se usan
  para la vista previa, dHash y nitidez (también decodifica HEIC). Si fallan, se usa el archivo completo.
- Videos: la duración viene de MediaStore y no se abre el video. El servidor interno no soporta
  rangos HTTP; si alguna vez se reproduce el video dentro de la app y el seek falla, la solución
  es responder `206 Partial Content` en `MediaPathHandler`.
- Huella parcial (> 64 MB): se leen solo el inicio y el final con `/part/<id>/<inicio>/<largo>`.
- Análisis: 4 elementos a la vez; un elemento que tarda más de 30 s se marca con aviso y se sigue.
- Los umbrales de `index.html` (`BLUR_MAX`, `SIM_MAX_DIST`) son de partida. Calíbralos con tus fotos.
- HEIC: si el WebView no lo decodifica, el elemento aparece sin vista previa y marcado con error.
- Todavía no se ha compilado: el entorno de trabajo no pudo instalar el Android SDK.
