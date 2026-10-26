# Reglas R8 — mínimas: las librerías (Tink, Room, OkHttp, lifecycle) traen sus
# propias consumer rules. Aquí solo lo que el shrinking no puede deducir solo.

# argon2kt accede a funciones nativas por JNI; mantener nombres para el linkage
-keep class com.lambdapioneer.argon2kt.** { *; }

# OkHttp/okio: algunas referencias opcionales generan warnings, no errores
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
