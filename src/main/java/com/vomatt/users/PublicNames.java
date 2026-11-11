package com.vomatt.users;

import com.vomatt.entity.User;
import com.vomatt.entity.UserPreference;
import com.vomatt.repository.UserPreferenceRepository;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Public names of many users at once, each masked by its owner's visibility settings, in one preference query. */
public final class PublicNames {

    private static final Set<VisibilityField> NAME_FIELDS =
            EnumSet.of(VisibilityField.DISPLAY_NAME, VisibilityField.FIRST_NAME, VisibilityField.LAST_NAME);
    private static final Map<String, VisibilityField> BY_KEY = NAME_FIELDS.stream()
            .collect(Collectors.toMap(VisibilityField::preferenceKey, field -> field));

    private PublicNames() {
    }

    /** Keyed by user id; every user given has an entry. */
    public static Map<UUID, PublicName> load(Collection<User> users, UserPreferenceRepository preferences) {
        return mask(users.stream().collect(Collectors.toMap(User::getId,
                u -> new PublicName(u.getDisplayName(), u.getFirstName(), u.getLastName()), (a, b) -> a)), preferences);
    }

    /** {@code names} (each user's own, unmasked) as their owners' visibility settings show them, in one preference query. */
    public static Map<UUID, PublicName> mask(Map<UUID, PublicName> names, UserPreferenceRepository preferences) {
        if (names.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Set<VisibilityField>> shown = new HashMap<>();
        names.keySet().forEach(id -> shown.put(id, defaults()));
        List<UserPreference> prefs = preferences.findByUserIdInAndKeyIn(names.keySet(), BY_KEY.keySet());
        for (UserPreference pref : prefs) {
            Set<VisibilityField> fields = shown.get(pref.getUser().getId());
            VisibilityField field = BY_KEY.get(pref.getKey());
            if (fields == null || field == null) {
                continue;
            }
            if (Boolean.parseBoolean(pref.getValue())) {
                fields.add(field);
            } else {
                fields.remove(field);
            }
        }
        return names.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().showing(shown.get(e.getKey()))));
    }

    public static PublicName load(User user, UserPreferenceRepository preferences) {
        return load(List.of(user), preferences).get(user.getId());
    }

    private static Set<VisibilityField> defaults() {
        Set<VisibilityField> fields = EnumSet.noneOf(VisibilityField.class);
        NAME_FIELDS.stream().filter(VisibilityField::isVisibleByDefault).forEach(fields::add);
        return fields;
    }
}
