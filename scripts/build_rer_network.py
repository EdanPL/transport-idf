import json
import math
import re
import unicodedata
import urllib.request
from pathlib import Path

# API Open Data Île-de-France Mobilités : Emplacements des gares ferrées
IDFM_GARE_API = (
    "https://data.iledefrance-mobilites.fr/api/explore/v2.1/catalog/datasets/"
    "emplacement-des-gares-idf/exports/json?where=mode%20%3D%20%27RER%27"
)

OUTPUT_PATH = Path(__file__).resolve().parent.parent / "src" / "main" / "resources" / "data" / "network-seed.json"


def normalize_id(name: str) -> str:
    """Transforme un nom de gare en identifiant propre (ex: Châtelet - Les Halles -> CHATELET_LES_HALLES)."""
    text = unicodedata.normalize("NFKD", name).encode("ascii", "ignore").decode("ascii")
    text = re.sub(r"[^A-Za-z0-9]+", "_", text.upper()).strip("_")
    return text


def haversine_meters(lat1: float, lon1: float, lat2: float, lon2: float) -> int:
    """Calcule la distance réelle en mètres entre deux coordonnées GPS."""
    r = 6371000
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlambda = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlambda / 2) ** 2
    return int(2 * r * math.atan2(math.sqrt(a), math.sqrt(1 - a)))


def estimate_duration_seconds(distance_meters: int) -> int:
    """Estime le temps inter-gare RER (vitesse moyenne 55 km/h + 35s de freinage/accélération)."""
    speed_mps = 55_000 / 3600
    return max(90, int(distance_meters / speed_mps) + 35)


def main():
    print("Téléchargement des gares RER depuis Île-de-France Mobilités...")
    with urllib.request.urlopen(IDFM_GARE_API) as response:
        raw_records = json.loads(response.read().decode("utf-8"))

    stations_by_id = {}
    lines_map = {"A": [], "B": [], "C": [], "D": [], "E": []}

    for rec in raw_records:
        name = rec.get("nom_long") or rec.get("nom_iv")
        geo = rec.get("geo_point_2d")
        indice = (rec.get("indice_lig") or "").strip().upper()

        if not name or not geo or indice not in lines_map:
            continue

        station_id = normalize_id(name)
        if station_id not in stations_by_id:
            stations_by_id[station_id] = {
                "id": station_id,
                "name": name,
                "latitude": round(geo["lat"], 6),
                "longitude": round(geo["lon"], 6)
            }

        # Évite les doublons d'une même gare sur la même ligne
        if not any(s["id"] == station_id for s in lines_map[indice]):
            lines_map[indice].append(stations_by_id[station_id])

    sections = []
    seen_edges = set()

    # Construction du graphe par plus proche voisin le long de chaque ligne RER
    # (avec liaison aux 2 plus proches voisins dans un rayon de 8 km pour couvrir les fourches/branches)
    for line, line_stations in lines_map.items():
        for s1 in line_stations:
            neighbors = []
            for s2 in line_stations:
                if s1["id"] == s2["id"]:
                    continue
                dist = haversine_meters(s1["latitude"], s1["longitude"], s2["latitude"], s2["longitude"])
                if dist <= 8500:
                    neighbors.append((dist, s2))

            neighbors.sort(key=lambda x: x[0])
            for dist, s2 in neighbors[:2]:
                for src, dst in [(s1["id"], s2["id"]), (s2["id"], s1["id"])]:
                    if (src, dst) not in seen_edges:
                        seen_edges.add((src, dst))
                        sections.append({
                            "source": src,
                            "target": dst,
                            "durationSeconds": estimate_duration_seconds(dist),
                            "distanceMeters": dist
                        })

    payload = {
        "stations": sorted(stations_by_id.values(), key=lambda x: x["name"]),
        "sections": sections
    }

    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    with open(OUTPUT_PATH, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)

    print(f"Succès : {len(payload['stations'])} gares RER et {len(payload['sections'])} tronçons générés dans {OUTPUT_PATH}")


if __name__ == "__main__":
    main()