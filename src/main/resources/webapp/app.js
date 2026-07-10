/* ====================================================================
   BudgetController PWA — uygulama mantığı
   Vanilla JS, framework yok. Tüm state global App nesnesinde.
   ==================================================================== */

const App = {
  /** Basic auth header (login sonrası set edilir) */
  auth: null,
  /** Geçerli kullanıcı bilgisi */
  user: null,
  /** Aktif masa (table detail view'ı için) */
  currentTable: null,
  /** Cache: bina/kat/salon listesi */
  tables: [],
  products: [],
  /** Durum filtresi: 'all' | 'EMPTY' | 'OCCUPIED' */
  statusFilter: 'all',
  /** Ürün picker'da aktif kategori filtresi (null = Tümü) */
  categoryFilter: null,
};

// ====================================================================
//   API client
// ====================================================================

async function api(method, path, body) {
  const headers = { 'Content-Type': 'application/json' };
  if (App.auth) headers['Authorization'] = App.auth;
  const opts = { method, headers };
  if (body !== undefined) opts.body = JSON.stringify(body);
  const resp = await fetch(`/api${path}`, opts);
  let data = null;
  try { data = await resp.json(); } catch (e) {}
  if (!resp.ok) {
    const msg = (data && data.error) ? data.error : `HTTP ${resp.status}`;
    const err = new Error(msg);
    err.status = resp.status;
    throw err;
  }
  return data;
}

// ====================================================================
//   View routing
// ====================================================================

function showView(viewId, title, backable = false) {
  document.querySelectorAll('.view').forEach(v => v.classList.remove('active'));
  const v = document.getElementById(viewId);
  if (v) v.classList.add('active');
  const header = document.getElementById('appHeader');
  const main = document.getElementById('appMain');
  if (viewId === 'view-login') {
    header.classList.add('hidden');
    main.classList.add('hidden');
  } else {
    header.classList.remove('hidden');
    main.classList.remove('hidden');
    document.getElementById('headerTitle').textContent = title || 'Dağkapı Ciğercisi';
    document.getElementById('backBtn').classList.toggle('hidden', !backable);
  }
}

function toast(message, kind = 'info') {
  const el = document.getElementById('toast');
  el.textContent = message;
  el.classList.remove('hidden', 'success', 'error');
  if (kind !== 'info') el.classList.add(kind);
  setTimeout(() => el.classList.add('hidden'), 2500);
}

// ====================================================================
//   Login
// ====================================================================

document.getElementById('loginForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const username = document.getElementById('loginUsername').value.trim();
  const password = document.getElementById('loginPassword').value;
  const errorEl = document.getElementById('loginError');
  errorEl.textContent = '';
  console.log('[LOGIN] Deneme:', username);
  try {
    // 1. ADIM: POST /api/login (Basic header'sız, JSON body ile)
    //          Bu endpoint açık — body'den username/password okur, doğrularsa kullanıcı bilgisi döner.
    const loginResp = await fetch('/api/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password })
    });
    console.log('[LOGIN] /api/login status:', loginResp.status);
    if (!loginResp.ok) {
      let errMsg = 'Kullanıcı adı veya şifre hatalı';
      try {
        const errData = await loginResp.json();
        if (errData && errData.error) errMsg = errData.error;
      } catch (e) {}
      errorEl.textContent = errMsg;
      return;
    }
    const me = await loginResp.json();
    console.log('[LOGIN] Başarılı:', me.username);  // şifre/token loglanmıyor

    // 2. ADIM: Sunucudan dönen Bearer token'ı kullan — Basic Auth artık storage'da TUTULMUYOR.
    if (!me.token) {
      // Geriye dönük: token gelmiyorsa Basic'e düş (eski API).
      App.auth = 'Basic ' + btoa(unescape(encodeURIComponent(`${username}:${password}`)));
    } else {
      App.auth = 'Bearer ' + me.token;
    }
    App.user = { id: me.id, username: me.username, fullName: me.fullName, role: me.role };
    // GÜVENLİK: Token sessionStorage'da — sekme kapanınca silinir. localStorage XSS-stealable.
    sessionStorage.setItem('auth', App.auth);
    sessionStorage.setItem('user', JSON.stringify(App.user));
    onLogin();
  } catch (err) {
    console.error('[LOGIN] Hata:', err);
    App.auth = null;
    errorEl.textContent = 'Bağlantı hatası: ' + (err.message || err);
  }
});

// Ses toggle butonu
const soundToggleBtn = document.getElementById('soundToggleBtn');
function updateSoundIcon() {
  soundToggleBtn.textContent = Sound.isEnabled() ? '🔊' : '🔇';
}
updateSoundIcon();
soundToggleBtn.addEventListener('click', () => {
  Sound.setEnabled(!Sound.isEnabled());
  updateSoundIcon();
  if (Sound.isEnabled()) Sound.play('kitchenSent');  // test sesi (mutfak bildirim)
  toast(Sound.isEnabled() ? 'Ses açık' : 'Ses kapalı', 'info');
});

document.getElementById('logoutBtn').addEventListener('click', async () => {
  stopAutoRefresh();
  // Sunucu tarafında token'ı iptal et (best-effort)
  try { await api('POST', '/logout'); } catch (e) { /* ignore */ }
  App.auth = null;
  App.user = null;
  sessionStorage.removeItem('auth');
  sessionStorage.removeItem('user');
  // Eski localStorage kalıntılarını da temizle (geçiş için)
  localStorage.removeItem('auth');
  localStorage.removeItem('user');
  showView('view-login');
});

// ========== Otomatik logout (5 dk hareketsizlik) ==========
const IDLE_LIMIT_MS = 5 * 60 * 1000;
let idleTimer = null;
function resetIdleTimer() {
  if (idleTimer) clearTimeout(idleTimer);
  if (!App.auth) return;  // login değilse takip etme
  idleTimer = setTimeout(() => {
    toast('⏱ Hareketsizlik nedeniyle çıkış yapıldı', 'info');
    stopAutoRefresh();
    // Token'ı sunucudan iptal et
    try { api('POST', '/logout'); } catch (e) { /* ignore */ }
    App.auth = null;
    App.user = null;
    sessionStorage.removeItem('auth');
    sessionStorage.removeItem('user');
    localStorage.removeItem('auth');
    localStorage.removeItem('user');
    showView('view-login');
  }, IDLE_LIMIT_MS);
}
['click', 'touchstart', 'keydown', 'mousemove', 'scroll'].forEach(evt => {
  document.addEventListener(evt, resetIdleTimer, { passive: true });
});

// ========== Sidebar menu ==========
document.getElementById('menuBtn').addEventListener('click', openSideMenu);
document.getElementById('sideMenuClose').addEventListener('click', closeSideMenu);
document.getElementById('sideMenuOverlay').addEventListener('click', closeSideMenu);
document.querySelectorAll('.side-menu-item').forEach(btn => {
  btn.addEventListener('click', () => {
    closeSideMenu();
    const v = btn.dataset.view;
    switch (v) {
      case 'dashboard': goToDashboard(); break;
      case 'tables':    goToTables();    break;
      case 'sales':     goToSales();     break;
      case 'expenses':  goToExpenses();  break;
      case 'daily':     goToDaily();     break;
      case 'refunds':   goToRefunds();   break;
      case 'hourly':    goToHourly();    break;
      case 'product-admin': goToProductAdmin(); break;
      case 'user-admin':    goToUserAdmin();    break;
      case 'suggestions':   goToSuggestions();  break;
      case 'reservations':  goToReservations(); break;
    }
  });
});

// ====================================================================
//   Otomatik tarih/saat yenileyici
//   - Gece yarısı geçince Gün Sonu / Saatlik görünümler otomatik bugüne döner
//   - Açık görünümdeki rapor periyodik olarak (60 sn) tazelenir
//   - Header altına canlı saat eklenir
// ====================================================================
let _lastSeenDay = todayStr();
function _activeViewId() {
  const v = document.querySelector('.view.active');
  return v ? v.id : null;
}
function _refreshActiveReport() {
  const id = _activeViewId();
  if (id === 'view-daily' && typeof loadDaily === 'function')   loadDaily();
  if (id === 'view-hourly' && typeof loadHourly === 'function') loadHourly();
}
function _onMinuteTick() {
  // Canlı saat
  const clockEl = document.getElementById('liveClock');
  if (clockEl) {
    const n = new Date();
    const hh = String(n.getHours()).padStart(2, '0');
    const mm = String(n.getMinutes()).padStart(2, '0');
    clockEl.textContent = `${n.getDate()}.${String(n.getMonth()+1).padStart(2,'0')}.${n.getFullYear()} ${hh}:${mm}`;
  }
  // Gün değişimini yakala
  const t = todayStr();
  if (t !== _lastSeenDay) {
    _lastSeenDay = t;
    const id = _activeViewId();
    // Aktif görünümün tarih input'unu da bugüne çek
    const di = document.getElementById('dailyDate');  if (di) di.value = t;
    const hi = document.getElementById('hourlyDate'); if (hi) hi.value = t;
    const mi = document.getElementById('dailyMonth'); if (mi) mi.value = todayMonth();
    if (id === 'view-daily' || id === 'view-hourly') _refreshActiveReport();
  } else {
    // Aynı gün — yine de açık raporları tazele
    _refreshActiveReport();
  }
}
// İlk tick'i hemen, sonra her 60 sn'de bir
setTimeout(_onMinuteTick, 100);
setInterval(_onMinuteTick, 60_000);
// Pencere/sekme tekrar görünür olunca da bir tetik (uyutulmuş cihazlar için)
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible') _onMinuteTick();
});

async function goToSuggestions() {
  showView('view-suggestions', 'Öneriler', true);
  const list = document.getElementById('staffSuggestionsList');
  list.innerHTML = '<p style="text-align:center;color:#999;">Yükleniyor...</p>';
  try {
    const suggestions = await api('GET', '/reports/staff-suggestions');
    if (!suggestions || suggestions.length === 0) {
      list.innerHTML = '<p style="color:#999;padding:20px;">Henüz yeterli veri yok (30 gün gerekir)</p>';
      return;
    }
    list.innerHTML = suggestions.map(s => `
      <div class="record-item">
        <div class="record-main">
          <div class="record-title">${escapeHtml(s.day)} • ${escapeHtml(s.hour)}</div>
          <div class="record-meta">Ortalama ${s.avgOrders} sipariş/saat</div>
        </div>
        <div class="record-amount">${s.suggestedStaff} garson</div>
      </div>
    `).join('');
  } catch (err) {
    list.innerHTML = '<p style="color:#c62828;padding:20px;">Hata: ' + escapeHtml(err.message) + '</p>';
  }
}

function openSideMenu() {
  // Yetkiye göre menu item'lerini filtrele
  document.querySelectorAll('.side-menu-item').forEach(b => {
    const allowed = b.dataset.role;
    if (allowed && App.user && !allowed.split(',').includes(App.user.role)) {
      b.style.display = 'none';
    } else {
      b.style.display = '';
    }
  });
  document.getElementById('sideMenu').classList.remove('hidden');
  document.getElementById('sideMenuOverlay').classList.remove('hidden');
}
function closeSideMenu() {
  document.getElementById('sideMenu').classList.add('hidden');
  document.getElementById('sideMenuOverlay').classList.add('hidden');
}

function onLogin() {
  document.getElementById('headerUser').textContent =
    (App.user.fullName || App.user.username) + ' (' + App.user.role + ')';
  // Garson → masalara git; Admin/Kasiyer → dashboard
  if (App.user.role === 'GARSON') {
    goToTables();
  } else {
    goToDashboard();
  }
  // Auto-refresh başlat
  startAutoRefresh();
  // Otomatik logout timer'ını başlat
  resetIdleTimer();
}

// ====================================================================
//   Dashboard (Admin/Kasiyer)
// ====================================================================

async function goToDashboard() {
  showView('view-dashboard', 'Gösterge Paneli');

  // Ciro kutusu sadece ADMIN'e — KASIYER ciroyu görmez
  const salesBox = document.getElementById('statSalesBox');
  if (salesBox) {
    salesBox.style.display = (App.user && App.user.role === 'ADMIN') ? '' : 'none';
  }
  // Hızlı istatistik chip kutusu da sadece ADMIN'e
  const quickStatsBox = document.getElementById('quickStatsBox');
  if (quickStatsBox) {
    quickStatsBox.style.display = (App.user && App.user.role === 'ADMIN') ? '' : 'none';
  }

  try {
    const tables = await api('GET', '/tables');
    App.tables = tables;
    const occupied = tables.filter(t => t.status && t.status !== 'EMPTY').length;
    document.getElementById('statTableOccupied').textContent = occupied;
    document.getElementById('statOrderCount').textContent = occupied;
    // Ciro: sadece admin için göster (yine de hesapla — değiştirebilir ileride)
    if (App.user && App.user.role === 'ADMIN') {
      const totalSales = tables
        .filter(t => t.total)
        .reduce((sum, t) => sum + Number(t.total || 0), 0);
      document.getElementById('statSalesTotal').textContent = '₺' + totalSales.toFixed(2);
    }
  } catch (err) {
    toast('Veri alınamadı: ' + err.message, 'error');
  }
}

document.getElementById('goToTablesBtn').addEventListener('click', goToTables);

// ========== Hızlı İstatistik chip'leri ==========
document.querySelectorAll('.quick-stat-btn').forEach(btn => {
  btn.addEventListener('click', async () => {
    const type = btn.dataset.stat;
    const modal = document.getElementById('quickStatModal');
    const body = document.getElementById('quickStatBody');
    document.getElementById('quickStatTitle').textContent = btn.textContent;
    body.innerHTML = 'Yükleniyor...';
    modal.classList.remove('hidden');
    try {
      const r = await api('GET', '/reports/quick?type=' + type);
      body.innerHTML = `
        <div>${escapeHtml(r.title)}</div>
        <strong>${escapeHtml(r.value)}</strong>
        <div style="color:#777;font-size:13px;">${escapeHtml(r.detail || '')}</div>
      `;
    } catch (err) {
      body.innerHTML = '<span style="color:#c62828">Hata: ' + escapeHtml(err.message) + '</span>';
    }
  });
});
document.getElementById('quickStatCloseBtn').addEventListener('click', () => {
  document.getElementById('quickStatModal').classList.add('hidden');
});

// ====================================================================
//   Masalar (Tables)
// ====================================================================

async function goToTables() {
  // Masa listesine dönüyoruz → varsa açık olan masa kilidini bırak
  if (App.currentTable) {
    api('DELETE', `/tables/${App.currentTable.tableNo}/lock`).catch(() => {});
    App.currentTable = null;
  }
  showView('view-tables', 'Masalar', App.user.role !== 'GARSON');
  try {
    App.tables = await api('GET', '/tables');
    // Yaklaşan rezervasyonları arka planda al — masaya badge düşürmek için
    api('GET', '/reservations/upcoming').then(rs => {
      const map = new Map();
      (rs || []).forEach(r => {
        // Her masa için en yakın rezervasyonu sakla
        if (!map.has(r.tableNo)) map.set(r.tableNo, r);
      });
      App.upcomingResv = map;
      renderTables();
    }).catch(() => {
      App.upcomingResv = new Map();
    });
    populateFilters();
    renderTables();
  } catch (err) {
    toast('Masalar alınamadı: ' + err.message, 'error');
  }
}

function populateFilters() {
  const bldSet = new Set(App.tables.map(t => t.building));
  const bldSel = document.getElementById('buildingFilter');
  bldSel.innerHTML = '<option value="">Tüm Binalar</option>' +
    [...bldSet].map(b => `<option value="${b}">${b}</option>`).join('');

  const floorSel = document.getElementById('floorFilter');
  floorSel.innerHTML = '<option value="">Tüm Katlar</option>';

  bldSel.addEventListener('change', () => {
    const b = bldSel.value;
    const floors = new Set(App.tables.filter(t => !b || t.building === b).map(t => t.floor));
    floorSel.innerHTML = '<option value="">Tüm Katlar</option>' +
      [...floors].map(f => `<option value="${f}">${f}</option>`).join('');
    renderTables();
  });
  floorSel.addEventListener('change', renderTables);
}

function renderTables() {
  const b = document.getElementById('buildingFilter').value;
  const f = document.getElementById('floorFilter').value;

  // Önce bina/kat filtrelerini uygula
  let filtered = App.tables.filter(t =>
    (!b || t.building === b) && (!f || t.floor === f));

  // Durum bazlı sayım (filtrelenmemiş listenden)
  const totalCount = filtered.length;
  const emptyCount = filtered.filter(t => !t.status || t.status === 'EMPTY').length;
  const occupiedCount = filtered.filter(t => t.status && t.status !== 'EMPTY').length;
  document.getElementById('statusCounts').textContent =
    `Toplam: ${totalCount}  •  Boş: ${emptyCount}  •  Dolu: ${occupiedCount}`;

  // Status filtresini uygula
  if (App.statusFilter === 'EMPTY') {
    filtered = filtered.filter(t => !t.status || t.status === 'EMPTY');
  } else if (App.statusFilter === 'OCCUPIED') {
    filtered = filtered.filter(t => t.status && t.status !== 'EMPTY');
  }

  const grid = document.getElementById('tablesGrid');
  if (filtered.length === 0) {
    grid.innerHTML = '<p style="grid-column:1/-1;text-align:center;color:#999;padding:30px;">Filtreye uyan masa yok</p>';
    return;
  }
  grid.innerHTML = filtered.map(t => {
    const statusClass = t.status === 'EMPTY' ? 'table-empty'
                       : t.status === 'ORDERED' ? 'table-ordered'
                       : t.status === 'SERVED'  ? 'table-served'
                       : 'table-empty';
    const totalStr = t.total && Number(t.total) > 0
      ? `<span class="table-total">₺${Number(t.total).toFixed(2)}</span>` : '';
    // Yaklaşan rezervasyon rozeti
    let resvBadge = '';
    if (App.upcomingResv && App.upcomingResv.has(t.tableNo)) {
      const r = App.upcomingResv.get(t.tableNo);
      const hhmm = r.startTime ? r.startTime.slice(11, 16) : '';
      resvBadge = `<span class="table-resv-badge">🕒 ${hhmm}</span>`;
    }
    return `<button class="table-button ${statusClass}" data-table="${t.tableNo}" style="position:relative;">
              <span class="table-no">Masa ${t.tableNo}</span>
              ${totalStr}
              ${resvBadge}
            </button>`;
  }).join('');
  grid.querySelectorAll('.table-button').forEach(btn => {
    btn.addEventListener('click', () => openTable(Number(btn.dataset.table)));
  });
}

// Durum filtresi butonları
document.querySelectorAll('.status-btn').forEach(btn => {
  btn.addEventListener('click', () => {
    App.statusFilter = btn.dataset.status;
    document.querySelectorAll('.status-btn').forEach(b => b.classList.remove('active'));
    btn.classList.add('active');
    renderTables();
  });
});

// ====================================================================
//   Masa detay
// ====================================================================

async function openTable(tableNo) {
  // Önceki masadan çıkarken kilidi bırak
  if (App.currentTable && App.currentTable.tableNo !== tableNo) {
    api('DELETE', `/tables/${App.currentTable.tableNo}/lock`).catch(() => {});
  }
  // Yeni masa için kilit al
  try {
    await api('POST', `/tables/${tableNo}/lock`, {});
  } catch (err) {
    if (err.status === 409) {
      toast(err.message, 'error');
      return;  // masa kilitli, dialog'u açma
    }
    // başka hata → devam et (lock kritik değil)
  }
  showView('view-table-detail', `Masa ${tableNo}`, true);
  try {
    const snap = await api('GET', `/tables/${tableNo}`);
    App.currentTable = snap;
    renderTableDetail(snap);
  } catch (err) {
    toast('Masa detayı alınamadı: ' + err.message, 'error');
  }
}

function renderTableDetail(snap) {
  document.getElementById('tableInfoTitle').textContent = 'Masa ' + snap.tableNo;
  document.getElementById('tableInfoMeta').textContent =
    (snap.building || '') + ' / ' + (snap.section || '') +
    (snap.salon ? ' / ' + snap.salon : '');

  const items = (snap.lines || []);
  const itemsEl = document.getElementById('orderItems');
  if (items.length === 0) {
    itemsEl.innerHTML = '<p style="text-align:center;color:#999;padding:20px;">Henüz kalem yok</p>';
  } else {
    itemsEl.innerHTML = items.map(it => {
      const cls = it.pending ? 'order-item pending' : 'order-item';
      const noteHtml = it.note ? `<div class="item-meta">${escapeHtml(it.note)}</div>` : '';
      const lineTotal = (Number(it.unitPrice) * Number(it.quantity)).toFixed(2);
      const safeName = escapeHtml(it.productName || '');
      return `<div class="${cls}" data-item-id="${it.itemId ?? ''}" data-item-name="${safeName}">
                <div>
                  <div class="item-name">${safeName}</div>
                  <div class="item-meta">${escapeHtml(it.quantityLabel || String(it.quantity))} × ₺${Number(it.unitPrice).toFixed(2)}</div>
                  ${noteHtml}
                </div>
                <div class="item-amount">₺${lineTotal}</div>
              </div>`;
    }).join('');
    // Her kaleme tıklayınca azalt/sil eylem sheet'i açılır — kimlik: itemId
    itemsEl.querySelectorAll('.order-item').forEach(row => {
      row.addEventListener('click', () => {
        openItemActionSheet(Number(row.dataset.itemId), row.dataset.itemName);
      });
    });
  }
  document.getElementById('orderTotal').textContent =
    '₺' + Number(snap.total || 0).toFixed(2);

  // Satış/Hesap böl/Temizle butonlarını role'e göre göster
  const isCashier = App.user.role === 'ADMIN' || App.user.role === 'KASIYER';
  document.getElementById('saleBtn').style.display = isCashier ? '' : 'none';
  document.getElementById('splitBtn').style.display = isCashier ? '' : 'none';
  document.getElementById('clearTableBtn').style.display = isCashier ? '' : 'none';
}

// ========== Auto-refresh ==========
let refreshTimer = null;
function startAutoRefresh() {
  stopAutoRefresh();
  refreshTimer = setInterval(() => {
    const active = document.querySelector('.view.active');
    if (!active) return;
    // Sadece masalar listesi veya masa detay açıkken yenile
    if (active.id === 'view-tables') {
      api('GET', '/tables').then(t => { App.tables = t; renderTables(); }).catch(() => {});
    } else if (active.id === 'view-table-detail' && App.currentTable) {
      api('GET', `/tables/${App.currentTable.tableNo}`)
        .then(snap => { App.currentTable = snap; renderTableDetail(snap); })
        .catch(() => {});
    } else if (active.id === 'view-dashboard') {
      api('GET', '/tables').then(t => {
        App.tables = t;
        const occupied = t.filter(x => x.status && x.status !== 'EMPTY').length;
        document.getElementById('statTableOccupied').textContent = occupied;
        document.getElementById('statOrderCount').textContent = occupied;
        if (App.user && App.user.role === 'ADMIN') {
          const totalSales = t.filter(x => x.total).reduce((s, x) => s + Number(x.total || 0), 0);
          document.getElementById('statSalesTotal').textContent = '₺' + totalSales.toFixed(2);
        }
      }).catch(() => {});
    }
  }, 5000);  // 5 saniyede bir
}
function stopAutoRefresh() {
  if (refreshTimer) { clearInterval(refreshTimer); refreshTimer = null; }
}

document.getElementById('addProductBtn').addEventListener('click', openProductPicker);
document.getElementById('finishOrderBtn').addEventListener('click', () => {
  if (App.currentTable) openTable(App.currentTable.tableNo);
});
document.getElementById('sendKitchenBtn').addEventListener('click', sendToKitchen);
document.getElementById('markServedBtn').addEventListener('click', markTableServed);
document.getElementById('saleBtn').addEventListener('click', () => openPaymentDialog(false));

// Sipariş teslim edildi → masa SERVED durumuna geçer (yeşil)
async function markTableServed() {
  if (!App.currentTable) return;
  try {
    await api('POST', `/tables/${App.currentTable.tableNo}/mark-served`, {});
    toast('✓ Sipariş teslim edildi', 'success');
    // Masa listesine dön — garson yeşil rengi görsün
    goToTables();
  } catch (err) {
    toast('İşlem başarısız: ' + err.message, 'error');
  }
}
document.getElementById('splitBtn').addEventListener('click', openSplitDialog);
document.getElementById('transferBtn').addEventListener('click', openTransferDialog);
document.getElementById('clearTableBtn').addEventListener('click', clearTable);

// Mutfağa gönder
async function sendToKitchen() {
  if (!App.currentTable) return;
  try {
    const r = await api('POST', `/tables/${App.currentTable.tableNo}/send-to-kitchen`, {});
    Sound.play('kitchenSent');
    toast(`Mutfağa gönderildi (${r.kitchenCount} yazıcı)`, 'success');
    openTable(App.currentTable.tableNo);
  } catch (err) {
    toast('Mutfak: ' + err.message, 'error');
  }
}

// Masa temizle
async function clearTable() {
  if (!App.currentTable) return;
  if (App.user.role !== 'ADMIN' && App.user.role !== 'KASIYER') {
    toast('Bu işlem için Admin/Kasiyer yetkisi gerekir', 'error');
    return;
  }
  if (!confirm('Masa tamamen temizlensin mi?')) return;
  const reason = prompt('İade nedeni:', '');
  if (reason === null) return;
  if (!reason.trim()) { toast('Neden gerekli', 'error'); return; }
  try {
    await api('DELETE', `/tables/${App.currentTable.tableNo}?reason=${encodeURIComponent(reason)}`);
    toast('Masa temizlendi', 'success');
    goToTables();
  } catch (err) {
    toast('Temizleme: ' + err.message, 'error');
  }
}

// ========== Ödeme dialogu ==========
let paymentOriginalAmount = 0;  // hızlı tutar şablonları için baz
let paymentCurrentAmount = 0;

function openPaymentDialog(forSplit) {
  if (!App.currentTable || !App.currentTable.total || Number(App.currentTable.total) <= 0) {
    toast('Açık sipariş yok', 'error');
    return;
  }
  if (App.user.role !== 'ADMIN' && App.user.role !== 'KASIYER') {
    toast('Satış için Admin/Kasiyer yetkisi gerekir', 'error');
    return;
  }
  const dlg = document.getElementById('paymentDialog');
  paymentOriginalAmount = Number(App.currentTable.total);
  paymentCurrentAmount = paymentOriginalAmount;
  document.getElementById('paymentDialogAmount').textContent =
    '₺' + paymentCurrentAmount.toFixed(2);
  dlg.classList.remove('hidden');
  // Her ödeme yöntemi butonuna click → recordSale çağır
  dlg.querySelectorAll('[data-method]').forEach(btn => {
    btn.onclick = async () => {
      const method = btn.dataset.method;
      dlg.classList.add('hidden');
      try {
        await api('POST', `/tables/${App.currentTable.tableNo}/sale`, { method });
        toast('Satış tamamlandı', 'success');
        goToTables();
      } catch (err) {
        toast('Satış: ' + err.message, 'error');
      }
    };
  });
}
document.getElementById('paymentCancelBtn').addEventListener('click', () => {
  document.getElementById('paymentDialog').classList.add('hidden');
});

// Hızlı tutar şablonları
document.querySelectorAll('.quick-amount-btn').forEach(btn => {
  btn.addEventListener('click', () => {
    const action = btn.dataset.action;
    let newAmount = paymentCurrentAmount;
    switch (action) {
      case 'round-50':
        newAmount = Math.ceil(paymentCurrentAmount / 50) * 50; break;
      case 'round-100':
        newAmount = Math.ceil(paymentCurrentAmount / 100) * 100; break;
      case 'disc-5':
        newAmount = paymentCurrentAmount * 0.95; break;
      case 'disc-10':
        newAmount = paymentCurrentAmount * 0.90; break;
      case 'free':
        newAmount = 0; break;
      case 'reset':
        newAmount = paymentOriginalAmount; break;
    }
    paymentCurrentAmount = Math.round(newAmount * 100) / 100;
    document.getElementById('paymentDialogAmount').textContent =
      '₺' + paymentCurrentAmount.toFixed(2);
    // Hangi butonun seçildiğini görsel olarak vurgula (basit feedback)
    btn.style.background = '#ffb74d';
    setTimeout(() => { btn.style.background = ''; }, 300);
  });
});

// ========== Hesap Böl dialogu ==========
let splitState = { total: 0, personCount: 0, amounts: [], methods: [] };

function openSplitDialog() {
  if (!App.currentTable || !App.currentTable.total || Number(App.currentTable.total) <= 0) {
    toast('Bölünecek hesap yok', 'error');
    return;
  }
  if (App.user.role !== 'ADMIN' && App.user.role !== 'KASIYER') {
    toast('Bu işlem için Admin/Kasiyer yetkisi gerekir', 'error');
    return;
  }
  const dlg = document.getElementById('splitDialog');
  splitState.total = Number(App.currentTable.total);
  document.getElementById('splitTotalAmount').textContent = '₺' + splitState.total.toFixed(2);
  document.getElementById('splitStep1').classList.remove('hidden');
  document.getElementById('splitStep2').classList.add('hidden');
  dlg.classList.remove('hidden');
}

// Adım 1 → 2: kişi sayısı seç
document.querySelectorAll('#splitStep1 [data-persons]').forEach(btn => {
  btn.addEventListener('click', () => {
    splitState.personCount = parseInt(btn.dataset.persons, 10);
    buildSplitStep2();
  });
});
document.getElementById('splitManualGo').addEventListener('click', () => {
  const n = parseInt(document.getElementById('splitManualCount').value, 10);
  if (!n || n < 2) { toast('En az 2 kişi', 'error'); return; }
  splitState.personCount = n;
  buildSplitStep2();
});

function buildSplitStep2() {
  const n = splitState.personCount;
  const total = splitState.total;
  // Eşit böl varsayılan, kullanıcı sonra istediği gibi değiştirebilir
  equalSplit();
  splitState.methods = new Array(n).fill(null);

  const list = document.getElementById('splitPersonsList');
  list.innerHTML = `
    <div class="split-sum-bar">
      <span>Toplam: <strong>₺${total.toFixed(2)}</strong></span>
      <button type="button" class="split-equal-btn" id="splitEqualBtn">⇄ Eşit Böl</button>
      <span class="split-status" id="splitStatus"></span>
    </div>
  ` + splitState.amounts.map((amt, i) => `
    <div class="split-person-row">
      <div class="person-label">
        <span>Kişi ${i + 1}</span>
        <span class="amount-input-wrap">
          ₺ <input type="text" inputmode="decimal" autocomplete="off"
                   pattern="[0-9.,]*"
                   class="amount-input" data-person="${i}"
                   value="${amt.toFixed(2)}">
        </span>
      </div>
      <div class="method-buttons">
        <button class="method-btn" data-person="${i}" data-method="CASH">💵 Nakit</button>
        <button class="method-btn" data-person="${i}" data-method="CREDIT_CARD">💳 Kart</button>
        <button class="method-btn" data-person="${i}" data-method="TRANSFER">🏦 EFT</button>
      </div>
    </div>
  `).join('');

  // Method butonları
  list.querySelectorAll('.method-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      const person = parseInt(btn.dataset.person, 10);
      const method = btn.dataset.method;
      splitState.methods[person] = method;
      list.querySelectorAll(`.method-btn[data-person="${person}"]`)
          .forEach(b => b.classList.remove('selected'));
      btn.classList.add('selected');
    });
  });

  // Tutar inputları — canlı toplam kontrol
  // Hem virgül hem nokta kabul eder; harfler/sembolleri yok sayar.
  list.querySelectorAll('.amount-input').forEach(inp => {
    inp.addEventListener('input', () => {
      const i = parseInt(inp.dataset.person, 10);
      const cleaned = inp.value.replace(/[^0-9.,]/g, '').replace(',', '.');
      const v = parseFloat(cleaned);
      splitState.amounts[i] = isNaN(v) ? 0 : v;
      updateSplitStatus();
    });
    // Mobilde dokun → tüm metni seç (üzerine yazmak için kolaylık)
    inp.addEventListener('focus', () => inp.select());
  });

  // Eşit böl butonu
  document.getElementById('splitEqualBtn').addEventListener('click', () => {
    equalSplit();
    list.querySelectorAll('.amount-input').forEach((inp, i) => {
      inp.value = splitState.amounts[i].toFixed(2);
    });
    updateSplitStatus();
  });

  updateSplitStatus();
  document.getElementById('splitStep1').classList.add('hidden');
  document.getElementById('splitStep2').classList.remove('hidden');
}

/** Eşit bölme — küsuratı son parçaya atar */
function equalSplit() {
  const n = splitState.personCount;
  const total = splitState.total;
  const per = Math.floor((total / n) * 100) / 100;
  splitState.amounts = [];
  let sum = 0;
  for (let i = 0; i < n - 1; i++) {
    splitState.amounts.push(per);
    sum += per;
  }
  splitState.amounts.push(Math.round((total - sum) * 100) / 100);
}

/** Canlı toplam status etiketi — eksik/fazla/tam */
function updateSplitStatus() {
  const el = document.getElementById('splitStatus');
  if (!el) return;
  const sum = splitState.amounts.reduce((s, v) => s + (Number(v) || 0), 0);
  const diff = splitState.total - sum;
  if (Math.abs(diff) <= 0.01) {
    el.textContent = '✓ Tam';
    el.style.color = '#2e7d32';
  } else if (diff > 0) {
    el.textContent = '⚠ Eksik: ₺' + diff.toFixed(2);
    el.style.color = '#f57c00';
  } else {
    el.textContent = '⚠ Fazla: ₺' + (-diff).toFixed(2);
    el.style.color = '#c62828';
  }
}

document.getElementById('splitConfirmBtn').addEventListener('click', async () => {
  // Toplam tutar kontrolü
  const sum = splitState.amounts.reduce((s, v) => s + (Number(v) || 0), 0);
  const diff = Math.abs(splitState.total - sum);
  if (diff > 0.10) {
    toast(`Parça toplamı (₺${sum.toFixed(2)}) sipariş toplamıyla (₺${splitState.total.toFixed(2)}) eşit değil`, 'error');
    return;
  }
  // Her tutarın pozitif olması
  for (let i = 0; i < splitState.personCount; i++) {
    if (!splitState.amounts[i] || splitState.amounts[i] <= 0) {
      toast(`Kişi ${i + 1} tutarı 0'dan büyük olmalı`, 'error');
      return;
    }
    if (!splitState.methods[i]) {
      toast(`Kişi ${i + 1} için yöntem seçilmedi`, 'error');
      return;
    }
  }
  const parts = splitState.amounts.map((amt, i) => ({
    amount: amt,
    method: splitState.methods[i]
  }));
  try {
    await api('POST', `/tables/${App.currentTable.tableNo}/split-sale`, { parts });
    toast(`Hesap ${splitState.personCount} kişiye bölündü`, 'success');
    document.getElementById('splitDialog').classList.add('hidden');
    goToTables();
  } catch (err) {
    toast('Hesap böl: ' + err.message, 'error');
  }
});
document.getElementById('splitCancelBtn').addEventListener('click', () => {
  document.getElementById('splitDialog').classList.add('hidden');
});

// ========== Masa transfer dialogu ==========
async function openTransferDialog() {
  if (!App.currentTable) return;
  const dlg = document.getElementById('transferDialog');
  // Boş hedef masaları getir (tüm masalar listesinden filtrele)
  try {
    const tables = await api('GET', '/tables');
    const targets = tables
      .filter(t => t.tableNo !== App.currentTable.tableNo
                && (!t.status || t.status === 'EMPTY'))
      .map(t => t.tableNo);
    if (targets.length === 0) {
      toast('Boş masa yok', 'error');
      return;
    }
    const sel = document.getElementById('transferTargetSelect');
    sel.innerHTML = targets.map(n => `<option value="${n}">Masa ${n}</option>`).join('');
    dlg.classList.remove('hidden');
  } catch (err) {
    toast('Masalar alınamadı: ' + err.message, 'error');
  }
}
document.getElementById('transferConfirmBtn').addEventListener('click', async () => {
  const target = parseInt(document.getElementById('transferTargetSelect').value, 10);
  if (!target) return;
  try {
    await api('POST', `/tables/${App.currentTable.tableNo}/transfer`,
              { targetTableNo: target });
    toast(`Masa ${target}'e taşındı`, 'success');
    document.getElementById('transferDialog').classList.add('hidden');
    goToTables();
  } catch (err) {
    toast('Transfer: ' + err.message, 'error');
  }
});
document.getElementById('transferCancelBtn').addEventListener('click', () => {
  document.getElementById('transferDialog').classList.add('hidden');
});

// ========== Kalem eylem sheet (azalt/sil) ==========
// itemId = mutation identity (order_items.id); productName YALNIZ display.
let actionItemId = null;
let actionItemName = '';
function openItemActionSheet(itemId, productName) {
  actionItemId = itemId;
  actionItemName = productName || '';
  document.getElementById('itemActionTitle').textContent = actionItemName;
  document.getElementById('itemActionSheet').classList.remove('hidden');
}
/** Mutation öncesi defensive kontrol: finite positive integer değilse null. */
function validActionItemId() {
  const id = Number(actionItemId);
  return (Number.isInteger(id) && id > 0) ? id : null;
}
/** Azalt/Sil başarısı sonrası view-aware refresh — picker'da kal, detayda navigate. */
async function refreshAfterItemMutation() {
  const active = document.querySelector('.view.active');
  if (active && active.id === 'view-products') {
    await refreshAddedSummary();
  } else {
    openTable(App.currentTable.tableNo);
  }
}
document.getElementById('itemDecreaseBtn').addEventListener('click', async () => {
  document.getElementById('itemActionSheet').classList.add('hidden');
  const itemId = validActionItemId();
  if (itemId === null) {
    toast('Kalem kimliği geçersiz — ekranı yenileyip tekrar deneyin', 'error');
    return;
  }
  let reason = null;
  if (App.user.role === 'ADMIN' || App.user.role === 'KASIYER') {
    reason = prompt('İade nedeni:', '');
    if (reason === null) return;
    if (!reason.trim()) { toast('Neden gerekli', 'error'); return; }
  }
  try {
    await api('POST', `/tables/${App.currentTable.tableNo}/decrease-item`,
              { itemId, quantity: 1, reason });
    toast('1 adet azaltıldı', 'success');
    await refreshAfterItemMutation();
  } catch (err) {
    toast('Azalt: ' + err.message, 'error');
  }
});
document.getElementById('itemRemoveBtn').addEventListener('click', async () => {
  document.getElementById('itemActionSheet').classList.add('hidden');
  const itemId = validActionItemId();
  if (itemId === null) {
    toast('Kalem kimliği geçersiz — ekranı yenileyip tekrar deneyin', 'error');
    return;
  }
  if (!confirm(`'${actionItemName}' tamamen silinsin mi?`)) return;
  let reason = null;
  if (App.user.role === 'ADMIN' || App.user.role === 'KASIYER') {
    reason = prompt('İade nedeni:', '');
    if (reason === null) return;
    if (!reason.trim()) { toast('Neden gerekli', 'error'); return; }
  }
  try {
    await api('POST', `/tables/${App.currentTable.tableNo}/remove-item`,
              { itemId, reason });
    toast('Kalem silindi', 'success');
    await refreshAfterItemMutation();
  } catch (err) {
    toast('Sil: ' + err.message, 'error');
  }
});
document.getElementById('itemCancelBtn').addEventListener('click', () => {
  document.getElementById('itemActionSheet').classList.add('hidden');
});

// ====================================================================
//   Ürün seçici
// ====================================================================

async function openProductPicker() {
  showView('view-products', 'Ürün Ekle', true);
  try {
    App.products = await api('GET', '/products');
    App.categoryFilter = null;  // her açılışta sıfırla
    buildCategoryBar();
    renderProducts();
    refreshAddedSummary();
  } catch (err) {
    toast('Ürünler alınamadı: ' + err.message, 'error');
  }
}

/** Kategorilerden filter butonları oluşturur — "Tümü" + her benzersiz kategori adı. */
function buildCategoryBar() {
  const bar = document.getElementById('categoryFilterBar');
  if (!bar) return;
  // Benzersiz kategoriler (sıralı), null/boş hariç
  const seen = new Set();
  const cats = [];
  for (const p of App.products) {
    const name = (p.categoryName || '').trim();
    if (!name || seen.has(name)) continue;
    seen.add(name);
    cats.push(name);
  }
  // Türkçe sırala
  cats.sort((a, b) => a.localeCompare(b, 'tr'));

  bar.innerHTML =
    `<button class="cat-btn active" data-cat="">Tümü</button>` +
    cats.map(c => `<button class="cat-btn" data-cat="${escapeHtml(c)}">${escapeHtml(c)}</button>`).join('');

  bar.querySelectorAll('.cat-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      App.categoryFilter = btn.dataset.cat || null;
      bar.querySelectorAll('.cat-btn').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      renderProducts();
    });
  });
}

document.getElementById('productSearch').addEventListener('input', renderProducts);

function renderProducts() {
  const q = (document.getElementById('productSearch').value || '').toLocaleLowerCase('tr');
  const cat = App.categoryFilter;
  const filtered = App.products.filter(p => {
    if (q && !(p.name && p.name.toLocaleLowerCase('tr').includes(q))) return false;
    if (cat && (p.categoryName || '').trim() !== cat) return false;
    return true;
  });
  const list = document.getElementById('productsList');
  list.innerHTML = filtered.map(p => {
    const cls = p.active === false ? 'product-card inactive' : 'product-card';
    const badge = p.active === false ? '<span class="badge-out">● TÜKENDİ</span>' : '';
    const price = Number(p.unitPrice || 0).toFixed(2);
    return `<div class="${cls}" data-product="${p.id}" data-active="${p.active}">
              <div class="product-name">${escapeHtml(p.name || '')}</div>
              <div class="product-price">₺${price}</div>
              ${badge}
            </div>`;
  }).join('');
  list.querySelectorAll('.product-card').forEach(card => {
    if (card.dataset.active === 'false') return;  // tükendi → tıklama yok
    card.addEventListener('click', () => {
      const product = App.products.find(p => String(p.id) === card.dataset.product);
      if (product) openAddItemDialog(product);
    });
  });
}

/** Aktif ürün ekleme dialogu state'i */
const addItemState = { product: null, portion: 1, extraPieces: 0, note: '' };

function openAddItemDialog(product) {
  addItemState.product = product;
  addItemState.portion = 1;
  addItemState.extraPieces = 0;
  addItemState.note = '';

  document.getElementById('addItemProductName').textContent = product.name || 'Ürün';

  // İçecek kategorisinde şiş bölümü gizlenir — sadece "adet" sorulur
  // Ek garanti: piecesPerPortion null/0 ise zaten şiş yok
  const isDrinkCategory = isDrink(product);
  const unitLabelLower = (product.unitLabel || '').toLocaleLowerCase('tr');
  const hasPieces = product.piecesPerPortion && Number(product.piecesPerPortion) > 0;
  const unitImpliesAdet = ['adet', 'kase', 'tabak', 'kg', 'lt', 'litre'].includes(unitLabelLower);
  const isPieceBased = hasPieces && !isDrinkCategory && !unitImpliesAdet;

  const unit = product.unitLabel || (isPieceBased ? 'şiş' : (isDrinkCategory ? 'adet' : 'porsiyon'));
  const price = Number(product.unitPrice || 0);
  let priceInfo = '₺' + price.toFixed(2) + ' / ' + (isDrinkCategory ? 'adet' : 'porsiyon');
  if (isPieceBased) {
    const perPiece = price / product.piecesPerPortion;
    priceInfo += ` (1 ${unit} = ₺${perPiece.toFixed(2)})`;
  }
  document.getElementById('addItemPriceInfo').textContent = priceInfo;

  // Porsiyon yerine "Adet" göster (içecek için daha doğru)
  const portionLabel = document.querySelector('#addItemDialog .qty-row:first-of-type .qty-label');
  if (portionLabel) portionLabel.textContent = isDrinkCategory ? 'Adet' : 'Porsiyon';

  // Şiş satırı sadece şiş bazlı (içecek olmayan) ürünlerde görünür
  const piecesRow = document.getElementById('addItemPiecesRow');
  if (isPieceBased) {
    piecesRow.style.display = '';
    document.getElementById('addItemPiecesLabel').textContent = '+ Ek ' + unit;
  } else {
    piecesRow.style.display = 'none';
  }

  document.getElementById('addItemPortion').value = 1;
  document.getElementById('addItemExtraPieces').value = 0;
  document.getElementById('addItemNote').value = '';
  document.querySelectorAll('.note-chip').forEach(c => c.classList.remove('selected'));
  updateAddItemTotal();
  document.getElementById('addItemDialog').classList.remove('hidden');
}

function updateAddItemTotal() {
  const p = addItemState.product;
  if (!p) return;
  const price = Number(p.unitPrice || 0);
  const portion = Number(document.getElementById('addItemPortion').value) || 0;
  const extra = Number(document.getElementById('addItemExtraPieces').value) || 0;
  const isPieceBased = p.piecesPerPortion && p.piecesPerPortion > 0;
  let total;
  if (isPieceBased) {
    const perPiece = price / p.piecesPerPortion;
    const totalPieces = portion * p.piecesPerPortion + extra;
    total = perPiece * totalPieces;
  } else {
    total = price * portion;
  }
  document.getElementById('addItemTotalPreview').textContent = '₺' + total.toFixed(2);
}

// +/- butonlar
document.querySelectorAll('#addItemDialog .qty-btn').forEach(btn => {
  btn.addEventListener('click', () => {
    const action = btn.dataset.action;
    if (action === 'portion-minus') {
      const el = document.getElementById('addItemPortion');
      el.value = Math.max(0, (Number(el.value) || 0) - 1);
    } else if (action === 'portion-plus') {
      const el = document.getElementById('addItemPortion');
      el.value = Math.min(50, (Number(el.value) || 0) + 1);
    } else if (action === 'pieces-minus') {
      const el = document.getElementById('addItemExtraPieces');
      el.value = Math.max(0, (Number(el.value) || 0) - 1);
    } else if (action === 'pieces-plus') {
      const el = document.getElementById('addItemExtraPieces');
      el.value = Math.min(50, (Number(el.value) || 0) + 1);
    }
    updateAddItemTotal();
  });
});
document.getElementById('addItemPortion').addEventListener('input', updateAddItemTotal);
document.getElementById('addItemExtraPieces').addEventListener('input', updateAddItemTotal);

// Not chip'leri — tıklanınca textarea'ya yaz (toggle)
document.querySelectorAll('.note-chip').forEach(chip => {
  chip.addEventListener('click', () => {
    const note = chip.dataset.note;
    const ta = document.getElementById('addItemNote');
    if (chip.classList.contains('selected')) {
      // Çıkar
      chip.classList.remove('selected');
      ta.value = ta.value.split(',').map(s => s.trim())
                  .filter(s => s !== note).join(', ');
    } else {
      chip.classList.add('selected');
      const cur = ta.value.trim();
      ta.value = cur ? cur + ', ' + note : note;
    }
  });
});

// Onay → API'ye gönder
document.getElementById('addItemConfirmBtn').addEventListener('click', async () => {
  const p = addItemState.product;
  if (!p) return;
  const portion = Number(document.getElementById('addItemPortion').value) || 0;
  const extra = Number(document.getElementById('addItemExtraPieces').value) || 0;
  const note = document.getElementById('addItemNote').value.trim();
  // Aynı algoritma — isDrink kontrolü dahil
  const isDrinkCategory = isDrink(p);
  const isPieceBased = !isDrinkCategory && p.piecesPerPortion && p.piecesPerPortion > 0;

  let quantity, pieces;
  if (isPieceBased) {
    pieces = portion * p.piecesPerPortion + extra;
    if (pieces <= 0) { toast('Adet 0 olamaz', 'error'); return; }
    quantity = pieces;
  } else {
    if (portion <= 0) { toast('Adet 0 olamaz', 'error'); return; }
    quantity = portion;
    pieces = null;
  }

  try {
    const body = { productId: p.id, quantity };
    if (pieces !== null) body.pieces = pieces;
    if (note) body.note = note;
    const r = await api('POST', `/tables/${App.currentTable.tableNo}/items`, body);
    const noteStatus = (note && r) ? r.noteStatus : null;
    if (noteStatus && noteStatus !== 'APPLIED') {
      // Ürün eklendi ama not uygulanamadı — success toast yerine uyarı.
      if (noteStatus === 'UNSUPPORTED_SCHEMA') {
        toast('Ürün eklendi ancak ürün notları bu kurulumda desteklenmiyor.', 'error');
      } else if (noteStatus === 'FAILED') {
        toast('Ürün eklendi ancak not kaydedilemedi. Lütfen tekrar deneyin.', 'error');
      } else {
        toast('Ürün eklendi ancak not sipariş kalemine uygulanamadı.', 'error');
      }
    } else {
      toast(`✓ ${p.name} eklendi`, 'success');
    }
    document.getElementById('addItemDialog').classList.add('hidden');
    // ÖNEMLİ: masa detayına geri dönme — kullanıcı picker'da kalır
    // başka ürün eklemek için. Sadece picker üstündeki "ekleme özeti"ni güncelle.
    refreshAddedSummary();
  } catch (err) {
    toast('Ekleme: ' + err.message, 'error');
  }
});

/** Picker üstündeki "şu ana kadar eklenen" bar'ını günceller. */
async function refreshAddedSummary() {
  if (!App.currentTable) return;
  try {
    const snap = await api('GET', `/tables/${App.currentTable.tableNo}`);
    App.currentTable = snap;
    const bar = document.getElementById('addedSummary');
    if (bar) {
      const lines = (snap.lines || []);
      const itemCount = lines.reduce((sum, it) => sum + Number(it.quantity || 0), 0);
      // Masadaki TÜM ürünler — snapshot sırası aynen, sort/truncate/gizleme yok.
      // Server snapshot source of truth: local quantity tahmini yapılmaz.
      const linesHtml = lines.length === 0 ? '' : `
        <div class="added-summary-lines">
          ${lines.map(it =>
            `<div data-item-id="${it.itemId ?? ''}" data-item-name="${escapeHtml(it.productName || '')}">${escapeHtml(it.productName || '')} — ${escapeHtml(it.quantityLabel || String(it.quantity))}</div>`
          ).join('')}
        </div>`;
      bar.innerHTML = `
        <span>📋 <strong>Masa ${snap.tableNo}</strong> — ${itemCount} kalem</span>
        <span class="added-total">₺${Number(snap.total || 0).toFixed(2)}</span>
        ${linesHtml}
      `;
      // Satıra dokununca mevcut Azalt/Sil/Vazgeç sheet'i açılır — kimlik: itemId
      bar.querySelectorAll('.added-summary-lines > div').forEach(row => {
        row.addEventListener('click', () => {
          openItemActionSheet(Number(row.dataset.itemId), row.dataset.itemName);
        });
      });
    }
  } catch (err) {
    // sessiz geç — refresh önemli değil
  }
}
document.getElementById('addItemCancelBtn').addEventListener('click', () => {
  document.getElementById('addItemDialog').classList.add('hidden');
});

// ====================================================================
//   Header back button — view'a göre davranış
// ====================================================================

document.getElementById('backBtn').addEventListener('click', () => {
  const active = document.querySelector('.view.active');
  if (!active) return;
  switch (active.id) {
    case 'view-table-detail':
      goToTables();
      break;
    case 'view-products':
      openTable(App.currentTable.tableNo);
      break;
    case 'view-tables':
      goToDashboard();
      break;
    default:
      goToDashboard();
  }
});

// ====================================================================
//   Yardımcı
// ====================================================================

/**
 * Bir ürünün "içecek" kategorisinde olup olmadığını tespit eder.
 * İçecek ürünleri için porsiyon/şiş yerine sadece "adet" sorulur.
 *
 * NOT: toLocaleLowerCase('tr') ZORUNLU — yoksa Türkçe "İçecek" → "i̇çecek"
 * gibi yanlış bir karaktere düşer ve "içecek" keyword'ünü bulamayız.
 */
function isDrink(product) {
  if (!product) return false;
  const rawCat = product.categoryName || '';
  const cat = rawCat.toLocaleLowerCase('tr');
  if (!cat) return false;
  // ASCII versiyonunu da hesapla (çç → c, şş → s vb. fallback için)
  const catAscii = cat
    .replace(/ç/g, 'c').replace(/ğ/g, 'g').replace(/ı/g, 'i')
    .replace(/ö/g, 'o').replace(/ş/g, 's').replace(/ü/g, 'u');
  const keywords = [
    'içecek', 'icecek', 'içkı', 'ickı', 'icki', 'içki',
    'su', 'ayran', 'kola', 'çay', 'cay',
    'fanta', 'sprite', 'kahve', 'gazoz',
    'meşrubat', 'mesrubat', 'soda', 'limonata',
    'şalgam', 'salgam', 'şıra', 'sira',
    'bar', 'meşrub', 'mesrub', 'drink', 'beverage'
  ];
  return keywords.some(k => cat.includes(k) || catAscii.includes(k));
}

function escapeHtml(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

// ====================================================================
//   Satışlar / Giderler / Gün Sonu / İşlem Geçmişi
// ====================================================================

function todayStr() {
  // Yerel saat dilimine göre bugün (UTC değil) — TR'de gece geçince UTC günü atlayabiliyor
  const d = new Date();
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

async function goToSales() {
  showView('view-sales', 'Satışlar', true);
  const dateInput = document.getElementById('salesDate');
  if (!dateInput.value) dateInput.value = todayStr();
  dateInput.onchange = () => loadSales();
  loadSales();
}
async function loadSales() {
  const date = document.getElementById('salesDate').value || todayStr();
  try {
    const data = await api('GET', '/sales?date=' + date);
    // KASIYER: toplam ciro gözükmesin, sadece sayı
    const summaryEl = document.getElementById('salesSummary');
    if (App.user && App.user.role === 'ADMIN' && data.total != null) {
      summaryEl.textContent = data.count + ' satış • ₺' + Number(data.total).toFixed(2);
    } else {
      summaryEl.textContent = data.count + ' satış';
    }
    const list = document.getElementById('salesList');
    if (!data.sales || data.sales.length === 0) {
      list.innerHTML = '<p style="text-align:center;color:#999;padding:20px;">Bu günde satış yok</p>';
      return;
    }
    list.innerHTML = data.sales.map(s => {
      const t = s.timestamp ? s.timestamp.slice(11, 16) : '';
      const method = methodLabel(s.method);
      const orderAttr = s.orderId != null ? `data-order="${s.orderId}"` : '';
      const cursor = s.orderId != null ? 'cursor:pointer;' : '';
      return `<div class="record-item green clickable" ${orderAttr} style="${cursor}">
                <div class="record-main">
                  <div class="record-title">Masa ${s.tableNo || '?'} • ${method}</div>
                  <div class="record-meta">${escapeHtml(s.performer || '?')} • ${t}</div>
                </div>
                <div class="record-amount">₺${Number(s.amount || 0).toFixed(2)} ›</div>
              </div>`;
    }).join('');
    // Tıklama → detay modal
    list.querySelectorAll('.record-item[data-order]').forEach(item => {
      item.addEventListener('click', () => {
        const orderId = item.dataset.order;
        const sale = data.sales.find(s => String(s.orderId) === String(orderId));
        if (sale) openSaleDetail(sale);
      });
    });
  } catch (err) {
    toast('Satışlar alınamadı: ' + err.message, 'error');
  }
}

/** Satış detay modal — kalemleri çek ve göster. */
async function openSaleDetail(sale) {
  const dlg = document.getElementById('saleDetailModal');
  document.getElementById('saleDetailTitle').textContent =
    'Masa ' + (sale.tableNo || '?') + ' — ' + methodLabel(sale.method);
  const t = sale.timestamp ? sale.timestamp.replace('T', ' ').slice(0, 16) : '';
  document.getElementById('saleDetailMeta').textContent =
    (sale.performer || '?') + ' • ' + t;
  document.getElementById('saleDetailTotal').textContent =
    '₺' + Number(sale.amount || 0).toFixed(2);
  const itemsEl = document.getElementById('saleDetailItems');
  itemsEl.innerHTML = '<p style="text-align:center;color:#999;">Yükleniyor...</p>';
  dlg.classList.remove('hidden');
  try {
    const items = await api('GET', `/orders/${sale.orderId}/items`);
    if (!items || items.length === 0) {
      itemsEl.innerHTML = '<p style="text-align:center;color:#999;padding:20px;">Kalem bulunamadı</p>';
      return;
    }
    itemsEl.innerHTML = items.map(it => {
      const noteHtml = it.note ? `<div class="item-meta">${escapeHtml(it.note)}</div>` : '';
      const total = Number(it.lineTotal || (Number(it.unitPrice) * Number(it.quantity))).toFixed(2);
      return `<div class="order-item">
                <div>
                  <div class="item-name">${escapeHtml(it.name || '')}</div>
                  <div class="item-meta">${it.quantity} × ₺${Number(it.unitPrice).toFixed(2)}</div>
                  ${noteHtml}
                </div>
                <div class="item-amount">₺${total}</div>
              </div>`;
    }).join('');
  } catch (err) {
    itemsEl.innerHTML = '<p style="color:#c62828;padding:20px;">Hata: ' + escapeHtml(err.message) + '</p>';
  }
}
document.getElementById('saleDetailCloseBtn').addEventListener('click', () => {
  document.getElementById('saleDetailModal').classList.add('hidden');
});

let expenseMode = 'manual';  // 'manual' veya 'kg'

async function goToExpenses() {
  showView('view-expenses', 'Giderler', true);
  const dateInput = document.getElementById('expensesDate');
  if (!dateInput.value) dateInput.value = todayStr();
  dateInput.onchange = () => loadExpenses();
  loadExpenses();
  loadExpenseTemplates();

  // Mod butonları
  document.querySelectorAll('[data-exp-mode]').forEach(btn => {
    btn.onclick = () => {
      expenseMode = btn.dataset.expMode;
      document.querySelectorAll('[data-exp-mode]').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      document.getElementById('expManualFields').classList.toggle('hidden', expenseMode !== 'manual');
      document.getElementById('expKgFields').classList.toggle('hidden', expenseMode !== 'kg');
    };
  });

  // Kg toplam canlı önizleme
  const updateKgPreview = () => {
    const kg = parseFloat((document.getElementById('newExpKg').value || '').replace(',', '.'));
    const price = parseFloat((document.getElementById('newExpUnitPrice').value || '').replace(',', '.'));
    const total = (Number.isFinite(kg) && Number.isFinite(price)) ? (kg * price) : 0;
    document.getElementById('expKgTotalPreview').textContent = '₺' + total.toFixed(2);
  };
  document.getElementById('newExpKg').oninput = updateKgPreview;
  document.getElementById('newExpUnitPrice').oninput = updateKgPreview;
}

async function loadExpenseTemplates() {
  try {
    const templates = await api('GET', '/expense-templates');
    const grid = document.getElementById('expenseTemplates');
    grid.innerHTML = templates.map(t => `
      <button class="exp-template-btn" data-name="${escapeHtml(t.name)}" data-mode="${t.defaultMode}">
        <span class="icon">${t.icon || '📝'}</span>
        ${escapeHtml(t.name)}
      </button>
    `).join('');
    grid.querySelectorAll('.exp-template-btn').forEach(btn => {
      btn.addEventListener('click', () => useExpenseTemplate(btn.dataset.name, btn.dataset.mode));
    });
  } catch (err) { /* sessiz geç */ }
}

/** Hazır gider şablonuna tıklandığında prompt'larla doldur. */
async function useExpenseTemplate(name, mode) {
  if (mode === 'kg') {
    const kgStr = prompt(name + ' — Kaç kg alındı?', '');
    if (kgStr === null) return;
    const kg = parseFloat((kgStr || '').replace(',', '.'));
    if (!Number.isFinite(kg) || kg <= 0) { toast('Geçerli kg girin', 'error'); return; }
    const priceStr = prompt(name + ' — Kg fiyatı kaç ₺?', '');
    if (priceStr === null) return;
    const price = parseFloat((priceStr || '').replace(',', '.'));
    if (!Number.isFinite(price) || price < 0) { toast('Geçerli fiyat girin', 'error'); return; }
    try {
      await api('POST', '/expenses/kg', {
        description: name, quantityKg: kg, unitPricePerKg: price, date: todayStr()
      });
      toast('✓ ' + name + ' gideri eklendi (₺' + (kg * price).toFixed(2) + ')', 'success');
      loadExpenses();
    } catch (err) { toast('Hata: ' + err.message, 'error'); }
  } else {
    const amtStr = prompt(name + ' — Toplam tutar (₺):', '');
    if (amtStr === null) return;
    const amount = parseFloat((amtStr || '').replace(',', '.'));
    if (!Number.isFinite(amount) || amount <= 0) { toast('Geçerli tutar girin', 'error'); return; }
    try {
      await api('POST', '/expenses', { amount, description: name, date: todayStr() });
      toast('✓ ' + name + ' gideri eklendi', 'success');
      loadExpenses();
    } catch (err) { toast('Hata: ' + err.message, 'error'); }
  }
}
async function loadExpenses() {
  const date = document.getElementById('expensesDate').value || todayStr();
  try {
    const data = await api('GET', '/expenses?date=' + date);
    document.getElementById('expensesSummary').textContent =
      data.count + ' gider • ₺' + Number(data.total || 0).toFixed(2);
    const list = document.getElementById('expensesList');
    if (!data.expenses || data.expenses.length === 0) {
      list.innerHTML = '<p style="text-align:center;color:#999;padding:20px;">Bu günde gider yok</p>';
      return;
    }
    list.innerHTML = data.expenses.map(e => {
      const t = e.createdAt ? e.createdAt.slice(11, 16) : '';
      return `<div class="record-item orange">
                <div class="record-main">
                  <div class="record-title">${escapeHtml(e.description || '')}</div>
                  <div class="record-meta">${escapeHtml(e.performer || '?')} • ${t}</div>
                </div>
                <div class="record-amount">₺${Number(e.amount || 0).toFixed(2)}</div>
              </div>`;
    }).join('');
  } catch (err) {
    toast('Giderler alınamadı: ' + err.message, 'error');
  }
}

document.getElementById('addExpenseBtn').addEventListener('click', async () => {
  const description = document.getElementById('newExpDescription').value.trim();
  if (!description) { toast('Açıklama girin', 'error'); return; }

  if (expenseMode === 'kg') {
    const kg = parseFloat((document.getElementById('newExpKg').value || '').replace(',', '.'));
    const price = parseFloat((document.getElementById('newExpUnitPrice').value || '').replace(',', '.'));
    if (!Number.isFinite(kg) || kg <= 0) { toast('Geçerli kg girin', 'error'); return; }
    if (!Number.isFinite(price) || price < 0) { toast('Geçerli kg fiyatı girin', 'error'); return; }
    try {
      await api('POST', '/expenses/kg', {
        description, quantityKg: kg, unitPricePerKg: price, date: todayStr()
      });
      document.getElementById('newExpKg').value = '';
      document.getElementById('newExpUnitPrice').value = '';
      document.getElementById('newExpDescription').value = '';
      document.getElementById('expKgTotalPreview').textContent = '₺0,00';
      toast('✓ Kg bazlı gider eklendi', 'success');
      loadExpenses();
    } catch (err) {
      toast('Gider eklenemedi: ' + err.message, 'error');
    }
  } else {
    const amount = parseFloat((document.getElementById('newExpAmount').value || '').replace(',', '.'));
    if (!Number.isFinite(amount) || amount <= 0) { toast('Geçerli tutar girin', 'error'); return; }
    try {
      await api('POST', '/expenses', { amount, description, date: todayStr() });
      document.getElementById('newExpAmount').value = '';
      document.getElementById('newExpDescription').value = '';
      toast('✓ Gider eklendi', 'success');
      loadExpenses();
    } catch (err) {
      toast('Gider eklenemedi: ' + err.message, 'error');
    }
  }
});

let reportPeriod = 'day';  // 'day' veya 'month'
function todayMonth() {
  const d = new Date();
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  return `${y}-${m}`;
}

async function goToDaily() {
  showView('view-daily', 'Gün Sonu / Aylık', true);
  const dateInput = document.getElementById('dailyDate');
  const monthInput = document.getElementById('dailyMonth');
  // Her görünüm açılışında bugüne/bu aya resetle — kullanıcı manuel değiştirmediği sürece güncel kalsın
  dateInput.value = todayStr();
  monthInput.value = todayMonth();
  reportPeriod = 'day';
  // Toggle buton görünümünü de senkronize et
  document.querySelectorAll('#view-daily [data-period]').forEach(b => {
    b.classList.toggle('active', b.dataset.period === 'day');
  });
  dateInput.classList.remove('hidden');
  monthInput.classList.add('hidden');
  dateInput.onchange = () => loadDaily();
  monthInput.onchange = () => loadDaily();
  // Gün/Ay toggle
  document.querySelectorAll('#view-daily [data-period]').forEach(btn => {
    btn.onclick = () => {
      reportPeriod = btn.dataset.period;
      document.querySelectorAll('#view-daily [data-period]').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      const isMonth = reportPeriod === 'month';
      dateInput.classList.toggle('hidden', isMonth);
      monthInput.classList.toggle('hidden', !isMonth);
      document.getElementById('dailyReportTitle').textContent = isMonth
        ? '📅 Aylık Rapor' : '📅 Gün Sonu Raporu';
      loadDaily();
    };
  });
  loadDaily();
}

async function loadDaily() {
  try {
    let r;
    if (reportPeriod === 'month') {
      const month = document.getElementById('dailyMonth').value || todayMonth();
      r = await api('GET', '/reports/monthly?month=' + month);
    } else {
      const date = document.getElementById('dailyDate').value || todayStr();
      r = await api('GET', '/reports/daily?date=' + date);
    }
    document.getElementById('dailySalesTotal').textContent =
      '₺' + Number(r.salesTotal || 0).toFixed(2);
    document.getElementById('dailyExpenseTotal').textContent =
      '₺' + Number(r.expenseTotal || 0).toFixed(2);
    const net = Number(r.netProfit || 0);
    const netEl = document.getElementById('dailyNetProfit');
    netEl.textContent = (net >= 0 ? '+ ' : '') + '₺' + Math.abs(net).toFixed(2);
    netEl.style.color = net >= 0 ? '#a5d6a7' : '#ef9a9a';
    document.getElementById('dailyDetailInfo').textContent =
      r.salesCount + ' satış • ' + r.expenseCount + ' gider';

    // Ürün satış özeti
    loadProductSummary();
  } catch (err) {
    toast('Rapor alınamadı: ' + err.message, 'error');
  }
}

async function loadProductSummary() {
  const tbody = document.getElementById('productSummaryBody');
  tbody.innerHTML = '<tr><td colspan="4" style="text-align:center;color:#999;">Yükleniyor...</td></tr>';
  try {
    let url;
    if (reportPeriod === 'month') {
      const month = document.getElementById('dailyMonth').value || todayMonth();
      url = '/reports/product-summary?month=' + month;
    } else {
      const date = document.getElementById('dailyDate').value || todayStr();
      url = '/reports/product-summary?date=' + date;
    }
    const r = await api('GET', url);
    if (!r.rows || r.rows.length === 0) {
      tbody.innerHTML = '<tr><td colspan="4" style="text-align:center;color:#999;padding:20px;">Bu dönemde ürün satışı yok</td></tr>';
      return;
    }
    tbody.innerHTML = r.rows.map(row => {
      const pp = Number(row.piecesPerPortion) || 0;
      const qty = Number(row.totalQty) || 0;
      const unit = row.unitLabel || (pp > 0 ? 'şiş' : 'porsiyon');
      const soldText = qty + ' ' + escapeHtml(unit);
      let portionText;
      if (pp > 0) {
        const portions = Number(row.portionEquivalent) || (qty / pp);
        portionText = portions.toFixed(2) + ' porsiyon  (1 porsiyon = ' + pp + ' ' + escapeHtml(unit) + ')';
      } else {
        portionText = qty + ' porsiyon';
      }
      return `<tr>
        <td>${escapeHtml(row.productName || '')}</td>
        <td>${escapeHtml(unit)}</td>
        <td class="num">${soldText}</td>
        <td class="portion">${portionText}</td>
      </tr>`;
    }).join('');
  } catch (err) {
    tbody.innerHTML = '<tr><td colspan="4" style="color:#c62828;padding:20px;">Hata: ' + escapeHtml(err.message) + '</td></tr>';
  }
}

async function goToRefunds() {
  showView('view-refunds', 'İşlem Geçmişi', true);
  try {
    const logs = await api('GET', '/refunds');
    document.getElementById('refundsCount').textContent = logs.length + ' kayıt';
    const list = document.getElementById('refundsList');
    if (logs.length === 0) {
      list.innerHTML = '<p style="text-align:center;color:#999;padding:20px;">Henüz iade/iptal yok</p>';
      return;
    }
    list.innerHTML = logs.map(l => {
      const t = l.createdAt ? l.createdAt.slice(0, 16).replace('T', ' ') : '';
      const actionTr = actionLabel(l.actionType);
      const detail = l.productName ? ` • ${escapeHtml(l.productName)}` : '';
      const tableInfo = l.tableNo ? `Masa ${l.tableNo}` : '';
      return `<div class="record-item red">
                <div class="record-main">
                  <div class="record-title">${actionTr}${detail}</div>
                  <div class="record-meta">
                    ${escapeHtml(l.userName || '?')} • ${tableInfo} • ${t}
                    ${l.reason ? '<br><i>Sebep: ' + escapeHtml(l.reason) + '</i>' : ''}
                  </div>
                </div>
                <div class="record-amount">₺${Number(l.amount || 0).toFixed(2)}</div>
              </div>`;
    }).join('');
  } catch (err) {
    toast('İşlem geçmişi alınamadı: ' + err.message, 'error');
  }
}

function methodLabel(m) {
  switch (m) {
    case 'CASH': return '💵 Nakit';
    case 'CREDIT_CARD':
    case 'CARD': return '💳 Kart';
    case 'TRANSFER': return '🏦 EFT';
    default: return m || '?';
  }
}
function actionLabel(a) {
  switch (a) {
    case 'CLEAR_TABLE':   return '🗑 Masa Temizle';
    case 'REMOVE_ITEM':   return '✖ Kalem Sil';
    case 'DECREASE_ITEM': return '− Kalem Azalt';
    case 'CANCEL_ORDER':  return '⛔ Sipariş İptal';
    default: return a || '?';
  }
}

// ====================================================================
//   Saatlik Yoğunluk (SVG bar chart)
// ====================================================================

let hourlyMode = 'count';  // 'count' veya 'amount'

async function goToHourly() {
  showView('view-hourly', 'Saatlik Yoğunluk', true);
  const dateInput = document.getElementById('hourlyDate');
  // Her görünüm açılışında bugüne resetle
  dateInput.value = todayStr();
  dateInput.onchange = () => loadHourly();
  document.querySelectorAll('#view-hourly .mode-btn').forEach(b => {
    b.onclick = () => {
      hourlyMode = b.dataset.mode;
      document.querySelectorAll('#view-hourly .mode-btn').forEach(x => x.classList.remove('active'));
      b.classList.add('active');
      loadHourly();
    };
  });
  loadHourly();
}

async function loadHourly() {
  const date = document.getElementById('hourlyDate').value || todayStr();
  try {
    const data = await api('GET', '/reports/hourly?date=' + date);
    renderHourlyChart(data.hours || []);
  } catch (err) {
    toast('Saatlik rapor: ' + err.message, 'error');
  }
}

function renderHourlyChart(hours) {
  const svg = document.getElementById('hourlyChart');
  // viewBox: 720 × 320
  const W = 720, H = 320;
  const padL = 50, padR = 10, padT = 20, padB = 40;
  const chartW = W - padL - padR;
  const chartH = H - padT - padB;
  const isAmount = hourlyMode === 'amount';
  const vals = hours.map(h => Number(isAmount ? (h.amount || 0) : (h.count || 0)));
  const maxV = Math.max(1, ...vals);
  const barW = chartW / 24;
  const barColor = isAmount ? '#2e7d32' : '#1976d2';

  let html = '';
  // Y grid lines
  for (let i = 0; i <= 5; i++) {
    const y = padT + chartH - (chartH * i / 5);
    const labelVal = isAmount
      ? (maxV * i / 5).toFixed(0)
      : Math.round(maxV * i / 5);
    html += `<line x1="${padL}" y1="${y}" x2="${padL + chartW}" y2="${y}" stroke="#eee" />`;
    html += `<text x="${padL - 6}" y="${y + 4}" text-anchor="end" font-size="10" fill="#666">${labelVal}</text>`;
  }
  // Bars
  hours.forEach((h, i) => {
    const v = Number(isAmount ? (h.amount || 0) : (h.count || 0));
    const bh = chartH * (v / maxV);
    const x = padL + i * barW + 1;
    const y = padT + chartH - bh;
    html += `<rect x="${x}" y="${y}" width="${barW - 2}" height="${bh}" fill="${barColor}" opacity="0.85" />`;
    if (v > 0 && bh > 14) {
      const txt = isAmount ? Math.round(v) : v;
      html += `<text x="${x + (barW - 2) / 2}" y="${y + 12}" text-anchor="middle" font-size="9" fill="white">${txt}</text>`;
    }
    if (i % 2 === 0) {
      html += `<text x="${x + (barW - 2) / 2}" y="${H - 18}" text-anchor="middle" font-size="10" fill="#666">${String(i).padStart(2,'0')}</text>`;
    }
  });
  // Axis labels
  html += `<text x="${W/2}" y="${H - 4}" text-anchor="middle" font-size="11" fill="#444">Saat</text>`;
  html += `<text x="-${H/2}" y="14" text-anchor="middle" font-size="11" fill="#444" transform="rotate(-90)">${isAmount ? 'Tutar (₺)' : 'Adet'}</text>`;
  svg.innerHTML = html;
}

// ====================================================================
//   Ürün Yönetimi (Admin/Kasiyer)
// ====================================================================

let prodAdminCache = [];

async function goToProductAdmin() {
  showView('view-product-admin', 'Ürün Yönetimi', true);
  try {
    const [products, categories] = await Promise.all([
      api('GET', '/products/all'),
      api('GET', '/categories')
    ]);
    prodAdminCache = products;
    // Kategori dropdown'unu doldur
    const sel = document.getElementById('newProdCategory');
    sel.innerHTML = '<option value="">— Kategori yok —</option>' +
      categories.map(c => `<option value="${c.id}">${escapeHtml(c.name || '')}</option>`).join('');
    renderProdAdmin();
  } catch (err) {
    toast('Ürünler alınamadı: ' + err.message, 'error');
  }
}

document.getElementById('prodAdminSearch').addEventListener('input', renderProdAdmin);

function renderProdAdmin() {
  const q = (document.getElementById('prodAdminSearch').value || '').toLocaleLowerCase('tr');
  const list = document.getElementById('prodAdminList');
  const filtered = prodAdminCache.filter(p =>
    !q || (p.name && p.name.toLocaleLowerCase('tr').includes(q)));
  if (filtered.length === 0) {
    list.innerHTML = '<p style="text-align:center;color:#999;padding:20px;">Ürün yok</p>';
    return;
  }
  list.innerHTML = filtered.map(p => {
    const cls = p.active ? 'record-item green' : 'record-item red';
    const status = p.active ? '✓ Stokta' : '● TÜKENDİ';
    const cat = p.categoryName ? ` • ${escapeHtml(p.categoryName)}` : '';
    return `<div class="${cls}">
              <div class="record-main">
                <div class="record-title">${escapeHtml(p.name)} ${p.active ? '' : '<span style="color:#c62828">(TÜKENDİ)</span>'}</div>
                <div class="record-meta">₺${Number(p.unitPrice || 0).toFixed(2)}${cat}</div>
              </div>
              <button class="btn ${p.active ? 'btn-danger' : 'btn-green'} btn-mini"
                      onclick="toggleProductTukendi(${p.id}, ${!p.active})">
                ${p.active ? 'Tükendi' : 'Stokta'}
              </button>
            </div>`;
  }).join('');
}

window.toggleProductTukendi = async function(id, newActive) {
  try {
    await api('POST', `/products/${id}/active`, { active: newActive });
    toast(newActive ? 'Stokta' : 'Tükendi olarak işaretlendi', 'success');
    // Cache'i güncelle
    const p = prodAdminCache.find(x => x.id === id);
    if (p) p.active = newActive;
    renderProdAdmin();
  } catch (err) {
    toast('Hata: ' + err.message, 'error');
  }
};

document.getElementById('createProdBtn').addEventListener('click', async () => {
  const name = document.getElementById('newProdName').value.trim();
  const price = parseFloat((document.getElementById('newProdPrice').value || '').replace(',', '.'));
  const categoryId = document.getElementById('newProdCategory').value || null;
  const unitLabel = document.getElementById('newProdUnitLabel').value || 'porsiyon';
  const pieces = parseInt(document.getElementById('newProdPiecesPerPortion').value || '0', 10);
  if (!name) { toast('Ad gerekli', 'error'); return; }
  if (!Number.isFinite(price) || price < 0) { toast('Geçerli fiyat girin', 'error'); return; }
  try {
    await api('POST', '/products', {
      name, unitPrice: price,
      categoryId: categoryId ? Number(categoryId) : null,
      unitLabel,
      piecesPerPortion: pieces > 0 ? pieces : null
    });
    document.getElementById('newProdName').value = '';
    document.getElementById('newProdPrice').value = '';
    document.getElementById('newProdPiecesPerPortion').value = '0';
    document.getElementById('newProdUnitLabel').value = 'porsiyon';
    toast('Ürün eklendi', 'success');
    goToProductAdmin();
  } catch (err) {
    toast('Ekleme: ' + err.message, 'error');
  }
});

// ====================================================================
//   Kullanıcı Yönetimi
// ====================================================================

let userAdminCache = [];

async function goToUserAdmin() {
  showView('view-user-admin', 'Kullanıcı Yönetimi', true);
  try {
    userAdminCache = await api('GET', '/users');
    renderUserAdmin();
  } catch (err) {
    toast('Kullanıcılar alınamadı: ' + err.message, 'error');
  }
}

function renderUserAdmin() {
  const list = document.getElementById('userAdminList');
  if (userAdminCache.length === 0) {
    list.innerHTML = '<p style="text-align:center;color:#999;padding:20px;">Kullanıcı yok</p>';
    return;
  }
  list.innerHTML = userAdminCache.map(u => {
    const cls = u.active ? 'record-item green' : 'record-item';
    const status = u.active ? '✓ Aktif' : '✕ Pasif';
    const roleEmoji = u.role === 'KASIYER' ? '💼' : '🍽️';
    return `<div class="${cls}">
              <div class="record-main">
                <div class="record-title">${roleEmoji} ${escapeHtml(u.fullName || u.username)}</div>
                <div class="record-meta">${escapeHtml(u.username)} • ${u.role} • ${status}</div>
              </div>
              <div style="display:flex;flex-direction:column;gap:4px;">
                <button class="btn btn-mini ${u.active ? 'btn-orange' : 'btn-green'}"
                        onclick="toggleUserActive(${u.id})">
                  ${u.active ? 'Pasif' : 'Aktif'}
                </button>
                <button class="btn btn-mini btn-blue" onclick="resetUserPwd(${u.id})">🔑 Şifre</button>
                <button class="btn btn-mini btn-danger" onclick="delUser(${u.id})">🗑</button>
              </div>
            </div>`;
  }).join('');
}

window.toggleUserActive = async function(id) {
  try {
    await api('POST', `/users/${id}/active`, {});
    toast('Durum güncellendi', 'success');
    goToUserAdmin();
  } catch (err) { toast('Hata: ' + err.message, 'error'); }
};
window.resetUserPwd = async function(id) {
  const pwd = prompt('Yeni şifre (en az 4 karakter):', '');
  if (!pwd || pwd.length < 4) return;
  try {
    await api('POST', `/users/${id}/reset-password`, { password: pwd });
    toast('Şifre güncellendi', 'success');
  } catch (err) { toast('Hata: ' + err.message, 'error'); }
};
window.delUser = async function(id) {
  if (!confirm('Bu kullanıcı silinsin mi?')) return;
  try {
    await api('DELETE', `/users/${id}`);
    toast('Silindi', 'success');
    goToUserAdmin();
  } catch (err) { toast('Hata: ' + err.message, 'error'); }
};

document.getElementById('createUserBtn').addEventListener('click', async () => {
  const username = document.getElementById('newUserName').value.trim();
  const fullName = document.getElementById('newUserFullName').value.trim() || username;
  const role = document.getElementById('newUserRole').value;
  const password = document.getElementById('newUserPassword').value;
  if (!username || !password) { toast('Kullanıcı adı + şifre gerekli', 'error'); return; }
  if (password.length < 4) { toast('Şifre en az 4 karakter', 'error'); return; }
  try {
    await api('POST', '/users', { username, fullName, role, password });
    document.getElementById('newUserName').value = '';
    document.getElementById('newUserFullName').value = '';
    document.getElementById('newUserPassword').value = '';
    toast('Kullanıcı oluşturuldu', 'success');
    goToUserAdmin();
  } catch (err) {
    toast('Oluşturma: ' + err.message, 'error');
  }
});

// ====================================================================
//   Başlatma — saved auth kontrol
// ====================================================================

(function bootstrap() {
  // Önce sessionStorage'a bak (yeni güvenli depo); sonra eski localStorage kalıntısı.
  let savedAuth = sessionStorage.getItem('auth');
  let savedUser = sessionStorage.getItem('user');
  if (!savedAuth || !savedUser) {
    // Eski localStorage'dan migrasyon — bir kez al, sessionStorage'a taşı, localStorage'dan sil.
    savedAuth = localStorage.getItem('auth');
    savedUser = localStorage.getItem('user');
    if (savedAuth && savedUser) {
      sessionStorage.setItem('auth', savedAuth);
      sessionStorage.setItem('user', savedUser);
    }
    // Her durumda localStorage'ı temizle — güvenlik kalıntısı kalmasın.
    localStorage.removeItem('auth');
    localStorage.removeItem('user');
  }
  if (savedAuth && savedUser) {
    App.auth = savedAuth;
    try {
      App.user = JSON.parse(savedUser);
      onLogin();
      return;
    } catch (e) { /* fall through to login */ }
  }
  showView('view-login');
})();

// ====================================================================
//   Masa Rezervasyonu
// ====================================================================

async function goToReservations() {
  showView('view-reservations', 'Rezervasyonlar', true);
  const dateInput = document.getElementById('reservationDate');
  dateInput.value = todayStr();
  dateInput.onchange = () => loadReservations();
  document.getElementById('newReservationBtn').onclick = () => openReservationModal();
  document.getElementById('closeReservationModal').onclick = closeReservationModal;
  document.getElementById('resvCancelBtn').onclick = closeReservationModal;
  document.getElementById('resvSaveBtn').onclick = saveReservation;
  document.getElementById('resvTablePickBtn').onclick = openTablePicker;
  document.getElementById('closeTablePickerBtn').onclick = closeTablePicker;
  await loadReservations();
}

// --- Masa seçici overlay ---
async function openTablePicker() {
  const modal = document.getElementById('tablePickerModal');
  const grid = document.getElementById('tablePickerGrid');
  grid.innerHTML = '<p style="text-align:center;color:#999;grid-column:1/-1;">Yükleniyor...</p>';
  modal.classList.remove('hidden');
  try {
    const tables = await api('GET', '/tables');
    if (!tables || tables.length === 0) {
      grid.innerHTML = '<p style="text-align:center;color:#999;grid-column:1/-1;">Masa yok</p>';
      return;
    }
    tables.sort((a, b) => (a.tableNo || 0) - (b.tableNo || 0));
    grid.innerHTML = tables.map(t => {
      const cls = (t.status && t.status !== 'EMPTY') ? 'occupied' : '';
      return `<button type="button" class="${cls}" data-tn="${t.tableNo}">Masa ${t.tableNo}</button>`;
    }).join('');
    grid.querySelectorAll('button').forEach(b => {
      b.onclick = () => {
        const tn = b.dataset.tn;
        document.getElementById('resvTableNo').value = tn;
        const pickBtn = document.getElementById('resvTablePickBtn');
        pickBtn.textContent = 'Masa ' + tn;
        pickBtn.classList.add('has-value');
        closeTablePicker();
      };
    });
  } catch (err) {
    grid.innerHTML = '<p style="color:#c62828;grid-column:1/-1;">Hata: ' + escapeHtml(err.message) + '</p>';
  }
}
function closeTablePicker() {
  document.getElementById('tablePickerModal').classList.add('hidden');
}

async function loadReservations() {
  const list = document.getElementById('reservationsList');
  const date = document.getElementById('reservationDate').value || todayStr();
  list.innerHTML = '<p style="text-align:center;color:#999;padding:20px;">Yükleniyor...</p>';
  try {
    const rows = await api('GET', '/reservations?date=' + date);
    if (!rows || rows.length === 0) {
      list.innerHTML = '<p style="text-align:center;color:#999;padding:20px;">Bu tarihte rezervasyon yok</p>';
      return;
    }
    list.innerHTML = rows.map(r => renderReservation(r)).join('');
    // Aksiyon butonlarını bağla
    list.querySelectorAll('[data-resv-action]').forEach(b => {
      b.onclick = async () => {
        const id = b.dataset.resvId;
        const act = b.dataset.resvAction;
        const label = ({cancel:'iptal', seat:'oturt', 'no-show':'gelmedi'})[act] || act;
        if (!confirm(`Bu rezervasyonu '${label}' olarak işaretle?`)) return;
        try {
          await api('POST', `/reservations/${id}/${act}`);
          loadReservations();
        } catch (err) {
          toast('İşlem başarısız: ' + err.message, 'error');
        }
      };
    });
  } catch (err) {
    list.innerHTML = '<p style="color:#c62828;padding:20px;">Hata: ' + escapeHtml(err.message) + '</p>';
  }
}

function renderReservation(r) {
  const cls = ({BOOKED:'', SEATED:'resv-seated', CANCELLED:'resv-cancelled', NO_SHOW:'resv-noshow'})[r.status] || '';
  const startT = r.startTime ? r.startTime.slice(11, 16) : '';
  const endT   = r.endTime   ? r.endTime.slice(11, 16)   : '';
  const phone = r.customerPhone ? ` • ☎ ${escapeHtml(r.customerPhone)}` : '';
  const notes = r.notes ? `<div class="resv-meta">📝 ${escapeHtml(r.notes)}</div>` : '';
  const actions = (r.status === 'BOOKED')
    ? `<div class="resv-actions">
         <button class="success" data-resv-id="${r.id}" data-resv-action="seat">✓ Oturt</button>
         <button data-resv-id="${r.id}" data-resv-action="no-show">⏰ Gelmedi</button>
         <button class="danger" data-resv-id="${r.id}" data-resv-action="cancel">✖ İptal</button>
       </div>` : '';
  return `<div class="resv-item ${cls}">
    <div class="resv-row">
      <span class="resv-time">${startT} – ${endT}</span>
      <span class="resv-table">Masa ${r.tableNo}</span>
    </div>
    <div class="resv-name">${escapeHtml(r.customerName || '?')} <span class="resv-meta">(${r.partySize || 1} kişi)${phone}</span></div>
    ${notes}
    ${actions}
  </div>`;
}

function openReservationModal() {
  const modal = document.getElementById('newReservationModal');
  // Varsayılan zamanları doldur — seçili tarihte 19:00–21:00
  const date = document.getElementById('reservationDate').value || todayStr();
  document.getElementById('resvStart').value = `${date}T19:00`;
  document.getElementById('resvEnd').value   = `${date}T21:00`;
  document.getElementById('resvTableNo').value = '0';
  const pickBtn = document.getElementById('resvTablePickBtn');
  pickBtn.textContent = 'Masa Seç …';
  pickBtn.classList.remove('has-value');
  document.getElementById('resvName').value = '';
  document.getElementById('resvPhone').value = '';
  document.getElementById('resvParty').value = '2';
  document.getElementById('resvNotes').value = '';
  modal.classList.remove('hidden');
}
function closeReservationModal() {
  document.getElementById('newReservationModal').classList.add('hidden');
}

async function saveReservation() {
  const tn = Number(document.getElementById('resvTableNo').value) || 0;
  if (tn <= 0) { toast('Önce masa seçin', 'error'); return; }
  const body = {
    tableNo: tn,
    startTime: document.getElementById('resvStart').value,
    endTime: document.getElementById('resvEnd').value,
    customerName: document.getElementById('resvName').value.trim(),
    customerPhone: document.getElementById('resvPhone').value.trim() || null,
    partySize: Number(document.getElementById('resvParty').value) || 1,
    notes: document.getElementById('resvNotes').value.trim() || null
  };
  if (!body.customerName) { toast('Müşteri adı gerekli', 'error'); return; }
  if (!body.startTime || !body.endTime) { toast('Saat seçimi gerekli', 'error'); return; }
  try {
    await api('POST', '/reservations', body);
    closeReservationModal();
    toast('Rezervasyon oluşturuldu', 'success');
    loadReservations();
  } catch (err) {
    toast('Eklenemedi: ' + err.message, 'error');
  }
}

// ====================================================================
//   PWA Service Worker kaydı (eskiden index.html'de inline idi;
//   CSP script-src 'self' inline'ı bloklar — buraya taşındı)
// ====================================================================
if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/app/sw.js')
      .then(() => console.log('[PWA] Service worker kayıtlı'))
      .catch(err => console.warn('[PWA] SW kayıt hatası:', err));
  });
}
