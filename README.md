# Dron V166 — app Android propia

App gratuita y sin anuncios para el dron V166 (red WiFi `FLOW_xxxxxx`), que reemplaza
a la app original descontinuada ("WiFi UAV").

## Instalar
1. Copia `DronV166.apk` al celular y ábrelo (acepta "instalar apps desconocidas").
2. Enciende el dron y conecta el celular a la WiFi `FLOW_D2D066`.
   Si Android avisa que la red no tiene internet, elige **mantener la conexión**.
3. Abre **Dron V166**.

## Qué hace
- **Modo cámara (por defecto):** video en vivo, foto, grabación MP4, cambio de cámara
  frontal/inferior, zoom de pellizco. El celular no envía órdenes de vuelo: se vuela con el control remoto.
- **Modo piloto (botón "Pilotar"):** dos palancas, despegar/aterrizar, apagar motores,
  velocidad 30/60/100 %, sin cabeza, calibrar, giro 360°, inclinación de cámara.
- Mantén presionada la etiqueta de estado (arriba a la izquierda) para ver el diagnóstico.

## Estado de las pruebas
- Probado con la captura real del dron: el video se arma y decodifica bien (42 de 42 cuadros).
- **No probado todavía:** la app en un teléfono real y todas las órdenes de vuelo.
  Primera prueba: lugar despejado, velocidad 30 %, control remoto a la mano.

## Protocolo (resumen)
- UDP a `192.168.169.1:8800` (y `8801`); el dron responde desde el puerto `1234`.
- `ef 00 04 00` inicia el video; `ef 02 …` confirma cuadros y lleva el comando de vuelo.
- Video: fragmentos `93 01` de un JPEG 640×360 sin cabecera (la app la añade).
- Comando de 20 bytes: `66 14 giro avance altura lado banderas modo … xor 99`.
- Firmware detectado: `FLOW_720P_V1.4.13_20240626_Beta(C22)`.
- Basado en la investigación abierta de turbodrone (Apache-2.0).

## Compilar
- Sin Gradle (Linux): `./build_apk.sh` (necesita aapt2, dalvik-exchange, zipalign, apksigner y un `android.jar`).
- Android Studio: abrir la carpeta; los `build.gradle` están incluidos pero no se han probado.
