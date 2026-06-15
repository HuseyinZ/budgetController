# Bulut Yedekleme Kurulum Kılavuzu

Restoran verilerinizi kaybetmemek için günlük otomatik MySQL yedekleme
+ bulut senkronizasyonu. **Yangında, hırsızlıkta, disk arızasında
verileriniz kurtarılır.**

---

## Yaklaşım

```
MySQL  →  mysqldump  →  .sql dosyası  →  OneDrive klasörü  →  bulut
```

Windows Görev Zamanlayıcısı her gece bir batch script çalıştırır.
Script `mysqldump.exe` ile DB'yi `.sql` dosyasına döker ve **OneDrive
(veya Google Drive / Dropbox) içindeki klasöre kaydeder.** Bulut
servisinin masaüstü uygulaması bu dosyayı otomatik buluta yükler.

**Avantajları:**
- Bedava (OneDrive ile gelen 5 GB, Google Drive 15 GB yeterli).
- Hiçbir programlama gerekmez.
- Geri yükleme çok kolay (`mysql -u root -p posdb < yedek.sql`).
- 30 gün geriye versiyon tutuluyor (eski yedekler otomatik silinir).

---

## Kurulum Adımları

### 1. Bulut Hesabı Hazırla

OneDrive / Google Drive / Dropbox'tan birinin masaüstü uygulamasını kur.
**Önerilen: OneDrive** çünkü Windows'ta zaten kurulu gelir.

İlk açılışta hangi klasörü senkronize edeceğini seçer.
Genelde: `C:\Users\<kullanıcı>\OneDrive`

### 2. Yedek Klasörü Oluştur

OneDrive klasörünün içine yeni bir klasör aç:
```
C:\Users\husey\OneDrive\posdb-yedekleri\
```

### 3. Script'i Yerleştir

`scripts/backup_posdb.bat` dosyasını kalıcı bir yere kopyala:
```
C:\budget\scripts\backup_posdb.bat
```

### 4. Script'i Düzenle

Notepad ile aç ve şu değişkenleri ayarla:

```bat
set "MYSQL_BIN=C:\Program Files\MySQL\MySQL Server 9.3\bin"
set "BACKUP_DIR=%USERPROFILE%\OneDrive\posdb-yedekleri"
set "MYSQL_USER=root"
set "MYSQL_PASS=GERCEK_SIFRENIZ"
set "DB_NAME=posdb"
set "RETENTION_DAYS=30"
```

> **Güvenlik**: DB şifresini bat dosyasında düz metin tutmak ideal değil.
> En azından bu dosyayı sadece yönetici kullanıcıya okunabilir yap.
> Alternatif: MySQL'e parolasız erişim için `--login-path` veya
> `mysql_config_editor` kullanmak (ileri seviye).

### 5. Manuel Test

Komut satırını yönetici olarak aç:
```cmd
cd C:\budget\scripts
backup_posdb.bat
```

Beklenen çıktı:
```
[2026-05-18 02:00:00] Yedekleme baslatildi -> ...posdb-2026-05-18-0200.sql
[OK] Yedek olusturuldu: ...
[TEMIZLIK] 30 gunden eski yedekler silindi (varsa).
```

OneDrive klasöründe `posdb-2026-05-18-0200.sql` görmelisin.
OneDrive ikon **mavi okul** simgesini gösterdikten sonra buluta yüklenmiş demektir.

### 6. Otomatik Zamanlama

Windows arama → "**Görev Zamanlayıcısı**" (taskschd.msc).

1. Sağ menüden **"Görev Oluştur"** (Create Task).
2. **Genel** sekmesi:
   - Ad: `posdb Günlük Yedek`
   - "En yüksek ayrıcalıklarla çalıştır" işaretli
   - "Kullanıcı oturum açmamış olsa bile çalıştır" işaretli
3. **Tetikleyiciler** → Yeni:
   - Günlük → saat **03:00** (restoran kapalıyken)
4. **Eylemler** → Yeni:
   - Program: `C:\budget\scripts\backup_posdb.bat`
   - Başlangıç yeri: `C:\budget\scripts`
5. **Koşullar**:
   - "Bilgisayar AC ile beslenmiyorsa görevi başlatma" işaretini KALDIR
     (eğer laptop ise)
6. Tamam → şifreni iste → onayla.

### 7. Test Çalıştır

Görev listesinde sağ tık → **Çalıştır**. Hata olmadan biterse OK.

---

## Geri Yükleme (kriz anında)

1. Yeni bir MySQL kurulu PC'de boş bir veritabanı oluştur:
   ```sql
   CREATE DATABASE posdb CHARACTER SET utf8mb4 COLLATE utf8mb4_turkish_ci;
   ```

2. En yeni yedeği OneDrive'dan indir.

3. Geri yükle:
   ```bash
   "C:\Program Files\MySQL\MySQL Server 9.3\bin\mysql.exe" ^
       --default-character-set=utf8mb4 ^
       -u root -p posdb < posdb-2026-05-18-0200.sql
   ```

4. Uygulamayı başlat → tüm veriler yerinde.

---

## Üç Yedekleme Düzeyi

Daha güvenli yedek için **3-2-1 kuralı**:
- **3 kopya**: 1 ana DB + 1 yerel disk + 1 bulut
- **2 farklı medya**: SSD + bulut
- **1 site dışı**: bulut (yangın/hırsızlık koruması)

Önerilen ek:
- Haftada bir, USB belleğe manuel kopyala (yerel ikinci yedek).
- Aylık bir yedeği bir başka kasada veya evde saklamak da değerlidir.

---

## Sorun Giderme

| Sorun | Çözüm |
|---|---|
| Script çalıştığında pencere açılıp kapanıyor | Komut satırında elle çalıştır, hatayı oku. Genelde MYSQL_BIN yolu yanlış. |
| `mysqldump: Got error 1045 (28000): Access denied` | MYSQL_USER / MYSQL_PASS doğru değil. |
| OneDrive yüklemiyor | OneDrive simgesinde "Senkronize değil" hatası var mı? Hesap girişini yenile. |
| Yedek dosyaları büyüyor | `mysqldump --skip-extended-insert` ile satır başı insert kullan (daha okunaklı ama büyük). Veya gzip ile sıkıştır: `mysqldump ... | "C:\path\to\gzip.exe" > posdb.sql.gz` |
| Görev Zamanlayıcısı çalışmıyor | "Tarihçe" sekmesinde detaylı log var. Görev "kullanıcı oturum açtığında" değil "her zaman" çalışsın. |

---

## İleri Seviye Alternatifler (opsiyonel)

### A) MySQL Binary Log → AWS S3 (rclone)
Eğer veri kaybı dakika hassasiyetli olmalıysa MySQL'in binlog'unu **gerçek zamanlı** S3'e gönder. Detaylı kurulum gerektirir.

### B) Replication (Master-Slave)
Aynı LAN'da ikinci bir MySQL instance kurup canlı kopya tut. PC arızalanırsa otomatik geçiş.

### C) Snapshot tabanlı yedek
Disk seviyesinde (Veeam, Macrium) tüm sistemi yedekle. Hızlı geri dönüş için ideal.

Yukarıdaki üç seçenek küçük-orta restoranlar için **gereksiz**. Tek günlük dump + bulut yeterlidir.

---

*Hazırlayan: Claude — Tarih: 2026-05-18*
