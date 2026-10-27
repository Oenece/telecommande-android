package fr.djamel.salon;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Pictogrammes au trait, dessinés en code (même épaisseur partout, façon SF Symbols). */
final class Icone extends Drawable {
    enum Forme { MARCHE, SOURCE, MUET, PLUS, MOINS, RETOUR, ACCUEIL, MENU, LECTURE, MICRO, ENVOYER, REGLAGES, AVANT, ARRIERE, LOUPE }

    private final Forme forme;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path chemin = new Path();
    private final RectF r = new RectF();
    private final int taille;

    Icone(Forme forme, int taillePx, int couleur) {
        this.forme = forme;
        this.taille = taillePx;
        p.setColor(couleur);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override public int getIntrinsicWidth() { return taille; }
    @Override public int getIntrinsicHeight() { return taille; }

    @Override public void draw(Canvas c) {
        float s = Math.min(getBounds().width(), getBounds().height());
        float x0 = getBounds().centerX() - s / 2, y0 = getBounds().centerY() - s / 2;
        c.save();
        c.translate(x0, y0);
        c.scale(s / 24f, s / 24f);
        p.setStrokeWidth(2f);
        chemin.reset();
        switch (forme) {
            case MARCHE:
                r.set(5, 5, 19, 19);
                c.drawArc(r, -50, 280, false, p);
                c.drawLine(12, 3.5f, 12, 11, p);
                break;
            case SOURCE: // une flèche qui entre dans un écran
                r.set(3, 5, 21, 17);
                c.drawRoundRect(r, 2, 2, p);
                c.drawLine(9, 20, 15, 20, p);
                chemin.moveTo(8, 11); chemin.lineTo(15, 11);
                chemin.moveTo(12.5f, 8.5f); chemin.lineTo(15, 11); chemin.lineTo(12.5f, 13.5f);
                c.drawPath(chemin, p);
                break;
            case MUET:
                chemin.moveTo(4, 9.5f); chemin.lineTo(7.5f, 9.5f); chemin.lineTo(12, 5.5f);
                chemin.lineTo(12, 18.5f); chemin.lineTo(7.5f, 14.5f); chemin.lineTo(4, 14.5f); chemin.close();
                c.drawPath(chemin, p);
                c.drawLine(15.5f, 9.5f, 20.5f, 14.5f, p);
                c.drawLine(20.5f, 9.5f, 15.5f, 14.5f, p);
                break;
            case PLUS:
                c.drawLine(12, 6, 12, 18, p);
                c.drawLine(6, 12, 18, 12, p);
                break;
            case MOINS:
                c.drawLine(6, 12, 18, 12, p);
                break;
            case RETOUR:
                chemin.moveTo(14.5f, 5.5f); chemin.lineTo(8, 12); chemin.lineTo(14.5f, 18.5f);
                c.drawPath(chemin, p);
                break;
            case ACCUEIL:
                chemin.moveTo(4.5f, 11); chemin.lineTo(12, 4.5f); chemin.lineTo(19.5f, 11);
                chemin.moveTo(6.5f, 9.5f); chemin.lineTo(6.5f, 19); chemin.lineTo(17.5f, 19); chemin.lineTo(17.5f, 9.5f);
                c.drawPath(chemin, p);
                break;
            case MENU:
                c.drawLine(5, 7, 19, 7, p);
                c.drawLine(5, 12, 19, 12, p);
                c.drawLine(5, 17, 19, 17, p);
                break;
            case LECTURE: // ▶ ❚❚
                chemin.moveTo(4, 6.5f); chemin.lineTo(11, 12); chemin.lineTo(4, 17.5f); chemin.close();
                c.drawPath(chemin, p);
                c.drawLine(15, 7, 15, 17, p);
                c.drawLine(19.5f, 7, 19.5f, 17, p);
                break;
            case MICRO:
                r.set(9, 3.5f, 15, 14.5f);
                c.drawRoundRect(r, 3, 3, p);
                r.set(6, 7.5f, 18, 17.5f);
                c.drawArc(r, 20, 140, false, p);
                c.drawLine(12, 17.5f, 12, 20.5f, p);
                break;
            case ENVOYER:
                chemin.moveTo(12, 19); chemin.lineTo(12, 5.5f);
                chemin.moveTo(6.5f, 11); chemin.lineTo(12, 5.5f); chemin.lineTo(17.5f, 11);
                c.drawPath(chemin, p);
                break;
            case REGLAGES: // trois points
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(6, 12, 1.6f, p);
                c.drawCircle(12, 12, 1.6f, p);
                c.drawCircle(18, 12, 1.6f, p);
                p.setStyle(Paint.Style.STROKE);
                break;
            case LOUPE:
                c.drawCircle(10.5f, 10.5f, 6, p);
                c.drawLine(15, 15, 20, 20, p);
                break;
            case AVANT:
                chemin.moveTo(5, 6.5f); chemin.lineTo(11, 12); chemin.lineTo(5, 17.5f);
                chemin.moveTo(12, 6.5f); chemin.lineTo(18, 12); chemin.lineTo(12, 17.5f);
                c.drawPath(chemin, p);
                break;
            case ARRIERE:
                chemin.moveTo(19, 6.5f); chemin.lineTo(13, 12); chemin.lineTo(19, 17.5f);
                chemin.moveTo(12, 6.5f); chemin.lineTo(6, 12); chemin.lineTo(12, 17.5f);
                c.drawPath(chemin, p);
                break;
        }
        c.restore();
    }

    @Override public void setAlpha(int a) { p.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter f) { p.setColorFilter(f); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
