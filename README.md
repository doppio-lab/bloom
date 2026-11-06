# Bloom

L'outil de prospection commerciale de Doppio Lab. Au café, la floraison (bloom) annonce la récolte des mois à l'avance : Bloom repère les entreprises qui vont avoir besoin d'une app native avant qu'elles ne lancent un appel d'offres.

Kotlin, Koog 1.3, Ktor 3, PostgreSQL. LLM au choix : local avec Ollama (gratuit), Anthropic, ou toute API compatible OpenAI.

Principe : **on ne contacte que sur preuve**. Chaque message part d'un signal observable (avis App Store qui
parlent de crash, offre d'emploi iOS ouverte depuis 74 jours, équipe qui recrute sur KMP), chaque affirmation
est reliée à un fait sourcé, et un garde-fou déterministe bloque tout chiffre inventé, tout jargon et toute
référence client hors sujet. Les LLM cherchent et rédigent ; le code score, vérifie et décide.

## Ce que fait l'outil

| Étape | Qui | Comment |
|---|---|---|
| Découverte | code | chaque nuit, neuf sources publiques (voir « Sources de découverte ») ; ESN, cabinets et éditeurs étrangers écartés |
| Bonne personne | code + humain + Dropcontact | carnet de contacts : dirigeants publiés à l'annuaire des entreprises, recherche LinkedIn préparée selon l'offre, email vérifié trouvé à partir du nom |
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
core/    domaine pur, testé : modèles, scoring, MessageGuard, moteur de séquences, références clients, ports (dépôts, sources de faits, rôles des agents, verrou d'instance, courrier, découverte) et cas d'usage (`usecase/` : planification, envoi, réponses, inscription, validation, tâches périodiques), testés avec des faux en mémoire
data/    PostgreSQL (Exposed + Flyway) : un dépôt par agrégat, qui implémente un port de core ; clients des sources de faits
agents/  agents Koog : recherche, synthèse, rédaction, classification des réponses, coach ; ne dépend que de core, le LLM (PromptExecutor) est injecté
app/     serveur Ktor : API, dashboard, envoi SMTP, relève IMAP, boucles de fond, désinscription
```

Le câblage est fait par Koin, uniquement dans `app` (`app/.../di/Modules.kt`) : un module par couche
(configuration, persistance, sources, agents, courrier, cas d'usage, boucles de fond, routes), chaque
implémentation liée au port de `core` qu'elle réalise. `core`, `data` et `agents` ne connaissent pas Koin. Un
composant facultatif (France Travail, Dropcontact) n'est déclaré que si sa clé est configurée. Les tests de
routes remplacent les ports par les faux en mémoire de `core` (`testFixtures`) et un test vérifie que chaque
module se résout, donc une dépendance oubliée fait échouer le build.

## Interface : la source de vérité est dans Claude Design

L'UI et l'UX de Bloom sont conçues dans le projet Claude Design
[Bloom](https://claude.ai/design/p/4aa99dff-b97a-4abb-a24f-a32b2d73243e), sur le design system Thermos (thème
clair ou sombre). Le code suit la maquette, jamais l'inverse : tout changement de mise en page, de composant, de texte
d'interface ou de token commence dans ce projet, puis est reporté dans
`app/src/main/resources/static/index.html`. Les règles détaillées sont dans `CLAUDE.md`.

## Lancer le serveur en local

Deux façons de faire. La première ne demande que Docker, la seconde est plus confortable pour développer.
Dans les deux cas, PostgreSQL tourne dans Docker et le schéma est créé tout seul au démarrage (Flyway).

### Prérequis

| Outil | Pour quoi | Vérifier |
|---|---|---|
| Docker avec Compose v2 (Docker Desktop ou OrbStack) | base de données, et serveur en option A | `docker compose version` |
| Un LLM : Ollama en local (gratuit) ou une clé d'API | agents de recherche, de rédaction et de classification | voir « Choisir le LLM » |
| Un JDK 17 ou plus récent | option B seulement, pour lancer Gradle | `java -version` |

Le code vise Java 21. En option B, Gradle utilise un JDK 21 déjà présent sur la machine. S'il n'en trouve
aucun, installer Temurin 21 (`brew install --cask temurin@21`).

### 1. Configurer

```bash
cp .env.example .env
```

Puis ouvrir `.env` et renseigner au minimum ces deux valeurs. Sans elles le serveur refuse de démarrer.

| Variable | Valeur |
|---|---|
| `ADMIN_PASSWORD` | le mot de passe du dashboard (l'utilisateur est toujours `doppio`), 16 caractères au moins |
| `UNSUBSCRIBE_SECRET` | une chaîne aléatoire, par exemple le résultat de `openssl rand -hex 32` |

Le serveur refuse aussi les valeurs d'exemple du `.env.example` (`change-me`, `une-longue-chaine-aleatoire`) et
un `DRY_RUN` autre que `true` ou `false`.

Tout le reste peut garder sa valeur d'exemple pour un premier lancement. `DRY_RUN=true` garantit qu'aucun
email ne part : les messages « envoyés » sont écrits dans les logs.

Le `.env` est ignoré par git. Les valeurs qui contiennent des espaces doivent rester entre guillemets doubles.

### Choisir le LLM

Le fournisseur se choisit avec `LLM_PROVIDER`. Le `.env.example` est réglé sur `ollama` : tout tourne sur la
machine, sans clé ni facture. C'est le mode pour essayer le produit.

| `LLM_PROVIDER` | Coût | À renseigner | Pour quoi |
|---|---|---|---|
| `ollama` | gratuit, local | rien (modèle par défaut `mistral-small3.2`) | développement, essais |
| `anthropic` | payant | `ANTHROPIC_API_KEY` | production : meilleure qualité de recherche et de rédaction |
| `openai` | selon le serveur | `LLM_MODEL`, plus `OPENAI_API_KEY` ou `OPENAI_BASE_URL` | OpenAI, ou un serveur compatible (LM Studio, llama.cpp, OpenRouter, Mistral) |

Sans `LLM_PROVIDER`, le serveur utilise `anthropic`. Le fournisseur et les modèles retenus sont écrits dans les
logs au démarrage (`LLM : ollama (principal : ..., rapide : ...)`).

**Mode local avec Ollama (macOS) :**

```bash
brew install ollama
brew services start ollama        # ou "ollama serve" dans un terminal, sans service permanent
ollama pull mistral-small3.2      # 15 Go, une seule fois
```

Ollama doit tourner sur la machine elle-même, pas dans Docker : sur Mac, un conteneur n'a pas accès au GPU.
Le serveur Bloom, lui, peut rester dans Docker : Compose le fait pointer vers l'Ollama de la machine hôte.

Pour un autre modèle : `ollama pull <modèle>` puis `LLM_MODEL=<modèle>`. Il doit savoir appeler des outils et
produire du JSON structuré (c'est le cas de `mistral-small3.2`, `qwen3`, `gpt-oss`). Compter 16 Go de mémoire
libre pour le modèle par défaut.

Ce qu'il faut attendre d'un modèle local, mesuré sur un Mac M5 Pro avec `mistral-small3.2` : 1 à 2 minutes pour
la recherche d'un compte, 30 secondes pour un brouillon. Les sorties structurées et le garde-fou fonctionnent.
En revanche l'exploration du site est moins fine qu'avec Anthropic : dans nos essais le modèle lisait bien les
pages mais n'enregistrait pas de citation. Suffisant pour tester le parcours, pas pour juger la qualité des
messages.

**Serveur compatible OpenAI (LM Studio, par exemple) :** `LLM_PROVIDER=openai`, `LLM_MODEL=<nom du modèle>`,
`OPENAI_BASE_URL=http://localhost:1234` (la racine, sans `/v1`). Depuis Docker, remplacer `localhost` par
`host.docker.internal`.

### 2a. Option A : tout dans Docker

```bash
docker compose up --build
```

Le premier build télécharge Gradle et les dépendances (quelques minutes). Le serveur est prêt quand
`curl localhost:8080/health` répond `ok`.

Pour le lancer en arrière-plan : `docker compose up --build -d`, puis `docker compose logs -f app` pour suivre.

### 2b. Option B : base dans Docker, serveur avec Gradle

```bash
docker compose up -d db                 # PostgreSQL sur 127.0.0.1:5432
set -a; source .env; set +a             # charge le .env dans le shell courant
./gradlew :app:run
```

Le serveur redémarre avec `Ctrl+C` puis `./gradlew :app:run`, sans reconstruire d'image. Dans IntelliJ :
lancer `main` dans `app/src/main/kotlin/lab/doppio/bloom/app/Application.kt` en fournissant les mêmes
variables d'environnement (champ « Environment variables » de la configuration, ou plugin EnvFile).

### 3. Ouvrir le dashboard

[http://localhost:8080](http://localhost:8080), utilisateur `doppio`, mot de passe `ADMIN_PASSWORD`.

Vérification en ligne de commande :

```bash
source .env
curl localhost:8080/health                                   # ok (sans authentification)
curl -u doppio:$ADMIN_PASSWORD localhost:8080/api/overview   # JSON des indicateurs
```

### 4. Premier jeu de données

Ajouter un compte et son contact, puis inscrire le contact dans une séquence :

```bash
curl -u doppio:$ADMIN_PASSWORD -H 'Content-Type: application/json' localhost:8080/api/accounts -d '{
  "account": {"name": "Exemple", "domain": "exemple.fr", "siren": "123456789", "sector": "RETAIL",
              "headcount": 120, "iosAppId": 123456789},
  "contacts": [{"accountId": 0, "fullName": "Claire Morel", "role": "Head of Mobile", "persona": "HEAD_OF_MOBILE",
                "email": "claire@exemple.fr", "dataSource": "site exemple.fr, page équipe"}]
}'
curl -u doppio:$ADMIN_PASSWORD -X POST localhost:8080/api/contacts/1/enroll -H 'Content-Type: application/json' -d '{}'
```

Autres points d'entrée : l'import CSV (section suivante) et la découverte automatique, qui tourne chaque
nuit entre 2h et 4h ou à la demande :

```bash
curl -u doppio:$ADMIN_PASSWORD -X POST localhost:8080/api/discovery/run
```

Attention, la découverte et la recherche appellent le LLM. Avec `anthropic` ou une API payante, elles consomment
des tokens facturés, même en `DRY_RUN`. La découverte dure plusieurs minutes et tourne en tâche de fond, bien
plus longtemps avec un modèle local.

Lancer la recherche d'un seul compte, pour voir les agents travailler :

```bash
curl -u doppio:$ADMIN_PASSWORD -X POST localhost:8080/api/accounts/1/research
```

Les brouillons d'email ne sont rédigés qu'à partir de la veille d'un créneau d'envoi (mardi à jeudi). Un
contact inscrit un vendredi ou un week-end n'aura donc son brouillon que le lundi matin.

### Ce qui tourne tout seul

| Boucle | Fréquence | Rôle |
|---|---|---|
| planification | 10 min | prépare les brouillons dus (agent rédacteur) |
| envoi | 2 min | envoie les emails approuvés, mar-jeu 8h30-11h30 (journalisés si `DRY_RUN=true`) |
| relève | 5 min | lit les réponses en IMAP (désactivée si `DRY_RUN=true`) |
| tâches | vérifiées toutes les 30 min | découverte (chaque nuit entre 2h et 5h, puis recherche), recherche (une fois par jour, dès 5h si la découverte ne l'a pas lancée), note du coach (une fois par semaine, dès le lundi 7h), purge RGPD (une fois par mois, dès le 1er à 4h) |

Garanties :

- Une seule instance fait tourner ces boucles (verrou Postgres). Une deuxième instance sert le dashboard
  et attend que la première s'arrête.
- Les tâches retiennent leur dernière exécution en base (`job_runs`) : un redémarrage ne les relance pas
  et ne les oublie pas.
- Un email est réservé avant l'envoi SMTP : il ne peut pas partir deux fois. S'il est interrompu par un
  redémarrage, il passe en `FAILED` et n'est jamais renvoyé tout seul. Un refus SMTP le remet en file
  15 puis 30 minutes plus tard, puis le passe en `FAILED`, sans bloquer les autres emails.
- Une réponse n'est marquée lue dans la boîte qu'une fois traitée. Les rebonds arrêtent la séquence et
  passent l'adresse en opposition.

### Variables d'environnement

| Variable | Défaut | Rôle |
|---|---|---|
| `LLM_PROVIDER` | `anthropic` | `ollama`, `anthropic` ou `openai` (le `.env.example` choisit `ollama`) |
| `LLM_MODEL` | selon le fournisseur | modèle principal ; obligatoire avec `openai` |
| `LLM_MODEL_FAST` | selon le fournisseur | modèle du classifieur de réponses ; par défaut Haiku avec `anthropic`, sinon le modèle principal |
| `LLM_DAILY_TOKEN_BUDGET` | vide | tokens autorisés par jour, tous agents confondus ; au-delà, les agents attendent le lendemain |
| `LLM_CONTEXT_LENGTH` | `16384` | fenêtre de contexte demandée à Ollama |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | adresse d'Ollama, fixée par Compose en option A |
| `ANTHROPIC_API_KEY` | vide | obligatoire avec `anthropic` |
| `OPENAI_API_KEY`, `OPENAI_BASE_URL` | vides | clé et racine de l'API avec `openai` |
| `ADMIN_PASSWORD` | obligatoire | mot de passe du dashboard et de l'API, 16 caractères au moins |
| `UNSUBSCRIBE_SECRET` | obligatoire | signe les liens de désinscription (HMAC), 16 caractères au moins |
| `DRY_RUN` | `true` | tant que c'est `true`, aucun email ne part et aucune boîte n'est relevée ; toute autre valeur que `true` ou `false` bloque le démarrage |
| `JAVA_OPTS` | vide | options de la JVM, par exemple `-XX:MaxRAMPercentage=60` (fixé par `deploy/docker-compose.prod.yml`) |
| `PORT` | `8080` | port HTTP du serveur (option B ; en option A, changer le mapping dans `docker-compose.yml`) |
| `LOG_LEVEL` | `INFO` | niveau des logs de Bloom (`DEBUG` pour le détail) |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/bloom` | URL JDBC, fixée par Compose en option A |
| `DB_USER`, `DB_PASSWORD` | `bloom`, `bloom` | identifiants PostgreSQL |
| `PUBLIC_BASE_URL` | `http://localhost:8080` | base des liens de désinscription |
| `FT_CLIENT_ID`, `FT_CLIENT_SECRET` | vides | API Offres d'emploi France Travail ; sans elles, la source « offres d'emploi » est coupée et le dashboard le signale |
| `DISCOVERY_SOURCES` | `bodacc,boamp,ted,decp,codeur,press,github,jobboards` | sources ajoutées à la découverte |
| `BODACC_MIN_CAPITAL` | `100000` | capital minimal, en euros, pour étudier une annonce BODACC |
| `DECP_RENEWAL_MONTHS` | `12` | un marché DECP est gardé s'il arrive à échéance dans ce nombre de mois |
| `CODEUR_MIN_BUDGET` | `1000` | bas de la tranche de budget, en euros, pour garder un projet Codeur |
| `GITHUB_TOKEN` | vide | jeton sans permission : 30 recherches GitHub par minute au lieu de 10 |
| `DROPCONTACT_API_KEY` | vide | recherche d'email par Dropcontact ; sans elle, saisie manuelle |
| `DISCOVERY_APPS_PER_RUN` | `400` | apps du classement analysées par découverte (environ 5 secondes chacune) |
| `DISCOVERY_EXCLUDE` | vide | entreprises à ne jamais prospecter, séparées par des virgules ; s'ajoute à la liste noire du dashboard |
| `SMTP_HOST`, `SMTP_PORT`, `IMAP_HOST` | Gmail | serveurs d'envoi et de relève |
| `MAILBOXES` | vide | boîtes d'envoi, `adresse:mot-de-passe-d-application:Nom affiché`, séparées par des virgules |
| `MAX_PER_MAILBOX_PER_DAY` | `25` | plafond d'envoi par boîte et par jour |
| `SENDER_NAME` | `l'équipe Doppio` | signature utilisée par le rédacteur |
| `LEGAL_FOOTER_FR`, `LEGAL_FOOTER_EN` | texte Doppio Lab | pied de message légal |

### Commandes utiles

```bash
./gradlew build                    # compile tout et lance les tests (Docker requis pour ceux de data)
./gradlew :core:test               # tests du domaine seulement
./gradlew :data:test               # tests sur un vrai Postgres (Testcontainers)
./gradlew :app:test                # routes (testApplication) et graphe Koin, avec des faux en mémoire, sans base
docker compose logs -f app         # logs du serveur (option A)
docker compose exec db psql -U bloom bloom   # console SQL
docker compose down                # arrêter, en gardant les données
docker compose down -v             # arrêter et effacer la base
```

### En cas de problème

| Symptôme | Cause et solution |
|---|---|
| `Variable d'environnement manquante : X` | `X` n'est pas dans `.env`, ou le `.env` n'a pas été chargé dans le shell (option B : refaire `set -a; source .env; set +a`) |
| `env file .env not found` | faire `cp .env.example .env` avant `docker compose` |
| `port is already allocated` sur 8080 ou 5432 | un autre service occupe le port : `lsof -nP -iTCP:8080 -sTCP:LISTEN`, puis l'arrêter ou changer le port de gauche dans `docker-compose.yml` (et `DATABASE_URL` si c'est 5432) |
| `Connection to localhost:5432 refused` (option B) | la base n'est pas lancée : `docker compose up -d db` |
| `password authentication failed for user "bloom"` | `DB_PASSWORD` a changé après la création de la base : remettre l'ancien, ou `docker compose down -v` (efface les données) |
| `ADMIN_PASSWORD a encore sa valeur d'exemple` ou `doit faire au moins 16 caractères` | générer un secret : `openssl rand -hex 32` |
| 401 sur le dashboard | utilisateur `doppio`, mot de passe `ADMIN_PASSWORD` ; après un changement du `.env`, redémarrer le serveur |
| Les agents échouent avec `ollama` | Ollama n'est pas lancé (`curl localhost:11434/api/version`) ou le modèle n'est pas téléchargé (`ollama list`) |
| Les agents échouent avec `anthropic` ou `openai` | clé invalide ou sans crédit, ou nom de modèle inconnu du serveur |
| Les agents sont très lents | normal en local ; vérifier avec `ollama ps` que le modèle tourne bien sur le GPU |
| Pas assez de détails dans les logs | les logs sont au niveau INFO ; `LOG_LEVEL=DEBUG` affiche aussi le détail de Bloom |
| `Could not find a valid Docker environment` dans les tests | Docker n'est pas lancé, ou son socket n'est pas à `/var/run/docker.sock` (OrbStack, Colima) : `export DOCKER_HOST=$(docker context inspect --format '{{.Endpoints.docker.Host}}')` |

### Passer en envoi réel

Ne mettre `DRY_RUN=false` qu'une fois le domaine d'envoi configuré (SPF, DKIM, DMARC) et chauffé 3 à 4
semaines, `MAILBOXES` rempli avec de vrais mots de passe d'application, et `PUBLIC_BASE_URL` pointant vers une
URL joignable depuis Internet (les liens de désinscription en dépendent).

## Mettre en production

Phase 1 : Bloom tourne sur un VPS en `DRY_RUN=true` (découverte, recherche, brouillons, validation), sans
envoyer d'email. Les fichiers sont dans `deploy/`.

```
Internet ──443──> Caddy (Let's Encrypt) ──> /u/... et /health ──> app:8080
                         └─ tout le reste : 404
Tailnet  ──443──> tailscale serve (https://bloom.<tailnet>.ts.net) ──> 127.0.0.1:8080 (mot de passe)
VPS : app (image ghcr.io/doppio-lab/bloom) + PostgreSQL (aucun port publié) + Caddy
```

Le dashboard et l'API ne sont joignables que par Tailscale. Seuls les liens de désinscription et la sonde de
santé sont publics, car un destinataire doit pouvoir se désinscrire sans compte.

| Fichier | Rôle |
|---|---|
| `deploy/docker-compose.prod.yml` | app, base et Caddy ; app sur 127.0.0.1 seulement, logs tournants, healthcheck |
| `deploy/Caddyfile` | HTTPS public, ne laisse passer que `/u/...` et `/health` |
| `deploy/.env.prod.example` | modèle du `.env` de production (Anthropic, budget de tokens, `DRY_RUN=true`) |
| `deploy/deploy.sh` | `./deploy.sh <sha>` : tire l'image, redémarre, attend `/health` |
| `deploy/backup.sh`, `bloom-backup.service`, `bloom-backup.timer` | sauvegarde chiffrée de la base chaque nuit à 1h (restic) |

L'image est publiée par la CI sur `ghcr.io/doppio-lab/bloom:<sha>` et `:latest` à chaque push sur `main`. Elle
est construite pour x86 : choisir un serveur x86 (Hetzner CX, pas CAX).

### Préparer le serveur (une fois)

1. VPS dans l'UE (Hetzner CX22 ou CX32, Scaleway Paris), Ubuntu 24.04, connexion par clé SSH uniquement.
   Créer un utilisateur `deploy`, l'ajouter au groupe `docker`, activer `unattended-upgrades`.
2. Installer Docker Engine avec Compose v2, restic, puis Tailscale : `sudo tailscale up --ssh --hostname bloom`.
3. Pare-feu (ufw et pare-feu du fournisseur) : ouvrir 80 et 443. Fermer 22 côté Internet une fois la connexion
   par `tailscale ssh deploy@bloom` vérifiée.
4. DNS : enregistrement A (et AAAA) `bloom.doppio-studio.fr` vers l'IP du VPS. Un sous-domaine du domaine
   d'envoi, jamais `doppio-lab.com`.
5. Copier `deploy/` dans `/opt/bloom`, puis `cp .env.prod.example .env && chmod 600 .env`. Renseigner les
   secrets (`openssl rand -hex 32` pour chacun), `ANTHROPIC_API_KEY` et `LLM_DAILY_TOKEN_BUDGET`.
6. Si le paquet GHCR est privé : `docker login ghcr.io` avec un jeton limité à `read:packages`.
7. `./deploy.sh <sha>`, puis publier le dashboard sur le tailnet :
   `sudo tailscale serve --bg --https=443 http://127.0.0.1:8080`.
8. Sauvegardes : écrire `/opt/bloom/restic.env` (`RESTIC_REPOSITORY`, `RESTIC_PASSWORD`, identifiants du
   stockage, chmod 600), `restic init`, copier les deux unités dans `/etc/systemd/system/`, puis
   `sudo systemctl enable --now bloom-backup.timer`. Activer aussi les snapshots du fournisseur.
9. Sonde externe (UptimeRobot, Better Stack) sur `https://bloom.doppio-studio.fr/health`.

### Exploiter

| Besoin | Commande, dans `/opt/bloom` |
|---|---|
| Déployer une version | `./deploy.sh <sha du commit sur main>` |
| Revenir en arrière | `./deploy.sh <sha précédent>` (les migrations Flyway ne se défont pas : vérifier qu'aucune n'a été ajoutée entre les deux) |
| Logs | `docker compose -f docker-compose.prod.yml logs -f app` |
| Console SQL | `docker compose -f docker-compose.prod.yml exec db psql -U bloom bloom` |
| Sauvegarder maintenant | `./backup.sh` |
| Restaurer | app arrêtée (`docker compose -f docker-compose.prod.yml stop app`) : `restic dump latest bloom.dump \| docker compose -f docker-compose.prod.yml exec -T db pg_restore --clean --if-exists -U bloom -d bloom`, puis `./deploy.sh` |
| Changer un secret | modifier `.env`, puis `docker compose -f docker-compose.prod.yml up -d app` |

Un `docker stop` arrête proprement : le serveur, puis les boucles, puis la base et le verrou d'instance, en
quelques secondes. Un redémarrage du VPS relance tout seul (`restart: unless-stopped`).

### Phase 2 : envoi réel

Avant `DRY_RUN=false` : corriger les P0 de `docs/audit-2026-10-20.md` (garde-fou des chiffres, renvoi SMTP,
timeouts SMTP et IMAP, arrêts « stop », citations Outlook), afficher une confirmation avant la désinscription par
GET (les scanners de liens des entreprises cliquent), configurer SPF, DKIM et DMARC, chauffer les boîtes 3 à 4
semaines, puis renseigner `MAILBOXES` et vérifier la relève IMAP.

## Liste noire et carnet de contacts

- **Liste noire** (vue Prospects). Le bouton « Liste noire » d'un prospect ou d'un compte classé l'écarte pour de
  bon : il sort de toutes les listes, ses séquences en cours s'arrêtent, ses brouillons en attente sont rejetés,
  et la découverte ne le recrée pas. Un nom peut aussi être ajouté à la main avant que la découverte ne le trouve.
  « Réintégrer » le retire de la liste sans relancer les séquences arrêtées.
- **Carnet** (vue Carnet). Pour chaque entreprise au-dessus de 45 ou qui a déjà un contact : les contacts choisis
  et leur état, les dirigeants publiés par l'Annuaire des entreprises (présidents, directeurs généraux, gérants ;
  ni administrateurs ni commissaires aux comptes), et les recherches LinkedIn à ouvrir. « Ajouter au carnet »
  transforme un dirigeant en contact sans lancer de séquence. Export CSV par `GET /api/book.csv`.
  Le registre ne donne que des mandataires sociaux : le responsable technique reste à trouver à la main.
  La date de naissance publiée par le registre n'est pas conservée.

## Sources de découverte

Chaque source est publique, lue sans contourner de conditions d'utilisation, et chaque fait garde le lien de
l'annonce, de l'offre ou de l'avis dont il vient. Tout est déterministe : aucun LLM dans la découverte.

| Source | Ce qu'elle repère | Signal | Activation |
|---|---|---|---|
| Classements App Store France (apps gratuites et apps aux meilleurs revenus, 21 catégories) | app française dont les avis se dégradent ou qui n'est plus mise à jour | note en baisse, plaintes, app à l'abandon | toujours active |
| Offres d'emploi France Travail | entreprise qui recrute un développeur mobile | recrutement mobile, KMP, recrutement bloqué | `FT_CLIENT_ID` et `FT_CLIENT_SECRET` |
| BODACC | augmentation de capital publiée aux annonces légales | levée de fonds | `bodacc` |
| BOAMP | appel d'offres public ouvert qui demande une application mobile | marché public | `boamp` |
| TED | marché public européen passé en France pour une application mobile | marché public | `ted` |
| DECP (données essentielles de la commande publique) | marché d'application mobile déjà attribué qui arrive à échéance dans `DECP_RENEWAL_MONTHS` mois | marché à renouveler | `decp` |
| Codeur.com | projet mobile publié par une entreprise, budget d'au moins `CODEUR_MIN_BUDGET` | aucun : liste « Demandes à répondre » | `codeur` |
| GitHub | organisation française qui publie du code Kotlin Multiplatform | code KMP public | `github` |
| Pages carrières (Greenhouse, Lever, Ashby, SmartRecruiters) | offre mobile d'un compte déjà connu, avec sa date de publication | recrutement mobile, KMP, recrutement bloqué | `jobboards` |
| Presse (FrenchWeb, Maddyness, JDN, L'Usine Digitale, Siècle Digital) | titre qui annonce une levée de fonds | levée de fonds | `press` |

Identifiants France Travail : créer un compte sur [francetravail.io](https://francetravail.io), déclarer une
application, y ajouter l'API « Offres d'emploi v2 », puis copier l'identifiant et la clé dans `FT_CLIENT_ID` et
`FT_CLIENT_SECRET`. Des identifiants refusés apparaissent dans la ligne « Dernière recherche » du dashboard.

Limites à connaître :

- BODACC donne le capital après l'augmentation, pas le montant levé. Les petites structures sont écartées
  (capital sous `BODACC_MIN_CAPITAL`, moins de 10 salariés, ni secteur couvert ni app sur l'App Store).
- Les pages carrières ne trouvent pas de nouvelles entreprises : elles enrichissent les comptes dont le site
  renvoie vers l'un des quatre services. Ashby et SmartRecruiters ne donnent pas l'ancienneté d'une offre.
- Presse : seuls le titre et le lien de l'article sont repris. Les conditions de réutilisation des flux n'ont pas
  été vérifiées ; retirer `press` de `DISCOVERY_SOURCES` en cas de doute.
- DECP ne donne que le SIRET de l'acheteur : son nom vient de l'annuaire des entreprises. L'échéance est
  calculée à partir de la date de notification et de la durée initiale, sans tenir compte d'une reconduction
  ou d'un avenant.
- GitHub sert à repérer des organisations. Aucun contact n'est créé à partir d'un compte GitHub.
- Codeur ne nomme pas l'entreprise et interdit de la contacter hors de la plateforme : ces projets ne deviennent
  jamais des comptes ni des séquences d'emails. Le flux est relu toutes les 30 minutes, car il ne garde que les
  derniers projets. Seuls le titre, la tranche de budget, les catégories et le lien sont conservés.
- LinkedIn : aucun accès conforme à ses offres (pas d'API de lecture, lecture automatique interdite par ses
  conditions). France Travail agrège déjà une centaine de sites partenaires, dont Indeed, HelloWork et l'APEC.
- Presse : seuls le titre et le lien sont repris. Les conditions de réutilisation des deux flux sont à
  vérifier avant d'activer la source.

## Ta liste en CSV

Onglet « Nouveaux prospects », bouton « Importer ma liste ». Virgule ou point-virgule, en-tête obligatoire :

```
name;domain;siren;ios_app_id;sector;headcount;warm_path;contact_name;contact_role;contact_email;contact_linkedin
Vélomoov;velomoov.fr;123456789;;MOBILITY_TRANSPORT;120;Recommandé par Julie;Claire Morel;Head of Mobile;claire@velomoov.fr;
```

## État du code

- Les quatre modules compilent (Kotlin 2.3.10, Koog 1.3.0, Ktor 3.3, Exposed 0.61) et les tests de `core`
  passent (score, garde-fou, séquences, exclusions, noms d'entreprise, CSV). Les tests de `data` tournent sur
  un vrai Postgres migré par Flyway (Testcontainers). La CI GitHub Actions lance `./gradlew build` et construit
  l'image Docker sur chaque PR.
- Vérifié en local : démarrage, migrations, authentification, `/api/overview`, création d'un compte. Les
  agents de recherche, de synthèse et de rédaction ont tourné de bout en bout sur un modèle local (Ollama, et le
  même modèle par l'API compatible OpenAI). Pas encore exercés : le fournisseur Anthropic avec une vraie clé, le
  classifieur de réponses, le coach, l'envoi SMTP et la relève IMAP.
- Hypothèses à confirmer au premier lancement : le flux de classement App Store par genre
  (`itunes.apple.com/fr/rss/topfreeapplications/limit=200/genre=…/json`) et le champ `entreprise.url` des offres
  France Travail. Si l'un ne répond plus, la découverte continue avec l'autre source.
- Mise en production prête pour la phase 1 (`deploy/`, image publiée sur GHCR) : pas encore déployée.
- Non fait : page de détail d'un compte dans le dashboard, Google Play.

## Garde-fous de conformité intégrés

- Pied de message : identité de l'expéditeur, source des données (RGPD art. 14), désinscription en un clic.
- `List-Unsubscribe` + `List-Unsubscribe-Post` (RFC 8058), lien signé HMAC, sans compte ni motif.
- Liste d'opposition par email et par domaine, jamais purgée ; vérifiée avant chaque brouillon et chaque envoi.
- Purge mensuelle des contacts sans échange depuis 3 ans (recommandation CNIL).
- Pas de pixel de suivi d'ouverture, pas de lien dans le premier email, texte brut.
- Aucune action automatisée sur LinkedIn (CGU, section 8.2).
