# BlueLock — ton Tucson sur Android

App Android personnelle pour contrôler ton Hyundai via Bluelink Canada, calquée sur Bluelocke (iOS),
avec le **verrouillage automatique quand tu t'éloignes**.

**Confidentialité** : aucun serveur intermédiaire. Identifiants, NIP, jetons et données du véhicule sont
chiffrés sur ton téléphone (Android Keystore) et exclus des sauvegardes Google. L'app ne parle qu'à
Hyundai (`mybluelink.ca`) ; l'image du véhicule vient du dépôt GitHub de Bluelocke, sans aucune donnée personnelle.
Aucune bibliothèque tierce : seulement le SDK Android.

## Fonctions

- Écran principal comme Bluelocke : essence/batterie + autonomie, image du véhicule, climatisation,
  verrouillage, statut (touche pour interroger l'auto), odomètre, batterie 12 V, emplacement, recharge (PHEV/VE).
- Climatisation « Chauffer » / « Refroidir » configurables (température, dégivrage, volant, sièges, durée).
- **Verrouillage auto** : à la déconnexion du Bluetooth de l'auto → compte à rebours annulable →
  vérifie que l'auto est déverrouillée et arrêtée → verrouille. Annulé si le Bluetooth se reconnecte.
  Mode « confirmation » possible (ne verrouille jamais sans ton accord). Bouton de test sans verrouillage.
- Widget d'écran d'accueil (4×2) avec boutons Verrouiller et Actualiser.
- Tuile des réglages rapides « Verrouiller l'auto ».
- Raccourcis par appui long sur l'icône : Verrouiller, Déverrouiller (avec confirmation), Chauffer, Refroidir.
- Code de vérification Hyundai (courriel ou texto) géré à la connexion.

## Obtenir l'APK (sans rien installer sur ton PC)

1. Crée un compte sur <https://github.com> si tu n'en as pas.
2. Crée un dépôt : **New repository** → nom `BlueLock` → coche **Private** → **Create repository**.
3. Sur la page du dépôt vide, clique **uploading an existing file**.
4. Décompresse `BlueLock.zip` sur ton PC, ouvre le dossier `BlueLock`, sélectionne **tout son contenu**
   (y compris le dossier `.github`) et glisse-le dans la page. Clique **Commit changes**.
5. Onglet **Actions** : la compilation « Construire l'APK » démarre seule (≈ 4 min).
   Si rien ne démarre, choisis « Construire l'APK » → **Run workflow**.
6. Ouvre l'exécution terminée (✓ vert) → en bas, **Artifacts** → télécharge `BlueLock-apk` (un .zip contenant `BlueLock.apk`).
7. Copie `BlueLock.apk` sur ton téléphone, ouvre-le et autorise l'installation d'applis de cette source.

Pour une mise à jour : remplace les fichiers dans le dépôt, attends la compilation, réinstalle par-dessus
(tes réglages sont conservés : la clé de signature est fixe).

*Alternative* : ouvrir le dossier dans Android Studio et faire **Run**.

## Configuration

1. Ouvre BlueLock → **Réglages** → courriel, mot de passe et NIP MyHyundai → **Enregistrer**.
   Entre le code de vérification reçu si Hyundai le demande.
2. Section **Verrouillage auto** : active-le, touche **Choisir le Bluetooth du véhicule** et sélectionne ton Tucson
   (accepte la permission « Appareils à proximité » et les notifications).
3. Touche **Désactiver l'optimisation de batterie** (important pour la fiabilité).
4. Touche **Tester** : tu dois voir le compte à rebours puis « Verrouillage auto OK » sans que l'auto soit verrouillée.

⚠ Garde toujours ta clé sur toi : si le téléphone reste dans l'auto quand elle s'éteint, elle peut se verrouiller.

## Bon à savoir

- Si Hyundai exige un nouveau code de vérification pendant que l'app est en arrière-plan, le verrouillage
  auto t'avertit : ouvre l'app une fois pour te reconnecter.
- API non officielle : Hyundai peut la modifier. Projet personnel non affilié à Hyundai. Basé sur Bluelocke (MIT).
