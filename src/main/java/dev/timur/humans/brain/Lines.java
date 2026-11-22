package dev.timur.humans.brain;

import dev.timur.humans.Human;

import java.util.Random;

/** Ready-made short lines: used for simple replies so we don't spend Flash-Lite on "ok" and "thanks". */
public final class Lines {
    private Lines() {}
    private static final Random R = new Random();

    public static final String[] AGREE = {"Okay!", "Sure thing, {p}.", "On it!", "Alright, alright.", "You got it.", "Fine by me.", "Yep!", "Let's go!",
        "Sure, why not.", "Okay okay, I'm going!", "Consider it done.", "As you wish, {p}."};
    public static final String[] REFUSE = {"Nah.", "No way, {p}.", "Not happening.", "Ask someone else.", "Hmm... no.", "Why should I?", "I'd rather not.", "Nope!"};
    public static final String[] THANKS = {"Thanks, {p}!", "Ooh, {item}! Thank you!", "For me? Thanks!", "You're the best, {p}.", "Nice, {item}! Cheers.",
        "Wow, thanks {p}!", "I'll put {item} to good use."};
    public static final String[] GREET = {"Hey {p}!", "Hi!", "Oh, hello {p}.", "Yo!", "Hey hey!", "Good to see you, {p}.", "Hiya!"};
    public static final String[] BYE = {"Bye {p}!", "See ya!", "Later!", "Take care.", "Bye bye!"};
    public static final String[] LAUGH = {"Haha!", "Hah, good one.", "Hehe.", "LOL", "Pfft, haha.", "Hahaha, stop it."};
    public static final String[] ANNOYED = {"Hmph.", "Rude.", "Whatever, {p}.", "Leave me alone.", "Ugh.", "Says you.", "Wow. Okay."};
    public static final String[] SHRUG = {"Hmm, no idea.", "Dunno.", "Maybe?", "Huh?", "Not sure, {p}.", "Hm."};
    public static final String[] CANT = {"Uh, I can't do that right now.", "I don't know how to do that.", "Can't, sorry."};
    public static final String[] WARN_BELIEVE = {"WHAT?!", "Oh no! On my way!", "Seriously?! I'm going!", "Not my house!!"};
    public static final String[] WARN_CHECK = {"Really? I'll go check!", "Hm, I'll take a look.", "Wait what? Going!"};
    public static final String[] WARN_DOUBT = {"Yeah right, nice try.", "I don't believe you, {p}.", "Sure, {p}. Sure."};
    public static final String[] WARN_NOTHING = {"Where? I don't see anything.", "Huh? Looks fine to me."};
    public static final String[] ASK_FOOD = {"Hey {p}, got any food? I'm starving...", "{p}, could you spare something to eat?", "So hungry... {p}, any food?"};
    public static final String[] ASK_CHEST = {"Hey {p}, can I take some {item} from this chest?", "{p}! Mind if I grab some {item} from here?"};
    public static final String[] ASK_OK = {"Thanks! Just a little.", "Yay, thanks {p}!", "You're a lifesaver."};
    public static final String[] ASK_DENIED = {"Aw, okay.", "Fine, I won't touch it.", "Okay okay."};
    public static final String[] TRAPPED = {"Hey! Who put me in here?!", "Uh... how do I get out of here?", "I'm stuck! Hold on...", "Very funny. Digging out now."};
    public static final String[] FIRE = {"FIRE! FIRE!", "Somebody help, it's burning!", "My house!!"};
    public static final String[] NOT_STUCK = {"I'm not stuck!", "Stuck? I'm fine.", "What hole?"};
    public static final String[] SMALLTALK_A = {"Nice day, huh?", "Did you see that creeper earlier?", "I've been thinking about building something big.",
        "How's your house going?", "These players are a bit weird, right?", "I found some coal today!", "Want to go mining later?", "I miss bread.",
        "Do you ever wonder why the sun is square?", "I saw a pig staring at me."};
    public static final String[] SMALLTALK_B = {"Hah, true.", "Totally.", "Mhm.", "No way!", "Ugh, don't remind me.", "Haha, yeah.", "Maybe tomorrow."};

    public static String pick(String[] pool, Human h, String player, String item) {
        String s = pool[R.nextInt(pool.length)].replace("{p}", player == null ? "friend" : player).replace("{item}", item == null ? "that" : item);
        return flavor(s, h);
    }

    /** Light personality flavour so canned lines don't all sound the same. */
    static String flavor(String s, Human h) {
        if (h == null) return s;
        String sp = h.personality.speech();
        double r = R.nextDouble();
        if (sp.contains("grumpy") && r < 0.3) return "Hmph. " + s;
        if (sp.contains("nervous") && r < 0.4 && s.length() > 1 && Character.isLetter(s.charAt(0))) return s.charAt(0) + "-" + s;
        if (sp.contains("dramatic") && r < 0.4) return s.replaceAll("[.!?]*$", "") + "!!";
        if (sp.contains("slang") && r < 0.35) return s.replaceAll("[.!?]*$", "") + (R.nextBoolean() ? " fr" : ", bro");
        if (sp.contains("cheerful") && r < 0.3) return s.replaceAll("[.!?]*$", "") + "! :D";
        return s;
    }
}
