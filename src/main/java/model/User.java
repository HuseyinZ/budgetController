package model;

public class User extends BaseEntity {
    public User(String username, String passwordHash, Role role) {
        setUsername(username);
        setPasswordHash(passwordHash);
        setRole(role);
    }
    // İsteğe bağlı: fullName alanını da alan bir ctor
    public User(String username, String passwordHash, Role role, String fullName) {
        this(username, passwordHash, role);
        setFullName(fullName);
    }

    private String username;
    private String passwordHash;
    private String fullName;   // <-- eklendi
    private Role role = Role.KASIYER;
    private boolean active = true;

    public String getUsername() { return username; }
    public void setUsername(String username) {
        if (username == null || (username = username.trim()).isEmpty())
            throw new IllegalArgumentException("username boş");
        this.username = username;
    }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) {
        if (passwordHash == null || passwordHash.isEmpty())
            throw new IllegalArgumentException("hash boş");
        this.passwordHash = passwordHash;
    }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) {
        if (fullName == null || (fullName = fullName.trim()).isEmpty())
            throw new IllegalArgumentException("full_name boş");
        this.fullName = fullName;
    }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
