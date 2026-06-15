# Görsel Klasörü Rehberi

Bu klasör uygulamanın UI'da kullandığı görselleri tutar.

## Yapı

```
src/main/resources/images/
├── README.md              ← bu dosya
├── placeholder.png        ← ürün görseli yoksa kullanılır
├── background.jpg         ← (opsiyonel) Dashboard arka planı
└── products/
    ├── 1.png              ← ürün id=1 için görsel
    ├── 2.png              ← ürün id=2 için görsel
    ├── 17.png             ← ürün id=17 için görsel
    └── ...
```

## Kurallar

### Ürün görselleri (`products/<id>.png`)

- Dosya adı **ürünün ID'si** olmalıdır.
- Ürün ID'sini ana ekrandaki **Ürünler** panelinden öğrenebilirsiniz
  (üzerine tıklayınca seçilen ürünün id'si veri tabanında belli olur).
- Önerilen boyut: **256×256 px** (PNG, RGBA).
- ProductPickerDialog 96×96 px'e küçültüp gösterir; bu yüzden 2-3 kat
  fazlası retina ekranlar için yeterli.
- **PNG** önerilir (transparan arka plan için). JPG ve GIF de okunur.
- Dosya boyutu < 500 KB olsun (uygulama JAR'ı şişmesin).

### placeholder.png

- Bir ürünün kendi görseli yoksa otomatik bu kullanılır.
- Boyut: 256×256 px, basit gri-beyaz "Ürün" yazılı bir görsel.

### background.jpg (opsiyonel)

- Dashboard arka planında gösterilebilir.
- Önerilen boyut: 1920×1080 px (full-HD), JPG (sıkıştırılmış).
- Çok renkli/parlak görsellerden kaçının — metin okunur kalmalı.
- Eğer eklemezseniz uygulama düz renkle açılır.

## Yeni Ürün Eklendiğinde

1. `Ürünler` panelinden yeni ürünü oluştur.
2. Ürünün DB'deki id'sini öğren (örn. son eklenen → en yüksek id).
3. Görseli bu klasöre `products/<id>.png` olarak kopyala.
4. JAR'ı yeniden build et (`mvn package`) **veya**
   geliştirme aşamasında IDE'yi yeniden başlat.
5. ProductPickerDialog'da yeni görselin geldiğini doğrula.

## Sık Karşılaşılan Sorunlar

- **Görsel görünmüyor:** Dosya adının id ile birebir eşleştiğinden,
  uzantının küçük harf `.png` olduğundan emin olun.
- **JAR'ın içine girmiyor:** `mvn clean package` ile yeniden derleyin;
  src/main/resources altındaki her şey otomatik dahil edilir.
- **Görsel bulanık:** Çok küçük yüklemişsiniz. En az 200×200 px verin.

## Toplu Hazırlama

Ürün katalogu büyükse, Photoshop/GIMP gibi araçlarla batch resize:
- Tümünü 256×256 px'e kare olarak kırp
- Aynı kalitede PNG export et
- Bir Excel'de "ürün id - dosya adı" eşlemesi tut

## Telif Hakları

Restoran kendi menüsünden çektiği fotoğrafları veya satın aldığı
royalty-free görselleri kullansın. İnternetteki rastgele görsellerin
ticari kullanımı yasal sorun yaratır.
