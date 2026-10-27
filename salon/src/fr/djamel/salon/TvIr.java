package fr.djamel.salon;

import android.content.Context;
import android.hardware.ConsumerIrManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

/**
 * Télécommande infrarouge de la TV Polaroid TVLED434K01 (= High One).
 * Protocole NEC étendu, adresse 0x7201 (octets 01 72), commande 8 bits suivie de son inverse.
 * Codes trouvés avec le Testeur IR (n° 242) ; touches reprises de Flipper-IRDB TVs/Bauhn/Bauhn_ATV_50FHD4.ir.
 */
final class TvIr {
    static final String TAG = "Salon";

    // Commandes (octet bas) de la télécommande d'origine
    static final int POWER = 0x1E, SOURCE = 0x07, MUTE = 0x5C, VOL_UP = 0x0A, VOL_DN = 0x06;
    static final int CH_UP = 0x48, CH_DN = 0x44, MENU = 0x1C, EXIT = 0x18, OK = 0x5F;
    static final int UP = 0x03, DOWN = 0x5E, LEFT = 0x02, RIGHT = 0x40;

    private static final int ADRESSE = 0x7201;
    private static final int FREQUENCE = 38000;

    private final ConsumerIrManager ir;
    private final Handler fil;

    TvIr(Context c) {
        ir = (ConsumerIrManager) c.getSystemService(Context.CONSUMER_IR_SERVICE);
        HandlerThread t = new HandlerThread("ir");
        t.start();
        fil = new Handler(t.getLooper());
    }

    boolean disponible() {
        return ir != null && ir.hasIrEmitter();
    }

    /** Envoie une touche (dans l'ordre d'appel, sans bloquer l'interface). */
    void envoyer(final int commande) {
        if (!disponible()) return;
        final int[] trame = trame(commande);
        fil.post(new Runnable() {
            @Override public void run() {
                try {
                    ir.transmit(FREQUENCE, trame);
                    Log.i(TAG, String.format("IR 0x%02X", commande));
                } catch (Exception e) {
                    Log.e(TAG, "IR impossible", e);
                }
            }
        });
    }

    /** NEC étendu : préambule 9 ms / 4,5 ms, 32 bits poids faible d'abord, puis impulsion de fin. */
    static int[] trame(int commande) {
        long valeur = (ADRESSE & 0xFFFFL)
                | ((long) (commande & 0xFF) << 16)
                | ((long) (~commande & 0xFF) << 24);
        int[] p = new int[2 + 64 + 1];
        int i = 0;
        p[i++] = 9000;
        p[i++] = 4500;
        for (int b = 0; b < 32; b++) {
            p[i++] = 560;
            p[i++] = ((valeur >> b) & 1) == 1 ? 1690 : 560;
        }
        p[i] = 560;
        return p;
    }
}
