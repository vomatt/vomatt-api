package com.vomattapi.domain.member;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity
@Table(name = "member_preferences", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"member_id", "preference_key"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = "member")
@EqualsAndHashCode(of = "id")
public class MemberPreference {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Column(name = "preference_key", nullable = false, length = 50)
    private String key;

    @Column(name = "preference_value")
    private String value;

    public MemberPreference(Member member, String key, String value) {
        this.member = member;
        this.key = key;
        this.value = value;
    }
}
