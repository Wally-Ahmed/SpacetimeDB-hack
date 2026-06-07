package dev.builders;

/**
 * Builder personality helper. Static, no Bukkit state.
 *
 * <p>Eight fixed personality types drive (a) a Builder's spoken voice when a disaster strikes and
 * (b) a light combat behavior hint ("fight" | "flee" | "neutral"). Used by the ScenarioEngine for
 * area-scoped warnings and by BuilderManager when assigning a personality on spawn.
 *
 * <p>Personalities are assigned deterministically per roster slot so a given Builder always has the
 * same type across restarts.
 */
public final class Personalities {

    private Personalities() {}

    /** The 8 personality types, in fixed order. {@link #forRosterSlot(int)} indexes into this. */
    public static final String[] TYPES = {
            "brave",      // 0
            "timid",      // 1
            "cheerful",   // 2
            "gruff",      // 3
            "scholarly",  // 4
            "greedy",     // 5
            "kind",       // 6
            "paranoid",   // 7
    };

    /**
     * Deterministic personality type for a roster slot. Wraps mod 8 (handles negatives), so it is
     * safe to pass any int (e.g. an NPC id used as a fallback slot).
     */
    public static String forRosterSlot(int slotIndex) {
        int i = ((slotIndex % TYPES.length) + TYPES.length) % TYPES.length;
        return TYPES[i];
    }

    /**
     * Light combat behavior hint per type:
     * brave/gruff → "fight"; timid/paranoid → "flee"; cheerful/scholarly/greedy/kind → "neutral".
     * Unknown types default to "neutral".
     */
    public static String behavior(String type) {
        if (type == null) return "neutral";
        switch (type) {
            case "brave":
            case "gruff":
                return "fight";
            case "timid":
            case "paranoid":
                return "flee";
            case "cheerful":
            case "scholarly":
            case "greedy":
            case "kind":
                return "neutral";
            default:
                return "neutral";
        }
    }

    /**
     * One in-character sentence blending personality, name, and job. {@code job} may be empty; when
     * empty it falls back to "Builder". Unknown types get a neutral-but-flavored default.
     */
    public static String backstory(String type, String name, String job) {
        String who = (name == null || name.isEmpty()) ? "This one" : name;
        String role = (job == null || job.isEmpty()) ? "Builder" : job;
        String t = (type == null) ? "" : type;
        switch (t) {
            case "brave":
                return who + ", a fearless " + role + " who has never once fled a fight.";
            case "timid":
                return who + ", a jittery " + role + " who would rather hide than face any danger.";
            case "cheerful":
                return who + ", a sunny " + role + " who hums a tune even when the sky falls.";
            case "gruff":
                return who + ", a blunt, no-nonsense " + role + " with little patience for chatter.";
            case "scholarly":
                return who + ", a thoughtful " + role + " who studies every threat before acting.";
            case "greedy":
                return who + ", a coin-counting " + role + " who values loot above almost all else.";
            case "kind":
                return who + ", a gentle " + role + " who puts the safety of others first.";
            case "paranoid":
                return who + ", a wary " + role + " who was certain disaster was coming all along.";
            default:
                return who + ", a " + role + " of the old craft.";
        }
    }

    /**
     * A short, in-character warning that varies by personality type and scenario kind. Scenario
     * kinds: {@code zombie_outbreak}, {@code wildfire}, {@code storm_flood}, {@code meteor}. Any
     * unknown type or kind falls back to a sensible default line. No emojis.
     */
    public static String warnLine(String type, String scenarioKind) {
        String t = (type == null) ? "" : type;
        String k = (scenarioKind == null) ? "" : scenarioKind;
        switch (t) {
            case "brave":
                switch (k) {
                    case "zombie_outbreak": return "To arms! The dead are upon us!";
                    case "wildfire":        return "Stand fast! We'll beat back these flames!";
                    case "storm_flood":     return "Let the storm rage. Hold your ground!";
                    case "meteor":          return "Fire from the sky? Then we meet it head on!";
                    default:                return "Steady now. Whatever comes, we face it!";
                }
            case "timid":
                switch (k) {
                    case "zombie_outbreak": return "Run! Flee while you can, the dead are coming!";
                    case "wildfire":        return "Fire! Oh no, get away, get away from here!";
                    case "storm_flood":     return "The waters are rising! Please, let's flee!";
                    case "meteor":          return "The sky is falling! Hide, everyone, hide!";
                    default:                return "Something's wrong! Run, just run!";
                }
            case "cheerful":
                switch (k) {
                    case "zombie_outbreak": return "Heads up, friends! A few walking corpses, no biggie!";
                    case "wildfire":        return "Toasty out today! Mind the flames, will you?";
                    case "storm_flood":     return "What a downpour! Watch your step in the puddles!";
                    case "meteor":          return "Ooh, shooting stars! Best not to stand under them!";
                    default:                return "Lovely chaos today! Do take care, won't you?";
                }
            case "gruff":
                switch (k) {
                    case "zombie_outbreak": return "Dead's walking. Pick up a blade or get out.";
                    case "wildfire":        return "Fire's spreading. Move it.";
                    case "storm_flood":     return "Storm's here. Don't drown, that's on you.";
                    case "meteor":          return "Rocks falling. Watch your thick skull.";
                    default:                return "Trouble's here. Deal with it.";
                }
            case "scholarly":
                switch (k) {
                    case "zombie_outbreak": return "Undead, en masse. I'd advise we regroup and arm ourselves.";
                    case "wildfire":        return "The blaze spreads with the wind. Clear flammables, quickly.";
                    case "storm_flood":     return "Pressure's dropping fast. Expect flooding; seek high ground.";
                    case "meteor":          return "Impacts incoming from above. Maintain distance from open sky.";
                    default:                return "Conditions are deteriorating. Caution is the rational course.";
                }
            case "greedy":
                switch (k) {
                    case "zombie_outbreak": return "Zombies?! Grab the valuables before they trample them!";
                    case "wildfire":        return "My goods! Don't let the fire touch the stockpile!";
                    case "storm_flood":     return "The flood'll ruin the stores! Save the loot first!";
                    case "meteor":          return "If a rock dents my chests I'll be furious! Cover them!";
                    default:                return "Protect the inventory, never mind the rest!";
                }
            case "kind":
                switch (k) {
                    case "zombie_outbreak": return "The dead are near, friends. Stay close, I'll keep you safe.";
                    case "wildfire":        return "Flames ahead! Get behind me, all of you, hurry.";
                    case "storm_flood":     return "The water rises. Take my hand, we'll reach high ground together.";
                    case "meteor":          return "The sky is falling. Shelter together, no one faces this alone.";
                    default:                return "Danger's about. Stay with me and we'll be alright.";
                }
            case "paranoid":
                switch (k) {
                    case "zombie_outbreak": return "I knew it! The dead always come. I warned you all!";
                    case "wildfire":        return "Fire! I said this would happen, didn't I? I said it!";
                    case "storm_flood":     return "The flood, just as I feared. No one ever listens to me!";
                    case "meteor":          return "From the sky, of course! I knew the heavens would turn on us!";
                    default:                return "See? See?! I always said disaster was coming!";
                }
            default:
                switch (k) {
                    case "zombie_outbreak": return "Beware! A zombie outbreak is upon us!";
                    case "wildfire":        return "Beware! A wildfire is spreading nearby!";
                    case "storm_flood":     return "Beware! A violent storm and floods approach!";
                    case "meteor":          return "Beware! Meteors are falling from the sky!";
                    default:                return "Beware! Danger is near!";
                }
        }
    }
}
