# budgetController — Komple Kurulum Rehberi (Sıfırdan Canlıya)

> Hedef senaryo:
> - **Kasada**: ana bilgisayar (dokunmatik ekran) — burada MySQL + ana uygulama çalışır
> - **Her salonda**: ek dokunmatik ekran — ana bilgisayardaki DB'ye bağlanır
> - **Her mutfakta**: 1 termal yazıcı (Döner / Fırın / Ocak)
> - Tüm cihazlar aynı LAN'a bağlı

Bu rehber sıfırdan, hiç şey kurulu değilken başlar. Komutlar tam, ayar
ekranları tam — başka kaynağa bakmana gerek yok.

---

## İçindekiler

1. [Genel Durum ve Topoloji](#1-genel-durum)
2. [Donanım Listesi ve Bütçe](#2-donanım)
3. [Ağ Kurulumu](#3-ağ)
4. [Kasada MySQL Kurulumu](#4-mysql)
5. [Veritabanı Şeması — Migration Sistemi](#5-db)
6. [Programı Derleme — JAR](#6-jar)
7. [Programı Windows Uygulaması Yapma — EXE](#7-exe)
8. [Ana Bilgisayara (Kasaya) Kurulum](#8-kasa)
9. [Kat Ekranlarına Kurulum](#9-kat)
10. [Mutfak Yazıcılarının Kurulumu](#10-yazici)
11. [İlk Konfigürasyon (Kullanıcılar / Ürünler / Eşleştirmeler)](#11-config)
12. [Otomatik Başlatma](#12-otostart)
13. [Test Senaryosu](#13-test)
14. [Yedek + Uzaktan Bakım Açma](#14-bakim)
15. [Sık Karşılaşılan Sorunlar](#15-sorun)

---

## 1. Genel Durum ve Topoloji {#1-genel-durum}

```
                  Restoran LAN  (192.168.1.0/24)
   ┌────────────────────────────────────────────────────────────┐
   │                                                            │
   │   ┌──────────────┐    ┌──────────────┐    ┌──────────────┐ │
   │   │  Kat 1 PC    │    │  Kat 2 PC    │    │  Bahçe PC    │ │
   │   │  192.168.1.11│    │  192.168.1.12│    │  192.168.1.13│ │
   │   └──────┬───────┘    └──────┬───────┘    └──────┬───────┘ │
   │          │ JDBC                │ JDBC              │        │
   │          └────────────┬────────┴───────────────────┘        │
   │                       │                                     │
   │                ┌──────▼───────┐                             │
   │                │  KASA PC     │  ⟵ ANA BİLGİSAYAR           │
   │                │  192.168.1.10│  • MySQL Server              │
   │                │  + Uygulama  │  • budgetController.exe      │
   │                └──────┬───────┘  • Otomatik yedekleyici     │
   │                       │                                     │
   │             ┌─────────┼─────────┐                            │
   │   ┌─────────▼─┐  ┌────▼────┐  ┌─▼─────────┐                 │
   │   │ Mutfak    │  │ Mutfak  │  │ Mutfak    │                 │
   │   │ DÖNER     │  │ FIRIN   │  │ OCAK      │                 │
   │   │ .241      │  │ .242    │  │ .243      │                 │
   │   └───────────┘  └─────────┘  └───────────┘                 │
   └────────────────────────────────────────────────────────────┘
```

**Her cihazın IP'si sabit** — DHCP rezervasyonu ile router'dan kalıcı atanır.

---

## 2. Donanım Listesi ve Bütçe {#2-donanım}

| Donanım | Adet | Birim ₺ | Toplam | Notlar |
|---|---|---:|---:|---|
| **Kasa PC** (Mini-PC veya endüstriyel POS) — Intel i5, 8GB RAM, 256GB SSD | 1 | 18.000 | 18.000 | MySQL barındıracak |
| **Kasa Dokunmatik Ekran** 17" | 1 | 4.500 | 4.500 | HDMI + USB touch |
| **Kat PC** (mini PC veya All-in-One dokunmatik) — Intel i3, 4GB RAM, 128GB SSD | 3 | 8.000 | 24.000 | Her kat için 1 |
| **Wi-Fi router** (Cat6 LAN portlu) | 1 | 1.500 | 1.500 | Çoğu restoranda var |
| **LAN switch** 8-port | 1 | 800 | 800 | Cihaz fazlaysa |
| **Cat6 kablo** (50m makara) | 1 | 600 | 600 | Kasa↔switch↔ekranlar |
| **Mutfak yazıcı** Epson TM-T20III LAN | 3 | 7.000 | 21.000 | Döner/Fırın/Ocak |
| **Wi-Fi köprü** TP-Link TL-WR802N | 3 | 500 | 1.500 | Yazıcı kablosuz için |
| **80mm termal kağıt** (10 lı paket) | 2 | 350 | 700 | İlk stok |
| **UPS** (Kasada güç kesintisinde DB bozulmasın) | 1 | 2.500 | 2.500 | 5-10 dk yeter |
| **Toplam donanım** | | | **~75.000 ₺** | (kameralar, ses hariç) |

**Karşılaştırma**: PDF'deki Max Bilişim teklifi POS+yazıcı kısmı için ~120.000 ₺
istiyordu. Burada yazılım sıfır, sadece donanım masrafı **75.000 ₺**.

> İndirim için: Kat PC'lerini hep aynı modelle al — toplu indirim alırsın.
> Sahibinden ikinci el dokunmatik POS ekranlarına bakmak ciddi bütçe kurtarır.

---

## 3. Ağ Kurulumu {#3-ağ}

### 3.1 Sabit IP atama (kritik!)

Router'ın yönetim paneline gir (genelde `http://192.168.1.1`, admin / admin
veya admin / 1234). **DHCP Rezervasyonu** veya **Static IP** bölümünden:

| MAC adresi | IP atanacak | Cihaz adı |
|---|---|---|
| (kasanın MAC'i) | 192.168.1.10 | KASA |
| (kat1 PC MAC) | 192.168.1.11 | KAT-1 |
| (kat2 PC MAC) | 192.168.1.12 | KAT-2 |
| (bahçe PC MAC) | 192.168.1.13 | BAHCE |
| (DÖNER yazıcı MAC) | 192.168.1.241 | YAZICI-DONER |
| (FIRIN yazıcı MAC) | 192.168.1.242 | YAZICI-FIRIN |
| (OCAK yazıcı MAC) | 192.168.1.243 | YAZICI-OCAK |

MAC adresini Windows'ta öğrenmek için: `ipconfig /all` çalıştır, "Physical
Address" altında görürsün.

### 3.2 Fiziksel bağlantı

- Kasa PC → switch → router (Cat6 ile)
- Her kat PC'si → Cat6 ile switch'e veya Wi-Fi ile router'a
- Yazıcılar → mutfak yakınındaki Wi-Fi köprüye → router'a Wi-Fi

### 3.3 Test

Kasa PC'de komut satırı:
```cmd
ping 192.168.1.11   :: kat 1 cevap verirse OK
ping 192.168.1.241  :: yazıcı cevap verirse OK
```

---

## 4. Kasada MySQL Kurulumu {#4-mysql}

### 4.1 İndir ve kur

1. https://dev.mysql.com/downloads/installer/ → **"MySQL Installer for Windows"**
2. Yükleyici → **Setup Type**: Custom
3. Seçilecek bileşenler:
   - **MySQL Server 8.4 LTS** (9.x "Innovation" sürümü değil — LTS uzun destek alır;
     CI de 8.4 üzerinde doğrulanır)
   - MySQL Workbench 8.x (yönetim için)
   - MySQL Shell (opsiyonel)
4. **Type and Networking** ekranında:
   - Config Type: Server Computer
   - Port: 3306 (default)
   - **"Show Advanced and Logging Options"** işaretle
5. **Accounts and Roles**:
   - Root parolasını gir (**GÜÇLÜ** — en az 16 karakter, sembol içersin)
   - Bir yere not et — bir daha bulamazsın
6. **Windows Service**:
   - Service adı: MySQL84
   - "Start the MySQL Server at System Startup" işaretli
7. Apply → tamamlandı.

### 4.2 LAN'dan erişime aç

MySQL kurulduktan sonra, kat PC'lerinden bağlanabilmesi için:

Konfig dosyası: `C:\ProgramData\MySQL\MySQL Server 8.4\my.ini` (Notepad'i yönetici olarak aç)

```ini
[mysqld]
bind-address = 0.0.0.0
```

### 4.3 Windows Firewall

Yönetici olarak komut satırı → çalıştır:
```cmd
netsh advfirewall firewall add rule name="MySQL 3306" dir=in action=allow protocol=TCP localport=3306 remoteip=192.168.1.0/24
```

Bu sadece LAN'ı (192.168.1.x) kabul eder, dışarıdan erişim kapalıdır.

### 4.4 MySQL'i yeniden başlat

```cmd
net stop MySQL84
net start MySQL84
```

### 4.5 İki veritabanı kullanıcısı — `budget_migrate` (şema) ve `budget_app` (runtime)

**Kural:** `root` yalnız veritabanını oluşturmak, kullanıcıları tanımlamak ve restore
etmek için kullanılır. Ondan sonra iki ayrı, en az yetkili hesap vardır:

| Hesap | Ne için | Ne zaman | DDL |
|---|---|---|---|
| `budget_migrate` | Şema migration'ları (`tools.Migrate --apply / --adopt-existing`) | Yalnız kurulum ve sürüm yükseltme anında, yönetici elinde | **Evet** (yalnız `posdb`) |
| `budget_app` | Uygulamanın kendisi (kasa, kat PC'leri, açılış şema kontrolü ve — H3'e kadar — uygulama içi `BackupService`) | Sürekli | **HAYIR** — CREATE/ALTER/DROP yok |

Uygulama runtime'da **hiçbir DDL/veri düzeltmesi çalıştırmaz** (eski `SchemaPatcher`
kaldırıldı); açılışta yalnız `schema_version` tablosunu OKUR. Gömülü varsayılan hesap
YOKTUR: config eksikse açılışta anlaşılır hata verir, root'a düşmez.

Workbench → root ile bağlan → yeni Query penceresi:

```sql
-- 1) Şema kullanıcısı — yalnız kasa PC'sinden, yalnız migration anında
CREATE USER 'budget_migrate'@'localhost' IDENTIFIED BY 'MIGRATE_ICIN_GUCLU_SIFRE';
GRANT SELECT, INSERT, UPDATE, DELETE,
      CREATE, ALTER, DROP, INDEX, REFERENCES
  ON posdb.* TO 'budget_migrate'@'localhost';

-- 2) Runtime kullanıcısı — kasa PC (localhost) ve gerekirse kat PC'leri (LAN)
--    CANONICAL RUNTIME GRANT: yalnız dört DML yetkisi. Başka hiçbir şey eklenmez.
CREATE USER 'budget_app'@'localhost'   IDENTIFIED BY 'APP_ICIN_GUCLU_SIFRE';
CREATE USER 'budget_app'@'192.168.1.%' IDENTIFIED BY 'APP_ICIN_GUCLU_SIFRE';
GRANT SELECT, INSERT, UPDATE, DELETE ON posdb.* TO 'budget_app'@'localhost';
GRANT SELECT, INSERT, UPDATE, DELETE ON posdb.* TO 'budget_app'@'192.168.1.%';
FLUSH PRIVILEGES;
```

`budget_app` **yalnız uygulamanın günlük DML işlemlerini** yapabilir. Bilinçli olarak
verilmeyenler:

| Yetki | Neden verilmiyor |
|---|---|
| `CREATE` / `ALTER` / `DROP` / `INDEX` / `REFERENCES` | Şema işi `budget_migrate`'in; runtime hiç DDL çalıştırmaz |
| `TRIGGER` | Uygulama trigger oluşturmaz/kullanmaz |
| `EVENT` | Zamanlanmış işler MySQL'de değil, uygulama/Görev Zamanlayıcısı tarafında |
| `LOCK TABLES`, `SHOW VIEW` | Yalnız dump araçlarının ihtiyacı; runtime'ın değil |
| `PROCESS`, `SUPER`, `FILE`, `RELOAD`, `GRANT OPTION`, `*.*` | Sunucu geneli yetki — hiçbir hesaba verilmez |

> **Yedekleme yetkileri buraya eklenmez — ve bunun bilinen bir sonucu vardır.**
> Uygulama içindeki `BackupService` şu an **runtime credential'ını** (`db.user` /
> `db.password`, yani `budget_app`) kullanarak
> `mysqldump --single-transaction --no-tablespaces --routines --triggers` çalıştırır.
> `budget_app` bilinçli olarak yalnız SELECT/INSERT/UPDATE/DELETE yetkisine sahip
> olduğundan, **bu least-privilege modelinde otomatik yedeğin sorunsuz çalışacağı
> garanti edilmez** (`--routines` / `--triggers` ek yetki isteyebilir).
>
> Bu bilinçli bir kabuldür: yedeklemenin credential ve yetki ayrımı ayrı bir iş
> kalemidir (audit **H3 — backup credential / privilege modeli**) ve **production'a
> geçmeden önce H3 tamamlanmalıdır.** Sorunu `budget_app`'e `TRIGGER`, `EVENT`,
> global `SELECT` veya benzeri ek yetkiler vererek çözmeyin — doğru çözüm, yedeğin
> kendi ayrı hesabını ve gerekiyorsa kendi mysqldump bayraklarını kullanmasıdır.

`REFERENCES`, migration'ların foreign key tanımlayabilmesi için `budget_migrate`'te
gereklidir (MySQL 8). Bu ayrım CI'da her değişiklikte doğrulanır
(`.github/workflows/ci.yml` → *Fresh install (MySQL 8.4)* job'ı): `budget_app` ile
SELECT/INSERT/UPDATE/DELETE'in **çalıştığı**, `CREATE TABLE` / `ALTER TABLE` /
`DROP TABLE` / `CREATE TRIGGER` / `CREATE EVENT`'in ise **reddedildiği** doğrulanır.

> `budget_migrate` parolasını `~/.budget/db.properties` dosyasına yazmak zorunda
> değilsiniz; §5'teki gibi yalnız migration komutunu çalıştırırken ortam değişkeni
> olarak vermeniz yeterlidir. Runtime dosyasında yalnız `budget_app` bulunur.

---

## 5. Veritabanı Şeması — Migration Sistemi {#5-db}

Şema artık **repo içinde versiyonlu SQL dosyalarıyla** yönetilir ve `tools.Migrate`
aracıyla uygulanır. Elle dump/DDL yükleme dönemi bitti:

> **ESKİ YÖNTEM — ARTIK KULLANILMAZ:** `posdb_*.sql` dump'larını ve `V2026_05_*` dosyalarını
> `mysql -u root ... <` ile tek tek yüklemek. Dump'lar üretim verisi/kullanıcı içerir ve
> **Git'e konmaz** (`.gitignore`); eski migration'lar yalnız tarihsel referans olarak
> `docs/db/legacy/` altındadır ve çalıştırılmaz.

Dosyalar: `src/main/resources/db/migration/`
| Dosya | İçerik |
|---|---|
| `V001__baseline_schema.sql` | Canonical şema — 16 tablo (roles, users, categories, products, dining_tables, kitchen_printers, orders, order_items, payments, order_logs, expenses, refund_log, reservations, category_printer_routes, print_jobs, user_area_permissions). Veri yok. |
| `V002__seed_roles.sql` | ADMIN / KASIYER / GARSON rolleri (idempotent). Kullanıcı **yok**. |
| `V003__seed_kitchen_printers.sql` | DONER / FIRIN / OCAK yazıcı şablonları — `host` placeholder (`192.0.2.x`), `is_active = 0`. Gerçek IP §10.4'te girilir. |

Uygulanan her migration `schema_version` tablosuna (versiyon, açıklama, SHA-256 checksum,
zaman) yazılır. Uygulanmış bir dosya sonradan değiştirilirse Migrate checksum
uyuşmazlığıyla **durur**. Rollback / destructive (DROP, veri silen) migration **yoktur**;
her değişiklik ileri yönlü ve idempotent yazılır.

### 5.1 Veritabanını oluştur (root, tek sefer)

Workbench Query'de:
```sql
CREATE DATABASE posdb
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_turkish_ci;
```
Sonra §4.5'teki iki kullanıcıyı (`budget_migrate`, `budget_app`) oluştur.

### 5.2 Migrate aracının çalıştırılması

`tools.Migrate` uygulama JAR'ının içindedir. Bağlantı bilgisi `~/.budget/db.properties`
(§8.4.1) + ortam değişkenlerinden okunur; **şema değiştiren komutlar için `budget_migrate`
kimliği ayrıca ve açıkça verilmek zorundadır** — verilmezse komut çalışmaz, `budget_app`'e
düşmez. Kasa PC'de (PowerShell):

```powershell
cd "C:\Program Files\budgetController"          # JAR'ın bulunduğu klasör
$env:DB_MIGRATE_USER = "budget_migrate"
$env:DB_MIGRATE_PASS = "MIGRATE_ICIN_GUCLU_SIFRE"    # oturum kapanınca silinir

java -cp budgetController-1.0-SNAPSHOT.jar tools.Migrate --status
```

Komutlar:

| Komut | Ne yapar | Kimlik | Ne zaman |
|---|---|---|---|
| `--status` | **Salt okuma.** Uygulanmış/bekleyen migration'ları ve checksum durumunu listeler; hiçbir şey yazmaz | `budget_app` yeter | Her zaman güvenli — önce bunu çalıştır |
| `--apply` | Bekleyen migration'ları versiyon sırasıyla uygular, `schema_version`'a kaydeder | `budget_migrate` **zorunlu** | Boş DB kurulumu ve her sürüm yükseltmesi |
| `--adopt-existing` | Elle kurulmuş **eski** DB'yi versiyon sistemine alır: şemanın V001 ile yapısal uyumunu (tablo/kolon/tip/PK/UNIQUE/FK/CHECK) doğrular; uyumluysa yalnız **V001**'i uygulanmış işaretler. Uyumsuzsa hiçbir kayıt yazmadan durur. **Veri/şema değiştirmez.** | `budget_migrate` **zorunlu** | Yalnız bir kez, migration sistemine geçişte |

### 5.3 Sıfırdan kurulum (boş `posdb`)

```powershell
java -cp budgetController-1.0-SNAPSHOT.jar tools.Migrate --status    # "Migration metadata başlatılmamış", V1 V2 V3 bekleyen
java -cp budgetController-1.0-SNAPSHOT.jar tools.Migrate --apply     # V001 → V002 → V003
java -cp budgetController-1.0-SNAPSHOT.jar tools.Migrate --status    # "Durum: GÜNCEL"
```

### 5.4 Mevcut (eski, elle kurulmuş) `posdb`'yi geçirme

```powershell
java -cp budgetController-1.0-SNAPSHOT.jar tools.Migrate --status           # metadata yok beklenir
java -cp budgetController-1.0-SNAPSHOT.jar tools.Migrate --adopt-existing   # yapısal doğrulama + V001 işareti
java -cp budgetController-1.0-SNAPSHOT.jar tools.Migrate --apply            # V002/V003: eksik rol / placeholder yazıcı eklenir
java -cp budgetController-1.0-SNAPSHOT.jar tools.Migrate --status           # "Durum: GÜNCEL"
```
Seed'ler idempotenttir: mevcut roller ve **gerçek yazıcı kayıtlarına dokunulmaz**, yalnız
eksik olanlar eklenir. Adoption "uyumsuz" derse listeyi okuyun; şemayı elle düzeltmek yerine
durumu bildirin — düzeltici migration (`V004+`) repo üzerinden gelir.

### 5.5 Uygulama açılışındaki şema kontrolü

Uygulama her açılışta `schema_version` tablosunu **yalnız okur** (DDL yetkisi gerekmez).
Şu durumlarda **açılmaz** ve şu mesajı gösterir:

> *Database schema is not ready. Run Migrate --status / --apply / --adopt-existing.*

- `schema_version` yok → DB hiç migrate/adopt edilmemiş → §5.3 veya §5.4
- bekleyen migration var → yeni sürüm kuruldu ama `--apply` çalıştırılmadı
- checksum uyuşmazlığı → uygulanmış bir migration dosyası değiştirilmiş (normalde olmamalı; destek isteyin)
- veritabanına ulaşılamıyor / `db.properties` eksik → §15

Bu bilinçli bir tasarımdır: şema eksikken uygulama "yarım çalışıp" sessizce veri
bozmaktansa hiç açılmaz. Teknik ayrıntı `logs/errors.log` dosyasındadır.

### 5.6 Kaldırılan otomatik veri düzeltmesi

Eski sürümler açılışta `products` tablosundaki negatif stokları otomatik 0'a çekiyordu.
Bu davranış **kaldırıldı**: uygulama artık açılışta hiçbir veri değiştirmez. Böyle bir
düzeltme gerekirse ayrı, yöneticinin bilinçli çalıştıracağı bir bakım adımı olarak
sunulacaktır (bu sürümde yok).

### 5.7 Doğrulama

Workbench'te (root veya budget_app):
```sql
USE posdb;
SELECT version, description, applied_at FROM schema_version ORDER BY version;  -- 1, 2, 3
SELECT name FROM roles;                                                          -- ADMIN, KASIYER, GARSON
SHOW TABLES;                                                                     -- 16 tablo + schema_version
```

---

## 6. Programı Derleme — JAR Üretimi {#6-jar}

### 6.1 Java Geliştirme Kiti (JDK) Kurulumu

1. https://adoptium.net/temurin/releases/?version=22 → **Java 22 LTS** (Windows MSI)
2. Yükle, "Set JAVA_HOME" işaretle
3. Komut satırını yeniden aç ve test et:
   ```cmd
   java -version
   ```
   `openjdk version "22.x.x"` görmelisin.

### 6.2 Maven Kurulumu (Geliştirme makinende)

> Geliştirme makinen = JAR'ı üretip restorana taşıyacağın kendi PC'n.

1. https://maven.apache.org/download.cgi → "Binary zip"
2. `C:\Program Files\apache-maven-3.9.x\` altına çıkar
3. Sistem değişkenlerine ekle:
   - `MAVEN_HOME = C:\Program Files\apache-maven-3.9.x`
   - `PATH` → `;%MAVEN_HOME%\bin` ekle
4. Test:
   ```cmd
   mvn -version
   ```

### 6.3 JAR'ı Üret

```cmd
cd C:\Users\husey\IdeaProjects\budgetController
mvn clean package
```

Birkaç dakika sonra:
```
target\budgetController-1.0-SNAPSHOT.jar  ← bu dosyaya ihtiyacın var
```

Test çalıştır:
```cmd
java -jar target\budgetController-1.0-SNAPSHOT.jar
```

Login ekranı açılıyor mu? Kontrol — `db.properties` localhost'a baktığı için
geliştirme makinende de aynı DB kuruluysa çalışır.

---

## 7. Programı Windows Uygulaması Yapma — EXE / Installer {#7-exe}

JAR'ı çift-tıklayarak çalıştırmak çoğu kullanıcı için zor. **jpackage** ile
gerçek bir Windows uygulaması (Start menüsünde simge, masaüstü kısayolu)
yapalım. Java 22 ile birlikte geliyor.

### 7.1 Hazırlık

```cmd
cd C:\Users\husey\IdeaProjects\budgetController
mkdir installer
```

### 7.2 İkonu hazırla (opsiyonel)

`installer/budget.ico` adında 256×256 px ICO dosyası koy. Yoksa default
Java duck simgesi kullanılır.

### 7.3 jpackage komutu

```cmd
jpackage ^
  --name "budgetController" ^
  --app-version "1.0.0" ^
  --vendor "Restoran Adi" ^
  --input target ^
  --main-jar budgetController-1.0-SNAPSHOT.jar ^
  --main-class org.budget.App ^
  --type msi ^
  --win-shortcut ^
  --win-menu ^
  --win-menu-group "budgetController" ^
  --win-dir-chooser ^
  --win-per-user-install ^
  --icon installer\budget.ico ^
  --dest installer
```

Birkaç dakika sonra:
```
installer\budgetController-1.0.0.msi
```

Bu **kurulum dosyası** restoran PC'sine kopyalanıp çift tıklayarak kurulabilir.
- Otomatik Java Runtime'ı içine alır (kullanıcının JDK kurmaya gerek yok)
- Start menüsüne simge ekler
- Masaüstüne kısayol ekler
- "Programlar ve Özellikler"den kaldırılabilir hale gelir

### 7.4 Alternatif: Çalıştırılabilir EXE

`--type exe` (msi yerine) ile tek dosyalı kurulum üretir.
`--type app-image` ile portable klasör üretir (kurulum gerektirmez).

### 7.5 Java olmadan üreten installer (önerilen)

`jpackage` zaten JRE'yi içine paketler — kullanıcının makinesinde Java
kurulu olmasına gerek yoktur. Bu çok büyük kolaylık.

---

## 8. Ana Bilgisayara (Kasaya) Kurulum {#8-kasa}

Kasa PC'sini bu sırayla hazırla:

### 8.1 Windows ayarları

- Windows 10/11 Pro (Home da çalışır)
- **Kullanıcı hesabı**: "kasa" (admin)
- **Görev çubuğu kilitle**, kafa karıştırmasın
- **Otomatik güncellemeyi gece 03:00'a al** (kapalıyken)

### 8.2 MySQL kurulu (4. bölümde anlattık)

### 8.3 Uygulamayı kur

`budgetController-1.0.0.msi` dosyasını USB ile getir → çift tıkla → Next, Next.

Varsayılan kurulum yolu: `C:\Users\kasa\AppData\Local\Programs\budgetController\`

### 8.4 Yapılandırma dosyaları

#### 8.4.1 DB bağlantısı

`C:\Users\kasa\.budget\db.properties` (yoksa oluştur — **üretim config kaynağı budur**;
repo içindeki `db.properties.example` yalnız şablondur, gerçek değer içermez):

```properties
db.url=jdbc:mysql://localhost:3306/posdb?useUnicode=true&characterEncoding=utf8&serverTimezone=Europe/Istanbul&allowPublicKeyRetrieval=true&sslMode=DISABLED
db.user=budget_app
db.password=GÜÇLÜ_BİR_SİFRE
db.pool.maxSize=15
```

`db.url`, `db.user`, `db.password` üçü de zorunludur; biri eksikse uygulama açılışta
"Veritabanı yapılandırması eksik: db.password ..." benzeri bir hata verir ve
başlamaz (gömülü varsayılan hesap yoktur). Dosyayı yalnız `kasa` kullanıcısının
okuyabileceği şekilde tutun (Özellikler → Güvenlik).

Migration kimliği (`budget_migrate`) bu dosyaya **yazılmak zorunda değildir**; §5.2'deki
gibi `DB_MIGRATE_USER` / `DB_MIGRATE_PASS` ortam değişkeni olarak yalnız Migrate
çalıştırırken verilir. İstenirse `db.migrate.user` / `db.migrate.password` anahtarları
olarak da dosyaya konabilir — uygulama runtime'ı bu anahtarları hiç okumaz.

Öncelik sırası (tüm anahtarlar için): JVM `-D` sistem özelliği > ortam değişkeni
(`DB_URL`, `DB_USER`, `DB_PASS`, `DB_MIGRATE_USER`, `DB_MIGRATE_PASS`) > `~/.budget/db.properties`.

#### 8.4.2 Restoran masa düzeni

`C:\Users\kasa\.budget\restaurant-layout.properties` (yoksa oluştur, içine
projedeki örneği kopyala):

```properties
area.1.building     = 1. Kat
area.1.section      = Salon A
area.1.startTableNo = 101
area.1.tableCount   = 12
# kendi restoranına göre düzenle...
```

#### 8.4.3 Admin parolası

İlk girişten önce ortam değişkeni ile güvenli admin parolası:
```cmd
setx BUDGET_ADMIN_SEED_PASSWORD "BurayaGüçlüParola!2026"
```

### 8.5 İlk çalıştırma

- Masaüstündeki "budgetController" kısayolu → çalıştır
- Login: `admin` / `BurayaGüçlüParola!2026`
- İlk işin: kullanıcı işlemleri → admin parolanı tekrar değiştir

---

## 9. Kat Ekranlarına Kurulum {#9-kat}

Her kat PC'sine aynı `.msi` kurulum dosyasını taşı ve kur.

### Tek fark: `db.properties`'te MySQL adresi

Kat PC'sinin `C:\Users\kat1\.budget\db.properties`:
```properties
db.url=jdbc:mysql://192.168.1.10:3306/posdb?useUnicode=true&characterEncoding=utf8&serverTimezone=Europe/Istanbul&allowPublicKeyRetrieval=true&sslMode=DISABLED
db.user=budget_app
db.password=GÜÇLÜ_BİR_SİFRE
```

(Kat PC'leri de aynı en az yetkili `budget_app` hesabını kullanır — §4.5'teki
`'budget_app'@'192.168.1.%'` tanımı bunun içindir.)

**Sadece IP değişti** (`localhost` → `192.168.1.10`). Kalan her şey aynı.

`restaurant-layout.properties` de **aynı içerikle** kopyalanmalı — tüm
ekranlar aynı salon tanımını görmeli.

### Test

Kat PC'de uygulamayı aç → garson hesabıyla giriş yap. Kasada eklediğin
masaları görmelisin.

---

## 10. Mutfak Yazıcılarının Kurulumu {#10-yazici}

### 10.1 Yazıcılara IP ata

Her Epson TM-T20III LAN'ı:
1. Yazıcı üzerinden status sheet bas (FEED + power on)
2. Status sheet'te varsayılan IP yazıyor (genelde 192.168.192.168)
3. Bilgisayarı geçici olarak 192.168.192.x'e ayarla → tarayıcıdan
   `http://192.168.192.168` → ağ ayarları → IP'yi 192.168.1.241'e değiştir
4. Bilgisayarı normal IP'sine geri al

### 10.2 Wi-Fi köprüye bağla

Her yazıcı için TP-Link TL-WR802N:
1. Köprüyü USB ile güçlendir
2. `tplinkwifi.net` → **Client mod** → restoran Wi-Fi'sini seç
3. Köprünün LAN portunu yazıcıya Cat6 ile bağla

### 10.3 Test

Kasa PC'den:
```cmd
ping 192.168.1.241
```

Yazıcıya test fişi bas:
```cmd
cd C:\Users\husey\IdeaProjects\budgetController
java -cp target\budgetController-1.0-SNAPSHOT.jar tools.PrintTestMain 192.168.1.241 9100 42
```

Yazıcıdan test fişi çıkıyorsa OK.

### 10.4 DB'de yazıcı kayıtlarını güncelle

`V003__seed_kitchen_printers.sql` üç yazıcıyı **placeholder host ile ve `is_active = 0`**
olarak oluşturur (bkz. §5). Gerçek IP'leri burada girip yazıcıları etkinleştirirsin —
şema değişikliği değil, normal veri güncellemesidir; `budget_app` yetkisiyle de yapılabilir.

Workbench (kendi ağındaki gerçek IP'leri yaz):
```sql
UPDATE kitchen_printers SET host='192.168.1.241', port=9100, is_active=1 WHERE code='DONER';
UPDATE kitchen_printers SET host='192.168.1.242', port=9100, is_active=1 WHERE code='FIRIN';
UPDATE kitchen_printers SET host='192.168.1.243', port=9100, is_active=1 WHERE code='OCAK';

SELECT code, host, port, is_active FROM kitchen_printers;   -- 192.0.2.x kalmamalı
```

---

## 11. İlk Konfigürasyon {#11-config}

Tüm bu adımları **kasa PC'sinden, admin olarak girerek** yap.

### 11.1 Kullanıcılar

`Kullanıcı İşlemleri` sekmesi → **Yeni Kullanıcı**

| Kullanıcı | Rol | Notlar |
|---|---|---|
| admin | ADMIN | Sahip (kendin) |
| kasiyer1 | KASIYER | Kasada oturan kişi |
| ahmet | GARSON | 1. kat garsonu |
| ayse | GARSON | 2. kat garsonu |
| mehmet | GARSON | Bahçe garsonu |

Her birine güçlü parola ata.

### 11.2 Kategoriler

`Ürünler` sekmesi → kategoriler MySQL'de halihazırda var. Workbench'te
kontrol et:
```sql
SELECT * FROM categories;
```

Eksik kategoriler için Workbench ile ekle:
```sql
INSERT INTO categories (name) VALUES
('Ciğer'), ('Döner'), ('Pide'), ('Kebap'),
('İçecek'), ('Tatlı'), ('Çorba'), ('Salata');
```

### 11.3 Ürünler

`Ürünler` sekmesi → Yeni → her ürün için:
- Ad: "Kuzu Ciğer Şiş"
- Kategori: "Ciğer"
- Porsiyon Fiyatı: 360,00 ₺
- Birim: "şiş"
- Porsiyondaki şiş adeti: 4

(Şiş bazlı değilse "Porsiyondaki şiş adeti" = 0 bırak.)

### 11.4 Kategori → Yazıcı eşleştirmesi

`Kullanıcı İşlemleri` → **Mutfak Eşleştirme** butonu →
- Ciğer → OCAK
- Döner → DONER
- Pide → FIRIN
- Kebap → OCAK
- İçecek → (boş — yazıcıya düşmez, kasa kendi servis eder)

### 11.5 Garson alan yetkilendirmesi

`Kullanıcı İşlemleri` → bir garsonu seç → **Alan Yetkileri** →
- Ahmet: ☑ 1. Kat / Salon A
- Ayşe: ☑ 2. Kat / Salon B
- Mehmet: ☑ Bahçe

Kaydet.

### 11.6 Demo Sipariş (test)

1. Kat PC'sinden Ahmet ile giriş yap
2. "Katlar" sekmesinde sadece 1. Kat görünür
3. Bir masaya tıkla → ürün ekle (örn. 5 şiş ciğer)
4. "Mutfağa Gönder" → Ocak yazıcıdan fiş çıkmalı
5. Kasa PC'sinde kasiyer1 ile satışı tamamla

---

## 12. Otomatik Başlatma {#12-otostart}

Her PC açıldığında uygulama tek başına açılmalı.

### 12.1 Windows başlangıç klasörüne kısayol koy

1. Win+R → `shell:startup` → Enter
2. Bu klasör açılır
3. Masaüstündeki "budgetController" kısayolunu sürükle bırak

### 12.2 Otomatik giriş (kasa PC için kullanışlı)

1. Win+R → `netplwiz` → Enter
2. "Bu bilgisayarda kullanıcıların kullanıcı adı ve parola girmesi
   gerekir" işaretini KALDIR
3. "kasa" kullanıcısının parolasını gir
4. Bilgisayar açıldığında direkt masaüstüne girer

### 12.3 MySQL otomatik başlatma

Kurulumda zaten "Start on system startup" işaretliydi. Doğrula:
```cmd
sc query MySQL84
```

`STATE: RUNNING` görmelisin.

---

## 13. Test Senaryosu {#13-test}

Tüm kurulum bittikten sonra şu senaryoyu uçtan uca dene:

1. ✅ Kasa PC'yi kapat ve aç → uygulama otomatik açılıyor mu?
2. ✅ Kat PC'sini aç → DB'ye bağlanıyor mu?
3. ✅ Ahmet ile giriş yap → sadece 1. kat görünüyor mu?
4. ✅ Bir masaya 5 şiş ciğer + 1 ayran ekle → fiyat doğru hesaplandı mı?
   (5×90 = 450 ₺ olmalı; 1 porsiyon 4 şiş × 90 ₺ = 360 ₺ ise birim 90/4=22.50)
   Yanlış! Doğrusu: porsiyon fiyatı 360 ₺ ise şiş başı 90 ₺, 5 şiş 450 ₺.
5. ✅ "Mutfağa Gönder" → Ocak yazıcıdan fiş çıkıyor mu?
6. ✅ Fişte salon + masa no büyük punto, vurgulu kalem var mı?
7. ✅ Kasada satış yap → Nakit → masa boşalıyor mu?
8. ✅ Aynı masaya yeniden ürün ekle → "YENİ" sarı vurgu var mı?
9. ✅ Admin → Gün Sonu → bugün ki ciro 450 ₺ görünüyor mu?
10. ✅ Admin → Saatlik Yoğunluk → şimdiki saat dilimi 1 işlem gösteriyor mu?

Her sorunun cevabı "Evet" ise sistem canlıya hazır.

---

## 14. Yedek + Uzaktan Bakım Açma {#14-bakim}

### 14.1 Otomatik yedekleme

> **Not (yetki modeli — production öncesi çözülmeli):** İki ayrı yedek yolu var.
> Harici `scripts/backup_posdb.bat` betiği kendi içinde tanımlanan hesapla çalışır
> (parolası **repoya girmez**). Uygulama içindeki `BackupService` ise **runtime
> credential'ını (`budget_app`) kullanır**; o hesapta bilinçle yalnız
> SELECT/INSERT/UPDATE/DELETE bulunduğundan (§4.5) `--routines --triggers` içeren
> mysqldump çağrısının bu modelde çalışacağı **garanti değildir**. Yedek hesap/yetki
> ayrımı audit **H3** kapsamındadır ve **production'a geçmeden önce tamamlanmalıdır**;
> bu arada `budget_app`'e ek yetki verilerek geçiştirilmemelidir. Yedeklerini elle
> doğrula (§14.1 adım 5).

`BULUT_YEDEKLEME.md`'ye göre:
1. OneDrive masaüstü uygulamasını kur, hesap aç
2. `scripts/backup_posdb.bat`'i `C:\budget\scripts\` altına kopyala
3. İçindeki MySQL şifresini doldur
4. Görev Zamanlayıcısı'nda gece 03:00 çalışacak görev tanımla
5. Test: bir kez manuel çalıştır, OneDrive'da yedek görmelisin

### 14.2 Uzaktan erişim

`UZAKTAN_BAKIM.md`'ye göre:
1. **AnyDesk** kur → ID'sini telefonuna kaydet
2. **Tailscale** kur → kasayı kendi laptop'ına ekle
3. Bağlantı şifresi en az 12 karakter

---

## 15. Sık Karşılaşılan Sorunlar {#15-sorun}

### "Kat PC kasa MySQL'e bağlanamıyor"
- Kasada Windows Firewall'da 3306 portu LAN'a açık mı?
- `my.ini` içinde `bind-address = 0.0.0.0` ayarlı mı?
- Kat PC'den `telnet 192.168.1.10 3306` test et
- `GRANT` cümlesinde `192.168.1.%` doğru mu?

### "Veritabanı yapılandırması eksik: db.url / db.user / db.password"
- `C:\Users\<kullanıcı>\.budget\db.properties` mevcut mu, üç anahtar da dolu mu? (§8.4.1)
- Gömülü varsayılan hesap **yoktur**; dosya yoksa uygulama açılmaz.

### "Database schema is not ready. Run Migrate --status / --apply / --adopt-existing."
Uygulama açılışta şemayı yalnız okuyarak doğrular; hazır değilse bilinçli olarak başlamaz (§5.5).
- Önce (güvenli, salt okuma): `java -cp budgetController-1.0-SNAPSHOT.jar tools.Migrate --status`
- Çıktı **"Migration metadata başlatılmamış"** → DB hiç geçirilmemiş:
  boş DB ise `--apply` (§5.3); eski elle kurulmuş DB ise önce `--adopt-existing`, sonra `--apply` (§5.4).
- Çıktı **bekleyen migration** listeliyor → yeni sürüm kuruldu; `--apply` çalıştır.
- Çıktı **checksum uyuşmazlığı** → uygulanmış bir migration dosyası değişmiş; elle düzeltme yapma, destek iste.
- Dialogda "database unreachable or configuration missing" → MySQL servisi çalışıyor mu, `db.properties` doğru mu (§4, §8.4.1).
- `--apply`/`--adopt-existing` "migrate kimliği eksik" diyorsa `DB_MIGRATE_USER` / `DB_MIGRATE_PASS` verilmemiştir (§5.2); `budget_app`'e düşmez.
- Ayrıntı: `logs/errors.log`.

### "Yazıcı bulunamadı / fiş basmıyor"
- `ping 192.168.1.241` cevap veriyor mu?
- Yazıcının yan tarafındaki LED yeşil yanıyor mu?
- Kağıt rulosu yerleşmiş mi (mavi kapakta çıkıntı doğru tarafta mı)?
- `PrintTestMain` ile manuel test → bağlantı hatası mı yoksa kâğıt mı?

### "Garson hiçbir kat görmüyor"
- Admin paneline gir → o garsonun "Alan Yetkileri"ne bak — atanmış kat var mı?
- Yoksa en az bir alan ata

### "Saat 1 saat farklı"
- Windows'ta zaman dilimi `Europe/Istanbul` mu? `tzutil /g` ile kontrol et
- Yanlışsa: `tzutil /s "Turkey Standard Time"`
- MySQL: `SELECT @@global.time_zone, @@session.time_zone;` — SYSTEM olmalı

### "Maven derleme başarısız"
- Java sürümü 22 mi? `java -version`
- `JAVA_HOME` doğru ayarlı mı?
- pom.xml'deki `maven.compiler.release` ile JDK uyumlu mu?

### "msi kurulumu hata veriyor"
- jpackage başka bir Java sürümü ile yapılmışsa — Java 22 ile yeniden üret
- Kurulum dosyasının imzasız olması Windows SmartScreen uyarısı verir — "Yine de çalıştır"

---

## EK: Kısayol Komut Cheat Sheet

```cmd
:: Derle
mvn clean package

:: Test çalıştır
java -jar target\budgetController-1.0-SNAPSHOT.jar

:: Yazıcı test
java -cp target\budgetController-1.0-SNAPSHOT.jar tools.PrintTestMain 192.168.1.241 9100 42

:: Installer üret
jpackage --name budgetController --app-version 1.0.0 ^
  --input target --main-jar budgetController-1.0-SNAPSHOT.jar ^
  --main-class org.budget.App --type msi --win-shortcut --win-menu

:: Şema durumu (salt okuma) / migration uygula (DB_MIGRATE_USER + DB_MIGRATE_PASS gerekli)
java -cp target\budgetController-1.0-SNAPSHOT.jar tools.Migrate --status
java -cp target\budgetController-1.0-SNAPSHOT.jar tools.Migrate --apply

:: MySQL bağlan
mysql -u budget_app -h 192.168.1.10 -p posdb

:: Yedek al (dump Git'e KONMAZ — .gitignore: posdb_*.sql)
mysqldump --default-character-set=utf8mb4 --single-transaction --no-tablespaces --routines --triggers -u root -p posdb > posdb_backup.sql

:: Yedekten geri yükle (sonra Migrate --status ile şema durumunu doğrula)
mysql --default-character-set=utf8mb4 -u root -p posdb < posdb_backup.sql

:: Uygulamayı yeniden başlat (process kapat, masaüstünden çift tıkla)
taskkill /F /IM javaw.exe
```

---

*Hazırlayan: Claude — Tarih: 2026-05-18*
*budgetController v1.0 için kurulum rehberi*
