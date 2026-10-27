package fr.djamel.salon;

import android.animation.TimeInterpolator;
import android.graphics.Typeface;
import android.view.animation.PathInterpolator;

/**
 * Jetons de design partagés, repris du launcher « Accueil » (note « Design style Apple », mode sombre).
 * Pour changer un réglage visuel, c'est ici.
 */
final class Ui {
    private Ui() {
    }

    // ---------- Couleurs ----------
    static final int BG = 0xff000000;
    static final int CARD = 0xff1c1c1e;       // surfaces
    static final int CARD2 = 0xff2c2c2e;      // boutons posés sur une surface
    static final int PRESSED = 0xff3a3a3c;
    static final int TEXT = 0xfff5f5f7;
    static final int TEXT2 = 0xffa1a1a6;
    static final int TEXT3 = 0xff8e8e93;
    static final int ACCENT = 0xff0a84ff;     // bleu iOS mode sombre
    static final int RED = 0xffff453a;        // marche/arrêt de la TV
    static final int GREEN = 0xff30d158;
    static final int HAIRLINE = 0x1fffffff;

    // ---------- Rayons concentriques ----------
    static final int R_CARD = 28, R_INNER = 18, R_CHIP = 12;

    // ---------- Espacements (grille de 4) ----------
    static final int S1 = 4, S2 = 8, S3 = 12, S4 = 16, S5 = 20, S6 = 24, S8 = 32;

    // ---------- Mouvement ----------
    static final TimeInterpolator EASE = new PathInterpolator(0.32f, 0.72f, 0f, 1f);
    static final int PRESS = 100, FAST = 150;

    static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
}
