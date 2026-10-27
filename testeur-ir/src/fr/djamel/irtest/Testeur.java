package fr.djamel.irtest;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.ConsumerIrManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Envoie un par un les codes « marche/arrêt » de la télécommande TV universelle du Flipper Zero,
 * pour trouver celui de la TV. Pause → les 6 derniers codes envoyés deviennent des boutons à retester.
 *
 * Pilotable depuis le PC (am start ne demande pas l'injection de touches) :
 *   am start -n fr.djamel.irtest/.Testeur --es cmd envoyer --ei n 42
 *   cmd = envoyer (n) | aller (n : prochain code) | demarrer | pause | vitesse (--ei ms 1500)
 */
public class Testeur extends Activity {

    static final String TAG = "TesteurIR";
    static final int NB_REVERIF = 6;
    static final int[] VITESSES = {1000, 1500, 2500};

    // Couleurs (mode sombre, même esprit que le launcher Accueil)
    static final int FOND = 0xFF000000, CARTE = 0xFF1C1C1E, CARTE2 = 0xFF2C2C2E;
    static final int TEXTE = 0xFFFFFFFF, TEXTE2 = 0xFF8E8E93, BLEU = 0xFF0A84FF, ROUGE = 0xFFFF453A, VERT = 0xFF30D158;

    static class Code {
        int numero, frequence;
        String protocole, description;
        int[] durees;
    }

    final List<Code> codes = new ArrayList<>();
    final List<Integer> historique = new ArrayList<>(); // index (0..n-1) des codes envoyés
    ConsumerIrManager ir;
    Handler ui, emetteur;
    int prochain = 0;      // index du prochain code à envoyer en mode automatique
    int dernier = -1;      // index du dernier code envoyé
    boolean enMarche = false;
    int vitesse = 1;       // index dans VITESSES
    File journal;

    TextView etat, grosNumero, description;
    ProgressBar barre;
    Button boutonMarche, boutonVitesse;
    LinearLayout zoneReverif, ligneReverif;

    final Runnable tic = new Runnable() {
        @Override public void run() {
            if (!enMarche) return;
            if (prochain >= codes.size()) {
                enMarche = false;
                majInterface();
                etat.setText("Terminé : les " + codes.size() + " codes ont été envoyés.");
                return;
            }
            envoyer(prochain);
            prochain++;
            ui.postDelayed(this, VITESSES[vitesse]);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(FOND);
        getWindow().setNavigationBarColor(FOND);
        ui = new Handler(Looper.getMainLooper());
        HandlerThread fil = new HandlerThread("ir");
        fil.start();
        emetteur = new Handler(fil.getLooper());
        ir = (ConsumerIrManager) getSystemService(CONSUMER_IR_SERVICE);
        journal = new File(getExternalFilesDir(null), "journal.txt");
        chargerCodes();
        construireInterface();
        verifierEmetteur();
        majInterface();
        traiter(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        traiter(intent);
    }

    @Override protected void onPause() {
        super.onPause();
        if (enMarche) basculer(); // on ne laisse pas tourner l'envoi écran éteint
    }

    // ---------- Codes ----------

    void chargerCodes() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(getAssets().open("codes.txt"), "UTF-8"))) {
            String ligne;
            while ((ligne = r.readLine()) != null) {
                if (ligne.isEmpty()) continue;
                String[] p = ligne.split("\\|");
                Code c = new Code();
                c.numero = Integer.parseInt(p[0]);
                c.protocole = p[1];
                c.description = p[2];
                c.frequence = Integer.parseInt(p[3]);
                String[] d = p[4].split(",");
                c.durees = new int[d.length];
                for (int i = 0; i < d.length; i++) c.durees[i] = Integer.parseInt(d[i]);
                codes.add(c);
            }
        } catch (Exception e) {
            Log.e(TAG, "Lecture des codes impossible", e);
        }
    }

    void verifierEmetteur() {
        if (ir == null || !ir.hasIrEmitter()) {
            etat.setText("Aucun émetteur infrarouge détecté.");
            etat.setTextColor(ROUGE);
            return;
        }
        StringBuilder s = new StringBuilder("Émetteur IR prêt · " + codes.size() + " codes");
        ConsumerIrManager.CarrierFrequencyRange[] plages = ir.getCarrierFrequencies();
        if (plages != null && plages.length > 0) {
            s.append(" · ").append(plages[0].getMinFrequency() / 1000).append("–")
             .append(plages[plages.length - 1].getMaxFrequency() / 1000).append(" kHz");
        }
        etat.setText(s);
        Log.i(TAG, s.toString());
    }

    void envoyer(final int index) {
        if (index < 0 || index >= codes.size()) return;
        final Code c = codes.get(index);
        dernier = index;
        // Un code déjà dans la liste garde sa place : les boutons ne bougent pas pendant la revérification
        if (!historique.contains(index)) {
            historique.add(index);
            while (historique.size() > NB_REVERIF) historique.remove(0);
        }
        noter("ENVOI #" + c.numero + " " + c.description + " (" + c.frequence + " Hz)");
        majInterface();
        emetteur.post(new Runnable() {
            @Override public void run() {
                try {
                    ir.transmit(c.frequence, c.durees);
                } catch (final Exception e) {
                    noter("ERREUR #" + c.numero + " : " + e);
                    ui.post(new Runnable() {
                        @Override public void run() {
                            etat.setText("Erreur sur #" + c.numero + " : " + e.getMessage());
                            etat.setTextColor(ROUGE);
                        }
                    });
                }
            }
        });
    }

    void noter(String message) {
        String horodate = new SimpleDateFormat("HH:mm:ss.SSS", Locale.FRANCE).format(new Date());
        Log.i(TAG, message);
        try (FileWriter w = new FileWriter(journal, true)) {
            w.write(horodate + " " + message + "\n");
        } catch (Exception ignore) {
        }
    }

    // ---------- Commandes ----------

    void basculer() {
        enMarche = !enMarche;
        ui.removeCallbacks(tic);
        if (enMarche) {
            if (prochain >= codes.size()) prochain = 0;
            noter("DEMARRAGE au #" + (prochain + 1));
            ui.post(tic);
        } else {
            noter("PAUSE (dernier envoyé : #" + (dernier + 1) + ")");
        }
        majInterface();
    }

    void traiter(Intent intent) {
        if (intent == null || intent.getStringExtra("cmd") == null) return;
        String cmd = intent.getStringExtra("cmd");
        int n = intent.getIntExtra("n", 0);
        noter("COMMANDE PC : " + cmd + (n > 0 ? " " + n : ""));
        switch (cmd) {
            case "envoyer": if (enMarche) basculer(); envoyer(n - 1); break;
            case "aller": prochain = Math.max(0, Math.min(codes.size() - 1, n - 1)); majInterface(); break;
            case "demarrer": if (!enMarche) basculer(); break;
            case "pause": if (enMarche) basculer(); break;
            case "vitesse":
                int ms = intent.getIntExtra("ms", 1500);
                for (int i = 0; i < VITESSES.length; i++) if (VITESSES[i] == ms) vitesse = i;
                majInterface();
                break;
        }
    }

    // ---------- Interface ----------

    int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    GradientDrawable fondArrondi(int couleur, float rayon) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(couleur);
        g.setCornerRadius(dp(rayon));
        return g;
    }

    TextView texte(String s, float taille, int couleur, boolean gras) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, taille);
        t.setTextColor(couleur);
        t.setGravity(Gravity.CENTER);
        if (gras) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    Button bouton(String s, int fond, float taille, View.OnClickListener action) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, taille);
        b.setTextColor(TEXTE);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setBackground(fondArrondi(fond, 14));
        b.setStateListAnimator(null);
        b.setOnClickListener(action);
        return b;
    }

    LinearLayout.LayoutParams lp(int largeur, int hauteur, float poids, int marge) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(largeur, hauteur, poids);
        p.setMargins(dp(marge), dp(marge), dp(marge), dp(marge));
        return p;
    }

    void construireInterface() {
        LinearLayout racine = new LinearLayout(this);
        racine.setOrientation(LinearLayout.VERTICAL);
        racine.setPadding(dp(20), dp(24), dp(20), dp(24));
        racine.setBackgroundColor(FOND);

        TextView titre = texte("Testeur IR", 28, TEXTE, true);
        racine.addView(titre);
        TextView sousTitre = texte("Vise la TV. Dès qu'elle réagit : Pause.", 16, TEXTE2, false);
        racine.addView(sousTitre, lp(-1, -2, 0, 4));
        etat = texte("", 13, TEXTE2, false);
        racine.addView(etat, lp(-1, -2, 0, 4));

        LinearLayout carte = new LinearLayout(this);
        carte.setOrientation(LinearLayout.VERTICAL);
        carte.setBackground(fondArrondi(CARTE, 20));
        carte.setPadding(dp(16), dp(20), dp(16), dp(20));
        grosNumero = texte("Prêt", 110, TEXTE, true);
        grosNumero.setIncludeFontPadding(false);
        carte.addView(grosNumero);
        description = texte("", 15, TEXTE2, false);
        carte.addView(description, lp(-1, -2, 0, 6));
        barre = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        barre.setMax(Math.max(1, codes.size()));
        barre.setProgressTintList(android.content.res.ColorStateList.valueOf(BLEU));
        carte.addView(barre, lp(-1, dp(8), 0, 8));
        racine.addView(carte, lp(-1, -2, 0, 8));

        boutonMarche = bouton("Démarrer", BLEU, 30, new View.OnClickListener() {
            @Override public void onClick(View v) { basculer(); }
        });
        racine.addView(boutonMarche, lp(-1, dp(120), 0, 8));

        LinearLayout pas = new LinearLayout(this);
        pas.addView(bouton("◀  −1", CARTE2, 18, new View.OnClickListener() {
            @Override public void onClick(View v) { if (enMarche) basculer(); envoyer(Math.max(0, dernier - 1)); prochain = dernier + 1; }
        }), lp(0, dp(64), 1, 4));
        pas.addView(bouton("Renvoyer", CARTE2, 18, new View.OnClickListener() {
            @Override public void onClick(View v) { if (enMarche) basculer(); if (dernier >= 0) envoyer(dernier); }
        }), lp(0, dp(64), 1, 4));
        pas.addView(bouton("+1  ▶", CARTE2, 18, new View.OnClickListener() {
            @Override public void onClick(View v) { if (enMarche) basculer(); envoyer(Math.min(codes.size() - 1, dernier + 1)); prochain = dernier + 1; }
        }), lp(0, dp(64), 1, 4));
        racine.addView(pas, lp(-1, -2, 0, 4));

        zoneReverif = new LinearLayout(this);
        zoneReverif.setOrientation(LinearLayout.VERTICAL);
        zoneReverif.setBackground(fondArrondi(CARTE, 20));
        zoneReverif.setPadding(dp(12), dp(14), dp(12), dp(14));
        zoneReverif.addView(texte("Revérifier : touche chaque numéro, un par un,\net regarde si la TV réagit", 15, TEXTE, false));
        ligneReverif = new LinearLayout(this);
        zoneReverif.addView(ligneReverif, lp(-1, -2, 0, 6));
        racine.addView(zoneReverif, lp(-1, -2, 0, 8));

        LinearLayout bas = new LinearLayout(this);
        boutonVitesse = bouton("", CARTE2, 15, new View.OnClickListener() {
            @Override public void onClick(View v) { vitesse = (vitesse + 1) % VITESSES.length; majInterface(); }
        });
        bas.addView(boutonVitesse, lp(0, dp(52), 1, 4));
        bas.addView(bouton("Reprendre au début", CARTE2, 15, new View.OnClickListener() {
            @Override public void onClick(View v) { if (enMarche) basculer(); prochain = 0; historique.clear(); dernier = -1; majInterface(); }
        }), lp(0, dp(52), 1, 4));
        racine.addView(bas, lp(-1, -2, 0, 4));

        ScrollView defilement = new ScrollView(this);
        defilement.setFillViewport(true);
        defilement.setBackgroundColor(FOND);
        defilement.addView(racine);
        setContentView(defilement);
    }

    void majInterface() {
        if (dernier >= 0) {
            Code c = codes.get(dernier);
            grosNumero.setText(String.valueOf(c.numero));
            description.setText(c.description + " · " + (dernier + 1) + " / " + codes.size());
        } else {
            grosNumero.setText("Prêt");
            description.setText(codes.size() + " codes à essayer · départ au n° " + (prochain + 1));
        }
        barre.setProgress(dernier + 1);
        boutonMarche.setText(enMarche ? "Pause" : (dernier < 0 ? "Démarrer" : "Reprendre"));
        boutonMarche.setBackground(fondArrondi(enMarche ? ROUGE : BLEU, 18));
        String v = String.format(Locale.FRANCE, "%.1f", VITESSES[vitesse] / 1000f);
        boutonVitesse.setText("Un code toutes les " + v + " s");

        zoneReverif.setVisibility(!enMarche && !historique.isEmpty() ? View.VISIBLE : View.GONE);
        ligneReverif.removeAllViews();
        for (final int index : historique) {
            final int numero = codes.get(index).numero;
            Button b = bouton(String.valueOf(numero), index == dernier ? VERT : CARTE2, 18, new View.OnClickListener() {
                @Override public void onClick(View v) { envoyer(index); }
            });
            ligneReverif.addView(b, lp(0, dp(64), 1, 3));
        }
    }
}
