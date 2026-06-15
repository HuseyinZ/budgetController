# Çoklu Ekran Kurulum Kılavuzu

Restoranın her katında bir POS ekranı + kasada bir ana bilgisayar kullanarak
tüm sipariş akışını tek MySQL veritabanı üzerinden senkronize etmek.

> **TL;DR:** Mevcut kodda **hiçbir değişiklik yapmaya gerek yok**. Sadece
> her ekrana aynı JAR'ı kurun, hepsi aynı MySQL'e baksın, masa layout'unu
> ortak `restaurant-layout.properties` üzerinden tanımlayın. AppState
> her 2 saniyede bir DB'yi yokladığı için ekranlar otomatik senkron olur.

---

## 1. Hedef Topoloji

```
   ┌─────────────────────────────────────────────────────────┐
   │  Restoran LAN  (192.168.1.0/24)                          │
   │                                                          │
   │  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  │
   │  │ Kat 1    │  │ Kat 2    │  │ Bahçe    │  │ Kasa     │  │
   │  │ PC       │  │ PC       │  │ PC       │  │ PC (ANA) │  │
   │  │ .101     │  │ .102     │  │ .103     │  │ .100     │  │
   │  └─────┬────┘  └─────┬────┘  └─────┬────┘  └─────┬────┘  │
   │        │             │             │             │       │
   │        └─────────────┴─────────────┴─────────────┘       │
   │                          │                                │
   │                ┌─────────▼─────────┐                      │
   │                │  MySQL Server     │  (kasada veya NAS'ta)│
   │                │  posdb            │                      │
   │                │  192.168.1.100    │                      │
   │                └─────────┬─────────┘                      │
   │                          │                                │
   │   ┌──────────────────────┴──────────────────────┐         │
   │   │                                              │         │
   │ ┌─▼────────┐  ┌────────────┐  ┌────────────┐    │         │
   │ │ Mutfak   │  │ Mutfak     │  │ Mutfak     │    │         │
   │ │ DÖNER    │  │ FIRIN      │  │ OCAK       │    │         │
   │ │ .241     │  │ .242       │  │ .243       │    │         │
   │ └──────────┘  └────────────┘  └────────────┘    │         │
   └─────────────────────────────────────────────────┘
```

- Her **kat ekranı** = bir mini-PC veya endüstriyel POS PC (Windows/Linux).
- **Ana bilgisayar (kasa)** = MySQL + budgetController (admin/kasiyer için).
- **Mutfak yazıcıları** = LAN üzerinde sabit IP, port 9100.

---

## 2. Donanım Önerileri

| Cihaz | Önerilen | Notlar |
|---|---|---|
| Kat PC (her kat için 1 adet) | Intel NUC, Mini PC, ya da kullanılmış endüstriyel POS PC | Win 10/11 + Java 22 |
| Kasa PC (ana) | Daha güçlü mini PC veya masaüstü (MySQL barındırır) | SSD önerilir |
| Ekran | 15" / 17" dokunmatik POS monitör | Garsonlar parmakla seçebilsin |
| Ağ | LAN switch (mevcut) + Cat6 | Wi-Fi çalışır ama LAN daha kararlı |
| Yedek güç | Kasada UPS (5-10 dk yeter) | DB darken kapanmasın |
| Toplam | ~ ₺25.000 – ₺40.000 (3 kat + kasa) | Yazıcılar hariç |

---

## 3. Kurulum Adımları (her ekran için)

### 3.1 Ana bilgisayar (kasa) — MySQL + uygulama

1. MySQL Server 9.3'ü kur (zaten var).
2. `posdb` veritabanını oluştur ve mevcut DDL + migration'ları yükle:
   ```bash
   mysql -u root -p < posdb_users.sql
   mysql -u root -p < posdb_categories.sql
   mysql -u root -p < posdb_products.sql
   # ...diğer ana DDL'ler...
   mysql -u root -p posdb < src/main/resources/db/migration/V2026_05_15__kitchen_printers.sql
   mysql -u root -p posdb < src/main/resources/db/migration/V2026_05_17__multi_kitchen_and_override.sql
   mysql -u root -p posdb < src/main/resources/db/migration/V2026_05_17b__portion_pricing_and_kg_expenses.sql
   mysql -u root -p posdb < src/main/resources/db/migration/V2026_05_17c__user_area_permissions.sql
   ```
3. MySQL'i LAN'dan erişilebilir yap:
   - `my.ini` içinde `bind-address = 0.0.0.0`
   - Windows Firewall'da 3306 portunu aç (sadece LAN için)
4. Uzak erişim için kullanıcı:
   ```sql
   CREATE USER 'budget'@'192.168.1.%' IDENTIFIED BY 'GÜÇLÜ_ŞİFRE';
   GRANT ALL ON posdb.* TO 'budget'@'192.168.1.%';
   FLUSH PRIVILEGES;
   ```
5. JAR'ı kasa PC'ye yükle, çalıştır. Admin parolasını ilk girişte değiştir.

### 3.2 Kat PC'leri

1. Java 22 kur (Oracle JDK veya OpenJDK).
2. `budgetController-1.0-SNAPSHOT.jar` (kasa PC'deki ile aynısı) bu ekrana kopyala.
3. Yapılandırma dosyaları:
   - `C:\Users\<kullanıcı>\.budget\db.properties` oluştur, içeriği:
     ```properties
     db.url=jdbc:mysql://192.168.1.100:3306/posdb?useUnicode=true&characterEncoding=utf8&serverTimezone=Europe/Istanbul&allowPublicKeyRetrieval=true&sslMode=DISABLED
     db.user=budget
     db.password=GÜÇLÜ_ŞİFRE
     ```
   - (Opsiyonel) `C:\Users\<kullanıcı>\.budget\restaurant-layout.properties`
     dosyasını **kasadaki ile aynı içerikle** kopyala. Yoksa JAR içindeki
     varsayılan kullanılır — bu da her ekranda aynıdır.
4. Uygulamayı başlat. Garson hesabıyla giriş yap.
5. Garson sadece **kendi katına atanmış salonu** görecektir (`Kullanıcı İşlemleri → Alan Yetkileri` üzerinden atayın).

### 3.3 Mutfak yazıcıları

1. Her yazıcıya sabit IP ata (router üzerinden DHCP rezervasyonu önerilir):
   - DÖNER: 192.168.1.241
   - FIRIN: 192.168.1.242
   - OCAK: 192.168.1.243
2. `kitchen_printers` tablosundaki `host` sütununu güncelle:
   ```sql
   UPDATE kitchen_printers SET host='192.168.1.241' WHERE code='DONER';
   UPDATE kitchen_printers SET host='192.168.1.242' WHERE code='FIRIN';
   UPDATE kitchen_printers SET host='192.168.1.243' WHERE code='OCAK';
   ```
3. Test:
   ```bash
   java -cp budgetController-1.0-SNAPSHOT.jar tools.PrintTestMain 192.168.1.241 9100 42
   ```

---

## 4. Davranış — Otomatik Senkronizasyon

`AppState` her 2 saniyede bir `pollChanges()` çağırarak DB'yi yoklar.
Bu sayede:

- Kat 1'deki garson bir masaya sipariş ekleyince → 2 sn içinde Kat 2 ekranı + kasa ekranı bu masanın "ORDERED" durumunu görür.
- Kasada satış yapılınca → kat ekranlarında masa anında "EMPTY"ye döner.
- Yeni ürün eklenince (Admin tarafından) → tüm ekranlardaki ürün picker'larda görünür.

**Çatışma riski yok** çünkü sipariş ekleme/silme her seferinde DB transaction'ı.

---

## 5. Garson Yetkileri (Kat Bazlı)

Her garson sadece kendi katına/salonuna atanmalı:

1. Kasadaki ekrandan **Admin** veya **Kasiyer** olarak gir.
2. `Kullanıcı İşlemleri` sekmesi → garsonu seç → `Alan Yetkileri` butonu.
3. Sadece bu garsonun çalıştığı bina/salonu işaretle, kaydet.
4. Garson login olunca **sadece** o salonu görür.

Örnek:
- "Ahmet" garson → Yalnızca "1. Bina / 1. Kat" işaretli
- "Ayşe" garson → Yalnızca "1. Bina / 2. Kat"
- "Mehmet" garson → "3. Bina / Bahçe"

---

## 6. Sorun Giderme

| Sorun | Çözüm |
|---|---|
| Kat PC bağlanamıyor | `db.properties` IP'sini kontrol et. Kasada `mysql -u budget -h 192.168.1.100 -p` ile dışarıdan dene. |
| MySQL "Access denied" | `GRANT` cümlesindeki IP aralığı LAN ile uyumlu mu? `192.168.1.%` |
| Garson hiçbir kat görmüyor | `Kullanıcı İşlemleri → Alan Yetkileri` ile en az bir salon ata. |
| Yazıcı fişi basmıyor | `telnet 192.168.1.241 9100` ile portu test et. Kağıt + yazıcı IP. |
| Sipariş güncel görünmüyor | 2 sn poll → 2-3 sn bekle. Hala görünmüyorsa DB bağlantısını kontrol et. |
| Saat dilimi yanlış | `db.url`'de `serverTimezone=Europe/Istanbul` parametresi var mı? |

---

## 7. Yedekleme

Kasada günde 1 kez otomatik MySQL dump'ı önerilir:

```bat
:: Windows Görev Zamanlayıcısında her gece 03:00'te çalışsın
"C:\Program Files\MySQL\MySQL Server 9.3\bin\mysqldump.exe" ^
  -u root -pŞİFRE posdb > C:\backups\posdb-%date:~10,4%-%date:~4,2%-%date:~7,2%.sql
```

`C:\backups` klasörünü haftada bir USB belleğe veya bulut depolamaya kopyala.

---

## 8. Genişletme

İleride bir ekran daha eklemek istersen (örn. 4. kat):

1. Yeni PC'yi LAN'a bağla.
2. JAR'ı kopyala, `db.properties` ekle (aynı MySQL'i göstersin).
3. `restaurant-layout.properties` dosyasına yeni salon ekle:
   ```properties
   area.8.building     = 4. Bina
   area.8.section      = Teras
   area.8.startTableNo = 401
   area.8.tableCount   = 8
   ```
4. Tüm ekranlardaki uygulamayı yeniden başlat.

---

*Hazırlayan: Claude — Tarih: 2026-05-17*
