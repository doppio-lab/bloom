# Bloom

L'outil de prospection commerciale de Doppio Lab. Au café, la floraison (bloom) annonce la récolte des mois à l'avance : Bloom repère les entreprises qui vont avoir besoin d'une app native avant qu'elles ne lancent un appel d'offres.

Kotlin, Koog 1.3, Ktor 3, PostgreSQL.

Principe : **on ne contacte que sur preuve**. Chaque message part d'un signal observable (avis App Store qui
parlent de crash, offre d'emploi iOS ouverte depuis 74 jours, équipe qui recrute sur KMP), chaque affirmation
est reliée à un fait sourcé, et un garde-fou déterministe bloque tout chiffre inventé, tout jargon et toute
référence client hors sujet. Les LLM cherchent et rédigent ; le code score, vérifie et décide.

## Ce que fait l'outil

| Étape | Qui | Comment |
|---|---|---|
| Découverte | code | chaque nuit : offres d'emploi mobiles en France + classements App Store France ; ESN, cabinets et éditeurs étrangers écartés |
| Bonne personne | humain + Dropcontact | recherche LinkedIn préparée selon l'offre, email vérifié trouvé à partir du nom |
| Collecte de faits | code | App Store (Lookup + avis RSS), France Travail (offres), annuaire des entreprises |
| Exploration du site | agent Koog outillé | lit 6 pages max, ne garde que des citations vérifiées mot pour mot |
| Dossier | agent Koog, sortie structurée | résumé, hypothèses reliées aux faits, angle, offre, raisons de ne pas contacter |
| Score | code | adéquation ICP (40) + signaux amortis dans le temps (50) + lien chaud (10) |
| Rédaction | agent Koog + `MessageGuard` | rédige, se fait refuser, corrige, 2 fois max |
| Validation | humain | file « À valider » du dashboard, édition possible, garde-fou réappliqué |
| Envoi | code | fenêtre mar-jeu 8h30-11h30, 25/jour/boîte, 3 min entre envois, RFC 8058 |
| LinkedIn | humain | note préparée, copiée, envoyée à la main (pas d'automatisation) |
| Réponses | agent rapide + code | classification, arrêt de séquence, opposition, date de relance |
| Coach | agent Koog outillé | note du lundi à partir des métriques |

## Modules

```
core/    domaine pur, testé : modèles, scoring, MessageGuard, moteur de séquences, références clients
data/    PostgreSQL (Exposed + Flyway), clients des sources de faits
agents/  agents Koog : recherche, synthèse, rédaction, classification des réponses, coach
app/     serveur Ktor : API, dashboard, envoi SMTP, relève IMAP, boucles de fond, désinscription
```

## Démarrer

```bash
cp .env.example .env        # renseigner ANTHROPIC_API_KEY, ADMIN_PASSWORD, UNSUBSCRIBE_SECRET
docker compose up --build   # dashboard sur http://localhost:8080 (utilisateur : doppio)
```

`DRY_RUN=true` par défaut : rien ne part, les emails sont écrits dans les logs. Ne le passer à `false`
qu'une fois le domaine d'envoi configuré (SPF, DKIM, DMARC) et chauffé 3 à 4 semaines.

Ajouter un compte :

```bash
curl -u doppio:$ADMIN_PASSWORD -H 'Content-Type: application/json' localhost:8080/api/accounts -d '{
  "account": {"name": "Exemple", "domain": "exemple.fr", "siren": "123456789", "sector": "RETAIL",
              "headcount": 120, "iosAppId": 123456789},
  "contacts": [{"accountId": 0, "fullName": "Claire Morel", "role": "Head of Mobile", "persona": "HEAD_OF_MOBILE",
                "email": "claire@exemple.fr", "dataSource": "site exemple.fr, page équipe"}]
}'
curl -u doppio:$ADMIN_PASSWORD -X POST localhost:8080/api/contacts/1/enroll -H 'Content-Type: application/json' -d '{}'
```

## Ta liste en CSV

Onglet « Nouveaux prospects », bouton « Importer ma liste ». Virgule ou point-virgule, en-tête obligatoire :

```
name;domain;siren;ios_app_id;sector;headcount;warm_path;contact_name;contact_role;contact_email;contact_linkedin
Vélomoov;velomoov.fr;123456789;;MOBILITY_TRANSPORT;120;Recommandé par Julie;Claire Morel;Head of Mobile;claire@velomoov.fr;
```

## État du code

- `core` compile en Kotlin 2.3.10 et ses 20 tests passent (score, garde-fou, séquences, exclusions, noms d'entreprise, CSV).
- `data`, `agents` et `app` ont été écrits contre les API de Koog 1.3.0 (vérifiées dans les sources),
  Ktor 3.3 et Exposed 0.61, mais **n'ont pas été compilés** : l'environnement de génération n'avait pas accès
  à Maven Central. Premier geste : `./gradlew build` et corriger ce qui casse.
- Hypothèses à confirmer au premier lancement : le flux de classement App Store par genre
  (`itunes.apple.com/fr/rss/topfreeapplications/limit=200/genre=…/json`) et le champ `entreprise.url` des offres
  France Travail. Si l'un ne répond plus, la découverte continue avec l'autre source.
- Non fait : page de détail d'un compte dans le dashboard, tests d'intégration (Testcontainers), Google Play.

## Garde-fous de conformité intégrés

- Pied de message : identité de l'expéditeur, source des données (RGPD art. 14), désinscription en un clic.
- `List-Unsubscribe` + `List-Unsubscribe-Post` (RFC 8058), lien signé HMAC, sans compte ni motif.
- Liste d'opposition par email et par domaine, jamais purgée ; vérifiée avant chaque brouillon et chaque envoi.
- Purge mensuelle des contacts sans échange depuis 3 ans (recommandation CNIL).
- Pas de pixel de suivi d'ouverture, pas de lien dans le premier email, texte brut.
- Aucune action automatisée sur LinkedIn (CGU, section 8.2).
