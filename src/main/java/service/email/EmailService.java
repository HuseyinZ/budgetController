package service.email;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * E-posta gönderim servisi (Jakarta Mail tabanlı).
 *
 * <p>Yapılandırma {@code src/main/resources/email-config.properties} dosyasından
 * okunur. Anahtarlar:
 * <ul>
 *   <li>{@code smtp.host} — SMTP sunucu adresi</li>
 *   <li>{@code smtp.port} — Port (587 STARTTLS, 465 SSL)</li>
 *   <li>{@code smtp.starttls} — true/false</li>
 *   <li>{@code smtp.ssl} — true/false (SMTPS)</li>
 *   <li>{@code smtp.user} — Kullanıcı adı (genelde e-posta adresi)</li>
 *   <li>{@code smtp.password} — Şifre (Gmail için uygulama şifresi)</li>
 *   <li>{@code smtp.from} — "Ad &lt;adres&gt;" formatında gönderen</li>
 *   <li>{@code report.to} — Varsayılan alıcı (virgülle ayrılır)</li>
 * </ul>
 *
 * <p>Hata durumunda {@link RuntimeException} fırlatır; çağıran katman
 * (UI) kullanıcıya gösterir.
 */
public class EmailService {

    private static final Logger LOG = LoggerFactory.getLogger(EmailService.class);
    private static final String CONFIG_PATH = "/email-config.properties";

    private final Properties config;

    public EmailService() {
        this.config = loadConfig();
    }

    /** Test/DI için: dışarıdan config geçirilebilir. */
    public EmailService(Properties config) {
        this.config = config == null ? new Properties() : config;
    }

    private Properties loadConfig() {
        Properties p = new Properties();
        try {
            if (!loadConfigInto(p)) {
                LOG.warn("email-config.properties bulunamadı (classpath:{})", CONFIG_PATH);
            }
        } catch (IOException ex) {
            LOG.warn("email-config.properties okunamadı: {}", ex.getMessage());
        }
        return p;
    }

    static boolean loadConfigInto(Properties properties) throws IOException {
        try (InputStream in = EmailService.class.getResourceAsStream(CONFIG_PATH)) {
            if (in == null) return false;
            // UTF-8 okuma (Türkçe karakterler için)
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return true;
        }
    }

    /** Konfigürasyonda alıcı tanımlıysa onu döner; yoksa boş string. */
    public String defaultRecipient() {
        return config.getProperty("report.to", "").trim();
    }

    /** SMTP host/user/password set edilmiş mi? UI'da "Excel'i Mail At" butonunu disable etmek için. */
    public boolean isConfigured() {
        return !empty(config.getProperty("smtp.host"))
                && !empty(config.getProperty("smtp.user"))
                && !empty(config.getProperty("smtp.password"));
    }

    /**
     * Excel ekiyle birlikte e-posta gönderir.
     *
     * @param to           virgülle ayrılmış alıcı listesi
     * @param subject      konu satırı
     * @param bodyText     mesaj gövdesi (plain text)
     * @param fileName     eklenen dosyanın adı (ör. "gun-sonu-2026-06-04.xlsx")
     * @param fileBytes    eklenen dosya içeriği
     * @param mimeType     ek MIME tipi (ör. application/vnd.openxmlformats-...)
     */
    public void sendWithAttachment(String to, String subject, String bodyText,
                                   String fileName, byte[] fileBytes, String mimeType) {
        if (empty(to)) {
            throw new IllegalArgumentException("Alıcı (to) boş olamaz");
        }
        if (!isConfigured()) {
            throw new IllegalStateException(
                    "SMTP yapılandırması eksik. email-config.properties dosyasını doldurun.");
        }

        final String host = config.getProperty("smtp.host").trim();
        final int port = parseInt(config.getProperty("smtp.port", "587"), 587);
        final boolean starttls = "true".equalsIgnoreCase(config.getProperty("smtp.starttls", "true"));
        final boolean ssl = "true".equalsIgnoreCase(config.getProperty("smtp.ssl", "false"));
        final String user = config.getProperty("smtp.user").trim();
        final String pass = config.getProperty("smtp.password").trim();
        final String from = config.getProperty("smtp.from", user).trim();

        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", String.valueOf(port));
        if (starttls) props.put("mail.smtp.starttls.enable", "true");
        if (ssl) {
            props.put("mail.smtp.ssl.enable", "true");
            props.put("mail.smtp.socketFactory.port", String.valueOf(port));
            props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
        }
        props.put("mail.smtp.connectiontimeout", "15000");
        props.put("mail.smtp.timeout", "30000");
        props.put("mail.smtp.writetimeout", "30000");

        Session session = Session.getInstance(props, new Authenticator() {
            @Override protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(user, pass);
            }
        });

        try {
            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(parseFrom(from, user));
            // Birden fazla alıcı için virgülle parse et
            for (String addr : to.split("[,;]")) {
                String a = addr.trim();
                if (a.isEmpty()) continue;
                msg.addRecipient(Message.RecipientType.TO, new InternetAddress(a));
            }
            msg.setSubject(subject == null ? "(konu yok)" : subject, "UTF-8");

            MimeMultipart mp = new MimeMultipart("mixed");

            // 1. gövde
            MimeBodyPart bodyPart = new MimeBodyPart();
            bodyPart.setText(bodyText == null ? "" : bodyText, "UTF-8");
            mp.addBodyPart(bodyPart);

            // 2. ek
            if (fileBytes != null && fileBytes.length > 0) {
                MimeBodyPart attach = new MimeBodyPart();
                String mt = (mimeType == null || mimeType.isBlank())
                        ? "application/octet-stream" : mimeType;
                ByteArrayDataSource ds = new ByteArrayDataSource(fileBytes, mt);
                attach.setDataHandler(new jakarta.activation.DataHandler(ds));
                attach.setFileName(fileName == null ? "rapor.xlsx" : fileName);
                mp.addBodyPart(attach);
            }

            msg.setContent(mp);
            Transport.send(msg);
            // GÜVENLİK: alıcı adresi log'a tam yazılmaz, maskelenir.
            LOG.info("E-posta gönderildi: to={}, subject={}",
                    service.util.Mask.email(to), subject);
        } catch (MessagingException ex) {
            // Hata mesajında SMTP user/password olabilir → maskele.
            String safe = ex.getMessage() == null ? "" : ex.getMessage()
                    .replaceAll("(?i)password=\\S+", "password=****");
            throw new RuntimeException("E-posta gönderilemedi: " + safe, ex);
        }
    }

    private static InternetAddress parseFrom(String fromHeader, String fallbackAddr) throws MessagingException {
        // "Ad <adres@x.com>" veya doğrudan "adres@x.com"
        try {
            InternetAddress[] arr = InternetAddress.parse(fromHeader);
            if (arr.length > 0) return arr[0];
        } catch (Exception ex) {
            LOG.warn("Sender address parsing failed; using fallback. Exception type: {}",
                    ex.getClass().getSimpleName());
        }
        return new InternetAddress(fallbackAddr);
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception ex) { return def; }
    }

    private static boolean empty(String s) { return s == null || s.trim().isEmpty(); }
}
