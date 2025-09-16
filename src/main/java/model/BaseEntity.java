package model;

import java.time.LocalDateTime;
import java.util.Objects;

public abstract class BaseEntity {
    protected Long id;
    protected LocalDateTime createdAt = LocalDateTime.now();
    protected LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public void touch() { this.updatedAt = java.time.LocalDateTime.now(); }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BaseEntity)) return false;
        BaseEntity that = (BaseEntity) o;
        return id != null && Objects.equals(id, that.id);
    }


    @Override public int hashCode() { return id != null ? id.hashCode() : super.hashCode(); }

    @Override public String toString() {
        return getClass().getSimpleName() + "{id=" + id + ", createdAt=" + createdAt + ", updatedAt=" + updatedAt + "}";
    }
}
