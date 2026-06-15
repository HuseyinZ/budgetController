# Güvenlik Notları

Bu dosya, `budgetController` uygulamasının üretime alınmadan önce **mutlaka**
yapılması gereken güvenlik adımlarını ve operasyonel önerileri içerir.

## 1. Secret rotasyonu (ZORUNLU — deploy öncesi)

Repo'da daha önce gerçek secret commit edildiyse, sadece dosyayı silmek
**yetmez** — git geçmişinde kalır. Ayrıca **history cleanup ÖNCE değil SONRA**
yapılmalıdır: eski secret nasılsa görülmüş kabul edilmeli; önce rotate edip
yeni secret ile uygulamanın çalıştığını doğrulayın.

**Sıra (kesin):**
1. **DB şifresini rotate et** (pos_app kullanıcısı), `~/.budget/db.properties`'i güncelle.
2. **Gmail/SMTP uygulama şifresini sil ve yenile**: https://myaccount.google.com/apppasswords
3. **Admin parolasını rotate et** (`UserService.changePassword` ya da uygulama içinden).
4. **`BACKUP_PASS` env değişkenini yeni bir rastgele değere set et**; eski yedeklerin restore'u için eski parolayı güvenli yerde sakla.
5. **Uygulamayı yeni secret'larla çalıştırıp doğrula** — login, e-posta, yedek üretimi sırasıyla.
6. **Git geçmişini temizle**:
   ```bash
   bfg --delete-files db.properties
   bfg --delete-files email-config.properties
   git reflog expire --expire=now --all && git gc --prune=now --aggressive
   git push --force origin master
   ```
7. **Tüm collaborator'lara yeniden clone bildirisi gönder** (force push'tan sonra eski clone'lar bozulur).
8. **GitHub'da Secret Scanning + Push Protection + Dependabot'u aç** (Settings → Code security and analysis).
9. **CI/local pre-commit**: `gitleaks detect --source . --verbose` veya `detect-secrets`.

## 2. Konfigürasyon dosyaları

Gerçek üretim ayarları **repo'ya değil**, kullanıcı dizinine konur:

```
Windows: C:\Users\<kullanıcı>\.budget\db.properties
Windows: C:\Users\<kullanıcı>\.budget\email-config.properties
Linux/Mac: ~/.budget/db.properties
Linux/Mac: ~/.budget/email-config.properties
```

Örnek için `src/main/resources/*.properties.example` dosyalarına bakın.

## 3. Üretim ortam değişkenleri (ZORUNLU)

```bash
# Üretim modunu aç → seed admin için env zorunlu hale gelir
BUDGET_ENV=production

# Seed admin parolası (ilk açılışta admin kullanıcısı yoksa kullanılır)
BUDGET_ADMIN_SEED_PASSWORD=<güçlü-rastgele-parola>

# DB bağlantısı (db.properties dışında env de geçilebilir)
DB_URL=jdbc:mysql://localhost:3306/posdb?useSSL=true&requireSSL=true
DB_USER=pos_app
DB_PASS=<güçlü-parola>

# Yedek şifrelemesi — verilmezse yedek düz .sql kalır
BACKUP_PASS=<güçlü-rastgele-parola>
# Üretimde şifresiz yedeklere izin VERMEK için:
BACKUP_ALLOW_PLAINTEXT=false

# REST API
# Önerilen senaryo (en sade ve güvenli): uygulama localhost'ta HTTP dinler,
# dış erişim Tailscale Serve üzerinden HTTPS. Tailscale TLS'i sonlandırıp
# 127.0.0.1:7070'e plain HTTP gönderir.
API_HTTP_ENABLED=true
API_HTTPS_ENABLED=false
API_BIND_ADDRESS=127.0.0.1           # SADECE local; Tailscale dışından erişim yok.
# CORS — scheme+host+port TAM eşleşmeli. Tailscale 7443'te yayınlanıyorsa:
API_ALLOWED_ORIGINS=https://<cihaz-adi>.<tailnet>.ts.net:7443
# Eğer Tailscale 443'te yayınlanıyorsa (Funnel public veya HTTPS default):
# API_ALLOWED_ORIGINS=https://<cihaz-adi>.<tailnet>.ts.net
API_MAX_BODY_BYTES=262144
API_LOGIN_RATE_PER_MIN=20
API_PAGE_DEFAULT=50
API_PAGE_MAX=500
```

> **Uyarı:** `API_HTTPS_ENABLED=true` ile Tailscale Serve `--https` aynı anda
> kullanılırsa **çakışma olur** — Tailscale TLS sonlandırması yaparken backend
> de TLS bekler. Aşağıdaki §7'deki Tailscale Serve örneğini kullanıyorsanız
> uygulamanın HTTP modunda kalması GEREKİR (`API_HTTP_ENABLED=true`,
> `API_HTTPS_ENABLED=false`).

## 4. Yedek şifreleme ve restore

### Şifreleme formatı

`BACKUP_PASS` env değişkeni set edildiğinde her yedek dosyası **AES-256-GCM**
ile şifrelenir: `budget-YYYYMMDD-HHmmss.sql.enc`.

* Format: `16 byte salt | 12 byte IV | ciphertext (128-bit GCM tag dahil)`
* Anahtar: PBKDF2-HMAC-SHA256(passphrase, salt, **600 000 iter**, 256-bit)
* OWASP 2023 Password Storage Cheat Sheet ile uyumlu.

### Çözme (decrypt)

`tools.BackupDecrypt` CLI ile:

```bash
# Önerilen — parola env'den (bash history'de görünmez)
BACKUP_PASS=<parola> \
java -cp target/budgetController-*.jar tools.BackupDecrypt \
     backups/budget-20260101-120000.sql.enc /tmp/restored.sql

# Veya interaktif (TTY parola sorar)
java -cp target/budgetController-*.jar tools.BackupDecrypt \
     backups/budget-20260101-120000.sql.enc /tmp/restored.sql

# Restore
mysql -u root -p posdb < /tmp/restored.sql

# DÜZ METİN dosyayı sil — KESİNLİKLE
shred -u /tmp/restored.sql        # Linux/Mac
del /F /Q C:\tmp\restored.sql      # Windows
```

### Aylık restore drill (ZORUNLU)

Yedeklerin restore edilemediği yedek = **yedek değildir**. Her ayın 1'inde
test edin:

```bash
# 1. Test makinesinde (kasaya değil) boş bir DB oluştur
mysql -u root -p -e "CREATE DATABASE posdb_drill"

# 2. Son yedeği çöz ve restore et
BACKUP_PASS=<parola> java -cp budgetController.jar \
    tools.BackupDecrypt budget-YYYYMMDD-*.sql.enc /tmp/drill.sql
mysql -u root -p posdb_drill < /tmp/drill.sql

# 3. Tablo sayısı + satır sayısı kontrolü
mysql -u root -p posdb_drill -e "
    SELECT COUNT(*) AS table_count FROM information_schema.tables WHERE table_schema='posdb_drill';
    SELECT COUNT(*) AS users   FROM users;
    SELECT COUNT(*) AS orders  FROM orders;
    SELECT COUNT(*) AS payments FROM payments;
"

# 4. Çıktıları logs/restore-drill-YYYY-MM.txt dosyasına kaydet
# 5. Temizle
mysql -u root -p -e "DROP DATABASE posdb_drill"
shred -u /tmp/drill.sql
```

### BACKUP_PASS kaybı

* **`BACKUP_PASS` kaybedildiğinde tüm şifreli yedekler okunamaz hale gelir.**
* Parolayı **şifre yöneticisi** (1Password, Bitwarden) veya **fiziksel kasa**
  içinde saklayın.
* Parola değişimi durumunda eski yedekleri eski parolayla saklayın; yeni
  yedekler yeni parolayla şifrelenir. Geçiş kayıtlarını SECURITY.md'ye işleyin.

### Off-site kopyası (önerilen)

```bash
# Tailscale üzerinden ikinci bir makineye nightly rsync
rsync -avz --delete \
    backups/*.sql.enc \
    backup-host:/srv/budget-backups/
```

Hem yangın/hırsızlık hem ransomware koruması sağlar.

## 5. DB kullanıcısı: least privilege ve TLS

### Kullanıcı

`root` kullanma. POS için ayrı bir kullanıcı oluştur:

```sql
CREATE USER 'pos_app'@'localhost' IDENTIFIED BY '<güçlü-parola>'
    REQUIRE SSL;  -- yalnız TLS üzerinden erişime izin ver
GRANT SELECT, INSERT, UPDATE, DELETE ON posdb.* TO 'pos_app'@'localhost';
-- Migration / SchemaPatcher için (ayrı bir kullanıcı tercih edilebilir):
GRANT ALTER, INDEX, REFERENCES, CREATE ON posdb.* TO 'pos_app'@'localhost';
FLUSH PRIVILEGES;
```

`DROP DATABASE`, `GRANT`, `SHUTDOWN` yetkileri verilmemelidir.

### TLS bağlantı zorlama

MySQL Connector/J 8.x'te modern parametre **`sslMode`** kullanılır (eski
`useSSL`/`requireSSL`/`verifyServerCertificate` artık deprecated alias):

```properties
# db.properties — DB başka makinedeyse veya LAN üstündeyse TLS şart
db.url=jdbc:mysql://db-host:3306/posdb?\
useUnicode=true&characterEncoding=utf8&serverTimezone=Europe/Istanbul&\
sslMode=VERIFY_IDENTITY&\
trustCertificateKeyStoreUrl=file:/etc/mysql/client-truststore.jks&\
trustCertificateKeyStorePassword=<truststore-parolası>
```

`sslMode` değer rehberi:

| Değer | Kullanım |
|---|---|
| `DISABLED` | Hiç TLS yok — sadece tek makinede `127.0.0.1` üzerinden olabilir |
| `PREFERRED` | TLS varsa kullan, yoksa düz bağlan — **güvensiz** (downgrade) |
| `REQUIRED` | TLS zorunlu, ama sertifika doğrulanmıyor — orta seviye |
| `VERIFY_CA` | TLS + CA doğrulaması — iyi |
| `VERIFY_IDENTITY` | TLS + CA + hostname doğrulaması — **önerilen** |

Aynı makinede `localhost`/`127.0.0.1` üzerinden MySQL kullanılıyorsa
TLS kritik değil (`DISABLED` kabul edilebilir). Başka makinedeyse veya
ağ üstündeyse `VERIFY_IDENTITY` zorunlu. Hostname doğrulaması self-signed
sertifikalarda sorun çıkarıyorsa `VERIFY_CA` kullanın.

## 6. Windows Firewall

Sadece LAN veya Tailscale subnet'ten 7443 portuna izin ver:

```powershell
# Tailscale subnet'inden (örnek 100.64.0.0/10) izin
New-NetFirewallRule -DisplayName "budgetController API (Tailscale)" `
    -Direction Inbound -Protocol TCP -LocalPort 7443 `
    -RemoteAddress 100.64.0.0/10 -Action Allow

# Yerel ağdan (örnek 192.168.1.0/24) izin
New-NetFirewallRule -DisplayName "budgetController API (LAN)" `
    -Direction Inbound -Protocol TCP -LocalPort 7443 `
    -RemoteAddress 192.168.1.0/24 -Action Allow

# Diğer tüm trafiği reddet
New-NetFirewallRule -DisplayName "budgetController API (block all other)" `
    -Direction Inbound -Protocol TCP -LocalPort 7443 -Action Block
```

## 7. HTTPS / reverse proxy

**Önerilen yol — Tailscale Serve:**
```bash
# Tailscale dış HTTPS'i sonlandırır, içeride 127.0.0.1:7070'e HTTP gönderir.
tailscale serve --bg --https=7443 http://127.0.0.1:7070
```
Bu senaryoda uygulama **HTTP modunda** çalışmalı:
```
API_HTTP_ENABLED=true
API_HTTPS_ENABLED=false
API_BIND_ADDRESS=127.0.0.1   # SADECE local; dış erişim Tailscale'den geçer
```
Tailscale tarayıcı sertifika yönetimini otomatik yapar; uygulamada keystore
yönetmek gerekmez. Public internet'e maruziyet **yok**, sadece Tailscale ağına
katılmış cihazlar erişebilir.

Eğer Funnel ile dış internete açacaksanız (genellikle önerilmez):
```bash
tailscale funnel --bg --https=443 http://127.0.0.1:7070
```

**Alternatif — Caddy / Nginx + Let's Encrypt:** kendi makinanızda reverse proxy
çalıştırırsanız aynı prensip — uygulama HTTP, proxy TLS sonlandırır.

**Uygulamanın kendi TLS'i** (`API_HTTPS_ENABLED=true`) sadece reverse proxy
olmayan ortamlar için (yerel ağ, geliştirme) anlamlıdır; sertifika dağıtımı
manuel olur.

## 8. CVE / supply-chain taraması

```bash
# OWASP Dependency-Check (ilk çalıştırma NVD veritabanını indirir, 5-15 dk)
mvn org.owasp:dependency-check-maven:check

# Rapor: target/dependency-check-report.html
```

CVSS ≥ 7 bulunursa build başarısız olur. Yanlış pozitifler
`owasp-suppressions.xml`'e **gerekçeli ve süreli** olarak eklenir
(her suppress'e `until="YYYY-MM-DDZ"`, varsayılan 3 ay).

### NVD API key (önerilen)

Anahtarsız NVD update ~30 dk sürer. Ücretsiz key ile ~2 dk:

```bash
# 1. https://nvd.nist.gov/developers/request-an-api-key
# 2. ~/.m2/settings.xml veya pom.xml'e:
mvn org.owasp:dependency-check-maven:check \
    -DnvdApiKey=<api-key>

# Veya kalıcı: ~/.m2/settings.xml
# <profiles><profile><id>owasp</id><properties>
#   <nvdApiKey>YOUR_KEY</nvdApiKey>
# </properties></profile></profiles>
```

### CI'da çalıştırma (GitHub Actions)

```yaml
# .github/workflows/security.yml
name: security
on:
  push:    { branches: [main] }
  pull_request: {}
  schedule: [{ cron: '0 4 * * *' }]   # her gece 04:00 UTC

jobs:
  dependency-check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21' }
      - uses: actions/cache@v4
        with:
          path: ~/.m2/repository/org/owasp/dependency-check-data
          key: dep-check-${{ runner.os }}
      - name: OWASP Dependency-Check
        run: |
          mvn -B org.owasp:dependency-check-maven:check \
              -DnvdApiKey=${{ secrets.NVD_API_KEY }}
      - uses: actions/upload-artifact@v4
        if: always()
        with:
          name: dependency-check-report
          path: target/dependency-check-report.html

  secret-scan:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0 }
      - uses: gitleaks/gitleaks-action@v2
        env:
          GITLEAKS_LICENSE: ${{ secrets.GITLEAKS_LICENSE }}
```

### Suppress süre uyarısı

`<suppress until="2026-09-06Z">` ile yazılır → tarih geçtiğinde plugin suppress'i
**otomatik dikkate almaz**, build CVE varsa fail eder. Bu sayede re-evaluation
unutulmaz.

### Pre-commit secret scanning (yerel)

Her commit'te otomatik gitleaks + detect-secrets çalıştırmak için
`.pre-commit-config.yaml` repo'da hazır. Kurulum:

```bash
# Linux/Mac
pip install pre-commit
pre-commit install
pre-commit install --hook-type pre-push

# Windows (PowerShell)
pip install pre-commit
pre-commit install
```

İlk kurulumdan sonra:

```bash
# Tüm repo'yu tara (mevcut dosyalarda secret var mı kontrol)
pre-commit run --all-files

# detect-secrets baseline oluştur (gerekirse yanlış pozitifleri işaretle)
pip install detect-secrets
detect-secrets scan > .secrets.baseline
git add .secrets.baseline
```

Acil durumda atlamak:
```bash
git commit --no-verify           # tüm hook'ları atla (önerilmez)
SKIP=gitleaks git commit -m "..."
```

### SBOM (Software Bill of Materials)

CycloneDX standardında SBOM her `mvn package` ile otomatik üretilir:

```bash
mvn package
# → target/bom.json  ve  target/bom.xml

# Sadece SBOM (test atlanır):
mvn org.cyclonedx:cyclonedx-maven-plugin:makeAggregateBom
```

SBOM'u Dependency-Track, Sonatype Nexus IQ, GitHub Dependency Graph gibi
araçlara yükleyerek **runtime CVE monitoring** ve **lisans uyumluluk** sağlanır.

## 9. Loglama ve audit

* `logs/budget-*.log` ve `logs/errors-*.log` dosyaları PII (kullanıcı adı,
  e-posta) içerir. **Şifre/token/kart YOK** — `service.util.Mask` ile maskelenir.
* **`logs/audit-*.log`** ayrı bir dosyada güvenlik olayları (1 yıl saklanır):
  `auth.success`, `auth.failure`, `auth.logout`, `auth.session.idle`,
  `user.password.changed`, `user.created`, `user.deleted`, `user.role.changed`,
  `product.deleted`, `payment.refund`, `backup.created`, `backup.restore.attempt`,
  `settings.changed`. Olay isimlendirme `<alan>.<olay>` formatında — SIEM'e
  ileride göndermek için hazır.
* Loglar gitignore'da; commit edilmemeli. Dışarı export edilirse önce
  maskelenmiş kopyası alınmalı.
* Windows'ta `logs/` klasörü için NTFS izinleri **sadece uygulama kullanıcısı**
  yazabilecek şekilde sıkılaştırılmalı (`icacls logs /inheritance:r /grant:r
  "%USERNAME%:(OI)(CI)F"`).

## 10. PWA notları

* Auth token `sessionStorage`'da saklanır (sekme kapanınca silinir).
  `localStorage` kullanımı kaldırıldı.
* **İki ayrı timeout:**
  * Absolute TTL: 8 saat (token oluşturulduğu andan itibaren).
  * Idle timeout: 30 dakika (son aktif istek anından itibaren).
  * Token çalınsa bile en fazla 30 dk inaktivite sonrası geçersiz olur.
* Logout endpoint'i (`POST /api/logout`) token'ı sunucudan iptal eder.

### HTTP güvenlik header'ları (her yanıt)

Aşağıdaki header'lar `ApiServer.applySecurityHeaders` tarafından her API +
statik dosya yanıtına eklenir:

| Header | Değer | Etki |
|---|---|---|
| `X-Content-Type-Options` | `nosniff` | MIME-sniff'i kapatır |
| `X-Frame-Options` | `DENY` | Clickjacking koruması |
| `Referrer-Policy` | `strict-origin-when-cross-origin` | Referer sızıntısını azaltır |
| `Strict-Transport-Security` | `max-age=31536000; includeSubDomains` | **Sadece HTTPS modunda** — TLS'i zorlar |
| `X-Request-Id` | UUID | İstek izlenebilirliği |

### Content-Security-Policy (CSP) — gerçek üretim değeri

Kodda `ApiServer.applySecurityHeaders` içinde set edilen tam CSP:

```
Content-Security-Policy:
  default-src 'self';
  script-src 'self';
  style-src 'self' 'unsafe-inline';
  img-src 'self' data:;
  object-src 'none';
  base-uri 'self';
  frame-ancestors 'none'
```

**`style-src` 'unsafe-inline' notu** (geçici): PWA bazı yerlerde inline
`style="..."` attribute'leri kullanıyor (örn. error mesajlarının kırmızı
rengi, dynamic grid ölçüleri). CSP `'unsafe-inline'` olmadan bunlar render
edilmez. Sonraki bakım turunda inline style'ların hepsi CSS class'larına
taşınıp `'unsafe-inline'` **kaldırılacak**. Bu, inline style üzerinden
XSS yüzeyini sıfırlar.

Diğer direktifler **sıkı**:
* `script-src 'self'` — harici JS yüklenmez, inline `<script>` çalışmaz
* `object-src 'none'` — `<object>`/`<embed>` tamamen yasak
* `frame-ancestors 'none'` — clickjacking imkansız
* `base-uri 'self'` — `<base>` tag injection engellenir
* `default-src 'self'` — başka tüm kaynak tipleri için fallback (only same-origin)

## 11. Şifre saklama

* Kullanıcı şifreleri **asla düz metin** olarak saklanmaz.
* Algoritma: **BCrypt** (`org.mindrot.jbcrypt`), `BCrypt.gensalt()` ile
  her şifre için ayrı salt. Cost factor default 10.
* `UserService.login` sadece `$2` ile başlayan (BCrypt) hash'leri kabul eder;
  başka biçimde hash veya plaintext kayıt giriş için **red edilir**.
* Şifreler hiçbir log dosyasına yazılmaz; bcrypt hash'i de loglanmaz.
* Önerilen alternatifler: Argon2id (üst seviye, daha modern). Geçiş yapmak
  istenirse `UserService.login` içine "yeni hash formatına yükselt" branch'i
  eklenebilir (gradual rehashing).

## 12. Bilinen sınırlar / TODO

* **Javalin 6 + Jetty 11 EOL.** Deploy sonrası ilk bakım turunda Javalin 7 +
  Jetty 12 migration yapılacak (Java 17+ gerek; bizde zaten 22+). Mevcut yapı
  kısa vadede `bind 127.0.0.1 + Tailscale + reverse proxy` mitigation'ı ile
  güvence altına alındı (owasp-suppressions.xml'de gerekçeli suppress).
* **Java 22 non-LTS.** Üretim için **Java 21 LTS** (uzun süreli destek) veya
  **Java 25 LTS** önerilir. Javalin 7 zaten Java 17+ yeterli buluyor.
  Geçiş için `pom.xml`'de `<maven.compiler.release>21</maven.compiler.release>`.
* Session store şu an in-memory. Birden fazla JVM instance'a ölçeklenirse
  Redis/DB'ye taşınmalı (kod TODO ile işaretli).
* CSP başlığı `'unsafe-inline'` içerir (PWA inline style kullanıyor). Tüm
  inline style'lar bir CSS dosyasına taşınırsa kaldırılabilir.
* 2FA (TOTP / Google Authenticator) henüz desteklenmiyor — yüksek değerli
  admin hesabı için gelecekte eklenmeli.
* Parola politikası **bilinçli olarak** uygulanmadı (kullanıcı kapsam dışı
  bıraktı). Min uzunluk / karmaşıklık eklenmek istenirse `UserService.changePassword`
  içine validator eklemek yeterli olur.
* **Restore drill (zorunlu — operasyonel)**: ayda en az bir kez yedekten geri
  yükleme testi yapın (bkz. §4). `BACKUP_PASS`'ı kaybetmek = tüm yedekleri
  kaybetmek; parolayı şifre yöneticisine / kasaya kaydedin.

## 13. Acil müdahale

Şüpheli erişim / sızıntı tespit edilirse:
1. `API_HTTP_ENABLED=false` ve `API_HTTPS_ENABLED=false` ile API'yi durdur.
2. DB şifresini değiştir.
3. Tüm aktif kullanıcı oturumlarını sıfırla: uygulamayı yeniden başlatın
   (in-memory session store boşalır).
4. Admin parolasını rotate et.
5. SMTP uygulama şifresini Google/Microsoft hesabından iptal et.
6. Yedeklerin (şifrelenmemiş `.sql` dosyalar) sızdığından şüpheleniliyorsa
   `BACKUP_PASS` ile yeni şifreli yedeğe geçin ve eski düz yedekleri silin.
