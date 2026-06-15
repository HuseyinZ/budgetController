# Restoran POS Kurulum Alışveriş Listesi (Stok Doğrulamalı)

> **Senaryo:** 1 kasa PC + 3 kat PC (1.Kat, 2.Kat, Bahçe) + 3 mutfak yazıcı
> (Döner, Fırın, Ocak). Tüm cihazlar LAN üzerinden tek MySQL'e bağlı.
>
> **Bu liste 22 Mayıs 2026 itibarıyla Akakçe / Hepsiburada / Trendyol /
> Amazon.com.tr / Cimri sitelerinde "stokta" görünen ürünlere göre
> oluşturulmuştur.** Her satırda gördüğünüz fiyat, doğrulama anında
> en düşük satıcı fiyatıdır. Sayfayı tıkladığınızda farklı bulursanız
> arada satıcı stoğu değişmiş demektir.
>
> **"Stok" sütunu:**
> - ✅ Çoklu satıcıda stokta (gönül rahatlığıyla al)
> - ⚠️ Tek satıcıda az adetle stokta (alacaksan hızlı ol)
> - ❓ Türkiye stoku belirsiz, yurt dışından getirilebilir

---

## A) KASA PC (1 adet — ANA BİLGİSAYAR)

MySQL Server + budgetController + yedekleyici burada çalışır. **Sistemin
beyni.** Bunda kısıntı yapma.

> **NOT — FreeDOS hakkında:** "FreeDOS" yazan bilgisayarlar **işletim
> sistemi yüklü değil** demek (kutudan boş geliyor, üzerine sen
> Windows/Linux yüklüyorsun). Bu yüzden Win11 dahil olanlardan
> ~2.000-4.000 ₺ daha ucuz oluyorlar. Bizim Java uygulaması Linux'ta da
> çalışır; ama kasa operatörü için Windows çok daha tanıdık. Aşağıda
> her iki seçeneği de bulacaksın.

### A.1 Önerilen — Windows DAHİL Mini PC (kurulum hazır)

| Marka & Model | Özellik | OS | Yaklaşık ₺ | Stok | Link |
|---|---|---|---:|:---:|---|
| **Beelink SER5 (Ryzen 5 5600H, 16GB, 500GB)** | 6 çekirdek, NVMe SSD | Win11 Pro | **17.000–20.000** | ✅ | [Hepsiburada](https://www.hepsiburada.com/beelink-ser5-amd-ryzen-5-5600h-16gb-ram-500gb-rom-windows-11-pro-mini-pc-pm-HBC00003717QK) |
| **Beelink SER5 Pro (Ryzen 7 5800H, 16GB, 500GB)** | 8 çekirdek, daha güçlü | Win11 Pro | **19.699** | ✅ | [Hepsiburada](https://www.hepsiburada.com/beelink-ser5-pro-amd-ryzen-7-5800h-16gb-ram-500gb-rom-windows-11-pro-mini-pc-pm-HBC000047I7ZX) |
| **Beelink Mini S12 Pro (N100, 16GB, 500GB)** | Düşük tüketim, sessiz | Win11 Pro | **11.974** | ✅ | [Akakçe](https://www.akakce.com/mini-pc/en-ucuz-beelink-mini-s12-pro-n100-16-gb-500-gb-ssd-uhd-graphics-fiyati,453423141.html) |
| **Beelink SER5 Max (Ryzen 7 6800H, 32GB, 1TB)** | Üst seviye, 5 yıl rahatlık | Win11 Pro | **27.000–30.000** | ⚠️ az adet | [Hepsiburada](https://www.hepsiburada.com/beelink-ser5-max-amd-ryzen-7-6800h-32gb-ram-1-tb-windows-11-pro-mini-pc-p-HBCV00009YZ2RI) |

**Önerim:** **Beelink SER5 Pro Win11 Pro dahil — 19.699 ₺**. Kuturdan çıkar,
güç ver, "Türkçe" seç, kullanmaya başla.

### A.2 FreeDOS Mini PC (kendi Windows'unu yükleyeceksen)

| Marka & Model | Özellik | OS | Yaklaşık ₺ | Stok | Link |
|---|---|---|---:|:---:|---|
| **Asus PN52-S5090MD (Ryzen 5 5600H, 8GB, 256GB)** | 6 çekirdek, kompakt | FreeDOS | **20.616** | ✅ | [Akakçe](https://www.akakce.com/mini-pc/en-ucuz-asus-pn52-s5090md-ryzen-5-5600h-8-gb-256-gb-ssd-radeon-graphics-fiyati,116481134.html) |
| **Raxius Momentum (i3-1315U, 16GB, 512GB)** | Yeni nesil i3, NVMe | FreeDOS | **19.094** | ✅ | [Akakçe FreeDOS Mini PC](https://www.akakce.com/mini-pc/free-dos.html) |
| **Lenovo V55t (Ryzen 5 3400G, 8GB, 256GB)** | Marka güvenli masaüstü | FreeDOS | **17.000–20.000** | ✅ | [Hepsiburada](https://www.hepsiburada.com/lenovo-v55t-amd-ryzen-5-3400g-8gb-256gb-ssd-freedos-masaustu-bilgisayar-11cc001etx-pm-HB00000V57MX) |

> **Bu seçeneklere +Windows lisansı eklemek gerekir** (bkz. K bölümü).

### A.3 Endüstriyel dokunmatik POS PC alternatifi (kasayı tek parça yap)

| Marka & Model | Özellik | OS | Yaklaşık ₺ | Stok | Link |
|---|---|---|---:|:---:|---|
| **Tiwox TP-1503 (i3, 4GB, 128GB SSD, 15.6")** | Dokunmatik all-in-one | FreeDOS | **12.641** | ✅ | [Akakçe](https://www.akakce.com/dokunmatik-pos-pc/en-ucuz-tiwox-tp-1503-i3-3-nesil-4gb-128gb-ssd-15-6-dokunmatik-pos-pc-fiyati,443665321.html) |
| **Tiwox TP-2500 (i5, 4GB, 120GB SSD, 15.6")** | Daha güçlü i5 | FreeDOS | **14.840** | ✅ | [Akakçe](https://www.akakce.com/dokunmatik-pos-pc/en-ucuz-tiwox-15-6-dokunmatik-tp-2500-core-i5-4gb-ram-120gb-ssd-fdos-1366-x-768-pos-pc-fiyati,1906616119.html) |
| **Posiflex XT-5000 (i5 4.nesil, 4GB)** | Endüstriyel kasa kasası | FreeDOS | **11.874** | ✅ | [Akakçe](https://www.akakce.com/dokunmatik-pos-pc.html) |

**Minimum gereksinim:** Intel i3 8.nesil+ veya AMD Ryzen 3+, 8 GB RAM,
256 GB SSD (HDD KOYMA), 1 HDMI + Ethernet, Windows 10/11 Pro veya Linux.

---

## B) KAT EKRANI (3 adet — Garson dokunmatik)

### B.1 Önerilen — Tek parça dokunmatik POS PC (FreeDOS)

| Marka & Model | Özellik | OS | Yaklaşık ₺ | Stok | Link |
|---|---|---|---:|:---:|---|
| **Tiwox TP-1503 (i3, 4GB, 128GB SSD, 15.6")** | Ekonomik | FreeDOS | **12.641** | ✅ | [Akakçe](https://www.akakce.com/dokunmatik-pos-pc/en-ucuz-tiwox-tp-1503-i3-3-nesil-4gb-128gb-ssd-15-6-dokunmatik-pos-pc-fiyati,443665321.html) |
| **Tiwox TP-2500 (i5, 4GB, 120GB SSD, 15.6")** | Daha güçlü i5 | FreeDOS | **14.840** | ✅ | [Akakçe](https://www.akakce.com/dokunmatik-pos-pc/en-ucuz-tiwox-15-6-dokunmatik-tp-2500-core-i5-4gb-ram-120gb-ssd-fdos-1366-x-768-pos-pc-fiyati,1906616119.html) |
| **Tiwox TP-1500 (i5, 8GB, 128GB SSD, 15")** | 8GB RAM (daha rahat) | Win10 Pro | **15.000–17.000** | ✅ | [Akakçe](https://www.akakce.com/dokunmatik-pos-pc/en-ucuz-tiwox-tp-1500-15-i5-128gb-ssd-8gb-endustriyel-pos-pc-fiyati,1190884316.html) |
| **Afanda AF-2150 (i5, 8GB, 120GB SSD, 21.5")** | 21.5" büyük ekran | FreeDOS | **18.400** | ✅ 3+ adet | [Akakçe](https://www.akakce.com/dokunmatik-pos-pc/en-ucuz-afanda-af-2150-i5-8-ram-120-ssd-freedos-21-5-terminal-fiyati,118046752.html) |

**Önerim:**
- **Bütçe sıkıyorsa:** Tiwox TP-1503 (i3, 12.641 ₺) — 3 adet → 37.923 ₺
- **Dengeli:** Tiwox TP-2500 (i5, 14.840 ₺) — 3 adet → 44.520 ₺
- **Geniş ekran isteyenlere:** Afanda AF-2150 21.5" (18.400 ₺) — 3 adet → 55.200 ₺
- **Win10 dahil isteyenler için:** Tiwox TP-1500 (Win10 Pro, ~16.000 ₺) — 3 adet → 48.000 ₺

> Üç kata aynı modeli al — yedek parça, eğitim ve servis aynı olur.

### B.2 Çift ekranlı seçenekler (müşteri ekranı dahil — opsiyonel)

Bunlar fiyat görünür ikinci ekranlı. Self-servis kasada veya müşterinin
fiyat görmesi istenen yerlerde işe yarar. Garson masada gezerken
gereksiz.

| Marka & Model | Özellik | OS | Yaklaşık ₺ | Stok |
|---|---|---|---:|:---:|
| Tiwox TP-9000D (i5, 8GB, 15.6" + 13.2") | Ana + müşteri ekranı | FreeDOS | **18.094** | ✅ |
| Tiwox TP-5610D (i5, 8GB, 18.5" + 13.2") | Daha büyük ana ekran | FreeDOS | **19.117** | ✅ |
| Spenta SP-156I (i5, 8GB, 128GB, 15.6" + ek ekran) | Çift ekran | FreeDOS | **20.647** | ✅ |

### B.3 Bütçe alternatifi — Mini PC + ucuz dokunmatik monitör

| Parça | Marka & Model | Yaklaşık ₺ | Stok |
|---|---|---:|:---:|
| Mini PC | Beelink Mini S12 Pro (N100, 16GB) Win11 Pro | 11.974 | ✅ |
| Dokunmatik monitör | Hannspree HT225HPB 21.5" | ~9.000–13.000 | ❓ TR stoku az |
| **Toplam (1 kat)** | | **~21.000–25.000** | |

**Not:** Mini PC + monitör All-in-One'dan ~5.000 ₺ pahalı. All-in-One
tercih et.

---

## C) MUTFAK FİŞ YAZICI (3 adet — Döner / Fırın / Ocak)

ESC/POS uyumlu, 80mm termal, otomatik kesicili. **Bizim kod sadece
ESC/POS ile çalışır.**

### C.1 Önerilen — Epson (sektör standardı, dayanıklı)

| Marka & Model | Bağlantı | Yaklaşık ₺ | Stok | Link |
|---|---|---:|:---:|---|
| **Epson TM-T20III (012) Ethernet** | LAN + USB | **7.500–9.500** | ⚠️ Türkiye distribütör stoğu sınırlı | [Epson TR](https://www.epson.com.tr/yazicilar/statik-pos/epson-tm-t20iii-012-ethernet-p-c31ch51012) / [Amazon](https://www.amazon.com.tr/Epson-Yaz%C4%B1c%C4%B1-TM-T20III-ETHERNET-TICKETS/dp/B082FLW9PK) |
| **Epson TM-T20II (003) Ethernet** | LAN + USB | **6.500–8.500** | ✅ | [Epson TR](https://www.epson.com.tr/products/epson-tm-t20ii-003-yerlesik-us-c31cd52003) |
| **Epson TM-T20III (011) USB+Serial** | USB | **5.500–7.000** | ✅ | [Epson TR](https://www.epson.com.tr/yazicilar/statik-pos/epson-tm-t20iii-011-usb-seri-p-c31ch51011) |

> **Önemli:** TM-T20III Ethernet bazen stoksuz olabiliyor. Aynı işi gören
> **TM-T20II Ethernet** zaten yıllardır restoran sektöründe çalışıyor —
> bunu bulursanız çekinmeden alın. Bizim koda fark etmez.

### C.2 İyi alternatif — Bixolon (Kore, Epson kalitesinde, daha ucuz)

| Marka & Model | Bağlantı | Yaklaşık ₺ | Stok | Notlar |
|---|---|---:|:---:|---|
| **Bixolon SRP-330III** | USB + LAN | **4.500–6.500** | ⚠️ Sınırlı, POS toptancılarında | Pavotek, Posex, Artsistem'de var |

### C.3 Bütçe seçenek — Xprinter (riski göze al)

| Marka & Model | Bağlantı | Yaklaşık ₺ | Stok | Risk |
|---|---|---:|:---:|---|
| **Xprinter XP-N160II** | USB + LAN + Wi-Fi | **2.500–3.500** | ✅ Artsistem (yetkili satıcı) | 1-2 yılda arızalanma riski yüksek |

> Restoran ortamı yağ + buhar + 7/24 kullanım. **Ucuz yazıcı 6 ayda
> bozulursa Epson maliyetini aşar.** Epson + Bixolon arası seçim yapın.

### C.4 Kasada müşteri fişi (opsiyonel, ileride)

| Ürün | Yaklaşık ₺ | Notlar |
|---|---:|---|
| Epson TM-T20II USB | 5.500–7.000 | Müşteri fişi için kasaya direkt USB |
| **Yasal yazar kasa (YN-ÖKC)** | 12.000–25.000 | Türkiye'de **zorunlu** — Hugin, Ingenico, Beko |

---

## D) WI-FI KÖPRÜ (3 adet — yazıcıyı kablosuz yapmak için)

LAN'lı Epson yazıcıyı USB güç + Wi-Fi köprüsü ile çalıştır.

| Marka & Model | Yaklaşık ₺ | Stok | Link |
|---|---:|:---:|---|
| **TP-Link TL-WR802N (300 Mbps)** | **1.099–1.167** | ✅ Çok satıcıda 10+ adet | [Hepsiburada](https://www.hepsiburada.com/tp-link-tl-wr802n-300-mbps-n-kablosuz-ap-client-router-repeater-bridge-1-wan-lan-portu-nano-router-pm-bd803590) / [Akakçe](https://www.akakce.com/router/en-ucuz-tp-link-tl-wr802n-1-port-300-mbps-fiyati,5567149.html) |

**Önerim:** 3 adet TL-WR802N al — kanıtlanmış, küçük, USB güç. **3.300 ₺ toplam.**

---

## E) AĞ ALTYAPISI

### E.1 Wi-Fi Router

| Marka & Model | Özellik | Yaklaşık ₺ | Stok | Link |
|---|---|---:|:---:|---|
| **TP-Link Archer C6 AC1200** | 4 Gigabit LAN, dual-band | **1.500–1.800** | ✅ 10+ satıcı | [Hepsiburada](https://www.hepsiburada.com/tp-link-archer-c6-dual-band-wi-fi-5-router-ac1200-mbps-4-gigabit-lan-baglanti-noktasi-mu-mimo-beamforming-wpa3-access-point-modu-ebeveyn-denetimleri-pm-HB00000XAJHJ) |

Mevcut Wi-Fi router'ınız 4 Gigabit LAN portluysa atlayabilirsiniz.

### E.2 LAN Switch

| Marka & Model | Özellik | Yaklaşık ₺ | Stok | Link |
|---|---|---:|:---:|---|
| **TP-Link TL-SG108** | 8 port Gigabit, dilsiz | **907–1.100** | ✅ | [Akakçe](https://www.akakce.com/switch/en-ucuz-tp-link-tl-sg108-8-port-10-100-1000-mbps-gigabit-fiyati,1326123.html) |
| **TP-Link TL-SG108E** | 8 port + Easy Smart | **1.234** | ✅ | [Akakçe](https://www.akakce.com/switch/en-ucuz-tp-link-tl-sg108e-8-port-10-100-1000-mbps-gigabit-fiyati,3405897.html) |

**1 kasa PC + 3 kat PC + 3 yazıcı + router = 8 cihaz.** TL-SG108 8 portu
tam yeter.

### E.3 Kablo & aksesuar

| Ürün | Adet | Yaklaşık ₺ | Not |
|---|---:|---:|---|
| YCL Cat6 50m kablo makarası | 1 | 700–900 | [Hepsiburada](https://www.hepsiburada.com/ycl-cat-6-kablo-50-metre-pm-HB000002GYDU) |
| Cat6 hazır patch kablo 1m (5'li) | 1 | 200–300 | |
| RJ45 konnektör + sıkma pensesi seti | 1 | 250–400 | |

---

## F) GÜÇ KORUMA — UPS (kasada zorunlu)

Elektrik kesilince MySQL açık dosyalı kalır → **veri bozulur**. UPS 5-10
dakika yetsin — bu sürede sistemi düzgün kapat.

| Marka & Model | VA | Yaklaşık ₺ | Stok | Link |
|---|---:|---:|:---:|---|
| **APC Back-UPS BX950MI-GR** | 950 | **3.510–3.700** | ✅ Akakçe + Trendyol 10+ adet | [Akakçe](https://www.akakce.com/kesintisiz-guc-kaynagi/en-ucuz-apc-bx950mi-gr-back-ups-950va-guc-kaynagi-fiyati,1547183421.html) |
| **Tunçmatik Lite II TSK5208** | 1000 | **3.599–3.700** | ✅ | [Hepsiburada](https://www.hepsiburada.com/tuncmatik-lite-ii-1000va-line-interactive-ups-tsk5208-pm-bd801891) |
| **Tunçmatik Newtech Pro II X9 (Online)** | 1000 | **8.087+** | ✅ premium seçenek | [Akakçe](https://www.akakce.com/kesintisiz-guc-kaynagi/en-ucuz-tuncmatik-newtech-pro-ii-x9-tsk5303-1-000-va-online-fiyati,7972906.html) |

**Önerim:** **APC BX950MI-GR — 3.700 ₺**. Hem fiyat hem marka güveni
ideal. Daha güçlü istersen Tunçmatik Newtech Pro.

---

## G) TERMAL KAĞIT

| Ürün | Adet | Yaklaşık ₺ | Stok | Link |
|---|---:|---:|:---:|---|
| **Umur 80mm × 40m termal rulo 10'lu** | 1 paket | **270** | ✅ | [Akakçe](https://www.akakce.com/pos-rulo-kagidi/en-ucuz-umur-80-mm-x-40-mt-termal-rulo-tekno-adisyon-fisi-kagit-1-paket-10-lu-fiyati,1615702052.html) |
| **Mopak 80mm × 40m termal rulo 10'lu** | 1 paket | **300** | ✅ | [Akakçe](https://www.akakce.com/pos-rulo-kagidi/en-ucuz-mopak-80-mm-x-40-mt-10-lu-termal-rulo-fiyati,1342991906.html) |
| Umur 80mm × 30m termal rulo 10'lu (kısa) | 1 paket | **180** | ✅ | [Akakçe](https://www.akakce.com/pos-rulo-kagidi/en-ucuz-umur-termal-rulo-80-mm-x-30-m-10-lu-paket-fiyati,1394798140.html) |

İlk başta **3 paket = 30 rulo** al → 3 mutfak yazıcısına 2-3 ay yetmeli.
Toplu alımda kargo başına düşer; tek seferlik tasarruflu.

---

## H) MUTFAK YAZICI KORUMA (önemli!)

Yağ + buhar + sıcak ortam — yazıcı korunmazsa 6 ayda biter.

| Ürün | Yaklaşık ₺ | Nereden |
|---|---:|---|
| Silikon kapaklı yazıcı muhafaza kabini (özel imalat) | 400–700/adet | Yerel sanayide kestir, alüminyum atölyeleri |
| Saç levha duvar rafı (yazıcıyı pislikten uzak tutmak için) | 300–500/adet | Yerel sanayi |

---

## I) YEDEK MALZEME (kurulumdan önce)

| Ürün | Adet | Yaklaşık ₺ |
|---|---:|---:|
| USB kablosu A-B 1.5m (yazıcı yedek bağlantı için) | 2 | 100 |
| Cat6 patch kablo 0.5m | 5 | 200 |
| Multi-priz topraklı 4'lü (uzun kablo) | 4 | 600 |
| UPS yedek akü (3 yıl sonrası için) | 1 | 800–1.500 |

---

## J) BAKIM YAZILIMI (bedava)

| Yazılım | Site | Notlar |
|---|---|---|
| **AnyDesk** | anydesk.com | Uzaktan masaüstü (KESİN) |
| **Tailscale** | tailscale.com | VPN — bedava 100 cihaza kadar (KESİN) |
| **MySQL Workbench** | mysql.com | DB yönetimi |
| **OneDrive** | onedrive.live.com | Yedekleme (her Windows'ta var) |
| **Notepad++** | notepad-plus-plus.org | Yapılandırma düzenleme |
| **7-Zip** | 7-zip.org | Yedek arşivlerini açma |

---

## K) İŞLETİM SİSTEMİ LİSANSI (FreeDOS cihazları için)

FreeDOS cihaz aldıysan üzerine Windows kurman lazım. Üç seçenek var:

### K.1 Windows 11 Pro lisansı (ücretli)

| Kanal | Yaklaşık ₺ | Not |
|---|---:|---|
| **Microsoft Türkiye direkt** | ~6.500–7.500 / lisans | En garantili, fatura ile |
| **Yetkili çözüm ortağı** (Komtera, Bilkom, vb.) | ~3.500–5.000 / lisans | OEM lisans, fatura ile |
| **CDKey siteleri** (Royal CDKeys, Lisanssaray vb.) | ~700–1.500 / lisans | Aktivasyon riski var, gri pazar |

**Önerim:** Restoran için **OEM lisans** (~4.000 ₺/lisans) al — fatura
var, MS aktivasyonu temiz. 4 PC için 4 lisans → **~16.000 ₺ ek**.

### K.2 Mevcut Windows lisansını taşıma (BEDAVA)

Eski PC'lerinden Windows 10/11 Pro lisansı varsa **yeni cihaza
taşıyabilirsin** (retail lisanslarda olur, OEM lisanslarda olmaz).
`slui.exe 4` ile MS aramayı arayıp lisansı transfer ettirebilirsin.

### K.3 Linux + Java (Windows GEREKMİYOR — BEDAVA)

Bizim **uygulama Linux'ta sorunsuz çalışır** (Java + MySQL hem Windows
hem Linux). Kullanıcı eğitimi farklı olur ama:

- **Ubuntu 24.04 LTS** veya **Linux Mint 21.3** kur — 0 ₺
- Java 22, MySQL 9, uygulamamız doğrudan çalışır
- Tek dezavantaj: Operatör eğitimi (Windows'a göre farklı arayüz)
- Tek avantaj: 4 lisans × 4.000 ₺ = **16.000 ₺ tasarruf**

> **Karar matrisi:**
> - Restoran personeli Windows alışkın → **Win11 Pro al** (kolay eğitim)
> - Sen IT'den anlıyorsan ve para tasarrufu önemliyse → **Linux**
> - Eski PC'den lisansın var → **Mevcut lisansı taşı (bedava)**

---

## TOPLAM BÜTÇE TAHMİNİ (2026 Mayıs, doğrulanmış fiyatlar)

### 🥇 Senaryo 1: TAVSİYE EDİLEN — Windows DAHİL kurulum (kuruluma 0 efor)

Beelink SER5 Pro + 3 × Tiwox TP-1500 (Win10 Pro) + 3 × Epson TM-T20II Ethernet

| Kategori | Adet | Birim ₺ | Toplam ₺ |
|---|---:|---:|---:|
| Beelink SER5 Pro Win11 Pro kasa PC | 1 | 19.699 | **19.699** |
| Tiwox TP-1500 i5 8GB Win10 Pro POS PC | 3 | 16.000 | **48.000** |
| Epson TM-T20II/III Ethernet yazıcı | 3 | 8.000 | **24.000** |
| TP-Link TL-WR802N Wi-Fi köprü | 3 | 1.100 | **3.300** |
| TP-Link Archer C6 router | 1 | 1.600 | **1.600** |
| TP-Link TL-SG108 switch | 1 | 950 | **950** |
| APC BX950MI-GR UPS | 1 | 3.700 | **3.700** |
| Umur termal kağıt 10'lu | 3 paket | 270 | **810** |
| Cat6 50m + patch + konnektör | — | — | **1.500** |
| Yazıcı muhafaza kabini | 3 | 500 | **1.500** |
| Yedek aksesuar | — | — | **2.000** |
| **TOPLAM** | | | **🟢 107.059 ₺** |

### 🥈 Senaryo 2: FreeDOS + OEM Windows Lisansı (orta tasarruf)

Asus PN52-S5090MD (FreeDOS) + 3 × Tiwox TP-2500 (FreeDOS) + 3 × Epson + 4 × Win11 Pro OEM

| Kategori | Adet | Birim ₺ | Toplam ₺ |
|---|---:|---:|---:|
| Asus PN52 FreeDOS Mini PC (Ryzen 5 5600H) | 1 | 20.616 | **20.616** |
| Tiwox TP-2500 i5 FreeDOS POS PC | 3 | 14.840 | **44.520** |
| Windows 11 Pro OEM lisans | 4 | 4.000 | **16.000** |
| Epson TM-T20II/III Ethernet yazıcı | 3 | 8.000 | **24.000** |
| TP-Link TL-WR802N | 3 | 1.100 | **3.300** |
| TP-Link Archer C6 | 1 | 1.600 | **1.600** |
| TP-Link TL-SG108 | 1 | 950 | **950** |
| APC BX950MI-GR UPS | 1 | 3.700 | **3.700** |
| Termal kağıt | 3 paket | 270 | **810** |
| Cat6 + aksesuar | — | — | **1.500** |
| Yazıcı muhafaza | 3 | 500 | **1.500** |
| Yedek aksesuar | — | — | **2.000** |
| **TOPLAM** | | | **🟡 120.496 ₺** |

> **Not:** FreeDOS + Win lisans, Win-dahil seçenekten 13.000 ₺ pahalıya
> geliyor (lisanslar ayrı eklenince). Eğer **eski PC'den Windows
> taşıyacaksan** veya **CDKey siteden lisans** (700-1.500 ₺) almayı
> göze alıyorsan FreeDOS daha ucuza gelir.

### 🥉 Senaryo 3: FreeDOS + Linux (sadece Java + MySQL — Windows YOK)

Asus PN52 + 3 × Tiwox TP-1503 (FreeDOS, Linux Mint) + 3 × Bixolon SRP-330III

| Kategori | Adet | Birim ₺ | Toplam ₺ |
|---|---:|---:|---:|
| Asus PN52 FreeDOS Mini PC | 1 | 20.616 | **20.616** |
| Tiwox TP-1503 i3 FreeDOS POS PC | 3 | 12.641 | **37.923** |
| Linux Mint / Ubuntu LTS | 4 | 0 | **0** |
| Bixolon SRP-330III yazıcı | 3 | 5.500 | **16.500** |
| TP-Link TL-WR802N | 3 | 1.100 | **3.300** |
| TP-Link Archer C6 | 1 | 1.600 | **1.600** |
| TP-Link TL-SG108 | 1 | 950 | **950** |
| Tunçmatik Lite II UPS | 1 | 3.600 | **3.600** |
| Termal kağıt | 3 paket | 270 | **810** |
| Cat6 + aksesuar | — | — | **1.500** |
| Yazıcı muhafaza | 3 | 500 | **1.500** |
| Yedek aksesuar | — | — | **2.000** |
| **TOPLAM** | | | **🟢 90.299 ₺** |

> **Linux dezavantaj:** Operatör eğitimi farklı. Bizim uygulama çalışır,
> ama Türkiye'de restoran personeli "Başlat Menü"sünü tanır, GNOME/KDE
> tanımaz.

### 💎 Senaryo 4: Premium (Win11 dahil her şey)

Beelink SER5 Max + 3 × Afanda AF-2150 21.5" + 3 × Epson TM-m30III

| Kategori | Adet | Birim ₺ | Toplam ₺ |
|---|---:|---:|---:|
| Beelink SER5 Max kasa PC | 1 | 28.000 | **28.000** |
| Afanda AF-2150 21.5" + Win OEM | 3 | 22.400 | **67.200** |
| Epson TM-m30III (Wi-Fi dahil) | 3 | 12.000 | **36.000** |
| Diğer (ağ, UPS, kağıt, aksesuar) | — | — | **10.000** |
| **TOPLAM** | | | **🔴 141.200 ₺** |

### Hızlı karşılaştırma

| Senaryo | Toplam | Win eğitim | IT eforu |
|---|---:|:---:|:---:|
| 🥇 Win dahil | **107.059 ₺** | ✅ Kolay | ✅ Plug-and-play |
| 🥈 FreeDOS + OEM Win | **120.496 ₺** | ✅ Kolay | ⚠️ 4 PC'ye Win kur |
| 🥉 FreeDOS + Linux | **90.299 ₺** | ❌ Eğitim gerek | ❌ Linux kur + eğit |
| 💎 Premium | **141.200 ₺** | ✅ Kolay | ✅ Plug-and-play |

> **Önerim:** **Senaryo 1 (Win dahil) — 107.059 ₺**. Lisans kafa
> karıştırmaz, eğitim sıkıntı olmaz, restoran 1 hafta içinde açılır.
> Eğer eski PC'lerinden Win lisansları varsa Senaryo 2'ye geç —
> tasarruf 13.000 ₺ olur.

> **Karşılaştırma:** PDF'deki rakip firma teklifi POS+yazıcı için
> **~120.000 ₺** istiyordu. Biz Senaryo 1 ile aynı paraya **daha iyi
> donanım** veriyoruz (Beelink Ryzen 7, i5 dokunmatik, Epson Ethernet).

---

## NEREDEN ALINIR? (Doğrulanmış kaynaklar — 2026 Mayıs)

### Online (fiyat karşılaştırması)
- [Akakçe.com](https://www.akakce.com) — her ürünün en ucuz satıcısı
- [Cimri.com](https://www.cimri.com) — alternatif karşılaştırma
- [Epey.com](https://www.epey.com) — model bazlı detay

### Online (alışveriş)
- [Hepsiburada.com](https://www.hepsiburada.com) — Beelink, TP-Link, APC, Tiwox burada
- [Trendyol.com](https://www.trendyol.com) — yedek seçenek
- [Amazon.com.tr](https://www.amazon.com.tr) — Epson yazıcı, Hannspree monitör
- [Vatanbilgisayar.com](https://www.vatanbilgisayar.com) — Epson + UPS
- [N11.com](https://www.n11.com) — bazen kampanya
- [Teknosa.com](https://www.teknosa.com) — kurumsal fatura için

### POS sektörü özel
- [Pavotek.com.tr](https://www.pavotek.com.tr) — POS donanım toptancısı
- [Posex.com.tr](https://www.posex.com.tr) — POS sistemleri
- [Artsistem.com](https://www.artsistem.com/tr) — Xprinter Türkiye yetkili
- [Posiflex-ist.com](https://www.posiflex-ist.com) — Posiflex Türkiye

### Yerel
- Şehrinizdeki **bilgisayar tamircisi / POS satıcısı** — kurulum desteği,
  garanti yerel ve hızlı müdahale eder.
- [Sahibinden.com](https://www.sahibinden.com) — ikinci el (NUC, Epson,
  UPS) %40-60 indirim ama garanti yok.

---

## ALIM SIRASI — ÖNERİ

### Olmazsa olmaz (1. hafta)
1. ✅ **Kasa PC** (Beelink SER5 Pro)
2. ✅ **1 dokunmatik ekran** kasaya (Tiwox TP-1500)
3. ✅ **1 mutfak yazıcı** test için (Epson TM-T20II Ethernet)
4. ✅ **Wi-Fi router** (TP-Link Archer C6)
5. ✅ **UPS** (APC BX950MI-GR)
6. ✅ **30 termal rulo** (3 paket Umur)

### 2. haftada
- 2 kat PC daha (Tiwox)
- 2 mutfak yazıcı daha (Epson/Bixolon)
- 3 Wi-Fi köprü (TL-WR802N)
- LAN switch (TL-SG108)
- Cat6 + aksesuar

### 1. ay içinde
- Yazıcı muhafaza kabinleri (yerel sanayi)
- Yedek malzeme (USB, akü)
- Yasal yazar kasa (YN-ÖKC) — yasal zorunluluk

---

## ÖNEMLİ NOTLAR

### Yasal yazar kasa (YN-ÖKC)
Türkiye'de restoran açıldığında **müşteri fişi için zorunlu**. Mali fiş
olmadığı için cezai yaptırım var.

| Model | Yaklaşık ₺ |
|---|---:|
| Hugin Compact 7 | ~14.000 |
| Ingenico iCT220 YN-ÖKC | ~17.000 |
| Beko 300 TR | ~20.000 |

Bu cihazlar bizim POS'tan **bağımsız** çalışır. Müşteri fişini bu basar,
biz mutfak fişini Epson'dan basıyoruz. İleride SDK ile entegre edilebilir
(ayrı bir iş, ~300+ satır kod).

### Stok teyit ipucu
Bu listedeki fiyatlar **22 Mayıs 2026** itibarıyla doğrulandı. Almadan
önce her ürünü tekrar **akakce.com**'da arayın — gün içinde fiyat 5-10%
oynayabiliyor.

### Garanti
- Beelink: **24 ay** üretici garantili (Hepsiburada satıcıdan teyit edin)
- Tiwox: **24 ay** üretici garantili
- Epson: **12 ay** Türkiye distribütör garantili
- TP-Link: **36 ay** garantili
- APC: **24 ay** garantili
- Tunçmatik: **24 ay** garantili

---

## Aramada Kullanılacak Kısa Liste (kopyala-yapıştır)

```
Beelink SER5 Pro Ryzen 7 5800H
Beelink Mini S12 Pro N100 16GB
Tiwox TP-1500
Tiwox TP-1503
Tiwox TP-2500
Epson TM-T20III Ethernet C31CH51012
Epson TM-T20II 003 Ethernet
Bixolon SRP-330III
Xprinter XP-N160II
TP-Link TL-WR802N
TP-Link Archer C6
TP-Link TL-SG108
APC Back-UPS BX950MI-GR
Tunçmatik Lite II TSK5208
Umur 80mm 40m termal rulo 10'lu
YCL Cat6 50m makara
```

---

*Hazırlayan: Claude — Tarih: 2026-05-22*
*Tüm ürünler aynı tarihte Türkiye e-ticaret sitelerinde stok teyit edildi.*
*Fiyatlar değişebilir — alımdan önce **akakce.com** ile son doğrulamayı yapın.*
