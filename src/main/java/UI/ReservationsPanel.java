package UI;

import model.Reservation;
import model.ReservationStatus;
import model.RestaurantTable;
import model.User;
import service.ReservationService;
import service.RestaurantTableService;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;

/**
 * Masa rezervasyonu yönetim paneli (Swing).
 *
 * <ul>
 *   <li>Üstte tarih seçici + "Yeni Rezervasyon" + "Bugün" + "Yenile" butonları</li>
 *   <li>Tabloda o gün için tüm rezervasyonlar listelenir</li>
 *   <li>Sağ tıkla / butonla iptal / oturt (SEATED) / gelmedi (NO_SHOW) işaretleme</li>
 * </ul>
 */
public class ReservationsPanel extends JPanel {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");

    private final ReservationService service = new ReservationService();
    private final RestaurantTableService tableService = new RestaurantTableService();
    private final User currentUser;

    private final JSpinner dateSpinner = new JSpinner(
            new SpinnerDateModel(new Date(), null, null, java.util.Calendar.DAY_OF_MONTH));

    private final DefaultTableModel model = new DefaultTableModel(
            new Object[]{"ID", "Masa", "Başlangıç", "Bitiş", "Müşteri", "Telefon", "Kişi", "Durum", "Not"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable table = new JTable(model);

    public ReservationsPanel(User currentUser) {
        this.currentUser = currentUser;
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        add(buildToolbar(), BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);

        table.setRowHeight(28);
        table.setFont(table.getFont().deriveFont(13f));
        table.getColumnModel().getColumn(0).setMaxWidth(60);
        table.getColumnModel().getColumn(1).setMaxWidth(60);
        table.getColumnModel().getColumn(6).setMaxWidth(60);
        // Durum sütunu renkli
        table.getColumnModel().getColumn(7).setCellRenderer(new StatusRenderer());

        reload();
    }

    private JComponent buildToolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);

        bar.add(new JLabel("Tarih: "));
        dateSpinner.setEditor(new JSpinner.DateEditor(dateSpinner, "dd-MM-yyyy"));
        dateSpinner.setPreferredSize(new Dimension(160, 36));
        bar.add(dateSpinner);

        bar.addSeparator();
        JButton today = new JButton("Bugün");
        today.addActionListener(e -> { dateSpinner.setValue(new Date()); reload(); });
        bar.add(today);

        JButton reload = new JButton("Yenile");
        reload.addActionListener(e -> reload());
        bar.add(reload);

        bar.addSeparator();
        JButton add = new JButton("➕ Yeni Rezervasyon");
        add.setPreferredSize(new Dimension(200, 40));
        add.addActionListener(e -> openNewDialog());
        bar.add(add);

        bar.addSeparator();
        JButton cancel = new JButton("✖ İptal");
        cancel.addActionListener(e -> applyToSelected(ReservationStatus.CANCELLED, "iptal"));
        bar.add(cancel);

        JButton seat = new JButton("✓ Oturt");
        seat.addActionListener(e -> applyToSelected(ReservationStatus.SEATED, "oturt"));
        bar.add(seat);

        JButton noshow = new JButton("⏰ Gelmedi");
        noshow.addActionListener(e -> applyToSelected(ReservationStatus.NO_SHOW, "gelmedi"));
        bar.add(noshow);

        // Spinner değişimi otomatik yeniden yükler
        dateSpinner.addChangeListener(e -> reload());
        return bar;
    }

    private void reload() {
        Date d = (Date) dateSpinner.getValue();
        LocalDate date = toLocalDate(d);
        List<Reservation> rows = service.listForDate(date);
        model.setRowCount(0);
        for (Reservation r : rows) {
            model.addRow(new Object[]{
                    r.getId(),
                    r.getTableNo(),
                    r.getStartTime() == null ? "" : r.getStartTime().format(TIME_FMT),
                    r.getEndTime() == null ? "" : r.getEndTime().format(TIME_FMT),
                    r.getCustomerName() == null ? "" : r.getCustomerName(),
                    r.getCustomerPhone() == null ? "" : r.getCustomerPhone(),
                    r.getPartySize(),
                    r.getStatus() == null ? "BOOKED" : r.getStatus().name(),
                    r.getNotes() == null ? "" : r.getNotes()
            });
        }
    }

    private void applyToSelected(ReservationStatus status, String label) {
        int row = table.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Önce listeden bir rezervasyon seçin.",
                    "Uyarı", JOptionPane.WARNING_MESSAGE);
            return;
        }
        long id = ((Number) model.getValueAt(row, 0)).longValue();
        int confirm = JOptionPane.showConfirmDialog(this,
                "Bu rezervasyonu '" + label + "' olarak işaretle?",
                "Onay", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;
        try {
            switch (status) {
                case CANCELLED -> service.cancel(id);
                case SEATED    -> service.markSeated(id);
                case NO_SHOW   -> service.markNoShow(id);
                default -> {}
            }
            reload();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "İşlem başarısız: " + ex.getMessage(),
                    "Hata", JOptionPane.ERROR_MESSAGE);
        }
    }

    // --------------------------------------------------------------
    //   Yeni rezervasyon dialog'u
    // --------------------------------------------------------------
    private void openNewDialog() {
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this),
                "Yeni Rezervasyon", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setLayout(new BorderLayout(8, 8));

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 6, 4, 6);
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.weightx = 1;

        Date selectedDay = (Date) dateSpinner.getValue();
        LocalDate baseDate = toLocalDate(selectedDay);
        Date startDefault = toDate(baseDate, LocalTime.of(19, 0));
        Date endDefault = toDate(baseDate, LocalTime.of(21, 0));

        // Masa: gerçek restoran masalarından picker
        final int[] selectedTableNo = { 0 };
        JButton tablePickBtn = new JButton("Masa Seç …");
        tablePickBtn.setPreferredSize(new Dimension(160, 36));
        tablePickBtn.addActionListener(ae -> {
            Integer picked = openTablePicker(dialog);
            if (picked != null) {
                selectedTableNo[0] = picked;
                tablePickBtn.setText("Masa " + picked);
            }
        });

        JSpinner startF = new JSpinner(new SpinnerDateModel(startDefault, null, null, java.util.Calendar.MINUTE));
        startF.setEditor(new JSpinner.DateEditor(startF, "dd-MM-yyyy HH:mm"));
        JSpinner endF = new JSpinner(new SpinnerDateModel(endDefault, null, null, java.util.Calendar.MINUTE));
        endF.setEditor(new JSpinner.DateEditor(endF, "dd-MM-yyyy HH:mm"));
        JTextField nameF = new JTextField();
        JTextField phoneF = new JTextField();
        JSpinner partyF = new JSpinner(new SpinnerNumberModel(2, 1, 50, 1));
        JTextArea notesF = new JTextArea(3, 20);
        notesF.setLineWrap(true);
        notesF.setWrapStyleWord(true);

        int row = 0;
        addRow(form, g, row++, "Masa:",      tablePickBtn);
        addRow(form, g, row++, "Başlangıç:", startF);
        addRow(form, g, row++, "Bitiş:",     endF);
        addRow(form, g, row++, "Müşteri:",   nameF);
        addRow(form, g, row++, "Telefon:",   phoneF);
        addRow(form, g, row++, "Kişi sayısı:", partyF);
        addRow(form, g, row++, "Notlar:",    new JScrollPane(notesF));

        JButton ok = new JButton("Kaydet");
        JButton cancel = new JButton("Vazgeç");
        ok.setPreferredSize(new Dimension(120, 36));
        cancel.setPreferredSize(new Dimension(120, 36));

        ok.addActionListener(ae -> {
            try {
                int tn = selectedTableNo[0];
                if (tn <= 0) {
                    JOptionPane.showMessageDialog(dialog, "Lütfen önce bir masa seçin.",
                            "Uyarı", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                LocalDateTime s = toLocalDateTime((Date) startF.getValue());
                LocalDateTime e = toLocalDateTime((Date) endF.getValue());
                String name = nameF.getText().trim();
                String phone = phoneF.getText().trim();
                int party = ((Number) partyF.getValue()).intValue();
                String notes = notesF.getText().trim();
                String creator = currentUser == null ? null : currentUser.getUsername();

                service.create(tn, s, e, name, phone.isEmpty() ? null : phone,
                        party, notes.isEmpty() ? null : notes, creator);
                dialog.dispose();
                reload();
            } catch (IllegalArgumentException | IllegalStateException ex) {
                JOptionPane.showMessageDialog(dialog, ex.getMessage(),
                        "Uyarı", JOptionPane.WARNING_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(dialog, "Kaydedilemedi: " + ex.getMessage(),
                        "Hata", JOptionPane.ERROR_MESSAGE);
            }
        });
        cancel.addActionListener(ae -> dialog.dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancel);
        buttons.add(ok);

        dialog.add(form, BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private static LocalDate toLocalDate(Date date) {
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private static Date toDate(LocalDate date, LocalTime time) {
        return Date.from(date.atTime(time).atZone(ZoneId.systemDefault()).toInstant());
    }

    private static LocalDateTime toLocalDateTime(Date date) {
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
    }

    private void addRow(JPanel p, GridBagConstraints g, int row, String label, JComponent comp) {
        g.gridy = row;
        g.gridx = 0; g.weightx = 0;
        p.add(new JLabel(label), g);
        g.gridx = 1; g.weightx = 1;
        p.add(comp, g);
    }

    /**
     * Restoran masalarından seçim için modal picker.
     * Seçilen masa numarasını döner; iptal halinde {@code null}.
     */
    private Integer openTablePicker(Window owner) {
        JDialog picker = new JDialog(owner, "Masa Seç", Dialog.ModalityType.APPLICATION_MODAL);
        final Integer[] picked = { null };

        java.util.List<RestaurantTable> tables;
        try {
            tables = tableService.getAllTables();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(owner, "Masalar alınamadı: " + ex.getMessage(),
                    "Hata", JOptionPane.ERROR_MESSAGE);
            return null;
        }
        tables.sort((a, b) -> {
            int an = a.getTableNo() == null ? 0 : a.getTableNo();
            int bn = b.getTableNo() == null ? 0 : b.getTableNo();
            return Integer.compare(an, bn);
        });

        // Scrollable JPanel: viewport genişliğine uy, yüksekliğe değil → dikey scroll çalışır
        JPanel grid = new JPanel(new GridLayout(0, 5, 8, 8)) {
            @Override
            public java.awt.Dimension getPreferredSize() {
                java.awt.Dimension d = super.getPreferredSize();
                // Tablo sayısı az ise yüksekliğin daralmasına izin ver, çok ise scroll için olduğu gibi bırak
                return d;
            }
        };
        grid.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        if (tables.isEmpty()) {
            grid.add(new JLabel("Tanımlı masa yok"));
        }
        for (RestaurantTable t : tables) {
            if (t.getTableNo() == null) continue;
            int no = t.getTableNo();
            JButton b = new JButton("Masa " + no);
            b.setPreferredSize(new Dimension(110, 60));
            b.setFont(b.getFont().deriveFont(Font.BOLD, 14f));
            // Doluysa hafif renkli ipucu
            if (t.isOccupied()) {
                b.setBackground(new Color(252, 232, 232));
                b.setToolTipText("Dolu (yine de rezervasyon eklenebilir)");
            } else {
                b.setBackground(new Color(220, 247, 220));
            }
            b.addActionListener(ae -> {
                picked[0] = no;
                picker.dispose();
            });
            grid.add(b);
        }

        // Scroll pane — dikey scroll daima görünür, yatay yok, mouse wheel hızlı
        JScrollPane sp = new JScrollPane(grid,
                JScrollPane.VERTICAL_SCROLLBAR_ALWAYS,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setPreferredSize(new Dimension(640, 420));
        sp.getVerticalScrollBar().setUnitIncrement(24);
        sp.setWheelScrollingEnabled(true);
        // Grid çok küçükse pencere gereksiz büyük olmasın
        sp.setMinimumSize(new Dimension(400, 300));

        JButton cancel = new JButton("Vazgeç");
        cancel.setPreferredSize(new Dimension(120, 36));
        cancel.addActionListener(ae -> picker.dispose());
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        south.add(cancel);

        picker.setLayout(new BorderLayout(8, 8));
        picker.add(sp, BorderLayout.CENTER);
        picker.add(south, BorderLayout.SOUTH);
        picker.pack();
        // Ekran yüksekliğinin %80'inden fazla olmasın
        Dimension scr = Toolkit.getDefaultToolkit().getScreenSize();
        int maxH = (int) (scr.height * 0.8);
        if (picker.getHeight() > maxH) {
            picker.setSize(picker.getWidth(), maxH);
        }
        picker.setLocationRelativeTo(owner);
        picker.setVisible(true);
        return picked[0];
    }

    /** Durum sütunu için renkli renderer. */
    private static class StatusRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel,
                                                       boolean focus, int row, int col) {
            Component c = super.getTableCellRendererComponent(t, v, sel, focus, row, col);
            String s = String.valueOf(v);
            if (!sel) {
                switch (s) {
                    case "BOOKED"    -> c.setBackground(new Color(220, 235, 252));
                    case "SEATED"    -> c.setBackground(new Color(220, 247, 220));
                    case "CANCELLED" -> c.setBackground(new Color(245, 220, 220));
                    case "NO_SHOW"   -> c.setBackground(new Color(245, 235, 200));
                    default          -> c.setBackground(Color.WHITE);
                }
            }
            return c;
        }
    }
}
