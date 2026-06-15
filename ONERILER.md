# İlave İyileştirme Önerileri

Restoran POS sisteminizde bu noktaya kadar gelen önemli yetenekler dışında,
kullanım kalitesini ciddi şekilde artıracak bazı ek özellikler.

---

## A. Yüksek Etkili / Düşük Maliyetli

### 1. **Otomatik Logout / Oturum Kilidi**
Garson telefonu/masayı bırakıp gittiğinde başkası yanlışlıkla onun adına
sipariş alamasın diye 5 dakika boşta kalan oturum kilitlenir; parola
ile geri açılır.

**Etki:** Güvenlik + kötüye kullanım önlemi.
**Maliyet:** ~50 satır kod (`Timer` + LoginPanel açar).

### 2. **Sesli Bildirim**
Yeni sipariş mutfak yazıcısına basıldığında mutfakta bir buzzer çalsın
veya bilgisayardan ses gelsin. ESC/POS yazıcıların built-in beep
komutu (`ESC B n t`) zaten var (`EscPos.beep()` zaten ekledim).

**Etki:** Mutfak fişi sırasında kaçırmıyor.
**Maliyet:** 10 satır kod — sendOrderToKitchens sonrası beep ekle.

### 3. **Hızlı Erişim Çubuğu (Sık Ürünler)**
Tabletten en çok seçilen 8-10 ürünü "Sık Ürünler" bölümünde
sabitleyerek garsonun her seferinde kategoriden seçmesi engellenir.
DB'de bir `is_favorite` bayrağı veya son 7 günün en çok satılanları.

**Etki:** Sipariş hızı %30+ artar.
**Maliyet:** 1 yeni sütun + UI satırı.

### 4. **Gün Sonu Raporu**
Gün bitince admin tek tıkla:
- Toplam satış
- Mutfak başına satış
- Kategori başına satış
- Garson başına ciro
- Gider toplamı
- Net kar
şeklinde PDF/Excel rapor üretir.

**Etki:** Restoran yönetimi profesyonelleşir.
**Maliyet:** Apache POI zaten projede; ~200 satır kod.

### 5. **Yazar Kasa (ÖKC) Entegrasyonu**
Türkiye'de yasal yazar kasa zorunluluğu var (gelir vergisi).
Hugin/Ingenico/Beko'nun YN-ÖKC modellerine seri port ile fatura
gönderme. Bu ayrı bir teknik konu — gerekiyorsa ayrı incelenmeli.

**Etki:** Yasal uyum.
**Maliyet:** Cihazın SDK'sı + ~300 satır kod.

---

## B. Operasyonel Konfor

### 6. **Karanlık Tema**
Gece ışıkları kısıkken dokunmatik ekran çok parlar.
FlatLaf'ta `FlatDarculaLaf.setup()` ile dakikalar içinde eklenebilir.
Admin panelinden tema seçimi.

### 7. **Sipariş Notları**
Garson bir kaleme not ekleyebilsin (örn: "az pişmiş", "soğansız").
Order_items tablosuna `note` sütunu (zaten Receipt.Line.note destekliyor).
UI tarafında ekleme yok — eklenebilir.

### 8. **Multi-Dil**
Türkçe + İngilizce. Personel arasında İngilizce konuşan varsa.
Java `ResourceBundle` ile basit çözüm.

### 9. **Yedek Yazıcı**
Bir mutfak yazıcısı bozulursa otomatik olarak başka bir yazıcıya yönlendir.
`kitchen_printers` tablosuna `fallback_printer_id` ekle.

### 10. **Vardiya Takibi**
Garsonların giriş-çıkış saatleri, hangi vardiyada ne kadar sipariş
aldıkları. Personel maaş hesaplamasında işe yarar.

---

## C. Veri & Analitik

### 11. **Müşteri Sadakat Programı**
Müşteri kartı / telefon numarasıyla puan biriktirme. Ödüller.
Yeni tablo `customers` + `customer_visits`. Orta vadede.

### 12. **Stok Uyarıları**
Bir ürünün stoku belirli bir eşiğin altına düştüğünde admin'e
"Stok azaldı: Adana kebap (3 porsiyon kaldı)" diye uyarı.
Mevcut `products.stock_qty` zaten kullanılıyor.

### 13. **Saatlik Yoğunluk Grafiği**
ProfitPanel'e saatlik satış adedi grafiği. Restoran sahibi
"hangi saatte personel artıralım?" kararı verir.

---

## D. Stratejik / Uzun Vadeli

### 14. **Mobil Garson Uygulaması (REST API)**
Önceki turlarda mimari hazırladığım Spring Boot REST API + Android/iOS
native uygulamalar. Garson kendi telefonundan sipariş alabilsin.
Bu ana plan zaten var; uygulanması 8-12 hafta.

### 15. **Çevrimdışı Modu**
İnternet/MySQL bağlantısı koptuğunda yerelde sipariş alıp,
bağlantı dönünce senkronize etme. Bu ileri seviye bir özellik;
H2 ile local cache + replay logic gerek.

### 16. **QR Menü**
Müşteri masada QR okutup kendi siparişini girer; garson onaylar.
Bu büyük bir UX değişikliği — restoranın kültürüne bağlı.

### 17. **Bulut Yedekleme**
Günlük MySQL dump'ı otomatik AWS S3 / Google Drive'a yükle.
Yangında/hırsızlıkta tüm veri korunur. ~30 satır script.

---

## Öncelik Tablosu

| # | Özellik | Etki | Süre | Acil mi? |
|---|---|---|---|---|
| 1 | Otomatik logout | ★★★ | 0.5 gün | Evet |
| 2 | Sesli bildirim | ★★ | 0.25 gün | Evet |
| 3 | Hızlı erişim | ★★★ | 1 gün | Evet |
| 4 | Gün sonu raporu | ★★★ | 2 gün | Evet |
| 5 | YN-ÖKC | ★★★★ | 1-2 hafta | Hukuki gereklilik varsa |
| 6 | Karanlık tema | ★ | 0.5 gün | Tercih |
| 11 | Sadakat programı | ★★ | 1 hafta | Sonra |
| 14 | Mobil app | ★★★★ | 8-12 hafta | Büyük yatırım |
| 17 | Bulut yedek | ★★★ | 0.5 gün | Evet |

---

## Benim Tavsiyem

**Önce şunları yapın (1-2 gün):**
1. Otomatik logout (güvenlik)
2. Sesli bildirim (operasyonel)
3. Bulut yedekleme (veri kaybı önleme)

**Sonra:**
4. Gün sonu raporu (yönetim için kritik)
5. Hızlı erişim çubuğu (UX)

**Hukuki olarak:**
6. Yazar kasa entegrasyonu (Türkiye'de zorunlu)

**Büyük yatırımlar:**
7. Mobil app (8-12 hafta proje)

Hangi öneriyi yapmak istersen söyle, hemen ekleyebilirim.
