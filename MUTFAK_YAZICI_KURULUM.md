# Mutfak Yazıcı Sistemi — Kurulum Kılavuzu

Bu doküman; çoklu mutfak, kablosuz bağlantı ve minimal maliyet hedeflerine
göre eklenen yazıcı sisteminin donanım seçimini, kurulum adımlarını ve
yazılım entegrasyonunu özetler.

---

## 1. Senaryo

Restoranda **birden fazla mutfak** bulunuyor:

- **Mutfak 1:** Sıcak yemek (ciğer, ızgara, kebap) → 1 yazıcı
- **Mutfak 2:** Döner / dürüm → 1 yazıcı
- (Daha sonra eklenebilir: 3., 4. mutfak — sistem her ek mutfak için
  sadece DB tarafına yeni satır + IP gerektirir, kod değişikliği yok.)

Garson siparişi onayladığında **ürünün kategorisine bakılır** ve fiş
otomatik olarak doğru mutfağa gönderilir. Ciğer kategorisindeki ürünler
1. mutfağa, döner kategorisindekiler 2. mutfağa düşer.

---

## 2. Tavsiye Edilen Donanım (Minimum Maliyet)

### 2.1 Yazıcı (her mutfak için 1 adet)

| Model                          | Bağlantı            | Yaklaşık Fiyat (TR)  | Yorum |
|--------------------------------|---------------------|----------------------|-------|
| **Epson TM-T20III (Ethernet)** | LAN + USB           | ₺6.500 – 8.500       | Sektörün de facto standardı, ESC/POS, otomatik kesici, 80mm. Tek dezavantajı: Wi-Fi yok. Çözüm: LAN portu var, Wi-Fi köprü ile kablosuzlaştırılır. |
| Epson TM-m30III                | LAN + USB + Wi-Fi   | ₺9.500 – 12.000      | Doğrudan Wi-Fi destekli. Kaliteli ama 1.5x daha pahalı. |
| Xprinter XP-N160II Wi-Fi       | Wi-Fi + USB         | ₺2.500 – 3.500       | Çin malı, ucuz; dayanıklılığı düşük; sıcak/yağlı mutfak ortamı için garanti vermem. ESC/POS uyumludur ve **bu projedeki kod onunla da çalışır**. |

**Önerim:** **Epson TM-T20III (LAN)** + **TP-Link TL-WR802N** Wi-Fi köprü.

**Neden:**
- Tek yazıcı ~ ₺7.000.
- Yazıcının yanına küçük bir Wi-Fi köprü koyarak Wi-Fi'a bağlanır
  (TL-WR802N ~ ₺500). Yazıcı kendi başına Wi-Fi'lı imiş gibi davranır.
- 2 mutfak için toplam: **2 × ₺7.000 + 2 × ₺500 = ~₺15.000**.
- Xprinter ile alternatif: 2 × ₺3.000 = ~₺6.000 — ancak 1-2 yıl içinde
  arızalanma riski yüksek. Restoran ortamında uzun vadede maliyet daha
  yüksek olabilir.

### 2.2 Aksesuarlar (tek seferlik)

| Kalem                              | Miktar | Birim ₺ | Toplam |
|------------------------------------|--------|--------:|-------:|
| 80mm termal kağıt rulosu (10'lu)   | 1      | 350     | 350    |
| Cat6 patch kablo 1 m (köprü ↔ yazıcı) | 2 | 30      | 60     |
| Wi-Fi köprü (TP-Link TL-WR802N)    | 2      | 500     | 1.000  |
| Yağ damlasına karşı kapaklı kabin/raf | 2   | 400     | 800    |
| **Toplam aksesuar**                |        |         | **2.210** |

**Genel toplam donanım: ~₺17.000** (2 yazıcı + aksesuar). Yazılım için
ekstra para harcamanıza gerek yok — sistem kendi kodumuzla çalışıyor.

### 2.3 Karşılaştırma — Max Bilişim teklifi (PDF)

PDF'deki teklifte:

- 2 termal yazıcı: $100 (₺3.500) — marka belirsiz, muhtemelen Xprinter.
- Nexvera POS yazılımı ana kullanıcı + 5 el terminali: $810 (₺28.350).
- 5 Samsung tablet: $1.000 (₺35.000).
- 1 endüstriyel POS PC: $510 (₺17.850).
- Kurulum + ağ: $1.000 + kablolama hariç.

**Toplam yalnız POS+yazıcı kısmı (kameralar hariç):** $3.420 ≈ ₺119.700.

**Bizim alternatifimiz:**
- 2 kaliteli yazıcı + Wi-Fi köprü + aksesuar: ₺17.000.
- POS yazılımı: zaten elinizde (budgetController) — ₺0.
- Mobil uygulama: kullanıcının kendi telefonu (sonraki faz).
- Toplam: **~₺17.000** — yani teklifin yalnız %14'ü kadar maliyetle aynı
  işlevi alıyorsunuz (kamera/ses sistemi hariç).

---

## 3. Ağ Kurulumu

```
        ┌──────────────────────────┐
        │   Restoran Wi-Fi Router   │  (mevcut)
        │   192.168.1.1            │
        └────────────┬─────────────┘
                     │  Wi-Fi
       ┌─────────────┼─────────────┐
       │             │             │
   ┌───▼───┐    ┌───▼───┐     ┌───▼───┐
   │ Telef │    │  POS  │     │  Köprü│
   │ Garson│    │  PC   │     │ TL-WR │
   └───────┘    └───┬───┘     └───┬───┘
                    │             │ LAN (kablolu)
                    │         ┌───▼─────┐
                    │         │ Epson   │
                    │         │ TM-T20  │
                    │         │ Mutfak 1│
                    │         │ .1.241  │
                    │         └─────────┘
                    │
                    │  (aynı şekilde Mutfak 2 için 2. köprü + yazıcı)
                    │
                ┌───▼───┐
                │ MySQL │
                │ posdb │
                └───────┘
```

### 3.1 Sabit IP atama (kritik)

Yazıcıların IP'sini DHCP'ye bırakmayın — her yeniden başlatmada IP
değişebilir. **Router'ın DHCP rezervasyon özelliğini** kullanarak yazıcıların
MAC adreslerini sabit IP'ye bağlayın:

- Mutfak 1 yazıcı: `192.168.1.241`
- Mutfak 2 yazıcı: `192.168.1.242`

### 3.2 TL-WR802N köprü modu (her köprü için tek seferlik)

1. Köprüyü 5V USB ile besle (telefon şarjı yeter).
2. Bilgisayardan `http://tplinkwifi.net` arayüzüne gir.
3. Mode → **Client (Bridge)** seç.
4. Mevcut restoran Wi-Fi'sini seç ve şifresini gir.
5. Köprünün IP'sini de DHCP rezervasyonu ile sabitle (örn. `.240`).
6. Köprünün LAN portunu Epson yazıcının LAN portuna Cat6 kablo ile bağla.

---

## 4. Sistem Adımları (Kod Tarafında Yapılması Gerekenler)

### 4.1 Veritabanı şemasını güncelle (tek seferlik)

```bash
mysql -u root -p posdb < src/main/resources/db/migration/V2026_05_15__kitchen_printers.sql
```

Bu işlem:
- `kitchen_printers`, `category_printer_routes`, `print_jobs` tablolarını oluşturur
- `order_items` tablosuna `printed_at`, `print_count` sütunlarını ekler
- Demo amaçlı 2 yazıcı kaydı atar (KITCHEN_1, KITCHEN_2 — IP placeholder)

### 4.2 Yazıcı kayıtlarını gerçek IP ile güncelle

```sql
UPDATE kitchen_printers SET host='192.168.1.241' WHERE code='KITCHEN_1';
UPDATE kitchen_printers SET host='192.168.1.242' WHERE code='KITCHEN_2';
```

### 4.3 Kategori → Yazıcı eşleştirmesi

Önce kategori ID'lerini öğren:

```sql
SELECT id, name FROM categories;
-- diyelim ki: 10=Ciğer, 20=Döner, 30=İçecek
```

Sonra eşleştir:

```sql
-- Ciğer kategorisini 1. mutfağa
INSERT INTO category_printer_routes (category_id, printer_id)
VALUES (10, (SELECT id FROM kitchen_printers WHERE code='KITCHEN_1'));

-- Döner kategorisini 2. mutfağa
INSERT INTO category_printer_routes (category_id, printer_id)
VALUES (20, (SELECT id FROM kitchen_printers WHERE code='KITCHEN_2'));

-- İçecek kategorisini her iki mutfağa (örnek: garson görsün diye)
INSERT INTO category_printer_routes (category_id, printer_id)
SELECT 30, id FROM kitchen_printers WHERE code IN ('KITCHEN_1','KITCHEN_2');
```

### 4.4 Donanım testini yap (yazıcı bağlanır bağlanmaz)

```bash
# Önce telnet ile bağlanılabilirliği test et:
telnet 192.168.1.241 9100

# Sonra projemizdeki test aracını çalıştır:
mvn -q -DskipTests package
java -cp target/budgetController-1.0-SNAPSHOT.jar \
     tools.PrintTestMain 192.168.1.241 9100 42
```

Doğru çalıştıysa şu örnek fiş basılır:

```
        *** MUTFAK TEST ***
==========================================
MASA : 5
Garson: Ahmet Garson
Saat  : 15.05.2026 18:30
Fiş No: #12345
------------------------------------------
 2 x Kuzu Ciğer Şiş
     > Az pişmiş
 1 x Adana Kebap
 3 x Ayran
 1 x Şakşuka
     > Acılı
------------------------------------------
NOT: Acele lütfen — masa bekliyor
```

### 4.5 Gerçek siparişle test

UI tarafında `TableOrderDialog`'a bir "Mutfağa Gönder" butonu ekleyip
şu çağrıyı yapacaksınız (sonraki turda yapılacak):

```java
PrintingService printing = new PrintingService();
List<PrintingService.PrintResult> results =
        orderService.sendToKitchens(orderId, printing);

for (var r : results) {
    log.info(r.toString());   // OK ya da FAIL detayı
}
```

---

## 5. Yazılım Mimarisi Özeti

```
TableOrderDialog
   └─► OrderService.sendToKitchens(orderId, printingService)
        └─► PrintingService.sendOrderToKitchens(...)
             ├─► KitchenRouter.routeItems(items)         ← kategori → yazıcı
             │     └─► ProductDAO + CategoryPrinterRouteDAO
             ├─► her hedef için Receipt oluştur
             ├─► PrintJobDAO.enqueue(...)                ← önce kuyruğa
             ├─► TcpEscPosPrinter.print(receipt)         ← ağ üstünden
             └─► PrintJobDAO.markPrinted | markFailed
```

### Bağımlılıklar

- **Yeni kütüphane yok** — sadece JDK socket + (zaten projede olan)
  SLF4J + Gson.
- Yazıcılar **ESC/POS standardı**: Epson, Bixolon, Xprinter, Star,
  Posiflex — tümünde aynı kod çalışır.

---

## 6. Hata Durumları ve Toleransı

| Senaryo                          | Sistem davranışı                                  |
|----------------------------------|---------------------------------------------------|
| Yazıcı kapalı / kağıt bitti      | `PrinterException` atar; iş `print_jobs.FAILED`'e düşer. Admin paneli yeniden bas butonu açılabilir. |
| Wi-Fi koptu                      | Yazıcı timeout (3 sn) verir, aynı şekilde queue'ya düşer. |
| Yanlış IP                        | Connect timeout — log'a yazar, queue'ya düşer. |
| Kategori için yazıcı tanımsız    | Log uyarısı, fiş gönderilmez (sessiz). Admin kategoriyi eşleştirebilir. |
| Aynı sipariş iki kez gönderildi  | `print_jobs` tablosunda iki kayıt olur — manuel temizleme. (V2: idempotency key eklenebilir.) |

---

## 7. Genişletme — Yeni Mutfak Ekleme

Yeni mutfak (örn. tatlı bölümü) eklemek istersen:

1. Yeni Epson TM-T20III + TL-WR802N al (~₺7.500).
2. Köprüyü kur, yazıcıya IP `192.168.1.243` ata.
3. SQL ile kaydet:

```sql
INSERT INTO kitchen_printers (code, display_name, host, port, char_per_line, note)
VALUES ('KITCHEN_3', 'Mutfak 3 - Tatlı', '192.168.1.243', 9100, 42, 'Künefe, sütlaç');

INSERT INTO category_printer_routes (category_id, printer_id)
SELECT c.id, p.id FROM categories c, kitchen_printers p
WHERE c.name='Tatlı' AND p.code='KITCHEN_3';
```

4. Uygulamayı yeniden başlat ya da admin panelden "yazıcı cache'i
   temizle" tuşuna bas (PrintingService.invalidateCache()).

**Kod değişikliği gerekmez.**

---

## 8. Eksik / Sonraki Adımlar

- [ ] `TableOrderDialog`'a "Mutfağa Gönder" butonu (UI).
- [ ] Admin paneline `KitchenPrinter` yönetimi (CRUD).
- [ ] Admin paneline kategori → yazıcı eşleştirme UI'sı.
- [ ] `print_jobs.FAILED` için arka plan retry worker'ı (Spring
      gelmeden ScheduledExecutorService ile).
- [ ] Logo / restoran adı için fiş üst başlığını parametre yap.
- [ ] (Opsiyonel) Yazıcı IP'leri yerine mDNS adı kullanımı.

---

## 9. Hızlı Soru-Cevap

**S: ESC/POS dışı bir yazıcı alırsam ne olur?**
C: Kod çalışmaz. Ama termal fiş yazıcılarının %95'i ESC/POS uyumludur;
   ucuz Çin malları dahil. Almadan önce ürün sayfasında "ESC/POS support"
   yazdığından emin olun.

**S: Şarj noktası (kasiyer) için ayrı yazıcı lazım mı?**
C: Hayır — kasa müşteri fişi yasal yazar kasa (ÖKC) ile basılır, bu
   sistemden bağımsızdır. Bizim sistemimiz sadece mutfak siparişi
   fişini basar.

**S: 58mm yazıcılarla çalışır mı?**
C: Evet, sadece `kitchen_printers.char_per_line` sütununu 42 yerine 32
   yapın. Geri kalan her şey otomatik adapte olur.

**S: Yazıcı IP'sini değiştirirsem?**
C: SQL ile güncelleyin (`UPDATE kitchen_printers SET host='...' WHERE
   code='...'`). Uygulamayı yeniden başlatın veya admin panelden
   cache'i temizleyin.

---

*Hazırlayan: Claude · Tarih: 2026-05-17*
