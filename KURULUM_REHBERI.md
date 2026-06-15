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
5. [Veritabanı + Migration'lar](#5-db)
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
   - MySQL Server 9.3.0
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
   - Service adı: MySQL93
   - "Start the MySQL Server at System Startup" işaretli
7. Apply → tamamlandı.

### 4.2 LAN'dan erişime aç

MySQL kurulduktan sonra, kat PC'lerinden bağlanabilmesi için:

Konfig dosyası: `C:\ProgramData\MySQL\MySQL Server 9.3\my.ini` (Notepad'i yönetici olarak aç)

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
net stop MySQL93
net start MySQL93
```

### 4.5 LAN kullanıcısı oluştur (root'u doğrudan kullanmayalım)

Workbench → root ile bağlan → yeni Query penceresi:

```sql
CREATE USER 'budget'@'192.168.1.%' IDENTIFIED BY 'GÜÇLÜ_BİR_SİFRE';
CREATE USER 'budget'@'localhost'  IDENTIFIED BY 'GÜÇLÜ_BİR_SİFRE';
GRANT ALL ON posdb.* TO 'budget'@'192.168.1.%';
GRANT ALL ON posdb.* TO 'budget'@'localhost';
FLUSH PRIVILEGES;
```

---

## 5. Veritabanı + Migration'lar {#5-db}

### 5.1 Veritabanını oluştur

Workbench Query'de:
```sql
CREATE DATABASE posdb
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_turkish_ci;
```

### 5.2 Ana DDL'leri yükle

Komut satırında (proje klasöründe):
```cmd
cd C:\Users\husey\IdeaProjects\budgetController
set MYSQL="C:\Program Files\MySQL\MySQL Server 9.3\bin\mysql.exe"

%MYSQL% --default-character-set=utf8mb4 -u root -p posdb < posdb_roles.sql
%MYSQL% --default-character-set=utf8mb4 -u root -p posdb < posdb_users.sql
%MYSQL% --default-character-set=utf8mb4 -u root -p posdb < posdb_categories.sql
%MYSQL% --default-character-set=utf8mb4 -u root -p posdb < posdb_products.sql
%MYSQL% --default-character-set=utf8mb4 -u root -p posdb < posdb_dining_tables.sql
%MYSQL% --default-character-set=utf8mb4 -u root -p posdb < posdb_orders.sql
%MYSQL% --default-character-set=utf8mb4 -u root -p posdb < posdb_order_items.sql
%MYSQL% --default-character-set=utf8mb4 -u root -p posdb < posdb_payments.sql
%MYSQL% --default-character-set=utf8mb4 -u root -p posdb < posdb_expenses.sql
%MYSQL% --default-character-set=utf8mb4 -u root -p posdb < posdb_returns.sql
%MYSQL% --default-character-set=utf8mb4 -u root -p posdb < posdb_routines.sql
```

### 5.3 Migration'ları sırayla çalıştır

```cmd
cd C:\Users\husey\IdeaProjects\budgetController\src\main\resources\db\migration

%MYSQL% --default-character-set=utf8mb4 -u root -p posdb < V2026_05_15__kitchen_printers.sql
%MYSQL% --default-character-set=utf8mb4 -u root -p posdb < V2026_05_17__multi_kitchen_and_override.sql
%MYSQL% --default-character-set=utf8mb4 -u root -p posdb < V2026_05_17b__portion_pricing_and_kg_expenses.sql
%MYSQL% --default-character-set=utf8mb4 -u root -p posdb < V2026_05_17c__user_area_permissions.sql
%MYSQL% --default-character-set=utf8mb4 -u root -p posdb < V2026_05_18__order_item_notes.sql
```

### 5.4 Doğrulama

Workbench'te:
```sql
USE posdb;
SHOW TABLES;
-- En az şu tabloları görmelisin:
-- categories, dining_tables, expenses, kitchen_printers,
-- category_printer_routes, order_items, orders, payments,
-- print_jobs, products, roles, user_area_permissions, users
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

`C:\Users\kasa\.budget\db.properties` (yoksa oluştur):

```properties
db.url=jdbc:mysql://localhost:3306/posdb?useUnicode=true&characterEncoding=utf8&serverTimezone=Europe/Istanbul&allowPublicKeyRetrieval=true&sslMode=DISABLED
db.user=budget
db.password=GÜÇLÜ_BİR_SİFRE
db.pool.maxSize=15
```

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
db.user=budget
db.password=GÜÇLÜ_BİR_SİFRE
```

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

Workbench:
```sql
UPDATE kitchen_printers SET host='192.168.1.241' WHERE code='DONER';
UPDATE kitchen_printers SET host='192.168.1.242' WHERE code='FIRIN';
UPDATE kitchen_printers SET host='192.168.1.243' WHERE code='OCAK';
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
sc query MySQL93
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

### "Uygulama açılırken hata: db.properties bulunamadı"
- `C:\Users\<kullanıcı>\.budget\db.properties` mevcut mu?
- Yoksa: kurulum klasöründeki default kullanılır, ama localhost'a bakar

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

:: MySQL bağlan
mysql -u budget -h 192.168.1.10 -p posdb

:: Yedek al
mysqldump --default-character-set=utf8mb4 -u root -p posdb > posdb-backup.sql

:: Yedekten geri yükle
mysql --default-character-set=utf8mb4 -u root -p posdb < posdb-backup.sql

:: Uygulamayı yeniden başlat (process kapat, masaüstünden çift tıkla)
taskkill /F /IM javaw.exe
```

---

*Hazırlayan: Claude — Tarih: 2026-05-18*
*budgetController v1.0 için kurulum rehberi*
