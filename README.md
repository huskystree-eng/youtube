# Lite TV

App Android mínima (sin dependencias, APK de ~1 MB) que abre YouTube a pantalla completa en un TV box con poca RAM. Limita la calidad máxima para que no se trabe.

## Cómo usarla

- **ATRÁS (mantener apretado)** o botón **MENÚ**: abre el menú de la app.
- Menú: cambiar modo (TV / Móvil), calidad máxima (144p a 720p), recargar, limpiar caché, salir.
- **Modo TV** (`youtube.com/tv`): interfaz pensada para control remoto. Es el modo por defecto.
- **Modo Móvil** (`m.youtube.com`): más liviano, pero conviene tener un mouse USB o un control tipo "air mouse".
- ATRÁS (toque corto): vuelve atrás en YouTube. En modo Móvil, dos toques seguidos salen de la app.

## Cómo obtener el APK (sin instalar nada en tu PC)

1. Creá una cuenta en https://github.com y un repositorio nuevo (privado está bien).
2. Subí todo el contenido de esta carpeta (arrastrar y soltar en la web de GitHub).
3. Andá a la pestaña **Actions** → **Compilar APK** → **Run workflow**.
4. Cuando termine (unos 3 a 5 minutos), entrá a esa ejecución y bajá el artefacto **lite-tv-apk** (viene en un .zip con `app-debug.apk` adentro).

### Alternativa: Android Studio

Abrí la carpeta con Android Studio y elegí **Build > Build APK(s)**.

## Instalarlo en el box

1. Copiá `app-debug.apk` a un pendrive y conectalo al box.
2. Abrí el explorador de archivos del box, tocá el APK y aceptá **Instalar apps de orígenes desconocidos** si lo pide.
3. Si el box no trae explorador de archivos, instalá la app **Downloader** desde su tienda, o usá una app de archivos como "File Commander".

## Si algo no anda

- **El modo TV no carga o se ve raro:** cambiá a modo Móvil desde el menú. También podés probar otro `UA_TV` en `MainActivity.java`.
- **El video se corta igual:** bajá la calidad a 240p o 144p desde el menú, y usá cable de red si el box tiene puerto Ethernet.
- **Iniciar sesión con tu cuenta:** en modo TV, YouTube te muestra un código para ingresar desde el celular en `youtube.com/activate`. En modo Móvil, Google bloquea el inicio de sesión dentro de apps con WebView.
- **Límite de la calidad:** el tope se aplica con un script sobre el reproductor web. Si YouTube cambia su código interno, puede dejar de respetarse; en ese caso también podés elegir la calidad desde el engranaje del propio reproductor.
