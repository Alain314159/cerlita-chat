# Reglas R8 — mínimas: las librerías (Tink, Room, OkHttp, lifecycle) traen sus
# propias consumer rules. Aquí solo lo que el shrinking no puede deducir solo.

# argon2kt accede a funciones nativas por JNI; mantener nombres para el linkage
-keep class com.lambdapioneer.argon2kt.** { *; }

# D62: AndroidViewModelFactory (lifecycle 2.11, sin consumer rules) instancia
# los ViewModels por reflexión con el constructor (Application) — R8 no puede
# ver esa reflexión, hay que asegurar el constructor explícitamente.
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }

# OkHttp/okio: algunas referencias opcionales generan warnings, no errores
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# WebRTC (D75): JNI llama a org.webrtc por nombre — no se puede shrink
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
