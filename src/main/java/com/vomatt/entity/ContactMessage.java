package com.vomatt.entity;

import com.vomatt.entity.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/** A question sent from the about site's contact form; the sender is anonymous and {@code email} is the reply-to address. */
@Entity
@Table(name = "contact_messages")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"email", "message"})
public class ContactMessage extends BaseEntity {

    @NotNull
    @Column(name = "name", length = 100)
    private String name;

    @NotNull
    @Column(name = "email", length = 254)
    private String email;

    @NotNull
    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    /** Page language the visitor wrote from: {@code en} or {@code zh}. */
    @NotNull
    @Column(name = "locale", length = 8)
    private String locale;
}
