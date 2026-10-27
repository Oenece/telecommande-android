package fr.djamel.salon;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * Molette façon télécommande Siri (Apple TV) :
 * - anneau : toucher en haut / bas / gauche / droite = flèche (doigt maintenu = répétition) ;
 *   faire tourner le doigt sur l'anneau = avancer (sens horaire) ou reculer (sens inverse) dans la vidéo ;
 * - centre : toucher = OK, glisser = flèches (comme un pavé tactile), appui long = Menu.
 */
final class Molette extends View {
    interface Ecouteur {
        void fleche(String action); // dpad_up, dpad_down, dpad_left, dpad_right
        void ok();
        void menu();
        void defiler(int sens);     // +1 avancer, -1 reculer
    }

    Ecouteur ecouteur;

    /** Mode TV : la molette pilote le menu de la TV (infrarouge) ; anneau teinté de rouge et « TV » au centre. */
    private boolean modeTv;

    void modeTv(boolean actif) {
        if (modeTv != actif) {
            modeTv = actif;
            invalidate();
        }
    }

    private static final float DEGRES_PAR_CRAN = 30f; // 12 crans par tour
    private static final float SEUIL_ROTATION = 20f;
    private static final String[] DIRECTIONS = {"dpad_right", "dpad_down", "dpad_left", "dpad_up"};

    private final float d;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trait = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint texte = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path chevron = new Path();
    private final RectF r = new RectF();

    // géométrie (px)
    private float cx, cy, rExt, rCentre;

    // état du geste
    private enum Mode { AUCUN, ANNEAU, ROTATION, CENTRE }
    private Mode mode = Mode.AUCUN;
    private int direction = -1;           // 0 droite, 1 bas, 2 gauche, 3 haut
    private boolean repetee, centreAppuye, longFait, aGlisse;
    private float angleDepart, angleDernier, cumul, angleDoigt;
    private int crans;                    // crans du geste en cours (affichés au centre)
    private float x0, y0, xPas, yPas;
    private long tDebut;
    private float eclat;                  // 0..1 : traînée lumineuse de la rotation

    private final Runnable repetition = new Runnable() {
        @Override public void run() {
            if (direction < 0 || ecouteur == null) return;
            repetee = true;
            ecouteur.fleche(DIRECTIONS[direction]);
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            postDelayed(this, 140);
        }
    };

    private final Runnable appuiLong = new Runnable() {
        @Override public void run() {
            if (mode == Mode.CENTRE && !aGlisse) {
                longFait = true;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                if (ecouteur != null) ecouteur.menu();
            }
        }
    };

    private final Runnable eteindre = new Runnable() {
        @Override public void run() {
            eclat = Math.max(0, eclat - 0.08f);
            if (eclat > 0) postDelayed(this, 16);
            else crans = 0;
            invalidate();
        }
    };

    Molette(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        trait.setStyle(Paint.Style.STROKE);
        trait.setStrokeCap(Paint.Cap.ROUND);
        trait.setStrokeJoin(Paint.Join.ROUND);
        texte.setTextAlign(Paint.Align.CENTER);
        texte.setTypeface(Ui.MEDIUM);
        setHapticFeedbackEnabled(true);
        setContentDescription("Molette : flèches sur l'anneau, OK au centre, tourner pour avancer ou reculer dans la vidéo");
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        float s = Math.min(w, h);
        cx = w / 2f;
        cy = h / 2f;
        rExt = s / 2f - 6 * d;
        rCentre = rExt * 0.40f;
    }

    // ---------- Dessin ----------

    @Override protected void onDraw(Canvas c) {
        // 1. Halo très léger derrière la molette : la pose sur la page
        p.setShader(new RadialGradient(cx, cy, rExt * 1.25f, new int[]{0x14ffffff, 0x00ffffff}, null, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, rExt * 1.25f, p);

        // 2. Anneau : dégradé vertical, plus clair en haut
        p.setShader(new LinearGradient(0, cy - rExt, 0, cy + rExt, 0xff3a3a3c, 0xff1c1c1e, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, rExt, p);
        p.setShader(null);

        // Arête haute éclairée
        trait.setStrokeWidth(1.2f * d);
        trait.setShader(new LinearGradient(0, cy - rExt, 0, cy + rExt, new int[]{0x38ffffff, 0x00ffffff, 0x00ffffff},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, rExt - 0.6f * d, trait);
        trait.setShader(null);

        // Mode TV : liseré rouge autour de l'anneau
        if (modeTv) {
            trait.setColor(0xccff453a);
            trait.setStrokeWidth(2.5f * d);
            c.drawCircle(cx, cy, rExt - 2 * d, trait);
        }

        // 3. Quart touché : léger éclaircissement
        if (mode == Mode.ANNEAU && direction >= 0) {
            p.setColor(0x14ffffff);
            r.set(cx - rExt, cy - rExt, cx + rExt, cy + rExt);
            c.drawArc(r, direction * 90 - 45, 90, true, p);
        }

        // 4. Graduations (60) ; pendant la rotation, une traînée bleue suit le doigt
        for (int i = 0; i < 60; i++) {
            float a = (float) Math.toRadians(i * 6);
            float ecart = Math.abs(angleEcart(i * 6, angleDoigt));
            int couleur = 0x12ffffff;
            if (eclat > 0 && ecart < 40) {
                float force = (1 - ecart / 40f) * eclat;
                couleur = Relief.melanger(0x12ffffff, Ui.ACCENT, force) | 0xff000000;
                couleur = (couleur & 0x00ffffff) | ((int) (0x30 + 0xcf * force) << 24);
            }
            trait.setColor(couleur);
            trait.setStrokeWidth((i % 5 == 0 ? 2f : 1.2f) * d);
            float r1 = rExt - 7 * d, r2 = rExt - (i % 5 == 0 ? 14 : 11) * d;
            c.drawLine(cx + r1 * (float) Math.cos(a), cy + r1 * (float) Math.sin(a),
                    cx + r2 * (float) Math.cos(a), cy + r2 * (float) Math.sin(a), trait);
        }

        // 5. Chevrons des quatre directions
        float rm = (rExt + rCentre) / 2f;
        for (int i = 0; i < 4; i++) {
            boolean actif = mode == Mode.ANNEAU && direction == i;
            dessinerChevron(c, i, rm, actif ? Ui.TEXT : Ui.TEXT2);
        }

        // 6. Rainure entre l'anneau et le centre
        trait.setColor(0x99000000);
        trait.setStrokeWidth(3 * d);
        c.drawCircle(cx, cy, rCentre + 5 * d, trait);

        // 7. Bouton central bombé
        float rc = rCentre * (centreAppuye ? 0.97f : 1f);
        int h = centreAppuye ? 0xff242426 : 0xff38383a, b = centreAppuye ? 0xff141416 : 0xff1e1e20;
        p.setShader(new LinearGradient(0, cy - rc, 0, cy + rc, h, b, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, rc, p);
        p.setShader(null);
        trait.setStrokeWidth(1f * d);
        trait.setShader(new LinearGradient(0, cy - rc, 0, cy + rc, new int[]{centreAppuye ? 0x0cffffff : 0x30ffffff, 0x00ffffff, 0x0affffff},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, rc - 0.5f * d, trait);
        trait.setShader(null);

        // 8. Texte du centre : « OK », ou le nombre de crans pendant une rotation
        if (crans != 0) {
            texte.setColor(Ui.TEXT);
            texte.setTextSize(30 * d);
            c.drawText((crans > 0 ? "»  +" : "«  −") + Math.abs(crans), cx, cy + 10 * d, texte);
        } else if (modeTv) {
            texte.setColor(Ui.RED);
            texte.setTextSize(12 * d);
            c.drawText("MENU DE LA TV", cx, cy - 14 * d, texte);
            texte.setColor(Ui.TEXT);
            texte.setTextSize(17 * d);
            c.drawText("OK", cx, cy + 14 * d, texte);
        } else {
            texte.setColor(Ui.TEXT3);
            texte.setTextSize(17 * d);
            c.drawText("OK", cx, cy + 6 * d, texte);
        }
    }

    private void dessinerChevron(Canvas c, int dir, float rayon, int couleur) {
        float a = (float) Math.toRadians(dir * 90);
        float x = cx + rayon * (float) Math.cos(a), y = cy + rayon * (float) Math.sin(a);
        float t = 9 * d;
        chevron.reset();
        // chevron pointant vers l'extérieur
        float ux = (float) Math.cos(a), uy = (float) Math.sin(a), vx = -uy, vy = ux;
        chevron.moveTo(x - ux * t * 0.5f + vx * t, y - uy * t * 0.5f + vy * t);
        chevron.lineTo(x + ux * t * 0.5f, y + uy * t * 0.5f);
        chevron.lineTo(x - ux * t * 0.5f - vx * t, y - uy * t * 0.5f - vy * t);
        trait.setColor(couleur);
        trait.setStrokeWidth(2.6f * d);
        c.drawPath(chevron, trait);
    }

    // ---------- Gestes ----------

    private float angle(float x, float y) {
        float a = (float) Math.toDegrees(Math.atan2(y - cy, x - cx));
        return a < 0 ? a + 360 : a;
    }

    /** Écart signé b - a ramené entre -180 et 180. */
    private static float angleEcart(float b, float a) {
        float e = (b - a) % 360;
        if (e > 180) e -= 360;
        if (e < -180) e += 360;
        return e;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float dist = (float) Math.hypot(x - cx, y - cy);
                if (dist > rExt + 24 * d) return false; // hors de la molette
                x0 = xPas = x;
                y0 = yPas = y;
                tDebut = e.getEventTime();
                repetee = longFait = aGlisse = false;
                crans = 0;
                if (dist <= rCentre + 6 * d) {
                    mode = Mode.CENTRE;
                    centreAppuye = true;
                    postDelayed(appuiLong, 550);
                } else {
                    mode = Mode.ANNEAU;
                    angleDepart = angleDernier = angleDoigt = angle(x, y);
                    cumul = 0;
                    direction = Math.round(angleDepart / 90f) % 4;
                    postDelayed(repetition, 450);
                }
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (mode == Mode.ANNEAU || mode == Mode.ROTATION) {
                    float a = angle(x, y);
                    float delta = angleEcart(a, angleDernier);
                    angleDernier = a;
                    angleDoigt = a;
                    cumul += delta;
                    if (mode == Mode.ANNEAU && Math.abs(angleEcart(a, angleDepart)) > SEUIL_ROTATION) {
                        mode = Mode.ROTATION; // c'est une rotation, pas un appui sur une flèche
                        removeCallbacks(repetition);
                        cumul = angleEcart(a, angleDepart);
                        direction = -1;
                    }
                    if (mode == Mode.ROTATION) {
                        while (Math.abs(cumul) >= DEGRES_PAR_CRAN) {
                            int sens = cumul > 0 ? 1 : -1; // angle croissant = sens horaire à l'écran
                            cumul -= sens * DEGRES_PAR_CRAN;
                            crans += sens;
                            if (ecouteur != null) ecouteur.defiler(sens);
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        }
                        eclat = 1;
                    }
                    invalidate();
                } else if (mode == Mode.CENTRE) {
                    if (Math.hypot(x - x0, y - y0) > 10 * d) {
                        aGlisse = true;
                        removeCallbacks(appuiLong);
                    }
                    float dx = x - xPas, dy = y - yPas, pas = 40 * d;
                    if (Math.abs(dx) >= pas || Math.abs(dy) >= pas) {
                        String f = Math.abs(dx) > Math.abs(dy) ? (dx > 0 ? "dpad_right" : "dpad_left") : (dy > 0 ? "dpad_down" : "dpad_up");
                        if (ecouteur != null) ecouteur.fleche(f);
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        xPas = x;
                        yPas = y;
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
                removeCallbacks(repetition);
                removeCallbacks(appuiLong);
                if (mode == Mode.ANNEAU && !repetee && direction >= 0 && ecouteur != null) {
                    ecouteur.fleche(DIRECTIONS[direction]);
                } else if (mode == Mode.CENTRE && !aGlisse && !longFait && e.getEventTime() - tDebut < 500 && ecouteur != null) {
                    ecouteur.ok();
                }
                finir();
                return true;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(repetition);
                removeCallbacks(appuiLong);
                finir();
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void finir() {
        boolean rotation = mode == Mode.ROTATION;
        mode = Mode.AUCUN;
        direction = -1;
        centreAppuye = false;
        if (rotation) postDelayed(eteindre, 400); // le compteur reste lisible un instant
        else crans = 0;
        invalidate();
    }
}
