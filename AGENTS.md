# Geliştirme kuralları

- Önce `README.md` ve `docs/ARCHITECTURE.md` dosyalarını oku. Yalnızca kullanıcının istediği aşamayı uygula.
- Çalışan kitaplık, SAF import ve yerel kalıcılık davranışları regresyon tabanıdır. Sahte reader/AI/çeviri başarısı veya production demo verisi ekleme.
- UI ve ViewModel içine Room, Readium, dosya kopyalama veya sağlayıcı SDK mantığı taşıma. Yeni özellikleri kendi paket sınırlarında geliştir.
- Gereksiz modül, framework veya boş gelecek arayüzleri ekleme. Bağımlılık gerekçesini belgeye yaz; sürümleri version catalog içinde sabitle.
- Room sürüm değişikliği açık migration ve veri koruma testi gerektirir. Destructive fallback kullanma. `app/schemas` dosyalarını koru.
- Coroutine iptalini hata olarak yutma. Dosya/DB commit tutarlılığını ve süreç sonlandırma senaryolarını koru.
- Gelecek özet işlevinde sadece yüzdeden veya en ileri konumdan tüm kitap prefix'i çıkarma. Dokümante edilmiş scope/coverage sınırını uygula.
- API anahtarlarını kaynak/BuildConfig/APK içine koyma. Kitap içeriğini loglama veya otomatik yükleme.
- Değişikliğe uygun testleri ve Android Lint'i çalıştır; teslimden önce `:app:assembleDebug` başarılı olmalı. Bağımlılık/obfuscation değişikliklerinde release derlemesini de kontrol et.
- Test edilmemiş cihaz davranışlarını doğrulanmış gibi bildirme. Belgeleri değişen gerçek davranışa göre güncelle.
