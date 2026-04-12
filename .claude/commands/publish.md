Publie le plugin sur le JetBrains Marketplace en suivant le workflow complet.

## Étape 0 — Détecter les nouveaux tools

Compare `git diff main --name-only` et `git diff --cached --name-only` pour détecter si des fichiers `*Toolset.kt` ont été modifiés.
Si oui, récupère les noms des nouveaux `@McpTool` ajoutés (grep sur les fichiers stagés).
Garde cette liste pour les vérifications suivantes.

## Étape 1 — Checklist qualité (BLOQUER si échec)

### 1a — Tests de réflexion
Vérifie dans `src/test/kotlin/.../ReflectionApiTest.kt` (et tout fichier `*Test.kt` de réflexion) que chaque appel `Class.forName`, `getDeclaredField`, `getMethod` présent dans les fichiers `*Toolset.kt` stagés a bien un test correspondant.
→ Si un appel de réflexion n'est pas couvert : **bloquer** et lister les appels manquants.

### 1b — Tests automatiques pour nouveaux tools
Si un nouveau tool a été détecté (étape 0) : vérifier qu'il existe au moins un test (dans `src/test/`) qui le couvre (heavy test, unit test, ou réflexion).
→ Si aucun test pour un nouveau tool : **bloquer** et demander d'en ajouter.

### 1c — Tests verts
```bash
./gradlew test 2>&1 | tail -20
```
→ Si `BUILD FAILED` ou tests en échec : **bloquer**.

### 1d — README à jour
Lis `README.md` et vérifie que le tableau des tools contient bien tous les tools listés dans `McpCompanionSettings.TOOL_GROUPS`.
→ Si un tool est absent du README : **bloquer** et lister les manquants.

### 1e — Overview à jour
Lis le tool `get_mcp_companion_overview` dans `McpCompanionToolset.kt` et vérifie que chaque tool présent dans `McpCompanionSettings.TOOL_GROUPS` est mentionné dans le texte de l'overview.
→ Si un tool est absent de l'overview : **bloquer** et lister les manquants.

### 1f — Page Settings à jour (nouveaux tools seulement)
Si un nouveau tool a été détecté : vérifie qu'il apparaît dans `McpCompanionSettings.TOOL_GROUPS` (il doit avoir été ajouté dans la bonne catégorie).
→ Si absent : **bloquer**.

Affiche un rapport de la checklist :
```
✅ Tests de réflexion couverts
✅ Tests auto présents pour nouveaux tools
✅ Tests verts
✅ README à jour
⚠️ Overview : tool 'get_foo' absent → BLOQUÉ
```
Ne pas continuer si un point est bloquant.

## Étape 2 — Incrémenter la version automatiquement

Lis la version actuelle dans `build.gradle.kts`.

- Si `$ARGUMENTS` contient un numéro de version explicite (ex: `2.7.0`) → utilise-le directement.
- Sinon, applique la règle :
  - **Nouveau tool détecté** (étape 0) → incrémenter le **2ème digit** et remettre le 3ème à 0 (ex: `2.6.1` → `2.7.0`)
  - **Pas de nouveau tool** (fix, amélioration) → incrémenter le **3ème digit** (ex: `2.6.1` → `2.6.2`)

Affiche la version calculée et demande confirmation avant de modifier le fichier.

## Étape 3 — Mettre à jour la version

Modifie la version dans `build.gradle.kts` avec l'outil Edit.

## Étape 3b — What's New (2ème digit uniquement)

Si le 2ème digit a été incrémenté (nouveau tool), ouvrir `src/main/resources/META-INF/plugin.xml` et localiser la section `<change-notes>`.

Demander à l'utilisateur : **"Que dois-je écrire dans le 'What's New' pour la version X.Y.0 ?"**
Attendre sa réponse, puis ajouter une entrée en tête de `<change-notes>` :
```xml
<b>X.Y.0</b>
<ul>
  <li>...</li>
</ul>
```

→ Passer cette étape si c'est uniquement une incrémentation du 3ème digit.

## Étape 4 — Build

```bash
./gradlew buildPlugin
```

Vérifie que le build est `BUILD SUCCESSFUL`. Arrêter si erreur.

## Étape 5 — Vérification de compatibilité

```bash
./gradlew verifyPlugin
```

Doit être `BUILD SUCCESSFUL`.
- Si `COMPATIBILITY_PROBLEMS` ou `INTERNAL_API_USAGES` détectés → **stopper et signaler**. Ne pas publier.
- Warnings mineurs (deprecated) → OK, continuer.

## Étape 6 — Demander accord explicite pour commit + push

Affiche un résumé :
- Version : X.Y.Z
- Fichiers modifiés (git status)
- Résultats de la checklist qualité
- Résultat verifyPlugin

Puis demande : **"Je publie la version X.Y.Z sur le Marketplace ?"**
Attendre confirmation explicite ("oui", "go", "ok", etc.) avant de continuer.

## Étape 7 — Commit + push

```bash
git add build.gradle.kts
git commit -m "X.Y.Z — <description courte des changements>"
git push
```

## Étape 8 — Publication

```bash
./gradlew publishPlugin
```

Vérifie que la sortie contient `BUILD SUCCESSFUL` et l'URL de la release sur le Marketplace.

## Étape 9 — Résumé final

Affiche :
- ✅ Version publiée : X.Y.Z
- Lien vers le plugin sur le Marketplace (si disponible dans la sortie Gradle)
