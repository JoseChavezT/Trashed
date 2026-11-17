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

## Comprimir

Apartado en la pantalla de inicio. Opciones antes de buscar: qué comprimir (fotos / fotos y videos),
qué hacer con el original (papelera / sobrescribir) y calidad (alta 85% / media 75%).

- Fotos: solo JPG. Se vuelven a codificar con la calidad elegida y se copia el EXIF (fecha de
  captura, orientación, cámara, ubicación). Se omiten fotos en movimiento y Ultra HDR.
- Videos: solo MP4. Media3 Transformer a H.265 (con respaldo), lado corto máximo 1080 px y
  ~10 Mb/s (alta) o ~6 Mb/s (media) a 1080p. Se escribe la fecha de grabación original.
- Papelera: crea el archivo nuevo en la misma carpeta, manda el original a la papelera y publica
  el nuevo con el mismo nombre. Solo en DCIM, Pictures y Movies (Android no permite crear en otras).
- Sobrescribir: pide permiso de escritura (`createWriteRequest`) y reemplaza el contenido.
- La fecha de modificación del archivo se intenta conservar; algunos teléfonos no lo permiten.
- Pide `ACCESS_MEDIA_LOCATION`: sin él, Android borra la ubicación al leer los archivos.
- Si el archivo comprimido no baja al menos un 10%, no se toca.

Puente: `compress(ids, {quality, overwrite}, onProgress)` →
`{done: [{id, before, after}], skipped: [{id, reason}], cancelled, datesKept, locationKept}`.
Progreso: `window.__storageProgress(callId, {phase, index, total, name, percent})`.
`listMedia` añade `thumb` y `path` (RELATIVE_PATH).

## Limitaciones conocidas

- Solo fotos y videos. Los archivos generales (PDF, APK, ZIP) no se leen en esta versión.
- Miniaturas: el nativo sirve `/thumb/<id>` con `ContentResolver.loadThumbnail` (256 px). Se usan
  para la vista previa, dHash y nitidez (también decodifica HEIC). Si fallan, se usa el archivo completo.
- Videos: la duración viene de MediaStore y no se abre el video. El servidor interno no soporta
  rangos HTTP; si alguna vez se reproduce el video dentro de la app y el seek falla, la solución
  es responder `206 Partial Content` en `MediaPathHandler`.
- Huella parcial (> 64 MB): se leen solo el inicio y el final con `/part/<id>/<inicio>/<largo>`.
- Análisis en la APK: nativo (`Analyzer.kt`) con 2 a 6 hilos; mismo algoritmo que el JS (dHash 9x8,
  varianza del Laplaciano 64x64 y SHA-256). Resultados en caché (`localStorage`, `featCache.v1`) por
  id, tamaño y fecha: un nuevo análisis solo procesa archivos nuevos o cambiados. Si falla, se usa el JS.
- Análisis en navegador: 4 elementos a la vez; si uno tarda más de 30 s se marca con aviso y se sigue.
- Los umbrales de `index.html` (`BLUR_MAX`, `SIM_MAX_DIST`) son de partida. Calíbralos con tus fotos.
- HEIC: si el WebView no lo decodifica, el elemento aparece sin vista previa y marcado con error.
- Todavía no se ha compilado: el entorno de trabajo no pudo instalar el Android SDK.
