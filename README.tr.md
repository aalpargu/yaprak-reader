# Yaprak

Kotlin ve Jetpack Compose ile geliştirdiğim kişisel Android kitaplık ve okuyucu. Kitapları cihazda tutar; biçime göre okuma, yer imi/not, arama, yedekleme ve isteğe bağlı kaynak temelli kitap soruları sunar.

[English and detailed setup](README.md)

## Teknoloji

Kotlin, Jetpack Compose, Room, Readium ve isteğe bağlı Node.js AI geçidi.

## Çalıştırma

JDK 17, uygun Android SDK ve Gradle wrapper kullanılır. `./gradlew :app:testDebugUnitTest` ve `./gradlew :app:lintDebug :app:assembleDebug`. Windows üzerinde `gradlew.bat` kullanılır.

## Kapsam ve durum

Geliştirme sürümüdür. Kaynak, kaydedilmiş 0.183.0 adayından alınmıştır; devam eden değişiklikler ayrı tutulur. Destek ve kaynak kapsamı kitap biçimine göre değişir. CBR/CBZ metin araması veya OCR sağlamaz; fiziksel cihaz ve canlı sağlayıcı kontrolleri ayrıca gerekir.
