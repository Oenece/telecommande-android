package fr.djamel.salon;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Surface en relief pour le mode sombre, sans ombre portée (invisible sur du noir) :
 * dégradé vertical plus clair en haut, arête haute éclairée, arête basse dans l'ombre.
 * - BOMBE : touche qui dépasse (boutons). Appuyée, elle s'aplatit et s'assombrit.
 * - CREUX : surface enfoncée (champ de texte) : plus sombre, ombre en haut, reflet en bas.
 * Forme : rectangle arrondi (rayon en px) ou rond si rayon < 0.
 */
final class Relief extends Drawable {
    enum Type { BOMBE, CREUX }

    private final Type type;
    private final float rayon;
    private int haut, bas; // couleurs du dégradé de fond
    private boolean appuye;
    private final Paint fond = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arete = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float trait;

    Relief(Type type, int couleur, float rayon, float densite) {
        this.type = type;
        this.rayon = rayon;
        this.trait = Math.max(1f, densite * 0.75f);
        couleur(couleur);
        arete.setStyle(Paint.Style.STROKE);
        arete.setStrokeWidth(trait);
    }

    /** Couleur de base : le haut est éclairci, le bas assombri (en relief) ou l'inverse (en creux). */
    void couleur(int c) {
        haut = type == Type.BOMBE ? melanger(c, 0xffffffff, 0.07f) : melanger(c, 0xff000000, 0.35f);
        bas = type == Type.BOMBE ? melanger(c, 0xff000000, 0.12f) : melanger(c, 0xffffffff, 0.02f);
        invalidateSelf();
    }

    void appuye(boolean a) {
        if (appuye != a) {
            appuye = a;
            invalidateSelf();
        }
    }

    @Override public void draw(Canvas c) {
        r.set(getBounds());
        r.inset(trait / 2, trait / 2);
        float rr = rayon < 0 ? Math.min(r.width(), r.height()) / 2 : rayon;
        int h = appuye ? melanger(haut, 0xff000000, 0.18f) : haut;
        int b = appuye ? melanger(bas, 0xff000000, 0.10f) : bas;
        fond.setShader(new LinearGradient(0, r.top, 0, r.bottom, h, b, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rr, rr, fond);
        // Arête : claire en haut (lumière venant d'au-dessus), sombre en bas — ou l'inverse en creux
        int clair = appuye ? 0x0affffff : 0x24ffffff, sombre = 0x66000000;
        int debut = type == Type.BOMBE ? clair : sombre, fin = type == Type.BOMBE ? 0x00ffffff : 0x10ffffff;
        arete.setShader(new LinearGradient(0, r.top, 0, r.bottom, new int[]{debut, 0x00ffffff, fin},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rr, rr, arete);
    }

    static int melanger(int a, int b, float t) {
        int ar = (a >> 16) & 0xff, ag = (a >> 8) & 0xff, ab = a & 0xff, aa = (a >>> 24);
        int br = (b >> 16) & 0xff, bg = (b >> 8) & 0xff, bb = b & 0xff;
        return (aa << 24) | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    @Override public void setAlpha(int a) { fond.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter f) { fond.setColorFilter(f); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
