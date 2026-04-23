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
- **Compteur d'outils dans `plugin.xml`** : compter exactement les outils dans `McpCompanionSettings.TOOL_GROUPS` (somme de tous les groupes) et vérifier que le nombre indiqué dans la balise `<description>` de `plugin.xml` (ligne `<b>NN tools — give your AI assistant…</b>`) correspond. Si différent → corriger automatiquement avant de présenter le plan.

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
  ✅/❌ Compteur d'outils dans plugin.xml (NN tools)

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

## Étape 2b — Validation des @McpDescription (nouveaux tools uniquement)

Si des nouveaux tools ont été détectés à l'étape 0, extraire la `@McpDescription` de chacun depuis le fichier `*Toolset.kt` correspondant et les afficher une par une :

```
📋 @McpDescription de `nom_du_tool` :
<texte complet de la description>

Ça te va ?
```

Attendre la validation pour chaque tool. Si l'utilisateur veut modifier → appliquer le changement dans le fichier Kotlin, puis continuer.

→ Passer si aucun nouveau tool.

## Étape 3 — What's New (2ème digit uniquement)

Si le 2ème digit a été incrémenté (nouveau tool), lire `build.gradle.kts` (section `changeNotes`) pour voir le style des entrées précédentes.

**Proposer une rédaction** basée sur les nouveaux tools détectés à l'étape 0, dans le style concis des entrées existantes (une seule phrase, code en `<code>`, pas de détails techniques).

Demander : **"Ça te va, ou tu veux reformuler ?"**
Attendre la validation (ou reformulation), puis ajouter l'entrée confirmée en tête de `changeNotes` dans `build.gradle.kts` :
```
<li><b>X.Y.0</b> — ...</li>
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
