# Legacy migration arşivi — YALNIZ TARİHSEL REFERANS

Bu klasördeki dosyalar **çalıştırılmak için değildir**.

- `src/main/resources` dışında olduklarından **JAR'a paketlenmezler**.
- `SchemaMigrator` yalnız classpath'teki `db/migration/V###__*.sql` dosyalarını
  tarar; bu klasör hiçbir koşulda taranmaz/çalıştırılmaz.
- İçerikleri **sanitize edilmiştir**: gerçek yazıcı IP'leri RFC 5737
  dokümantasyon aralığı (`192.0.2.x`) ile değiştirildi. Credential, kullanıcı,
  parola hash'i veya işletme verisi içermezler.
- `.sql.bak` kopyaları (yalnız satır sonu farkı olan duplikatlar) kaldırıldı.

## Neden arşivlendi?

2026-05 döneminde şema değişiklikleri elle (`mysql -u root ... < dosya`)
uygulanan tek tek dosyalarla yönetiliyordu ve `.gitignore`'daki blanket `*.sql`
kuralı nedeniyle **hiçbiri repoda tracked değildi**. C1 ile şema tek bir
canonical baseline'a (`V001__baseline_schema.sql`) konsolide edildi; bu dosyalar
"hangi kolon ne zaman, neden eklendi?" sorusuna cevap olsun diye burada duruyor.

## Dosyalar ve V001'deki karşılıkları

| Legacy dosya | İçerik | V001'de |
|---|---|---|
| `V2026_05_15__kitchen_printers.sql` | kitchen_printers, category_printer_routes, print_jobs; order_items.printed_at/print_count; demo yazıcı satırları; v_pending_print_jobs view | Tablolar/kolonlar baseline'da. View **alınmadı** (kod kullanmıyor). Yazıcı satırları → V003 placeholder seed |
| `V2026_05_17__multi_kitchen_and_override.sql` | Yazıcı yeniden adlandırma (DONER/FIRIN/OCAK), order_items.kitchen_override_id, plaintext parolalı kullanıcıları pasifleştirme | Kolon baseline'da. Yeniden adlandırma/pasifleştirme = tek seferlik veri işlemi, baseline'a girmez |
| `V2026_05_17b__portion_pricing_and_kg_expenses.sql` | products/order_items.pieces_per_portion+unit_label; expenses.quantity_kg+unit_price_per_kg | Baseline'da |
| `V2026_05_17c__user_area_permissions.sql` | user_area_permissions (DROP+CREATE) | Baseline'da (DROP'suz, IF NOT EXISTS) |
| `V2026_05_18__order_item_notes.sql` | order_items.note | Baseline'da |

## Mevcut üretim veritabanı için

Bu dosyalar üretimde zaten elle uygulanmıştır. Üretim DB'si yeni sürüm
sistemine `Migrate --adopt-existing` ile geçer: adoption, şemanın V001-V003 ile
**yapısal olarak uyumlu** olduğunu (tablo/kolon/tip/index/kritik constraint)
doğrular; uyumsuzlukta hiçbir `schema_version` kaydı yazmadan FAIL eder ve
**hiçbir veri düzeltmesi yapmaz** (negatif stok normalize vb. ayrı, explicit
bakım adımıdır).
