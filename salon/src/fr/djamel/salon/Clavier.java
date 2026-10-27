package fr.djamel.salon;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.Normalizer;

/**
 * Traduit du texte et des touches en événements d'entrée Linux (struct input_event 32 bits, 16 octets)
 * pour le clavier virtuel de la Fire TV. Disposition QWERTY (Generic.kl d'Android).
 */
final class Clavier {
    private Clavier() {
    }

    // Codes de touches Linux (linux/input-event-codes.h)
    static final int BACKSPACE = 14, ENTER = 28, SHIFT = 42, SPACE = 57;
    static final int UP = 103, LEFT = 105, RIGHT = 106, DOWN = 108;
    static final int PLAYPAUSE = 164, REWIND = 168, HOMEPAGE = 172, FASTFORWARD = 208, SEARCH = 217;

    private static final String LETTRES = "abcdefghijklmnopqrstuvwxyz";
    private static final int[] CODES_LETTRES = {30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50, 49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45, 21, 44};
    private static final String CHIFFRES = "1234567890";
    // caractère -> code, et le même avec Maj
    private static final String SIMPLES = " -='`;,./\\[]";
    private static final int[] CODES_SIMPLES = {57, 12, 13, 40, 41, 39, 51, 52, 53, 43, 26, 27};
    private static final String MAJ = "!@#$%^&*()_+\":~<>?|{}";
    private static final int[] CODES_MAJ = {2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 40, 39, 41, 51, 52, 53, 43, 26, 27};

    static byte[] touche(int code) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        appui(o, code, false);
        return o.toByteArray();
    }

    static byte[] touches(int code, int fois) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (int i = 0; i < fois; i++) appui(o, code, false);
        return o.toByteArray();
    }

    /** Les accents sont retirés (é → e) : les moteurs de recherche des applis TV n'en tiennent pas compte. */
    static byte[] texte(String s) {
        String t = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (char c : t.toCharArray()) {
            int i;
            if ((i = LETTRES.indexOf(c)) >= 0) appui(o, CODES_LETTRES[i], false);
            else if ((i = LETTRES.indexOf(Character.toLowerCase(c))) >= 0) appui(o, CODES_LETTRES[i], true);
            else if ((i = CHIFFRES.indexOf(c)) >= 0) appui(o, 2 + i, false);
            else if ((i = SIMPLES.indexOf(c)) >= 0) appui(o, CODES_SIMPLES[i], false);
            else if ((i = MAJ.indexOf(c)) >= 0) appui(o, CODES_MAJ[i], true);
            // autre caractère (emoji…) : ignoré
        }
        return o.toByteArray();
    }

    /** Nombre de caractères que texte() tape vraiment (pour savoir combien en effacer ensuite). */
    static int longueurTapee(String s) {
        String t = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        int n = 0;
        for (char c : t.toCharArray()) {
            if (LETTRES.indexOf(Character.toLowerCase(c)) >= 0 || CHIFFRES.indexOf(c) >= 0
                    || SIMPLES.indexOf(c) >= 0 || MAJ.indexOf(c) >= 0) n++;
        }
        return n;
    }

    private static void appui(ByteArrayOutputStream o, int code, boolean maj) {
        if (maj) evenement(o, 1, SHIFT, 1);
        evenement(o, 1, code, 1);
        evenement(o, 0, 0, 0);
        evenement(o, 1, code, 0);
        if (maj) evenement(o, 1, SHIFT, 0);
        evenement(o, 0, 0, 0);
    }

    private static void evenement(ByteArrayOutputStream o, int type, int code, int valeur) {
        ByteBuffer b = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0).putInt(0).putShort((short) type).putShort((short) code).putInt(valeur);
        o.write(b.array(), 0, 16);
    }
}
