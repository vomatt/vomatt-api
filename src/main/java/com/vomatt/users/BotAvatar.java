package com.vomatt.users;

import java.security.SecureRandom;
import java.util.List;

/**
 * The bot cast avatars: stored in {@code users.avatar_url} as {@code bot:<kind>}, rendered by the frontend.
 * Kinds and their order mirror vomatt-web {@code src/features/avatars/catalog.ts}.
 */
public final class BotAvatar {

    private static final String KIND_ALTERNATION =
            "boba|bao|oolong|su|egg|ice|youtiao|tofu|sausage|chip|tower|lumen"
            + "|mazu|tudi|yuelao|qiye|baye|shi|long|denglong|ding|jiaobei|xiang|banyan"
            + "|gray|nordic|reptile|mantis|anu|tallwhite|moth|blob|cyclops|probe|saucer|trio"
            + "|bear|magpie|pangolin|shihu|macaque|deer|pheasant|firefly|crab|flyfish|turtle|dog";

    /** Compile-time constant so it can be used in {@code @Pattern}. */
    public static final String PATTERN = "^bot:(" + KIND_ALTERNATION + ")$";

    public static final List<String> KINDS = List.of(KIND_ALTERNATION.split("\\|"));

    private static final SecureRandom RANDOM = new SecureRandom();

    private BotAvatar() {}

    public static String random() {
        return "bot:" + KINDS.get(RANDOM.nextInt(KINDS.size()));
    }
}
