package com.yaprak.reader.domain.assistant

/** A user-reviewable question draft, never an automatic provider call or guaranteed answer. */
enum class BookStudyTask(val question: String) {
    SUMMARY("Yalnız gönderilen kaynakları kullanarak seçilen EPUB dosyasının metnini Türkçe özetle. " +
        "Ana düşünceleri kısa maddelerle açıkla ve her maddeyi kaynak alıntısıyla destekle. " +
        "Kitabın diğer bölümlerini veya okuduğum kısmı bildiğini varsayma; desteklenmeyen bilgi ekleme."),
    PRACTICE("Yalnız gönderilen kaynaklar yeterliyse seçilen EPUB dosyası için Türkçe 5 kısa cevaplı çalışma sorusu oluştur. " +
        "Her maddede soru ve kısa cevap birlikte yer alsın; cevabı kaynak alıntısıyla destekle. " +
        "Beş farklı soru için bilgi yoksa daha az soru oluştur ve sınırı belirt. " +
        "Kitabın diğer bölümlerinden bilgi veya desteklenmeyen cevap ekleme."),
    ;

    fun questionForPrefix(): String = question.replace("seçilen EPUB dosyasının metnini", "kitabın başından seçtiğim içindekiler sınırının sonuna kadar gönderilen metni")
        .replace("seçilen EPUB dosyası için", "kitabın başından seçtiğim içindekiler sınırının sonuna kadar gönderilen metin için")
        .plus(" Sonraki metinler hakkında çıkarım yapma; bu kapsamın okuduğum kısım olduğunu varsayma.")

    fun questionForChapter(): String = question.replace("seçilen EPUB dosyasının", "seçilen içindekiler bölümünün")
        .replace("seçilen EPUB dosyası için", "seçilen içindekiler bölümü için")

    fun questionForSection(format: com.yaprak.reader.domain.library.BookFormat): String = when (format) {
        com.yaprak.reader.domain.library.BookFormat.PDF -> question.replace("seçilen EPUB dosyasının metnini", "seçilen PDF sayfasının çıkarılmış metnini")
            .replace("seçilen EPUB dosyası için", "seçilen PDF sayfasının çıkarılmış metni için")
        com.yaprak.reader.domain.library.BookFormat.TXT -> question.replace("seçilen EPUB dosyasının metnini", "seçilen TXT metin parçasını")
            .replace("seçilen EPUB dosyası için", "seçilen TXT metin parçası için")
        com.yaprak.reader.domain.library.BookFormat.EPUB -> question
        com.yaprak.reader.domain.library.BookFormat.FB2 -> question.replace("seçilen EPUB dosyasının metnini", "seçilen FB2 metin parçasını")
            .replace("seçilen EPUB dosyası için", "seçilen FB2 metin parçası için")
        com.yaprak.reader.domain.library.BookFormat.DOCX -> question.replace("seçilen EPUB dosyasının metnini", "seçilen DOCX belgesinin çıkarılmış metnini")
            .replace("seçilen EPUB dosyası için", "seçilen DOCX belgesinin çıkarılmış metni için")
        com.yaprak.reader.domain.library.BookFormat.CBZ, com.yaprak.reader.domain.library.BookFormat.CBR -> throw IllegalArgumentException("Unsupported study source")
    }
}
