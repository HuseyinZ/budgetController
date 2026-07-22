package UI;

import model.ItemAddWithNoteResult;
import model.ItemNoteUpdateResult;
import model.MoneyUtil;
import model.PaymentMethod;
import model.Role;
import model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import service.print.PrintingService;
import state.AppState;
import state.OrderLine;
import state.OrderLogEntry;
import state.TableOrderStatus;
import state.TableSnapshot;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class TableOrderDialog extends JDialog {
    private static final Logger LOG = LoggerFactory.getLogger(TableOrderDialog.class);
    private static final DateTimeFormatter LOG_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private final AppState appState;
    private final User currentUser;
    private final int tableNo;
    private final DefaultTableModel tableModel = new DefaultTableModel(new Object[]{"Ürün", "Adet", "Birim", "Toplam", "Durum"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new JTable(tableModel);
    private final JTextArea logArea = new JTextArea(8, 24);
    private final JLabel totalLabel = new JLabel(" ");
    private final JLabel statusLabel = new JLabel(" ");
    private final JButton markServedButton = new JButton("Sipariş hazır");
    private final JButton saleButton = new JButton("Satış yap");
    private final JButton sendKitchenButton = new JButton("Mutfağa Gönder");
    /**
     * Singleton tarzı tek bir PrintingService kullanımı — yazıcı cache'i
     * dialog kapansa bile sürer. Lazy init: yazıcı bağlanmamış olsa bile
     * uygulama açılır.
     */
    private static volatile PrintingService PRINTING_SERVICE;

    private static PrintingService printingService() {
        PrintingService local = PRINTING_SERVICE;
        if (local == null) {
            synchronized (TableOrderDialog.class) {
                local = PRINTING_SERVICE;
                if (local == null) {
                    try {
                        local = new PrintingService();
                    } catch (RuntimeException ex) {
                        // Yazıcı kayıtları yoksa DAO patlayabilir; sessiz no-op
                        local = null;
                    }
                    PRINTING_SERVICE = local;
                }
            }
        }
        return local;
    }
    private final PropertyChangeListener listener = this::handleStateChange;
    private final NumberFormat currencyFormat = MoneyUtil.turkishLiraCurrencyFormat();
    private final boolean waiterRole;
    private java.util.function.Consumer<Integer> onReadyListener;
    private boolean fullScreen;
    private Rectangle windowedBounds;

    public TableOrderDialog(Window owner, AppState appState, TableSnapshot snapshot, User user) {
        super(owner, "Masa " + snapshot.getTableNo(), ModalityType.APPLICATION_MODAL);
        this.appState = Objects.requireNonNull(appState, "appState");
        this.currentUser = Objects.requireNonNull(user, "user");

        // Masa kilidi — başkası kullanıyorsa uyarı ver, dialog'u açma
        if (!appState.acquireTableLock(snapshot.getTableNo(), user)) {
            AppState.TableLock lock = appState.getTableLock(snapshot.getTableNo());
            String holder = (lock == null) ? "?" : lock.userName;
            javax.swing.SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(owner,
                        "Bu masa şu anda " + holder + " tarafından kullanılıyor.\n"
                       + "Lütfen onun çıkmasını bekleyin.",
                        "Masa Kilitli", JOptionPane.WARNING_MESSAGE);
                dispose();
            });
        }
        this.tableNo = snapshot.getTableNo();
        this.waiterRole = user.getRole() == Role.GARSON;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(12, 12));
        setResizable(true);

        Dimension preferredSize = new Dimension(1300, 800);
        setPreferredSize(preferredSize);
        setMinimumSize(preferredSize);

        add(buildHeader(snapshot), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        updateFromSnapshot(snapshot);
        appState.addPropertyChangeListener(listener);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                appState.removePropertyChangeListener(listener);
            }
        });
        pack();
        setSize(Math.max(getWidth(), preferredSize.width), Math.max(getHeight(), preferredSize.height));
        setLocationRelativeTo(owner);
    }

    private JComponent buildHeader(TableSnapshot snapshot) {
        JPanel panel = new JPanel(new BorderLayout());
        JLabel title = new JLabel("Masa " + snapshot.getTableNo() + " - " + snapshot.getBuilding() + " / " + snapshot.getSection());
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
        panel.add(title, BorderLayout.WEST);
        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD));
        statusPanel.add(statusLabel);
        panel.add(statusPanel, BorderLayout.EAST);
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        return panel;
    }

    /** Dialog kapanırken masa kilidini bırak. */
    @Override
    public void dispose() {
        try { appState.releaseTableLock(tableNo, currentUser); }
        catch (RuntimeException ex) {
            LOG.debug("Table lock release failed during dialog dispose: {}", ex.toString());
        }
        super.dispose();
    }

    public void setOnReadyListener(java.util.function.Consumer<Integer> l) {
        this.onReadyListener = l;
    }

    private JComponent buildCenter() {
        JPanel panel = new JPanel(new BorderLayout(12, 12));

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton productsButton = new JButton("Ürünler");
        productsButton.addActionListener(e -> openProductPicker());
        productsButton.setPreferredSize(new Dimension(160,48));
        controls.add(productsButton);
        JButton decreaseButton = new JButton("Azalt");
        decreaseButton.setPreferredSize(new Dimension(160,48));
        decreaseButton.addActionListener(e -> decrementSelected());
        controls.add(decreaseButton);
        JButton removeButton = new JButton("Sil");
        removeButton.setPreferredSize(new Dimension(160,48));
        removeButton.addActionListener(e -> removeSelected());
        controls.add(removeButton);

        JButton noteButton = new JButton("Not Ekle");
        noteButton.setPreferredSize(new Dimension(160,48));
        noteButton.setToolTipText("Seçili kaleme not / özelleştirme (soğansız, az pişmiş, vs.) ekle");
        noteButton.addActionListener(e -> addNoteToSelected());
        controls.add(noteButton);

        // Masa transferi butonu — siparişi başka boş masaya taşır
        JButton transferButton = new JButton("Masayı Taşı");
        transferButton.setPreferredSize(new Dimension(160, 48));
        transferButton.setToolTipText("Bu masadaki açık siparişi başka boş masaya taşı");
        transferButton.setBackground(new Color(33, 150, 243));
        transferButton.setForeground(Color.WHITE);
        transferButton.setOpaque(true);
        transferButton.setBorderPainted(false);
        transferButton.addActionListener(e -> transferTableToTarget());
        controls.add(transferButton);
        panel.add(controls, BorderLayout.NORTH);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        // Dokunmatik dostu satır yüksekliği
        table.setRowHeight(Math.max(40, table.getRowHeight()));
        // Yeni eklenen ("YENİ") satırları farklı renkle göster
        table.setDefaultRenderer(Object.class, new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected,
                                                           boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column);
                if (!isSelected) {
                    Object statusCell = (t.getColumnCount() > 4) ? t.getValueAt(row, 4) : null;
                    boolean pending = "YENİ".equals(statusCell);
                    if (pending) {
                        c.setBackground(new Color(255, 243, 205));   // açık sarı/turuncu
                        c.setForeground(new Color(120, 60, 0));      // koyu turuncu metin
                        if (c instanceof JLabel jl) {
                            jl.setFont(jl.getFont().deriveFont(Font.BOLD));
                        }
                    } else {
                        c.setBackground(new Color(240, 240, 240));   // soluk gri (basıldı)
                        c.setForeground(Color.DARK_GRAY);
                        if (c instanceof JLabel jl) {
                            jl.setFont(jl.getFont().deriveFont(Font.PLAIN));
                        }
                    }
                }
                return c;
            }
        });
        JScrollPane tableScroll = new JScrollPane(table);
        panel.add(tableScroll, BorderLayout.CENTER);

        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("Değişiklik Geçmişi"));
        panel.add(logScroll, BorderLayout.EAST);

        return panel;
    }

    private void openProductPicker() {
        Window owner = SwingUtilities.getWindowAncestor(this);
        ProductPickerDialog dialog = new ProductPickerDialog(owner, tableNo);
        // Batch içindeki not hatalarını ve Stage 0G çakışmalarını biriktir —
        // picker kapandıktan sonra en fazla TEK özet dialog.
        EnumSet<ItemNoteUpdateResult> noteFailures = EnumSet.noneOf(ItemNoteUpdateResult.class);
        boolean[] noteConflict = {false};
        dialog.setOnSelect(selection -> {
            if (selection == null) return;
            // Stage 0G: guard + add + not tek AppState wrapper'ında (quantity
            // artmadan önce çakışma kontrolü; pieces null → porsiyon yolu).
            ItemAddWithNoteResult result = appState.addItemWithNote(
                    tableNo, selection.productId(), selection.quantity(),
                    selection.piecesOverride(), selection.note(), currentUser);
            if (!result.itemAdded()) {
                // Çakışma: ürün eklenmedi, not uygulanmadı — diğer ürünler işlenmeye devam eder.
                noteConflict[0] = true;
                return;
            }
            if (result.noteResult() != null && result.noteResult() != ItemNoteUpdateResult.APPLIED) {
                noteFailures.add(result.noteResult());
            }
        });
        dialog.setVisible(true);
        // APPLICATION_MODAL: buraya gelindiğinde picker kapanmıştır (EDT'deyiz).
        String warning = pickerBatchWarningMessage(noteConflict[0], noteFailures);
        if (warning != null) {
            JOptionPane.showMessageDialog(this, warning, "Uyarı", JOptionPane.WARNING_MESSAGE);
        }
    }

    /**
     * Picker batch'i için birleşik uyarı mesajı — batch başına en fazla TEK modal.
     * {@code null} → uyarı gerekmez.
     */
    private static String pickerBatchWarningMessage(boolean noteConflict,
                                                    EnumSet<ItemNoteUpdateResult> noteFailures) {
        boolean hasFailures = !noteFailures.isEmpty();
        if (!noteConflict && !hasFailures) {
            return null;
        }
        if (noteConflict && !hasFailures) {
            return "Bir veya daha fazla ürün siparişte farklı bir notla zaten bulunuyor. "
                    + "Farklı notlu ürünler henüz ayrı satır olarak desteklenmediği için bu ürünler eklenmedi.";
        }
        if (!noteConflict) {
            return batchNoteFailureMessage(noteFailures);
        }
        return "Bazı ürünler farklı not çakışması nedeniyle eklenmedi.\n\n"
                + "Ayrıca bir veya daha fazla ürün notu kaydedilemedi. Siparişi kontrol edin.";
    }

    /**
     * Picker batch'inde biriken not hataları için tek kullanıcı mesajı.
     * Öncelik: UNSUPPORTED_SCHEMA > FAILED > NOT_FOUND.
     */
    private static String batchNoteFailureMessage(EnumSet<ItemNoteUpdateResult> failures) {
        if (failures.contains(ItemNoteUpdateResult.UNSUPPORTED_SCHEMA)) {
            return "Ürünler siparişe eklendi ancak ürün notları bu kurulumda desteklenmediği için notlar kaydedilemedi.";
        }
        if (failures.contains(ItemNoteUpdateResult.FAILED)) {
            return "Ürünler siparişe eklendi ancak bir veya daha fazla not kaydedilemedi. Siparişi kontrol edip \"Not Ekle\" ile tekrar deneyin.";
        }
        return "Ürünler siparişe eklendi ancak bir veya daha fazla not ilgili sipariş kalemine uygulanamadı. Siparişi kontrol edin.";
    }

    /** Tek kalemlik not sonucu için kullanıcı mesajı. {@code APPLIED} için çağrılmamalı. */
    private static String noteResultWarning(ItemNoteUpdateResult result) {
        return switch (result) {
            case NOT_FOUND -> "Not uygulanamadı. Sipariş kalemi artık mevcut olmayabilir.";
            case UNSUPPORTED_SCHEMA -> "Ürün notları bu kurulumda desteklenmiyor. Not kaydedilemedi.";
            case FAILED -> "Not kaydedilemedi. Lütfen \"Not Ekle\" ile tekrar deneyin.";
            case APPLIED -> "";
        };
    }

    private JComponent buildFooter() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton clearButton = new JButton("Siparişi temizle");
        clearButton.setPreferredSize(new Dimension(160,48));
        clearButton.addActionListener(e -> clearOrder());
        clearButton.setVisible(true);
        panel.add(clearButton);

        markServedButton.setText("✓ Teslim Edildi");
        markServedButton.addActionListener(e -> markServed());
        // Yeşil renkli, dikkat çekici — garson siparişi servis ettikten sonra basar
        markServedButton.setBackground(new Color(46, 125, 50));
        markServedButton.setForeground(Color.WHITE);
        markServedButton.setOpaque(true);
        markServedButton.setBorderPainted(false);
        markServedButton.setFocusPainted(false);
        markServedButton.setFont(markServedButton.getFont().deriveFont(Font.BOLD));
        markServedButton.setVisible(true);
        markServedButton.setPreferredSize(new Dimension(180, 48));
        markServedButton.setToolTipText("Sipariş masaya servis edildi — masa yeşil olur");

        panel.add(markServedButton);

        // ---- Mutfağa Gönder butonu ----
        sendKitchenButton.setPreferredSize(new Dimension(180, 48));
        sendKitchenButton.setToolTipText("Açık siparişin tüm kalemlerini ilgili mutfak yazıcılarına basar");
        sendKitchenButton.addActionListener(e -> sendToKitchens());
        panel.add(sendKitchenButton);

        saleButton.addActionListener(e -> performSale());
        boolean canSell = currentUser.getRole() == Role.ADMIN || currentUser.getRole() == Role.KASIYER;
        saleButton.setVisible(canSell);
        panel.add(saleButton);
        saleButton.setPreferredSize(new Dimension(160,48));

        // ---- Hesabı Böl butonu ----
        JButton splitButton = new JButton("Hesabı Böl");
        splitButton.setPreferredSize(new Dimension(160, 48));
        splitButton.setBackground(new Color(255, 152, 0));
        splitButton.setForeground(Color.WHITE);
        splitButton.setOpaque(true);
        splitButton.setBorderPainted(false);
        splitButton.setToolTipText("Hesabı 2/3/4 kişiye böl, her parça için ayrı ödeme yöntemi seç");
        splitButton.addActionListener(e -> openSplitPaymentDialog());
        splitButton.setVisible(canSell);
        panel.add(splitButton);


        totalLabel.setFont(totalLabel.getFont().deriveFont(Font.BOLD));
        panel.add(totalLabel);
        panel.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
        return panel;
    }

    private void handleStateChange(PropertyChangeEvent event) {
        if (!AppState.EVENT_TABLES.equals(event.getPropertyName())) {
            return;
        }
        Object newValue = event.getNewValue();
        if (newValue instanceof Integer tableNumber && tableNumber == tableNo) {
            SwingUtilities.invokeLater(() -> updateFromSnapshot(appState.snapshot(tableNo)));
        }
    }

    private void decrementSelected() {
        int row = table.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Azaltmak için satır seçin", "Uyarı", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String product = stripNoteSuffix((String) tableModel.getValueAt(row, 0));
        // Admin/Kasiyer için iade nedeni iste; garson sadece pending kalemi azaltabilir (servis tarafında kontrol)
        String reason = null;
        if (currentUser != null
                && (currentUser.getRole() == model.Role.ADMIN || currentUser.getRole() == model.Role.KASIYER)) {
            reason = promptRefundReason("1 adet '" + product + "' azaltılacak. Nedeni:");
            if (reason == null) return;  // İptal edildi
        }
        try {
            appState.decreaseItem(tableNo, product, 1, currentUser, reason);
        } catch (SecurityException ex) {
            JOptionPane.showMessageDialog(this,
                    ex.getMessage(),
                    "Yetki Yok", JOptionPane.WARNING_MESSAGE);
        } catch (RuntimeException ex) {
            JOptionPane.showMessageDialog(this,
                    "Kalem azaltılamadı: " + ex.getMessage(),
                    "Hata", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Admin/Kasiyer için iade nedenini ister.
     * @return girilen sebep (boş olamaz); kullanıcı iptal ederse null
     */
    private String promptRefundReason(String prompt) {
        String reason = (String) JOptionPane.showInputDialog(
                this,
                prompt + "\n(Bu kayıt İşlem Geçmişi panelinde tutulur)",
                "İade Nedeni",
                JOptionPane.QUESTION_MESSAGE, null, null, "");
        if (reason == null) {
            return null;  // İptal
        }
        reason = reason.trim();
        if (reason.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "İade nedeni boş olamaz.",
                    "Eksik Bilgi", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        return reason;
    }

    /** Tabloda "Adana Kebap  (Soğansız)" gibi gösterilen değerden sadece ürün adını döner. */
    private String stripNoteSuffix(String label) {
        if (label == null) return null;
        int idx = label.indexOf("  (");
        return idx > 0 ? label.substring(0, idx) : label;
    }

    /**
     * Seçili kaleme not / özelleştirme ekle.
     * <p>Not: Şu an aynı ürünün birden fazla "varyant"ı (örn. 2 soğanlı + 1 soğansız)
     * tek satırda tutuluyor. Eklenen not o ürünün tüm satırına yansır.
     */
    private void addNoteToSelected() {
        int row = table.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Not eklemek için bir satır seçin",
                    "Uyarı", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String product = stripNoteSuffix((String) tableModel.getValueAt(row, 0));
        if (product == null) return;

        ProductNoteDialog dlg = new ProductNoteDialog(
                SwingUtilities.getWindowAncestor(this), product, null);
        String picked = dlg.pickNote();
        if (picked == null) return;   // İptal

        try {
            ItemNoteUpdateResult result = appState.setItemNote(tableNo, product, picked, currentUser);
            if (result != ItemNoteUpdateResult.APPLIED) {
                JOptionPane.showMessageDialog(this,
                        noteResultWarning(result),
                        "Uyarı", JOptionPane.WARNING_MESSAGE);
            }
        } catch (RuntimeException ex) {
            JOptionPane.showMessageDialog(this,
                    "Not kaydedilemedi: " + ex.getMessage(),
                    "Hata", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void removeSelected() {
        int row = table.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Silmek için satır seçin", "Uyarı", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String product = stripNoteSuffix((String) tableModel.getValueAt(row, 0));
        int confirm = JOptionPane.showConfirmDialog(this,
                product + " ürününü silmek istiyor musunuz?",
                "Onay", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;

        String reason = null;
        if (currentUser != null
                && (currentUser.getRole() == model.Role.ADMIN || currentUser.getRole() == model.Role.KASIYER)) {
            reason = promptRefundReason("'" + product + "' ürünü tamamen silinecek. Nedeni:");
            if (reason == null) return;
        }
        try {
            appState.removeItem(tableNo, product, currentUser, reason);
        } catch (SecurityException ex) {
            JOptionPane.showMessageDialog(this,
                    ex.getMessage(),
                    "Yetki Yok", JOptionPane.WARNING_MESSAGE);
        } catch (RuntimeException ex) {
            JOptionPane.showMessageDialog(this,
                    "Kalem silinemedi: " + ex.getMessage(),
                    "Hata", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** "Masayı Taşı" — boş hedef masalardan birini seçtir, transfer et. */
    private void transferTableToTarget() {
        java.util.List<Integer> targets = appState.getAvailableTransferTargets(tableNo, currentUser);
        if (targets.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Taşınabilecek boş masa bulunamadı.",
                    "Uyarı", JOptionPane.WARNING_MESSAGE);
            return;
        }
        // Hedef masa seçim listesi
        Integer[] options = targets.toArray(new Integer[0]);
        Object[] labels = new Object[options.length];
        for (int i = 0; i < options.length; i++) {
            labels[i] = "Masa " + options[i];
        }
        Object choice = JOptionPane.showInputDialog(this,
                "Bu siparişi hangi masaya taşımak istiyorsunuz?",
                "Masa Taşı",
                JOptionPane.QUESTION_MESSAGE, null, labels, labels[0]);
        if (choice == null) return;  // iptal
        // Seçimi indexe çevir
        int targetTable = -1;
        for (int i = 0; i < labels.length; i++) {
            if (labels[i].equals(choice)) {
                targetTable = options[i];
                break;
            }
        }
        if (targetTable < 0) return;

        // Onay
        int confirm = JOptionPane.showConfirmDialog(this,
                "Masa " + tableNo + " → Masa " + targetTable + " transferi onaylanıyor mu?",
                "Onay", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;

        try {
            appState.transferTable(tableNo, targetTable, currentUser);
            JOptionPane.showMessageDialog(this,
                    "Sipariş başarıyla Masa " + targetTable + "'e taşındı.",
                    "Başarılı", JOptionPane.INFORMATION_MESSAGE);
            dispose();  // dialog kapat — kullanıcı yeni masaya gitsin
        } catch (SecurityException ex) {
            JOptionPane.showMessageDialog(this,
                    ex.getMessage(),
                    "Yetki Yok", JOptionPane.WARNING_MESSAGE);
        } catch (RuntimeException ex) {
            JOptionPane.showMessageDialog(this,
                    "Transfer başarısız: " + ex.getMessage(),
                    "Hata", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void clearOrder() {
        // Garson masa temizleyemez — erken çık
        if (currentUser == null
                || (currentUser.getRole() != model.Role.ADMIN
                    && currentUser.getRole() != model.Role.KASIYER)) {
            JOptionPane.showMessageDialog(this,
                    "Masayı temizleme yetkisi sadece Admin/Kasiyer'de.",
                    "Yetki Yok", JOptionPane.WARNING_MESSAGE);
            return;
        }
        int choice = JOptionPane.showConfirmDialog(this,
                "Tüm siparişler silinsin mi? Bu işlem geri alınamaz.",
                "Onay", JOptionPane.YES_NO_OPTION);
        if (choice != JOptionPane.YES_OPTION) return;

        String reason = promptRefundReason("Masa " + tableNo + " tamamen temizlenecek. Nedeni:");
        if (reason == null) return;

        try {
            appState.clearTable(tableNo, currentUser, reason);
        } catch (SecurityException ex) {
            JOptionPane.showMessageDialog(this,
                    ex.getMessage(),
                    "Yetki Yok", JOptionPane.WARNING_MESSAGE);
        } catch (RuntimeException ex) {
            JOptionPane.showMessageDialog(this,
                    "Masa temizlenemedi: " + ex.getMessage(),
                    "Hata", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void markServed() {
        try {
            appState.markServed(tableNo, currentUser);
            JOptionPane.showMessageDialog(this,
                    "Masa " + tableNo + " — siparişler servis edildi olarak işaretlendi.",
                    "Teslim Edildi", JOptionPane.INFORMATION_MESSAGE);
            dispose();  // dialog kapansın, kullanıcı masa listesine dönsün (yeşil görsün)
        } catch (RuntimeException ex) {
            JOptionPane.showMessageDialog(this,
                    "İşlem başarısız: " + ex.getMessage(),
                    "Hata", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Açık siparişi mutfaklara basar. Ağ G/Ç UI'yi kilitlemesin diye
     * SwingWorker üzerinden çalıştırılır.
     */
    private void sendToKitchens() {
        PrintingService printing = printingService();
        if (printing == null) {
            JOptionPane.showMessageDialog(this,
                    "Yazıcı servisi başlatılamadı.\nÖnce admin paneliden yazıcıları tanımlayın.",
                    "Yazıcı yok", JOptionPane.WARNING_MESSAGE);
            return;
        }
        sendKitchenButton.setEnabled(false);
        sendKitchenButton.setText("Gönderiliyor...");

        new SwingWorker<List<PrintingService.PrintResult>, Void>() {
            @Override
            protected List<PrintingService.PrintResult> doInBackground() {
                return appState.sendOrderToKitchens(tableNo, currentUser, printing);
            }

            @Override
            protected void done() {
                sendKitchenButton.setText("Mutfağa Gönder");
                sendKitchenButton.setEnabled(true);
                try {
                    List<PrintingService.PrintResult> results = get();
                    if (results == null || results.isEmpty()) {
                        JOptionPane.showMessageDialog(TableOrderDialog.this,
                                "Mutfağa gönderilecek yeni kalem yok.\n"
                                        + "(Tüm kalemler zaten mutfakta, ya da ürün kategorilerinin\n"
                                        + " yazıcılara eşleştirildiğinden emin olun.)",
                                "Bilgi", JOptionPane.INFORMATION_MESSAGE);
                        // Yine de snapshot'ı tazele — durumlar değişmiş olabilir
                        updateFromSnapshot(appState.snapshot(tableNo));
                        return;
                    }
                    StringBuilder msg = new StringBuilder("Gönderim sonucu:\n");
                    boolean allOk = true;
                    for (PrintingService.PrintResult r : results) {
                        msg.append("  • ").append(r.target == null ? "?" : r.target.getDisplayName())
                                .append(" → ").append(r.success ? "BAŞARILI" : "HATA: " + r.errorMessage)
                                .append("\n");
                        allOk &= r.success;
                    }
                    JOptionPane.showMessageDialog(TableOrderDialog.this, msg.toString(),
                            allOk ? "Mutfağa gönderildi" : "Bazı yazıcılar başarısız",
                            allOk ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
                    // Snapshot tazele — pending kalemler "Mutfakta"ya dönsün
                    updateFromSnapshot(appState.snapshot(tableNo));
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(TableOrderDialog.this,
                            "Mutfağa gönderim başarısız:\n" + ex.getMessage(),
                            "Hata", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }


    /** Hesabı kişiye bölmek için ayrı dialog aç. */
    private void openSplitPaymentDialog() {
        TableSnapshot snapshot = appState.snapshot(tableNo);
        if (snapshot.getTotal() == null || snapshot.getTotal().compareTo(BigDecimal.ZERO) <= 0) {
            JOptionPane.showMessageDialog(this,
                    "Bölünecek hesap yok — masada açık sipariş bulunamadı.",
                    "Bilgi", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        SplitPaymentDialog dialog = new SplitPaymentDialog(
                SwingUtilities.getWindowAncestor(this),
                appState, currentUser, tableNo, snapshot.getTotal());
        dialog.setVisible(true);
        // Dialog kapanınca masa state'i güncellenir (event listener yoluyla);
        // başarılı tahsilat sonrası bu TableOrderDialog da kapansın
        TableSnapshot post = appState.snapshot(tableNo);
        if (post.getTotal() == null || post.getTotal().compareTo(BigDecimal.ZERO) <= 0) {
            dispose();
        }
    }

    private void performSale() {
        TableSnapshot snapshot = appState.snapshot(tableNo);
        if (snapshot.getTotal() == null || snapshot.getTotal().compareTo(BigDecimal.ZERO) <= 0) {
            JOptionPane.showMessageDialog(this, "Satış yapılacak ürün bulunamadı", "Bilgi", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        // Dokunmatik dostu + hızlı tutar chip'leri (yuvarlama/indirim/ikram)
        PaymentChoice choice = askPaymentMethod(snapshot.getTotal());
        if (choice == null) {
            return;   // İptal
        }
        // Eğer tutar değiştirilmediyse normal satış; değiştirildiyse tek parça split-sale ile override
        BigDecimal originalTotal = snapshot.getTotal().setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal finalTotal = choice.amount().setScale(2, java.math.RoundingMode.HALF_UP);
        try {
            if (finalTotal.compareTo(originalTotal) == 0) {
                appState.recordSale(tableNo, choice.method(), currentUser);
            } else if (finalTotal.signum() <= 0) {
                // İkram → 0 ₺ — split-sale tolere etmez (parça > 0 olmalı)
                // Bu yüzden bir kalem temizle akışı: masa temizle + log
                int ok = JOptionPane.showConfirmDialog(this,
                        "Bu sipariş ikram olarak işaretlenecek (tahsilat yok). Onayla?",
                        "İkram", JOptionPane.YES_NO_OPTION);
                if (ok != JOptionPane.YES_OPTION) return;
                appState.clearTable(tableNo, currentUser, "İkram (sahip onayı)");
            } else {
                // Override amount → tek parça hesap böl olarak yaz
                appState.recordSplitSale(tableNo, currentUser, java.util.List.of(
                        new state.AppState.SplitPart(finalTotal, choice.method())));
            }
            String diff = finalTotal.compareTo(originalTotal) == 0 ? ""
                    : "\nUygulanan tutar: " + currencyFormat.format(finalTotal)
                      + " (orijinal: " + currencyFormat.format(originalTotal) + ")";
            JOptionPane.showMessageDialog(this,
                    "Satış tamamlandı. Ödeme yöntemi: " + describePaymentMethod(choice.method()) + diff,
                    "Bilgi", JOptionPane.INFORMATION_MESSAGE);
            dispose();
        } catch (RuntimeException ex) {
            JOptionPane.showMessageDialog(this,
                    "Satış başarısız: " + ex.getMessage(),
                    "Hata", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Ödeme sonucu: method + son tutar (chip'lerle değiştirilmiş olabilir). */
    private record PaymentChoice(PaymentMethod method, BigDecimal amount) {}

    /**
     * Ödeme yöntemi seçimi — hızlı tutar şablonları (yuvarlama, indirim, ikram) ile.
     * @return seçim, ya da iptal edilirse null.
     */
    private PaymentChoice askPaymentMethod(BigDecimal total) {
        JDialog dlg = new JDialog((Window) SwingUtilities.getWindowAncestor(this),
                "Ödeme Yöntemi", Dialog.ModalityType.APPLICATION_MODAL);
        dlg.setLayout(new BorderLayout(12, 12));

        // Tutar canlı değişebilir — chip'ler tarafından
        final BigDecimal originalTotal = total;
        final BigDecimal[] currentTotal = { total };

        JLabel title = new JLabel("", SwingConstants.CENTER);
        title.setBorder(BorderFactory.createEmptyBorder(20, 20, 10, 20));
        Runnable updateTitle = () -> title.setText(
                "<html><div style='text-align:center;'>"
              + "<b>Ödenecek tutar</b><br/>"
              + "<span style='font-size:24pt;color:#c62828;'>"
              + currencyFormat.format(currentTotal[0]) + "</span>"
              + (currentTotal[0].compareTo(originalTotal) != 0
                  ? "<br/><i style='color:#888;font-size:11pt;'>Orijinal: "
                    + currencyFormat.format(originalTotal) + "</i>"
                  : "")
              + "</div></html>");
        updateTitle.run();
        dlg.add(title, BorderLayout.NORTH);

        // Merkez: Hızlı tutar chip'leri + ödeme butonları (dikey)
        JPanel center = new JPanel(new BorderLayout(0, 12));
        center.setBorder(BorderFactory.createEmptyBorder(8, 20, 8, 20));

        // Hızlı tutar chip'leri
        JPanel quickRow = new JPanel(new GridLayout(2, 3, 8, 8));
        quickRow.setBorder(BorderFactory.createTitledBorder("Hızlı Tutar Değiştir"));
        Color chipBg = new Color(255, 167, 38);
        addQuickChip(quickRow, "↗ 50₺ Yuvarla", chipBg, () -> {
            BigDecimal rounded = originalTotal.divide(new BigDecimal("50"), 0, java.math.RoundingMode.UP)
                    .multiply(new BigDecimal("50"));
            currentTotal[0] = rounded;
            updateTitle.run();
        });
        addQuickChip(quickRow, "↗ 100₺ Yuvarla", chipBg, () -> {
            BigDecimal rounded = originalTotal.divide(new BigDecimal("100"), 0, java.math.RoundingMode.UP)
                    .multiply(new BigDecimal("100"));
            currentTotal[0] = rounded;
            updateTitle.run();
        });
        addQuickChip(quickRow, "−5%", chipBg, () -> {
            currentTotal[0] = originalTotal.multiply(new BigDecimal("0.95"))
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            updateTitle.run();
        });
        addQuickChip(quickRow, "−10%", chipBg, () -> {
            currentTotal[0] = originalTotal.multiply(new BigDecimal("0.90"))
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            updateTitle.run();
        });
        addQuickChip(quickRow, "🎁 Bedava (İkram)", new Color(229, 57, 53), () -> {
            currentTotal[0] = BigDecimal.ZERO.setScale(2);
            updateTitle.run();
        });
        addQuickChip(quickRow, "↺ Orijinal", new Color(120, 120, 120), () -> {
            currentTotal[0] = originalTotal;
            updateTitle.run();
        });
        center.add(quickRow, BorderLayout.NORTH);

        // Ödeme yöntemi butonları
        JPanel buttons = new JPanel(new GridLayout(1, 3, 12, 0));
        final PaymentChoice[] picked = { null };
        Dimension big = new Dimension(200, 100);
        Font base = UIManager.getFont("Label.font");
        if (base == null) base = new Font("Dialog", Font.PLAIN, 14);
        Font payFont = base.deriveFont(Font.BOLD, 18f);

        JButton cashBtn = makePaymentButton("💵 Nakit", payFont, big);
        cashBtn.setBackground(new Color(165, 214, 167));
        cashBtn.addActionListener(e -> {
            picked[0] = new PaymentChoice(PaymentMethod.CASH, currentTotal[0]); dlg.dispose();
        });
        JButton cardBtn = makePaymentButton("💳 Kredi Kartı", payFont, big);
        cardBtn.setBackground(new Color(155, 203, 239));
        cardBtn.addActionListener(e -> {
            picked[0] = new PaymentChoice(PaymentMethod.CREDIT_CARD, currentTotal[0]); dlg.dispose();
        });
        JButton transferBtn = makePaymentButton("🏦 EFT", payFont, big);
        transferBtn.setBackground(new Color(255, 218, 121));
        transferBtn.addActionListener(e -> {
            picked[0] = new PaymentChoice(PaymentMethod.TRANSFER, currentTotal[0]); dlg.dispose();
        });
        buttons.add(cashBtn);
        buttons.add(cardBtn);
        buttons.add(transferBtn);
        center.add(buttons, BorderLayout.CENTER);

        dlg.add(center, BorderLayout.CENTER);

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        footer.setBorder(BorderFactory.createEmptyBorder(0, 12, 12, 12));
        JButton cancel = new JButton("İptal");
        cancel.setPreferredSize(new Dimension(140, 44));
        cancel.addActionListener(e -> dlg.dispose());
        footer.add(cancel);
        dlg.add(footer, BorderLayout.SOUTH);

        dlg.pack();
        dlg.setSize(Math.max(780, dlg.getWidth()), Math.max(420, dlg.getHeight()));
        dlg.setLocationRelativeTo(this);
        dlg.setVisible(true);
        return picked[0];
    }

    private void addQuickChip(JPanel parent, String text, Color bg, Runnable onClick) {
        JButton b = new JButton(text);
        b.setFont(b.getFont().deriveFont(Font.BOLD, 13f));
        b.setBackground(bg);
        b.setForeground(Color.WHITE);
        b.setOpaque(true);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addActionListener(e -> onClick.run());
        parent.add(b);
    }

    private JButton makePaymentButton(String label, Font font, Dimension size) {
        JButton btn = new JButton(label);
        btn.setPreferredSize(size);
        btn.setMinimumSize(size);
        btn.setFont(font);
        btn.setOpaque(true);
        btn.setFocusPainted(false);
        return btn;
    }

    private PaymentMethod mapPaymentMethod(String selection) {
        if (selection == null) {
            return PaymentMethod.CASH;
        }
        return switch (selection) {
            case "Kredi Kartı" -> PaymentMethod.CREDIT_CARD;
            case "Havale" -> PaymentMethod.TRANSFER;
            default -> PaymentMethod.CASH;
        };
    }

    private String describePaymentMethod(PaymentMethod method) {
        if (method == null) {
            return "Bilinmiyor";
        }
        return switch (method) {
            case CASH -> "Nakit";
            case CREDIT_CARD, CARD -> "Kredi Kartı";
            case DEBIT_CARD -> "Banka Kartı";
            case TRANSFER -> "Havale/EFT";
            case ONLINE -> "Online";
            case MIXED -> "Karma";
        };
    }

    private void updateFromSnapshot(TableSnapshot snapshot) {
        tableModel.setRowCount(0);
        for (OrderLine line : snapshot.getLines()) {
            String status = line.isPending() ? "YENİ" : "Mutfakta";
            String label = line.getProductName();
            if (line.getNote() != null && !line.getNote().isBlank()) {
                label = label + "  (" + line.getNote() + ")";
            }
            tableModel.addRow(new Object[]{
                    label,
                    line.getQuantityLabel(),
                    currencyFormat.format(line.getUnitPrice()),
                    currencyFormat.format(line.getLineTotal()),
                    status
            });
        }
        String historyText = snapshot.getHistory().stream()
                .map(this::formatLog)
                .collect(Collectors.joining("\n"));
        logArea.setText(historyText);
        logArea.setCaretPosition(logArea.getDocument().getLength());
        totalLabel.setText("Toplam: " + currencyFormat.format(snapshot.getTotal()));
        updateStatus(snapshot.getStatus());
    }

    private String formatLog(OrderLogEntry entry) {
        StringBuilder sb = new StringBuilder(entry.getTimestamp().format(LOG_TIMESTAMP_FORMATTER));
        String actor = entry.getActor();
        if (actor != null && !actor.isBlank()) {
            sb.append(" - ").append(actor);
        }
        String action = entry.getAction();
        if (action != null && !action.isBlank()) {
            sb.append(" - ").append(action);
        } else {
            String message = entry.getMessage();
            if (message != null && !message.isBlank()) {
                sb.append(" - ").append(message);
            }
        }
        return sb.toString();
    }

    private void updateStatus(TableOrderStatus status) {
        String text;
        Color color;
        if (status == null) {
            text = "Masa durumu belirsiz";
            color = Color.GRAY;
        } else {
            switch (status) {
                case EMPTY -> {
                    text = "Masa boş";
                    color = Color.DARK_GRAY;
                }
                case ORDERED -> {
                    text = "Sipariş alındı";
                    color = new Color(239, 108, 0);
                }
                case SERVED -> {
                    text = "Sipariş servis edildi";
                    color = new Color(46, 125, 50);
                }
                default -> {
                    text = status.name();
                    color = Color.DARK_GRAY;
                }
            }
        }
        statusLabel.setText(text);
        statusLabel.setForeground(color);
        if (waiterRole) {
            markServedButton.setEnabled(true);
            saleButton.setEnabled(false);
        } else {
            markServedButton.setEnabled(status == TableOrderStatus.ORDERED);
            saleButton.setEnabled(status == TableOrderStatus.SERVED || status == TableOrderStatus.ORDERED);
        }
    }

}
