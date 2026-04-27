"""Classifier per riconoscimento utente bilancia.

Approccio: nearest-centroid normalizzato.

Per ogni pesata confermata (via Telegram in fase di bootstrap o in caso di
ambiguità) salviamo un sample in training_data.json. Da questi calcoliamo
per ciascun utente un centroide nello spazio delle 7 feature biometriche:

    [weight_kg, fat_pct, water_pct, bone_kg, muscle_kg, visceral_fat, bmi]

Quando arriva una nuova pesata, calcoliamo la distanza euclidea normalizzata
(ogni feature divisa per la sua dev. standard sull'intero dataset) dalla
nuova pesata a ciascun centroide. La predizione è il centroide più vicino;
la confidence è la "distanza relativa" tra il primo e il secondo classificato:
se il primo è molto più vicino del secondo, la classificazione è certa.

Soglie:
  - Bootstrap: serve almeno MIN_SAMPLES_PER_USER campioni per CIASCUN utente
    prima di poter classificare automaticamente. Sotto soglia → ASK.
  - Confidence: se la confidence supera CONFIDENCE_THRESHOLD → AUTO. Sotto → ASK.

Storage: JSON in state_dir/training_data.json (mirrorato in Google Drive).
"""
from __future__ import annotations

import json
import logging
import math
import os
import statistics
from dataclasses import dataclass
from datetime import datetime
from typing import Optional

log = logging.getLogger("classifier")

FEATURES = ("weight_kg", "fat_pct", "water_pct", "bone_kg",
            "muscle_kg", "visceral_fat", "bmi")

# Settings (tunable). Valori default in linea con la discussione.
MIN_SAMPLES_PER_USER = 5
CONFIDENCE_THRESHOLD = 0.85
# Per il "bootstrap parziale": se solo alcuni utenti hanno raggiunto la
# soglia, possiamo auto-classificare verso quelli SOLO se la pesata cade
# CHIARAMENTE dentro il loro centroide (z-score distance bassa). Altrimenti
# assumiamo che possa essere uno degli utenti ancora in bootstrap e
# chiediamo via Telegram. Questo permette a chi raggiunge la soglia di
# uscire prima dal bootstrap senza compromettere l'accuratezza per gli altri.
PARTIAL_BOOTSTRAP_MAX_DISTANCE = 1.0


@dataclass
class ClassificationResult:
    decision: str            # "auto" | "ask"
    predicted_user: Optional[str]
    confidence: float        # 0..1
    reason: str              # log-friendly explanation
    distances: dict          # user → distance, for debugging


def _state_dir() -> str:
    """Lazy import to break circular dep with config (used by listener)."""
    from ha_push import _state_dir as _d  # type: ignore
    return str(_d())


def _training_file() -> str:
    p = _state_dir()
    return os.path.join(p, "training_data.json")


def _mirror_file() -> Optional[str]:
    """Return the path of the Drive mirror, if available."""
    here = os.path.dirname(os.path.abspath(__file__))
    mirror_dir = os.path.normpath(os.path.join(here, "..", "runtime-mipc"))
    if os.path.isdir(mirror_dir):
        return os.path.join(mirror_dir, "training_data.json")
    return None


def load_training() -> list[dict]:
    """Read all confirmed weighing samples."""
    f = _training_file()
    try:
        with open(f, "r", encoding="utf-8") as fp:
            data = json.load(fp)
        return data.get("samples", [])
    except FileNotFoundError:
        return []
    except Exception as e:
        log.warning("training_data load failed: %r", e)
        return []


def save_sample(user: str, record_dict: dict) -> None:
    """Append a confirmed sample to training_data.json (and mirror)."""
    samples = load_training()
    sample = {"name": user, "ts": int(datetime.now().timestamp())}
    for feat in FEATURES:
        v = record_dict.get(feat)
        if v is not None:
            sample[feat] = float(v)
    samples.append(sample)
    payload = {"version": 1, "samples": samples}

    f = _training_file()
    os.makedirs(os.path.dirname(f), exist_ok=True)
    tmp = f + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fp:
        json.dump(payload, fp, indent=2)
    os.replace(tmp, f)
    log.info("training sample saved: user=%s feat_count=%d (total samples=%d)",
             user, len([k for k in sample if k in FEATURES]), len(samples))

    # Mirror best-effort
    mirror = _mirror_file()
    if mirror:
        try:
            with open(mirror, "w", encoding="utf-8") as fp:
                json.dump(payload, fp, indent=2)
        except Exception:
            pass


def _per_user_count(samples: list[dict]) -> dict[str, int]:
    out: dict[str, int] = {}
    for s in samples:
        out[s["name"]] = out.get(s["name"], 0) + 1
    return out


def _feature_std(samples: list[dict]) -> dict[str, float]:
    """Compute std of each feature across the WHOLE dataset, used to
    normalize feature scales before computing distances."""
    out: dict[str, float] = {}
    for feat in FEATURES:
        vals = [float(s[feat]) for s in samples if feat in s]
        if len(vals) >= 2:
            sd = statistics.pstdev(vals)
            out[feat] = sd if sd > 1e-6 else 1.0
        else:
            out[feat] = 1.0
    return out


def _centroid(samples: list[dict]) -> dict[str, float]:
    out: dict[str, float] = {}
    for feat in FEATURES:
        vals = [float(s[feat]) for s in samples if feat in s]
        if vals:
            out[feat] = statistics.fmean(vals)
    return out


def _distance(record: dict, centroid: dict, std: dict) -> float:
    """Normalized euclidean distance: each feature scaled by its std."""
    sq = 0.0
    n = 0
    for feat in FEATURES:
        v = record.get(feat)
        c = centroid.get(feat)
        if v is None or c is None:
            continue
        s = std.get(feat, 1.0) or 1.0
        sq += ((float(v) - c) / s) ** 2
        n += 1
    if n == 0:
        return float("inf")
    return math.sqrt(sq / n)  # mean-squared, more robust to missing feats


def classify(record_dict: dict, known_users: list[str]) -> ClassificationResult:
    """Decide whether to auto-classify the new weighing or ask via Telegram.

    Args:
        record_dict: must contain at least the FEATURES that are available
            on the new reading (typically all 7 for a complete BIA record).
        known_users: list of profile slugs from config (e.g. ["user1",
            "user2", "user3"]). Bootstrap requires at least
            MIN_SAMPLES_PER_USER samples for each.

    Returns:
        ClassificationResult with decision='ask' or 'auto'.
    """
    samples = load_training()
    counts = _per_user_count(samples)

    # 1) Bootstrap PER-UTENTE. Un utente è "trained" quando ha ≥ MIN_SAMPLES.
    # Quelli che hanno raggiunto la soglia escono dal bootstrap; gli altri
    # restano in ASK. Se NESSUN utente è ancora trained → ASK totale.
    trained = [u for u in known_users if counts.get(u, 0) >= MIN_SAMPLES_PER_USER]
    untrained = [u for u in known_users if u not in trained]
    if not trained:
        return ClassificationResult(
            decision="ask",
            predicted_user=None,
            confidence=0.0,
            reason=f"bootstrap: no user trained yet (counts={counts}, "
                   f"need {MIN_SAMPLES_PER_USER}/user)",
            distances={},
        )

    # 2) Build per-user centroids only for trained users.
    std = _feature_std(samples)
    by_user: dict[str, list[dict]] = {}
    for s in samples:
        by_user.setdefault(s["name"], []).append(s)
    centroids = {u: _centroid(by_user[u]) for u in trained if u in by_user}

    # 3) Distance to each trained centroid.
    dists = {u: _distance(record_dict, centroids[u], std) for u in centroids}
    if not dists:
        return ClassificationResult(
            decision="ask",
            predicted_user=None,
            confidence=0.0,
            reason="no usable centroids",
            distances={},
        )

    sorted_users = sorted(dists.items(), key=lambda kv: kv[1])
    first_user, first_d = sorted_users[0]
    second_d = sorted_users[1][1] if len(sorted_users) > 1 else float("inf")

    # 4) Confidence relativa: 1 - first/second.
    if second_d == float("inf") or second_d == 0:
        confidence = 1.0 if first_d < 1.0 else 0.5
    else:
        confidence = max(0.0, min(1.0, 1.0 - (first_d / second_d)))

    # 5) Decisione. Se ci sono ancora utenti untrained, bisogna essere
    # extra-cauti: la pesata potrebbe appartenere a un utente di cui non
    # abbiamo ancora il centroide. Auto-classifica solo se la distanza
    # ASSOLUTA al centroide più vicino è bassa (≤ PARTIAL_BOOTSTRAP_MAX_
    # DISTANCE in z-score). Altrimenti ASK così l'utente può confermare.
    auto_ok = confidence >= CONFIDENCE_THRESHOLD
    if untrained:
        auto_ok = auto_ok and first_d <= PARTIAL_BOOTSTRAP_MAX_DISTANCE

    if auto_ok:
        why = f"auto: top={first_user} d={first_d:.3f} second_d={second_d:.3f} conf={confidence:.2f}"
        if untrained:
            why += f" (partial bootstrap, untrained={untrained})"
        return ClassificationResult(
            decision="auto",
            predicted_user=first_user,
            confidence=confidence,
            reason=why,
            distances=dists,
        )

    why = (f"ambiguous: top={first_user} d={first_d:.3f} "
           f"second_d={second_d:.3f} conf={confidence:.2f} below {CONFIDENCE_THRESHOLD}")
    if untrained:
        why += f" (partial bootstrap, untrained={untrained}, max_d={PARTIAL_BOOTSTRAP_MAX_DISTANCE})"
    return ClassificationResult(
        decision="ask",
        predicted_user=first_user,  # best guess, used to highlight in Telegram
        confidence=confidence,
        reason=why,
        distances=dists,
    )
