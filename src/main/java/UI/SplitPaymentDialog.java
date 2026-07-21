package UI;

import model.MoneyUtil;
import model.PaymentMethod;
import model.User;
import state.AppState;

import javax.swing.*;
import java.awt.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * Hesap bölme dialogu.
 *
 * <p>İki adımlı akış:
 * <ol>
 *   <li><b>Adım 1:</b> Kişi sayısı seçimi — manuel veya hazır şablonlar (2/3/4).
 *       Toplam tutar otomatik kişiye bölünür; küsurat son parçaya yazılır.</li>
 *   <li><b>Adım 2:</b> Her parça için ödeme yöntemi seçimi (Nakit/Kart/EFT).
 *       Her satır: "Kişi N: TL X.XX" + 3 buton.</li>
 * </ol>
 *
 * <p>Onay verince {@link AppState#recordSplitSale(int, User, java.util.List)}
 * çağrılır — her parça için ayrı Payment kaydı oluşur, sipariş kapanır.
 */
public class SplitPaymentDialog extends JDialog {

    private static final NumberFormat MONEY = MoneyUtil.turkishLiraCurrencyFormat();

    private final AppState appState;
    private final User currentUser;
    private final int tableNo;
    private final BigDecimal totalAmount;

    private int personCount = 2;
    private final List<PaymentMethod> selectedMethods = new ArrayList<>();
    private final List<BigDecimal> partAmounts = new ArrayList<>();
    /** Her kişi için tutar input alanları — UI'dan canlı okunur. */
    private final List<JTextField> amountFields = new ArrayList<>();
    /** Toplam canlı geri besleme etiketi (sağ üst). */
    private JLabel sumStatusLabel;

    private JPanel cardPanel;
    private CardLayout cardLayout;
    private JPanel methodSelectionPanel;

    public SplitPaymentDialog(Window owner, AppState appState, User user,
                              int tableNo, BigDecimal totalAmount) {
        super(owner, "Hesap Böl — Masa " + tableNo, ModalityType.APPLICATION_MODAL);
        this.appState = appState;
        this.currentUser = user;
        this.tableNo = tableNo;
        this.totalAmount = totalAmount == null ? BigDecimal.ZERO : totalAmount;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));
        setSize(640, 560);
        setLocationRelativeTo(owner);

        cardLayout = new CardLayout();
        cardPanel = new JPanel(cardLayout);
        cardPanel.add(buildStep1(), "step1");
        cardPanel.add(buildStep2Container(), "step2");
        add(cardPanel, BorderLayout.CENTER);
    }

    // ============================================================
    //   Adım 1 — kişi sayısı
    // ============================================================

    private JComponent buildStep1() {
        JPanel panel = new JPanel(new BorderLayout(8, 12));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JLabel title = new JLabel(
                "<html><b>Toplam:</b> " + MONEY.format(totalAmount)
              + "<br/><br/>Hesap kaç kişiye bölünsün?</html>");
        title.setFont(title.getFont().deriveFont(18f));
        panel.add(title, BorderLayout.NORTH);

        JPanel center = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(8, 8, 8, 8);
        gc.fill = GridBagConstraints.HORIZONTAL;

        // Hızlı şablonlar
        gc.gridx = 0; gc.gridy = 0; gc.gridwidth = 3;
        JLabel quickLabel = new JLabel("Hızlı seç:");
        quickLabel.setFont(quickLabel.getFont().deriveFont(Font.BOLD, 14f));
        center.add(quickLabel, gc);

        gc.gridwidth = 1; gc.gridy = 1; gc.weightx = 1;
        for (int i = 0; i < 3; i++) {
            int n = i + 2;  // 2, 3, 4
            JButton btn = makeQuickButton(n);
            gc.gridx = i;
            center.add(btn, gc);
        }

        // Manuel kişi sayısı
        gc.gridy = 2; gc.gridx = 0; gc.gridwidth = 3;
        center.add(Box.createVerticalStrut(20), gc);

        gc.gridy = 3;
        JLabel manualLabel = new JLabel("veya manuel kişi sayısı gir:");
        manualLabel.setFont(manualLabel.getFont().deriveFont(Font.BOLD, 14f));
        center.add(manualLabel, gc);

        gc.gridy = 4; gc.gridwidth = 1;
        gc.gridx = 0;
        JSpinner countSpinner = new JSpinner(new SpinnerNumberModel(personCount, 2, 20, 1));
        countSpinner.setFont(countSpinner.getFont().deriveFont(20f));
        ((JSpinner.DefaultEditor) countSpinner.getEditor()).getTextField()
                .setColumns(4);
        center.add(countSpinner, gc);

        gc.gridx = 1; gc.gridwidth = 2;
        JButton goBtn = new JButton("Bu sayıyla devam →");
        goBtn.setFont(goBtn.getFont().deriveFont(Font.BOLD, 14f));
        goBtn.setBackground(new Color(33, 150, 243));
        goBtn.setForeground(Color.WHITE);
        goBtn.setOpaque(true);
        goBtn.setBorderPainted(false);
        goBtn.setPreferredSize(new Dimension(200, 48));
        goBtn.addActionListener(e -> {
            personCount = (Integer) countSpinner.getValue();
            goToStep2();
        });
        center.add(goBtn, gc);

        panel.add(center, BorderLayout.CENTER);

        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton cancel = new JButton("İptal");
        cancel.addActionListener(e -> dispose());
        south.add(cancel);
        panel.add(south, BorderLayout.SOUTH);

        return panel;
    }

    private JButton makeQuickButton(int n) {
        JButton btn = new JButton(n + " Kişi");
        btn.setFont(btn.getFont().deriveFont(Font.BOLD, 22f));
        btn.setPreferredSize(new Dimension(140, 80));
        btn.setBackground(new Color(76, 175, 80));
        btn.setForeground(Color.WHITE);
        btn.setOpaque(true);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.addActionListener(e -> {
            personCount = n;
            goToStep2();
        });
        return btn;
    }

    // ============================================================
    //   Adım 2 — her kişi için ödeme yöntemi
    // ============================================================

    private JComponent buildStep2Container() {
        JPanel container = new JPanel(new BorderLayout(8, 8));
        container.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        methodSelectionPanel = new JPanel();
        methodSelectionPanel.setLayout(new BoxLayout(methodSelectionPanel, BoxLayout.Y_AXIS));

        JScrollPane sp = new JScrollPane(methodSelectionPanel);
        sp.setBorder(BorderFactory.createTitledBorder("Her kişi için ödeme yöntemi seçin"));
        // Hızlı scroll — varsayılan 1 px çok yavaş
        sp.getVerticalScrollBar().setUnitIncrement(48);
        sp.getVerticalScrollBar().setBlockIncrement(96);
        container.add(sp, BorderLayout.CENTER);

        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton back = new JButton("← Geri");
        back.addActionListener(e -> cardLayout.show(cardPanel, "step1"));
        south.add(back);

        JButton confirm = new JButton("Onayla ve Tahsil Et");
        confirm.setFont(confirm.getFont().deriveFont(Font.BOLD, 14f));
        confirm.setBackground(new Color(46, 125, 50));
        confirm.setForeground(Color.WHITE);
        confirm.setOpaque(true);
        confirm.setBorderPainted(false);
        confirm.setPreferredSize(new Dimension(220, 48));
        confirm.addActionListener(e -> finalizePayment());
        south.add(confirm);
        container.add(south, BorderLayout.SOUTH);

        return container;
    }

    private void goToStep2() {
        // Tutarı eşit böl — varsayılan değer (kullanıcı sonra değiştirebilir)
        partAmounts.clear();
        partAmounts.addAll(calculateEqualSplitAmounts());

        selectedMethods.clear();
        for (int i = 0; i < personCount; i++) {
            selectedMethods.add(null);
        }

        rebuildStep2();
        cardLayout.show(cardPanel, "step2");
    }

    /** Kullanıcı tutar inputunu değiştirince çağrılır — toplamı kontrol et + label güncelle. */
    private void updateSumStatus() {
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < amountFields.size(); i++) {
            String txt = amountFields.get(i).getText();
            try {
                BigDecimal v = new BigDecimal(txt.replace(',', '.').trim());
                partAmounts.set(i, v);
                sum = sum.add(v);
            } catch (NumberFormatException ex) {
                // boş veya hatalı — 0 say
                partAmounts.set(i, BigDecimal.ZERO);
            }
        }
        BigDecimal diff = totalAmount.subtract(sum);
        if (diff.abs().compareTo(new BigDecimal("0.01")) <= 0) {
            sumStatusLabel.setText("✓ Toplam tam (" + MONEY.format(sum) + ")");
            sumStatusLabel.setForeground(new Color(46, 125, 50));
        } else if (diff.signum() > 0) {
            sumStatusLabel.setText("⚠ Eksik: " + MONEY.format(diff) + " (toplam " + MONEY.format(sum) + ")");
            sumStatusLabel.setForeground(new Color(245, 124, 0));
        } else {
            sumStatusLabel.setText("⚠ Fazla: " + MONEY.format(diff.negate()) + " (toplam " + MONEY.format(sum) + ")");
            sumStatusLabel.setForeground(new Color(198, 40, 40));
        }
    }

    private void rebuildStep2() {
        methodSelectionPanel.removeAll();
        amountFields.clear();

        // Üst bilgi: toplam ve per-kişi + canlı sayım
        JPanel header = new JPanel(new BorderLayout(8, 4));
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));
        JLabel info = new JLabel(
                "<html><b>Toplam:</b> " + MONEY.format(totalAmount)
              + "  &nbsp;|&nbsp;  <b>" + personCount + " kişi</b></html>");
        info.setFont(info.getFont().deriveFont(15f));
        header.add(info, BorderLayout.WEST);

        sumStatusLabel = new JLabel(" ");
        sumStatusLabel.setFont(sumStatusLabel.getFont().deriveFont(Font.BOLD, 13f));
        sumStatusLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        header.add(sumStatusLabel, BorderLayout.EAST);
        header.setBorder(BorderFactory.createEmptyBorder(4, 8, 12, 8));
        methodSelectionPanel.add(header);

        // Hızlı buton: Eşit Böl (kullanıcı tutar girişini sıfırlamak isterse)
        JButton equalSplitBtn = new JButton("⇄ Eşit Böl");
        equalSplitBtn.setFont(equalSplitBtn.getFont().deriveFont(Font.BOLD, 12f));
        equalSplitBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        equalSplitBtn.setMaximumSize(new Dimension(140, 32));
        equalSplitBtn.setBackground(new Color(33, 150, 243));
        equalSplitBtn.setForeground(Color.WHITE);
        equalSplitBtn.setOpaque(true);
        equalSplitBtn.setBorderPainted(false);
        equalSplitBtn.setFocusPainted(false);
        equalSplitBtn.addActionListener(e -> {
            partAmounts.clear();
            partAmounts.addAll(calculateEqualSplitAmounts());
            for (int i = 0; i < amountFields.size(); i++) {
                amountFields.get(i).setText(partAmounts.get(i).toPlainString());
            }
            updateSumStatus();
        });
        methodSelectionPanel.add(equalSplitBtn);
        methodSelectionPanel.add(Box.createVerticalStrut(8));

        for (int i = 0; i < personCount; i++) {
            methodSelectionPanel.add(buildPersonRow(i, partAmounts.get(i)));
            methodSelectionPanel.add(Box.createVerticalStrut(8));
        }

        updateSumStatus();
        methodSelectionPanel.revalidate();
        methodSelectionPanel.repaint();
    }

    private List<BigDecimal> calculateEqualSplitAmounts() {
        BigDecimal perPerson = totalAmount
                .divide(BigDecimal.valueOf(personCount), 2, RoundingMode.DOWN);
        List<BigDecimal> equalSplitAmounts = new ArrayList<>(personCount);
        BigDecimal running = BigDecimal.ZERO;
        for (int i = 0; i < personCount - 1; i++) {
            equalSplitAmounts.add(perPerson);
            running = running.add(perPerson);
        }
        equalSplitAmounts.add(totalAmount.subtract(running).setScale(2, RoundingMode.HALF_UP));
        return equalSplitAmounts;
    }

    private JPanel buildPersonRow(int personIndex, BigDecimal amount) {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(200, 200, 200), 1),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        row.setBackground(Color.WHITE);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 110));

        // Üst satır: Kişi N + Tutar TextField
        JPanel topRow = new JPanel(new BorderLayout(8, 4));
        topRow.setOpaque(false);
        JLabel personLabel = new JLabel("Kişi " + (personIndex + 1));
        personLabel.setFont(personLabel.getFont().deriveFont(Font.BOLD, 14f));
        topRow.add(personLabel, BorderLayout.WEST);

        JPanel amountRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        amountRow.setOpaque(false);
        JLabel tlLabel = new JLabel("₺");
        tlLabel.setFont(tlLabel.getFont().deriveFont(Font.BOLD, 16f));
        amountRow.add(tlLabel);

        JTextField amountField = new JTextField(amount.toPlainString(), 8);
        amountField.setFont(amountField.getFont().deriveFont(Font.BOLD, 18f));
        amountField.setHorizontalAlignment(SwingConstants.RIGHT);
        amountField.setPreferredSize(new Dimension(140, 44));
        amountField.setMinimumSize(new Dimension(140, 44));
        amountField.setMaximumSize(new Dimension(180, 44));
        amountField.setEditable(true);
        amountField.setEnabled(true);
        amountField.setBackground(Color.WHITE);
        amountField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(120, 120, 120), 1),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        // Focus alınca tüm metni seç (kullanıcı kolay üzerine yazsın)
        amountField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent e) {
                javax.swing.SwingUtilities.invokeLater(amountField::selectAll);
            }
        });
        amountField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { updateSumStatus(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { updateSumStatus(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { updateSumStatus(); }
        });
        amountFields.add(amountField);
        amountRow.add(amountField);
        topRow.add(amountRow, BorderLayout.EAST);
        row.add(topRow);
        row.add(Box.createVerticalStrut(8));

        // Alt satır: 3 yöntem butonu — Nakit, Kart, EFT
        JPanel buttons = new JPanel(new GridLayout(1, 3, 6, 0));
        buttons.setOpaque(false);
        ButtonGroup group = new ButtonGroup();

        JToggleButton cashBtn = methodButton("💵 Nakit", new Color(76, 175, 80));
        cashBtn.addActionListener(e -> selectedMethods.set(personIndex, PaymentMethod.CASH));
        if (selectedMethods.get(personIndex) == PaymentMethod.CASH) cashBtn.setSelected(true);
        group.add(cashBtn);
        buttons.add(cashBtn);

        JToggleButton cardBtn = methodButton("💳 Kart", new Color(33, 150, 243));
        cardBtn.addActionListener(e -> selectedMethods.set(personIndex, PaymentMethod.CREDIT_CARD));
        if (selectedMethods.get(personIndex) == PaymentMethod.CREDIT_CARD) cardBtn.setSelected(true);
        group.add(cardBtn);
        buttons.add(cardBtn);

        JToggleButton eftBtn = methodButton("🏦 EFT", new Color(156, 39, 176));
        eftBtn.addActionListener(e -> selectedMethods.set(personIndex, PaymentMethod.TRANSFER));
        if (selectedMethods.get(personIndex) == PaymentMethod.TRANSFER) eftBtn.setSelected(true);
        group.add(eftBtn);
        buttons.add(eftBtn);

        row.add(buttons);
        return row;
    }

    private JToggleButton methodButton(String text, Color color) {
        JToggleButton btn = new JToggleButton(text);
        btn.setFont(btn.getFont().deriveFont(Font.BOLD, 13f));
        btn.setPreferredSize(new Dimension(130, 50));
        btn.setBackground(color);
        btn.setForeground(Color.WHITE);
        btn.setOpaque(true);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        return btn;
    }

    // ============================================================
    //   Onay & finalize
    // ============================================================

    private void finalizePayment() {
        // Önce tutar inputlarından partAmounts'u tazele
        updateSumStatus();

        // Toplam kontrol
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal v : partAmounts) {
            if (v != null) sum = sum.add(v);
        }
        BigDecimal diff = totalAmount.subtract(sum).abs();
        if (diff.compareTo(new BigDecimal("0.10")) > 0) {
            JOptionPane.showMessageDialog(this,
                    "Parça toplamları (" + MONEY.format(sum) + ") sipariş toplamına ("
                  + MONEY.format(totalAmount) + ") eşit değil.\n"
                  + "Fark: " + MONEY.format(diff) + "\n\n"
                  + "Lütfen tutarları düzeltin veya '⇄ Eşit Böl' butonuna tıklayın.",
                    "Tutar uyumsuz", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Her kişinin tutarı pozitif mi?
        for (int i = 0; i < partAmounts.size(); i++) {
            if (partAmounts.get(i) == null || partAmounts.get(i).signum() <= 0) {
                JOptionPane.showMessageDialog(this,
                        "Kişi " + (i + 1) + " için tutar 0'dan büyük olmalı.",
                        "Tutar eksik", JOptionPane.WARNING_MESSAGE);
                return;
            }
        }

        // Tüm kişiler yöntem seçti mi?
        for (int i = 0; i < selectedMethods.size(); i++) {
            if (selectedMethods.get(i) == null) {
                JOptionPane.showMessageDialog(this,
                        "Kişi " + (i + 1) + " için ödeme yöntemi seçilmedi.",
                        "Eksik", JOptionPane.WARNING_MESSAGE);
                return;
            }
        }

        // Onay
        int confirm = JOptionPane.showConfirmDialog(this,
                "Hesap " + personCount + " kişiye bölünecek ve her parça için ayrı ödeme kaydı oluşacak. Onaylıyor musunuz?",
                "Onay", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;

        // SplitPart listesini oluştur
        List<AppState.SplitPart> parts = new ArrayList<>();
        for (int i = 0; i < personCount; i++) {
            parts.add(new AppState.SplitPart(partAmounts.get(i), selectedMethods.get(i)));
        }

        try {
            appState.recordSplitSale(tableNo, currentUser, parts);
            JOptionPane.showMessageDialog(this,
                    "Hesap başarıyla " + personCount + " parça olarak tahsil edildi.",
                    "Başarılı", JOptionPane.INFORMATION_MESSAGE);
            dispose();
        } catch (SecurityException ex) {
            JOptionPane.showMessageDialog(this,
                    ex.getMessage(),
                    "Yetki Yok", JOptionPane.WARNING_MESSAGE);
        } catch (RuntimeException ex) {
            JOptionPane.showMessageDialog(this,
                    "Hesap bölme başarısız: " + ex.getMessage(),
                    "Hata", JOptionPane.ERROR_MESSAGE);
        }
    }
}
