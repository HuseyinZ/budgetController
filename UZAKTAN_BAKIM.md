# Uzaktan Bakım & Kontrol Kılavuzu

Restoran şehir dışında. Müşteri "ürün eklenmiyor", "yazıcı bağlanmıyor"
diyor. Yerine gitmeden nasıl tanı koyup düzeltirsin?

Bu kılavuz **3 katman** sunar: en kolay/ucuz olandan en profesyonele.

---

## 1. Katman: Uzak Masaüstü (BAŞLANGIÇ — herkes için)

**Ne işe yarar:** Kasanın ekranını uzaktan görüp tıklayarak yönetirsin.
Hem uygulamayı kullanabilir hem MySQL Workbench, Notepad, log dosyaları
gibi her şeyi açabilirsin.

### A) AnyDesk (ÖNERİLEN)
- **Ücretsiz** kişisel/küçük işletme kullanımı için
- Düşük gecikme, hızlı bağlantı
- Telefondan da bağlanabilirsin (iOS/Android uygulaması)

**Kurulum (kasada bir kez):**
1. https://anydesk.com → indir → kur
2. Açıldığında **9 haneli ID** görürsün — bu sayı seninkine bağlanacağın anahtar
3. Ayarlar → **Beklenmeyen Erişim** → şifre belirle (en az 12 karakter)
4. "Bilgisayar açıldığında AnyDesk otomatik başlat" işaretle
5. Bu ID'yi telefonuna kaydet

**Bağlanma (uzaktan):**
1. Kendi PC'nde AnyDesk → kasanın ID'sini gir → Bağlan
2. Beklenmeyen erişim şifresini gir
3. Kasanın masaüstü senin ekranında — istediğini yap

**Önemli:** AnyDesk bağlantısı internet üzerinden geçer; gizlilik için
kasaya bağlanan kişi sayısını **2 kişi (sen + ortağın)** ile sınırla.
"Bilinmeyen kullanıcı bağlandığında uyar" özelliğini aktive et.

### B) Alternatifler
- **TeamViewer** — Eski klasik, ama ücretsiz sürümünün kısıtları sıkı.
- **Chrome Remote Desktop** — Google hesabına bağlı, ücretsiz.
- **RustDesk** — Açık kaynak, kendi sunucunda da çalıştırılabilir.
- **Windows Remote Desktop (RDP)** — Sadece Pro/Enterprise edisyonlarında;
  port forwarding gerekir, **güvenlik için sakıncalı**.

---

## 2. Katman: VPN ile Direkt Erişim (ORTA SEVİYE)

**Ne işe yarar:** Sanki o restorandaki LAN'a bağlanmışsın gibi davranır.
MySQL Workbench'ten direkt DB'ye, yazıcılara, kameralara ulaşırsın.

### Tailscale (ÖNERİLEN)

Kurması en kolay VPN. Kendi sunucuna ihtiyaç yok.

**Kurulum:**
1. https://tailscale.com → bedava hesap aç (kişisel: 100 cihaza kadar bedava)
2. Kasaya `Tailscale Windows` kur, hesabına bağla
3. Her kat PC'sine de aynısını kur
4. Kendi laptop'una/telefonuna da kur
5. Hepsi otomatik aynı sanal ağda

**Bağlantı:**
```bash
# Uzaktan kasaya bağlandın diyelim, kasanın Tailscale IP'si 100.84.10.5
mysql -h 100.84.10.5 -u root -p posdb
```

MySQL Workbench'ten de aynı IP ile bağlanırsın.

**Avantajlar:**
- Hiç port açmana gerek yok (NAT problemleri yok)
- Şifrelenir, sertifikalanır
- Kim ne zaman bağlandı log tutulur
- "Çıkış" yapınca otomatik kapanır

### Alternatifler
- **WireGuard** — Daha düşük seviye, ücretsiz ama kendi konfigürasyonu yazman gerek
- **OpenVPN** — Klasik kurumsal çözüm, daha karmaşık
- **ZeroTier** — Tailscale benzeri, biraz daha eski

---

## 3. Katman: Sistem Bakımı için Erişim (İLERİ)

### A) MySQL'e Uzaktan SQL Sorgusu

VPN açıkken (Tailscale) kendi PC'nden:

```bash
# Tüm masaların durumu:
mysql -h 100.84.10.5 -u root -p posdb -e "
  SELECT d.table_no, d.status, o.id AS order_id, o.status AS order_status,
         o.created_at FROM dining_tables d
  LEFT JOIN orders o ON o.table_id = d.id
       AND o.status IN ('PENDING','IN_PROGRESS','READY')
  ORDER BY d.table_no;
"

# Bugün ne kadar ciro?
mysql -h 100.84.10.5 -u root -p posdb -e "
  SELECT SUM(amount) FROM payments
  WHERE DATE(paid_at) = CURDATE();
"

# Hangi ürün en çok satılıyor (son 7 gün)?
mysql -h 100.84.10.5 -u root -p posdb -e "
  SELECT oi.product_name, SUM(oi.quantity) AS toplam
  FROM order_items oi JOIN orders o ON o.id = oi.order_id
  WHERE o.closed_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)
  GROUP BY oi.product_name
  ORDER BY toplam DESC LIMIT 10;
"
```

### B) Log Dosyalarına Erişim

Logback ile her şey `logs/budget.log` ve `logs/errors.log`'da. İki yol:

**Yol 1: OneDrive senkronizasyonu**
Kasadaki `logs/` klasörünü OneDrive klasörü içine taşı (veya symlink).
Kendi PC'nde OneDrive aynı dosyaları gösterecek. **5 dk gecikmeli ama hep elinin altında.**

**Yol 2: Tailscale + SSH (eğer SSH server kuruluysa)**
Windows OpenSSH ile dosyaları kopyala:
```bash
scp husey@100.84.10.5:C:/Users/husey/IdeaProjects/budgetController/logs/errors.log .
```

**Yol 3: AnyDesk dosya transferi**
AnyDesk'in dosya transfer paneli ile log dosyalarını sürükle-bırak.

### C) Yazıcı Durumu Kontrolü

VPN açıkken yazıcılara ping at:
```bash
ping 100.84.10.5  # kasa
# Aynı LAN olduğu için yazıcılar da görünür:
ping 192.168.1.241  # mutfak 1
```

Yazıcı çalışıyor mu test et:
```bash
telnet 192.168.1.241 9100
# Bağlanıyorsa OK; tek karakter yaz, yazıcıdan ses geliyorsa diri.
```

### D) Uygulamayı Yeniden Başlatma

AnyDesk üzerinden:
1. Task Manager → budgetController java process'ini bul → kapat
2. Masaüstündeki başlatma kısayoluna çift tıkla

Ya da Görev Zamanlayıcısı'na bir görev koy:
- Tetikleyici: "Bağımsız Olay" (Event ID 4624 — login)
- Eylem: `java -jar C:\budget\budgetController.jar`

---

## 4. Acil Senaryolar

### "Yazıcı fiş basmıyor" çağrısı geldi

1. **AnyDesk** ile kasaya bağlan
2. `logs/errors.log` aç → "PrinterException" mesajları varsa yazıcı IP'sini kontrol et
3. `telnet 192.168.1.241 9100` ile yazıcının açık olduğunu doğrula
4. Çalışmıyorsa → mutfaktaki kişiye telefon: "Yazıcının kablosu çıkmış mı?"
5. Hala çalışmıyorsa → "Yedek yazıcı" (admin paneliden routing değiştir)

### "Sistem dondu" çağrısı geldi

1. AnyDesk ile bağlan
2. Task Manager → Java process Memory kullanımı çok yüksek mi?
3. `logs/budget.log` son 50 satırı oku → hata var mı?
4. Java process'i kapat → yeniden başlat (kullanıcılar yeniden login olur)
5. Sorun devam ediyorsa: MySQL Workbench → `SHOW PROCESSLIST` → uzun süredir
   çalışan sorgu var mı?

### "Bir kullanıcı kilitlendi"

VPN açıkken Workbench'ten:
```sql
USE posdb;
UPDATE users SET is_active = 1 WHERE username = 'ahmet';
-- veya parola sıfırla (bcrypt hash'ini admin paneliyle de yapabilirsin)
```

### "Veri kaybı oldu" — geri yükleme

1. AnyDesk ile bağlan
2. OneDrive'dan dün gece 03:00'teki yedeği seç (`posdb-2026-05-17-0300.sql`)
3. Workbench'te:
   ```sql
   DROP DATABASE posdb;
   CREATE DATABASE posdb CHARACTER SET utf8mb4 COLLATE utf8mb4_turkish_ci;
   ```
4. Komut satırında:
   ```cmd
   "C:\Program Files\MySQL\MySQL Server 9.3\bin\mysql.exe" -u root -p posdb < posdb-2026-05-17-0300.sql
   ```
5. Uygulamayı yeniden başlat

---

## 5. Düzenli Rutin Bakım Takvimi

| Sıklık | Görev |
|---|---|
| **Günlük** (otomatik) | MySQL yedeği OneDrive'a (Görev Zamanlayıcısı — bkz. BULUT_YEDEKLEME.md) |
| **Haftada bir** | `errors.log` dosyasına bak — WARN'ları gözden geçir |
| **Haftada bir** | Disk doluluk → loglar/yedekler 30 günden eski olanlar otomatik silinir mi? |
| **Ayda bir** | Tüm Windows Update + Java güvenlik yamalarını al |
| **Ayda bir** | Yazıcıların kağıt seviyesi, kabloları, bağlantısı |
| **Üç ayda bir** | Tüm garson kullanıcıların aktif olduğunu, parolaların güçlü olduğunu doğrula |
| **Altı ayda bir** | Yedekten geri yükleme provası — eski bir yedeği test sunucusuna açıp veri tutarlığını kontrol et |

---

## 6. Profesyonel İzleme (Opsiyonel)

Restoran büyürse şunlar düşünülebilir:

### a) Monitoring/Alarm
- **Uptime Kuma** (kendi sunucunda) → kasanın UP/DOWN durumunu izle
- Telefonuna push bildirim: "Kasa 5 dk'dır cevap vermiyor"

### b) Log Aggregation
- **Grafana Loki** veya **ELK** stack → tüm restoranların loglarını tek panelde topla
- Filtrele: "Bugün hangi restoran fiş basamadı?"

### c) Sürüm Yönetimi
- Yeni Java derlemesi → Git repo → CI/CD ile otomatik dağıtım
- "v1.2 sürümü hangi restoranda kurulu?" tek sorgu ile gör

Bu seviyeler küçük tek-şube restorant için **gereksiz**. Tek bir
Tailscale + AnyDesk + bulut yedek genelde yeterli.

---

## 7. Hızlı Özet

**En azından şu üçü kurulu olsun:**

1. ✅ **AnyDesk** — uzak masaüstü
2. ✅ **Tailscale** — direkt LAN erişimi (MySQL/SSH için)
3. ✅ **Bulut Yedek** (OneDrive + Görev Zamanlayıcısı — bkz. BULUT_YEDEKLEME.md)

**Bunlar ekstradan iyi olur:**
4. Logback dosya log (zaten yapılandırıldı — `logs/` klasörü kasada hazır)
5. OneDrive'da `logs/` klasörü symlink → uzaktan log incelemesi
6. Görev Zamanlayıcısı'nda gece otomatik MySQL dump

---

## Güvenlik Hatırlatması

- **AnyDesk şifresi** en az 12 karakter, karmaşık (büyük+küçük+sayı+sembol)
- **Tailscale** hesabını 2FA ile koru
- **MySQL root** parolası **asla** internete bağlı bir cihazda düz metin
- Yedeği OneDrive yerine **şifreli klasör**e (örn. Cryptomator) koymak daha güvenli
- AnyDesk **"Whitelist"** modu — sadece senin ID'nden bağlantı kabul etsin

---

*Hazırlayan: Claude — Tarih: 2026-05-18*
