"""Convertit la télécommande TV universelle du Flipper Zero (donnees/flipper_tv.ir)
en impulsions infrarouges pour ConsumerIrManager.

Sortie : assets/codes.txt, une ligne par code « Power » :
    numero|protocole|description|frequence|duree1,duree2,...
(durées en microsecondes, en alternance allumé / éteint, en commençant par allumé)

et donnees/telecommandes.json : pour chaque numéro, toutes les touches de la même
télécommande (volume, source…), pour la suite.

Usage : python -I outils/convertir_codes.py
"""
import json
import os
import sys

ICI = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE = os.path.join(ICI, "donnees", "flipper_tv.ir")
SORTIE = os.path.join(ICI, "assets", "codes.txt")
SORTIE_JSON = os.path.join(ICI, "donnees", "telecommandes.json")


def octets(champ):
    """« 08 00 00 00 » -> 0x00000008 (petit-boutiste, comme le Flipper)."""
    b = [int(x, 16) for x in champ.split()]
    return sum(v << (8 * i) for i, v in enumerate(b))


def bits_lsb(valeur, n):
    return [(valeur >> i) & 1 for i in range(n)]


def bits_msb(valeur, n):
    return [(valeur >> (n - 1 - i)) & 1 for i in range(n)]


def distance(preambule, bits, marque, espace0, espace1, fin=True):
    """Codage par distance d'impulsion (NEC, Samsung, Pioneer, RCA…)."""
    p = list(preambule)
    for b in bits:
        p += [marque, espace1 if b else espace0]
    if fin:
        p.append(marque)
    return p


def nec(adr, cmd):
    bits = bits_lsb(adr & 0xFF, 8) + bits_lsb(~adr & 0xFF, 8) + bits_lsb(cmd & 0xFF, 8) + bits_lsb(~cmd & 0xFF, 8)
    return 38000, distance([9000, 4500], bits, 560, 560, 1690)


def necext(adr, cmd):
    bits = bits_lsb(adr & 0xFFFF, 16) + bits_lsb(cmd & 0xFFFF, 16)
    return 38000, distance([9000, 4500], bits, 560, 560, 1690)


def nec42(adr, cmd):
    bits = (bits_lsb(adr & 0x1FFF, 13) + bits_lsb(~adr & 0x1FFF, 13)
            + bits_lsb(cmd & 0xFF, 8) + bits_lsb(~cmd & 0xFF, 8))
    return 38000, distance([9000, 4500], bits, 560, 560, 1690)


def samsung32(adr, cmd):
    bits = bits_lsb(adr & 0xFF, 8) + bits_lsb(adr & 0xFF, 8) + bits_lsb(cmd & 0xFF, 8) + bits_lsb(~cmd & 0xFF, 8)
    return 38000, distance([4500, 4500], bits, 550, 550, 1650)


def pioneer(adr, cmd):
    bits = bits_lsb(adr & 0xFF, 8) + bits_lsb(~adr & 0xFF, 8) + bits_lsb(cmd & 0xFF, 8) + bits_lsb(~cmd & 0xFF, 8)
    return 40000, distance([8500, 4225], bits, 500, 500, 1500)


def rca(adr, cmd):
    bits = (bits_msb(adr & 0xF, 4) + bits_msb(cmd & 0xFF, 8)
            + bits_msb(~adr & 0xF, 4) + bits_msb(~cmd & 0xFF, 8))
    return 56000, distance([4000, 4000], bits, 500, 1000, 2000)


def niveaux_vers_durees(niveaux, unite):
    """Liste de demi-bits (1 = allumé, 0 = éteint) -> durées fusionnées, sans le silence de tête."""
    durees = []
    precedent = None
    for n in niveaux:
        if n == precedent:
            durees[-1] += unite
        else:
            durees.append(unite)
            precedent = n
    if niveaux and niveaux[0] == 0:
        durees.pop(0)  # le signal doit commencer allumé
    if len(durees) % 2 == 0:
        durees.pop()  # un silence final ne sert à rien
    return durees


def rc5(adr, cmd, etendu=False):
    s2 = 0 if (etendu and cmd & 0x40) else 1
    bits = [1, s2, 0] + bits_msb(adr & 0x1F, 5) + bits_msb(cmd & 0x3F, 6)
    niveaux = []
    for b in bits:  # RC5 : 1 = éteint puis allumé, 0 = allumé puis éteint
        niveaux += [0, 1] if b else [1, 0]
    return 36000, niveaux_vers_durees(niveaux, 889)


def rc6(adr, cmd):
    t = 444
    niveaux = [1] * 6 + [0] * 2          # préambule
    niveaux += [1, 0]                     # bit de départ (1)
    niveaux += [0, 1] * 3                 # mode 000
    niveaux += [0, 0, 1, 1]               # bit de bascule (0), double largeur
    for b in bits_msb(adr & 0xFF, 8) + bits_msb(cmd & 0xFF, 8):
        niveaux += [1, 0] if b else [0, 1]  # RC6 : 1 = allumé puis éteint
    return 36000, niveaux_vers_durees(niveaux, t)


def sirc(adr, cmd, bits_adr):
    bits = bits_lsb(cmd & 0x7F, 7) + bits_lsb(adr, bits_adr)
    trame = [2400, 600]
    for b in bits:
        trame += [1200 if b else 600, 600]
    trame.pop()  # on termine sur une impulsion
    total = sum(trame)
    p = []
    for i in range(3):  # Sony demande au moins 3 trames, période 45 ms
        p += trame
        if i < 2:
            p.append(45000 - total)
    return 40000, p


ENCODEURS = {
    "NEC": nec,
    "NECext": necext,
    "NEC42": nec42,
    "Samsung32": samsung32,
    "Pioneer": pioneer,
    "RCA": rca,
    "RC5": lambda a, c: rc5(a, c, False),
    "RC5X": lambda a, c: rc5(a, c, True),
    "RC6": rc6,
    "SIRC": lambda a, c: sirc(a, c, 5),
    "SIRC15": lambda a, c: sirc(a, c, 8),
    "SIRC20": lambda a, c: sirc(a, c, 13),
}


def lire_blocs(chemin):
    blocs, bloc = [], None
    with open(chemin, encoding="utf-8") as f:
        for ligne in f:
            ligne = ligne.strip()
            if ligne.startswith("name:"):
                bloc = {"name": ligne[5:].strip()}
                blocs.append(bloc)
            elif bloc is not None and ":" in ligne and not ligne.startswith("#"):
                cle, val = ligne.split(":", 1)
                bloc[cle.strip()] = val.strip()
    return blocs


def encoder(bloc):
    """-> (frequence, durees, description) ou None si protocole non géré."""
    if bloc.get("type") == "raw":
        durees = [int(x) for x in bloc["data"].split()]
        durees = [max(1, d) for d in durees]
        return int(bloc.get("frequency", 38000)), durees, "brut"
    proto = bloc.get("protocol")
    if proto not in ENCODEURS:
        return None
    adr, cmd = octets(bloc["address"]), octets(bloc["command"])
    freq, durees = ENCODEURS[proto](adr, cmd)
    return freq, durees, "%s adr 0x%X cmd 0x%X" % (proto, adr, cmd)


def main():
    blocs = lire_blocs(SOURCE)
    lignes, telecommandes, ignores = [], {}, []
    numero = 0
    groupe = None
    for bloc in blocs:
        if bloc["name"] == "Power":
            r = encoder(bloc)
            if r is None:
                ignores.append(bloc.get("protocol"))
                groupe = None
                continue
            freq, durees, desc = r
            if sum(durees) > 2000000:
                ignores.append("trop long")
                groupe = None
                continue
            numero += 1
            proto = "brut" if bloc.get("type") == "raw" else bloc["protocol"]
            lignes.append("%d|%s|%s|%d|%s" % (numero, proto, desc, freq, ",".join(map(str, durees))))
            groupe = {"Power": bloc}
            telecommandes[numero] = groupe
        elif groupe is not None:
            groupe[bloc["name"]] = bloc  # autres touches de la même télécommande
    os.makedirs(os.path.dirname(SORTIE), exist_ok=True)
    with open(SORTIE, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lignes) + "\n")
    with open(SORTIE_JSON, "w", encoding="utf-8") as f:
        json.dump(telecommandes, f, ensure_ascii=False, indent=1)
    print("Codes Power convertis : %d" % numero)
    print("Ignorés : %s" % (ignores or "aucun"))


if __name__ == "__main__":
    sys.exit(main())
