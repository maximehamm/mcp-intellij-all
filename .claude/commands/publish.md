Publie le plugin sur le JetBrains Marketplace en suivant le workflow complet.

## Étape 0 — Analyse initiale (silencieuse, sans tests)

Effectue ces opérations en parallèle sans rien afficher encore :

**a) Détecter les nouveaux tools**
Compare `git diff main --name-only` pour détecter si des fichiers `*Toolset.kt` ont été modifiés.
Si oui, grep les nouveaux `@McpTool` ajoutés. Garde la liste.

**b) Calculer les versions candidates**
Lis la version actuelle dans `build.gradle.kts`.
- Si `$ARGUMENTS` contient un numéro explicite → l'utiliser tel quel, pas de choix à proposer.
- Sinon : calculer les deux options — option A (2ème digit+1, 3ème reset) et option B (3ème digit+1).
  Pré-sélectionner l'option recommandée selon le contexte : A si nouveau tool détecté, B sinon.

**c) Checklist qualité (sans tests)**
- Réflexion : chaque `Class.forName`/`getDeclaredField`/`getMethod` dans les Toolset modifiés a un test dans `*ReflectionTest.kt` ?
- Tests nouveaux tools : si nouveau tool, existe-t-il un test qui le couvre ?
- README : tous les tools de `McpCompanionSettings.TOOL_GROUPS` présents dans `README.md` ?
- Overview : tous les tools mentionnés dans `get_mcp_companion_overview` de `McpCompanionToolset.kt` ?
- Settings : nouveau tool présent dans `McpCompanionSettings.TOOL_GROUPS` ?

## Étape 1 — Présentation du plan et confirmation unique

Affiche un récapitulatif structuré :

```
📦 Version actuelle : X.Y.Z  →  prochaine : ?
🔧 Nouveaux tools : <liste ou "aucun">

Checklist :
  ✅/❌ Tests de réflexion
  ✅/❌ Tests nouveaux tools
  ⏳ Tests unitaires (seront lancés après confirmation)
  ✅/❌ README à jour
  ✅/❌ Overview à jour
  ✅/❌ Settings à jour

Fichiers modifiés : <liste git diff main --name-only>
```

Si un point statique est ❌ → expliquer le problème et **stopper**. Ne pas demander confirmation.

Si tous les points statiques sont ✅ → demander **une seule fois** :

> **"Quelle version veux-tu publier ?"**
> - **A) X.Y+1.0** — nouveau tool / fonctionnalité majeure ← *(recommandé si nouveau tool)*
> - **B) X.Y.Z+1** — fix / amélioration mineure ← *(recommandé sinon)*
> - ou saisis un numéro libre
>
> *(Les tests seront lancés après ta réponse. Si KO → publication annulée malgré ta confirmation.)*

Attendre la réponse. Si refus ou annulation → stopper.

## Étape 1b — Tests unitaires (après confirmation)

Lance les tests et affiche le résultat :

```bash
./gradlew test 2>&1 | tail -5
```

Si les tests échouent → **stopper immédiatement**, même si l'utilisateur a déjà confirmé :
> ❌ Tests KO — publication annulée. Corriger les tests avant de relancer `/publish`.

## Étape 2 — Mettre à jour la version

Modifie la version dans `build.gradle.kts` avec l'outil Edit.

## Étape 3 — What's New (2ème digit uniquement)

Si le 2ème digit a été incrémenté (nouveau tool), ouvrir `src/main/resources/META-INF/plugin.xml` et localiser la section `<change-notes>`.

Demander à l'utilisateur : **"Que dois-je écrire dans le 'What's New' pour la version X.Y.0 ?"**
Attendre sa réponse, puis ajouter une entrée en tête de `<change-notes>` :
```xml
<b>X.Y.0</b>
<ul>
  <li>...</li>
</ul>
```
→ Passer si 3ème digit seulement.

## Étape 4 — Build

```bash
./gradlew buildPlugin 2>&1 | grep -E "BUILD|error:"
```
Arrêter si `BUILD FAILED`.

## Étape 5 — Vérification de compatibilité

```bash
./gradlew verifyPlugin 2>&1 | grep -E "BUILD|COMPATIBILITY_PROBLEMS|INTERNAL_API_USAGES"
```
- `COMPATIBILITY_PROBLEMS` ou `INTERNAL_API_USAGES` → **stopper et signaler**.
- Warnings mineurs → OK.

## Étape 6 — Commit + push + publish

```bash
git add -A
git commit -m "X.Y.Z — <description courte>"
git push
./gradlew publishPlugin
```

## Étape 7 — Résumé final

```
✅ Version X.Y.Z publiée sur le Marketplace
```
