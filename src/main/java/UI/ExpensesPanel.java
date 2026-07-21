package UI;

import model.MoneyUtil;
import model.User;
import state.AppState;
import state.ExpenseRecord;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public class ExpensesPanel extends JPanel {
    private static final int COL_ID = 0;
    private static final int COL_DATE = 1;
    private static final int COL_DESCRIPTION = 2;
    private static final int COL_AMOUNT = 3;
    private static final int COL_USER = 4;
    private static final int COL_CREATED = 5;
    private final AppState appState;
    private final User currentUser;
    private final DefaultTableModel tableModel;
    private final JTable table;
    private final JTextField descriptionField = new JTextField(20);
    private final JSpinner amountSpinner = new JSpinner(new SpinnerNumberModel(0.0, 0.0, 1_000_000.0, 5.0));
    private final JSpinner kgSpinner = new JSpinner(new SpinnerNumberModel(0.0, 0.0, 10_000.0, 0.5));
    private final JSpinner kgPriceSpinner = new JSpinner(new SpinnerNumberModel(0.0, 0.0, 100_000.0, 1.0));
    private final JLabel kgCalcLabel = new JLabel("0,00 ₺");
    private final JRadioButton modeManual = new JRadioButton("Manuel (sadece tutar)", true);
    private final JRadioButton modeKg = new JRadioButton("Kilo bazlı (kg × kg fiyatı)");
    private final JSpinner filterDateSpinner = new JSpinner(new SpinnerDateModel(new Date(), null, null, java.util.Calendar.DAY_OF_MONTH));
    private final JSpinner expenseDateSpinner = new JSpinner(new SpinnerDateModel(new Date(), null, null, java.util.Calendar.DAY_OF_MONTH));
    private final JButton deleteButton = new JButton("Gider kaldır");
    private final PropertyChangeListener listener = this::handleStateChange;

    public ExpensesPanel(AppState appState, User currentUser) {
        this.appState = Objects.requireNonNull(appState, "appState");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser");
        setLayout(new BorderLayout(8, 8));

        tableModel = new DefaultTableModel(new Object[]{"ID", "Tarih", "Açıklama", "Tutar", "Kullanıcı", "Kayıt"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        table = new JTable(tableModel);
        table.setRowHeight(22);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getSelectionModel().addListSelectionListener(e -> updateDeleteButtonState());
        hideIdColumn();
        // Üst: Hızlı seçim + filtre (dikey)
        JPanel topPanel = new JPanel(new BorderLayout(0, 4));
        topPanel.add(buildQuickTemplatesPanel(), BorderLayout.NORTH);
        topPanel.add(buildFilterPanel(), BorderLayout.CENTER);
        add(topPanel, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(buildFormPanel(), BorderLayout.SOUTH);

        appState.addPropertyChangeListener(listener);
        reloadExpenses();
    }

    private JComponent buildFilterPanel() {
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.add(new JLabel("Tarih"));
        filterDateSpinner.setEditor(new JSpinner.DateEditor(filterDateSpinner, "dd-MM-yyyy"));
        toolbar.add(filterDateSpinner);
        JButton refreshButton = new JButton("Listele");
        refreshButton.addActionListener(e -> reloadExpenses());
        toolbar.add(refreshButton);

        deleteButton.setEnabled(false);
        deleteButton.addActionListener(e -> removeSelectedExpense());
        toolbar.add(deleteButton);

        toolbar.addSeparator();
        JButton exportButton = new JButton("Excel'e aktar");
        exportButton.addActionListener(e -> exportToExcel());
        toolbar.add(exportButton);
        return toolbar;
    }

    /**
     * Hızlı şablon paneli — PWA'daki gibi chip butonlar.
     * Şablonlar {@code expense-templates.properties} dosyasından okunur.
     * Kullanıcı bu dosyayı şu yola kopyalayıp düzenleyebilir:
     * {@code C:\Users\<kullanıcı>\.budget\expense-templates.properties}
     */
    private JComponent buildQuickTemplatesPanel() {
        JPanel container = new JPanel(new BorderLayout(0, 4));
        container.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Hızlı Seçim — tıkla, kg/tutar sorulsun"),
                BorderFactory.createEmptyBorder(4, 4, 4, 4)));

        // Şablon listesi
        List<service.expense.ExpenseTemplate> templates =
                service.expense.ExpenseTemplateService.loadTemplates();

        // WrapLayout — pencere darsa alt satıra geçer
        JPanel chipBar = new JPanel(new UI.WrapLayout(FlowLayout.LEFT, 10, 10));
        for (service.expense.ExpenseTemplate t : templates) {
            JButton btn = new JButton(t.icon() + "  " + t.name());
            // Daha büyük, dokunmatik-dostu
            btn.setFont(btn.getFont().deriveFont(Font.BOLD, 18f));
            btn.setPreferredSize(new Dimension(180, 60));
            btn.setMinimumSize(new Dimension(160, 60));
            // Belirgin renkler: kg → turuncu, manuel → yeşil
            Color base = t.isKgMode() ? new Color(255, 167, 38) : new Color(76, 175, 80);
            btn.setBackground(base);
            btn.setForeground(Color.WHITE);
            btn.setOpaque(true);
            btn.setBorderPainted(false);
            btn.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(base.darker(), 2, true),
                    BorderFactory.createEmptyBorder(8, 16, 8, 16)));
            btn.setFocusPainted(false);
            btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            btn.setHorizontalAlignment(SwingConstants.CENTER);
            btn.setToolTipText(t.isKgMode()
                    ? "Kg ve kg fiyatı sorulur (otomatik hesaplanır)"
                    : "Toplam tutar sorulur (manuel)");
            btn.addActionListener(e -> useTemplate(t));
            chipBar.add(btn);
        }

        JScrollPane scroll = new JScrollPane(chipBar,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        // Daha kompakt yükseklik — tablo + form yer kalsın
        scroll.setPreferredSize(new Dimension(900, 140));
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(20);
        container.add(scroll, BorderLayout.CENTER);

        // Düzenleme açıklaması
        JLabel hint = new JLabel(
                "<html><i>Düzenlemek için:</i> "
              + "<code>C:\\Users\\&lt;kullanıcı&gt;\\.budget\\expense-templates.properties</code> "
              + "dosyasını açıp yeni satır ekleyin/silin.</html>");
        hint.setFont(hint.getFont().deriveFont(11f));
        hint.setForeground(Color.GRAY);
        hint.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        container.add(hint, BorderLayout.SOUTH);
        return container;
    }

    /** Bir şablona tıklayınca prompt'larla gider ekler. */
    private void useTemplate(service.expense.ExpenseTemplate t) {
        if (t.isKgMode()) {
            String kgStr = JOptionPane.showInputDialog(this,
                    t.name() + " — Kaç kg alındı?", "Kg Miktarı",
                    JOptionPane.QUESTION_MESSAGE);
            if (kgStr == null) return;
            BigDecimal kg = parseDecimal(kgStr);
            if (kg == null || kg.signum() <= 0) {
                JOptionPane.showMessageDialog(this, "Geçerli kg girin", "Hata", JOptionPane.WARNING_MESSAGE);
                return;
            }
            String priceStr = JOptionPane.showInputDialog(this,
                    t.name() + " — Kg fiyatı kaç ₺?", "Kg Fiyatı",
                    JOptionPane.QUESTION_MESSAGE);
            if (priceStr == null) return;
            BigDecimal price = parseDecimal(priceStr);
            if (price == null || price.signum() < 0) {
                JOptionPane.showMessageDialog(this, "Geçerli fiyat girin", "Hata", JOptionPane.WARNING_MESSAGE);
                return;
            }
            try {
                appState.addKgBasedExpense(t.name(), kg, price, LocalDate.now(), currentUser);
                BigDecimal total = kg.multiply(price);
                JOptionPane.showMessageDialog(this,
                        "✓ " + t.name() + " gideri eklendi (" + total + " ₺)",
                        "Eklendi", JOptionPane.INFORMATION_MESSAGE);
                reloadExpenses();
            } catch (RuntimeException ex) {
                JOptionPane.showMessageDialog(this, "Gider eklenemedi: " + ex.getMessage(),
                        "Hata", JOptionPane.ERROR_MESSAGE);
            }
        } else {
            String amtStr = JOptionPane.showInputDialog(this,
                    t.name() + " — Toplam tutar (₺)?", "Tutar",
                    JOptionPane.QUESTION_MESSAGE);
            if (amtStr == null) return;
            BigDecimal amount = parseDecimal(amtStr);
            if (amount == null || amount.signum() <= 0) {
                JOptionPane.showMessageDialog(this, "Geçerli tutar girin", "Hata", JOptionPane.WARNING_MESSAGE);
                return;
            }
            try {
                appState.addExpense(amount, t.name(), LocalDate.now(), currentUser);
                JOptionPane.showMessageDialog(this,
                        "✓ " + t.name() + " gideri eklendi (" + amount + " ₺)",
                        "Eklendi", JOptionPane.INFORMATION_MESSAGE);
                reloadExpenses();
            } catch (RuntimeException ex) {
                JOptionPane.showMessageDialog(this, "Gider eklenemedi: " + ex.getMessage(),
                        "Hata", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    /** "10,50" veya "10.50" parsing. */
    private static BigDecimal parseDecimal(String s) {
        if (s == null) return null;
        String cleaned = s.replace(',', '.').replaceAll("[^0-9.]", "").trim();
        if (cleaned.isEmpty()) return null;
        try { return new BigDecimal(cleaned); }
        catch (NumberFormatException ex) { return null; }
    }

    private JComponent buildFormPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Yeni Gider Ekle (Manuel form)"),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 6, 4, 6);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1.0;

        // Spinner ve textfield'ları okunaklı ama kompakt
        Font formFont = panel.getFont().deriveFont(Font.PLAIN, 14f);
        Dimension fieldSize = new Dimension(180, 30);
        descriptionField.setFont(formFont);
        descriptionField.setPreferredSize(fieldSize);
        amountSpinner.setFont(formFont);
        amountSpinner.setPreferredSize(fieldSize);
        kgSpinner.setFont(formFont);
        kgSpinner.setPreferredSize(fieldSize);
        kgPriceSpinner.setFont(formFont);
        kgPriceSpinner.setPreferredSize(fieldSize);
        expenseDateSpinner.setFont(formFont);
        expenseDateSpinner.setPreferredSize(fieldSize);

        // Mod seçici (Manuel / Kg bazlı) — büyük radio butonlar
        ButtonGroup modeGroup = new ButtonGroup();
        modeGroup.add(modeManual);
        modeGroup.add(modeKg);
        modeManual.setFont(formFont.deriveFont(Font.BOLD));
        modeKg.setFont(formFont.deriveFont(Font.BOLD));
        modeManual.addActionListener(e -> refreshExpenseFormMode());
        modeKg.addActionListener(e -> refreshExpenseFormMode());

        int row = 0;
        gc.gridx = 0; gc.gridy = row;
        panel.add(new JLabel("Mod"), gc);
        gc.gridx = 1;
        JPanel modePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        modePanel.add(modeManual);
        modePanel.add(modeKg);
        panel.add(modePanel, gc);

        row++;
        gc.gridx = 0; gc.gridy = row;
        panel.add(new JLabel("Gider Tarihi"), gc);
        expenseDateSpinner.setEditor(new JSpinner.DateEditor(expenseDateSpinner, "dd-MM-yyyy"));
        gc.gridx = 1;
        panel.add(expenseDateSpinner, gc);

        row++;
        gc.gridx = 0; gc.gridy = row;
        panel.add(new JLabel("Açıklama / Ürün"), gc);
        gc.gridx = 1;
        panel.add(descriptionField, gc);

        // -- Manuel mod alanı --
        row++;
        gc.gridx = 0; gc.gridy = row;
        panel.add(new JLabel("Tutar"), gc);
        amountSpinner.setEditor(new JSpinner.NumberEditor(amountSpinner, "#,##0.00"));
        gc.gridx = 1;
        panel.add(amountSpinner, gc);

        // -- Kg modu alanları --
        row++;
        gc.gridx = 0; gc.gridy = row;
        panel.add(new JLabel("Kilo (kg)"), gc);
        kgSpinner.setEditor(new JSpinner.NumberEditor(kgSpinner, "#,##0.000"));
        gc.gridx = 1;
        panel.add(kgSpinner, gc);

        row++;
        gc.gridx = 0; gc.gridy = row;
        panel.add(new JLabel("Kg Fiyatı (₺/kg)"), gc);
        kgPriceSpinner.setEditor(new JSpinner.NumberEditor(kgPriceSpinner, "#,##0.00"));
        gc.gridx = 1;
        panel.add(kgPriceSpinner, gc);

        row++;
        gc.gridx = 0; gc.gridy = row;
        panel.add(new JLabel("Toplam"), gc);
        kgCalcLabel.setFont(kgCalcLabel.getFont().deriveFont(Font.BOLD));
        gc.gridx = 1;
        panel.add(kgCalcLabel, gc);

        // Toplam canlı hesaplama
        javax.swing.event.ChangeListener calc = e -> refreshKgTotal();
        kgSpinner.addChangeListener(calc);
        kgPriceSpinner.addChangeListener(calc);

        row++;
        gc.gridx = 0; gc.gridy = row; gc.gridwidth = 2;
        gc.anchor = GridBagConstraints.CENTER; gc.fill = GridBagConstraints.HORIZONTAL;
        JButton addButton = new JButton("+ Gider Ekle");
        addButton.setFont(addButton.getFont().deriveFont(Font.BOLD, 14f));
        addButton.setBackground(new Color(46, 125, 50));
        addButton.setForeground(Color.WHITE);
        addButton.setOpaque(true);
        addButton.setBorderPainted(false);
        addButton.setFocusPainted(false);
        addButton.setPreferredSize(new Dimension(260, 36));
        addButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addButton.addActionListener(e -> addExpense());
        panel.add(addButton, gc);
        gc.gridwidth = 1;

        // Tüm etiketleri büyüt (sadece JLabel'leri)
        for (Component c : panel.getComponents()) {
            if (c instanceof JLabel) c.setFont(formFont.deriveFont(Font.BOLD));
        }
        kgCalcLabel.setFont(formFont.deriveFont(Font.BOLD, 14f));
        kgCalcLabel.setForeground(new Color(198, 40, 40));

        refreshExpenseFormMode();
        return panel;
    }

    /** Mod değiştiğinde alanları aktif/pasif ayarlar. */
    private void refreshExpenseFormMode() {
        boolean kg = modeKg.isSelected();
        amountSpinner.setEnabled(!kg);
        kgSpinner.setEnabled(kg);
        kgPriceSpinner.setEnabled(kg);
        kgCalcLabel.setEnabled(kg);
        refreshKgTotal();
    }

    private void refreshKgTotal() {
        double kg = ((Number) kgSpinner.getValue()).doubleValue();
        double price = ((Number) kgPriceSpinner.getValue()).doubleValue();
        double total = kg * price;
        kgCalcLabel.setText(String.format(MoneyUtil.TURKISH_LOCALE, "%,.2f ₺", total));
    }

    private void handleStateChange(PropertyChangeEvent event) {
        if (AppState.EVENT_EXPENSES.equals(event.getPropertyName())) {
            SwingUtilities.invokeLater(this::reloadExpenses);
        }
    }

    private void addExpense() {
        String description = descriptionField.getText().trim();
        if (description.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Açıklama gerekli", "Uyarı", JOptionPane.WARNING_MESSAGE);
            return;
        }
        LocalDate date = convertToDate(expenseDateSpinner);

        if (modeKg.isSelected()) {
            double kg = ((Number) kgSpinner.getValue()).doubleValue();
            double kgPrice = ((Number) kgPriceSpinner.getValue()).doubleValue();
            if (kg <= 0) {
                JOptionPane.showMessageDialog(this, "Kilo sıfırdan büyük olmalı", "Uyarı", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (kgPrice <= 0) {
                JOptionPane.showMessageDialog(this, "Kg fiyatı sıfırdan büyük olmalı", "Uyarı", JOptionPane.WARNING_MESSAGE);
                return;
            }
            try {
                appState.addKgBasedExpense(description,
                        BigDecimal.valueOf(kg),
                        BigDecimal.valueOf(kgPrice),
                        date, currentUser);
            } catch (RuntimeException ex) {
                JOptionPane.showMessageDialog(this,
                        "Gider eklenemedi: " + ex.getMessage(),
                        "Hata", JOptionPane.ERROR_MESSAGE);
                return;
            }
            descriptionField.setText("");
            kgSpinner.setValue(0.0);
            kgPriceSpinner.setValue(0.0);
            refreshKgTotal();
        } else {
            double amountValue = ((Number) amountSpinner.getValue()).doubleValue();
            if (amountValue <= 0) {
                JOptionPane.showMessageDialog(this, "Tutar sıfırdan büyük olmalı", "Uyarı", JOptionPane.WARNING_MESSAGE);
                return;
            }
            appState.addExpense(BigDecimal.valueOf(amountValue), description, date, currentUser);
            descriptionField.setText("");
            amountSpinner.setValue(0.0);
        }
        reloadExpenses();
    }

    private void reloadExpenses() {
        LocalDate date = convertToDate(filterDateSpinner);
        List<ExpenseRecord> records = appState.getExpensesOn(date);
        tableModel.setRowCount(0);
        for (ExpenseRecord record : records) {
            tableModel.addRow(new Object[]{
                    record.getId(),
                    record.getExpenseDate(),
                    record.getDescription(),
                    String.format(MoneyUtil.TURKISH_LOCALE, "%.2f", record.getAmount()),
                    record.getPerformedBy(),
                    record.getCreatedAt()
            });
        }
        hideIdColumn();
        updateDeleteButtonState();
    }

    private LocalDate convertToDate(JSpinner spinner) {
        Date date = (Date) spinner.getValue();
        return Instant.ofEpochMilli(date.getTime()).atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private void hideIdColumn() {
        if (table.getColumnModel().getColumnCount() <= COL_ID) {
            return;
        }
        TableColumn column = table.getColumnModel().getColumn(COL_ID);
        column.setMinWidth(0);
        column.setMaxWidth(0);
        column.setPreferredWidth(0);
        column.setResizable(false);
        TableColumn headerColumn = table.getTableHeader().getColumnModel().getColumn(COL_ID);
        headerColumn.setMinWidth(0);
        headerColumn.setMaxWidth(0);
        headerColumn.setPreferredWidth(0);
    }

    private void updateDeleteButtonState() {
        boolean enabled = table.getSelectedRow() >= 0 && table.getSelectedRow() < tableModel.getRowCount();
        deleteButton.setEnabled(enabled);
    }

    private void removeSelectedExpense() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) {
            return;
        }
        int modelRow = table.convertRowIndexToModel(viewRow);
        if (modelRow < 0 || modelRow >= tableModel.getRowCount()) {
            return;
        }
        Long expenseId = resolveExpenseId(modelRow);
        if (expenseId == null || expenseId <= 0) {
            JOptionPane.showMessageDialog(this, "Seçili gider silinemedi", "Hata", JOptionPane.ERROR_MESSAGE);
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(this,
                "Seçili gider silinsin mi?",
                "Onay",
                JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            appState.deleteExpense(expenseId);
            reloadExpenses();
            table.clearSelection();
        } catch (RuntimeException ex) {
            String message = ex.getMessage();
            if (message == null || message.isBlank()) {
                message = "Gider silinemedi.";
            }
            JOptionPane.showMessageDialog(this, message, "Hata", JOptionPane.ERROR_MESSAGE);
        } finally {
            updateDeleteButtonState();
        }
    }

    private Long resolveExpenseId(int modelRow) {
        Object value = tableModel.getValueAt(modelRow, COL_ID);
        if (value instanceof Long id) {
            return id;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
    }

    private void exportToExcel() {
        LocalDate date = convertToDate(filterDateSpinner);
        List<ExpenseRecord> records = appState.getExpensesOn(date);

        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("giderler-" + date + ".xlsx"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        File file = chooser.getSelectedFile();
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Giderler");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Tarih");
            header.createCell(1).setCellValue("Açıklama");
            header.createCell(2).setCellValue("Tutar");
            header.createCell(3).setCellValue("Kullanıcı");
            header.createCell(4).setCellValue("Kayıt Zamanı");

            int rowIndex = 1;
            for (ExpenseRecord record : records) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(record.getExpenseDate().toString());
                row.createCell(1).setCellValue(record.getDescription());
                row.createCell(2).setCellValue(record.getAmount().doubleValue());
                row.createCell(3).setCellValue(record.getPerformedBy());
                row.createCell(4).setCellValue(record.getCreatedAt().toString());
            }

            for (int i = 0; i < 5; i++) {
                sheet.autoSizeColumn(i);
            }

            try (FileOutputStream out = new FileOutputStream(file)) {
                workbook.write(out);
            }
            JOptionPane.showMessageDialog(this, "Excel dosyası kaydedildi", "Bilgi", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Excel kaydedilemedi: " + ex.getMessage(), "Hata", JOptionPane.ERROR_MESSAGE);
        }
    }

    @Override
    public void removeNotify() {
        super.removeNotify();
        appState.removePropertyChangeListener(listener);
    }
}
