# CLAUDE.md — Bonnes pratiques pour ce projet

## Architecture

- Un seul fichier Kotlin : `McpCompanionToolset.kt` — tous les outils dans une classe, data classes en bas de fichier.
- Chaque outil = un `@McpTool` + `@McpDescription` + méthode `suspend`, plus un helper privé si la logique est complexe.
- Après l'ajout de chaque outil, mettre à jour `<description>` dans `plugin.xml` ET le tableau dans `README.md`.

## API IntelliJ MCP (2025.3+)

- Marker interface : `McpToolset` (pas de méthode à implémenter)
- Annotations : `@McpTool(name = "snake_case")`, `@McpDescription(description = "...")`
- Accès au projet : `coroutineContext.project` (propriété Kotlin, pas une fonction — import `com.intellij.mcpserver.project`)
- Toute interaction UI/EDT : utiliser `invokeAndWaitIfNeeded { }` ou `runReadAction { }`

## API de test : SMTestProxy

- `proxy.isPassed` / `proxy.isIgnored` — OK
- `proxy.isFailed` **n'existe pas** → utiliser `proxy.isDefect`
- `proxy.duration` est **nullable** (`Long?`) → toujours qualifier : `proxy.duration?.takeIf { it >= 0 }`
- Accès aux résultats : `UIUtil.findComponentOfType(content.component, SMTestRunnerResultsForm::class.java)`, puis `form.testsRootNode`
- Les résultats sont dans le tool window `"Run"`, pas `"Test Results"`

## Build

- Kotlin **2.2.0** requis (pour lire les metadata des jars IntelliJ 2025.3)
- `bundledPlugin("com.intellij.mcpServer")` ne suffit pas pour la compilation → ajouter aussi :
  `compileOnly(files(".../mcpserver.jar"))`
- `buildSearchableOptions { enabled = false }` — évite un crash JVM lors du build
- `jvmArgs("-Xbootclasspath/a:.../nio-fs.jar")` — requis pour `runIde` (fix `MultiRoutingFileSystemProvider`)

## Tester via MCP SSE (curl)

Le sandbox IntelliJ (lancé par `runIde`) expose le MCP sur un port dédié — trouver avec :
```bash
lsof -nP -iTCP -sTCP:LISTEN | grep java   # repérer le port du processus sandbox
```

Appel d'un outil :
```bash
curl -s -N --max-time 20 http://127.0.0.1:<PORT>/sse > /tmp/sse.txt &
sleep 2
# ⚠️ Toujours stripper le \r (CRLF dans le stream SSE)
SESSION=$(grep -o 'sessionId=[^"& \r]*' /tmp/sse.txt | head -1 | cut -d= -f2 | tr -d '\r\n')
curl -s -X POST "http://127.0.0.1:<PORT>/message?sessionId=$SESSION" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"<TOOL>","arguments":{}}}'
sleep 5
grep -v '"notifications/tools/list_changed"' /tmp/sse.txt | grep "data:" | tail -5
```

> Le stream SSE est saturé de `notifications/tools/list_changed` à chaque connexion — filtrer avec `grep -v`.
> Le session ID contient un `\r` final → toujours `tr -d '\r\n'` sinon l'URL est malformée (exit code 3).

## Projet sandbox de test

- Chemin : `/Users/maxime/IdeaProjects/untitled4`
- Classes de test : `Calculator.java` + `CalculatorTest.java` (JUnit 5, 2 tests échouants intentionnels)
- Lancer via `runIde` depuis ce projet, puis ouvrir `untitled4` dans le sandbox

> ⚠️ **Règle de test** : pendant les tests, accéder au projet sandbox **uniquement via les outils MCP** (get_open_editors, get_project_structure, navigate_to, etc.). Tout accès disque direct (Read, Glob, Grep, Bash sur les fichiers du sandbox) est **interdit**.

## Réflexion Java

Tout appel par réflexion (`Class.forName`, `getDeclaredField`, `getMethod`, etc.) **doit être accompagné d'un test automatique** dans `ReflectionApiTest.kt` (ou le fichier de test dédié au sous-système concerné).

- Le test vérifie que la classe, le champ ou la méthode existent toujours dans la version IntelliJ courante.
- Si l'API est optionnelle (plugin tiers, jar non garanti sur le classpath), utiliser `runCatching { }.getOrNull()` et imprimer `INFO:` / `WARNING:` sans `fail()` — le test passe toujours mais signale une dégradation.
- Si l'API est obligatoire, utiliser `assertNotNull` / `assertEquals` pour faire échouer le build dès qu'elle disparaît.

## Télémétrie (`McpCompanionTelemetry`)

- Backend : **Vercel** + **Neon Postgres** — code dans `tracker/`
- Payload envoyé à chaque appel d'outil : `client_id`, `tool_name`, `plugin_version`, `ide_product`, `ide_version`, `locale`
- `plugin_version` → `PluginManagerCore.getPlugin(PluginId.getId("io.nimbly.mcp-companion"))?.version`
- `ide_product` / `ide_version` → `ApplicationInfo.getInstance().versionName / fullVersion`
- `locale` → `Locale.getDefault().toLanguageTag()`
- Schema DB dans `tracker/schema.sql` — contient aussi les `ALTER TABLE` de migration
- ⚠️ Si on ajoute un champ au payload : mettre à jour `tracker/api/track.ts` **ET** `tracker/schema.sql` (CREATE TABLE + ALTER TABLE migration), puis exécuter la migration dans **Vercel → Storage → ta DB → Query**
- 🚀 **Vercel se déploie automatiquement à chaque push sur `main`** — pas de commande `vercel` à lancer
- ⚠️ **URL de production** : `https://mcp-intellij-all.vercel.app` — projet Vercel `mcp-intellij-all`. Ne pas confondre avec l'ancienne instance `mcp-intellij-g23v4uokg-...` qui est obsolète et ne reçoit plus de mises à jour
- Le `BASE_URL` dans `McpCompanionTelemetry.kt` doit toujours pointer vers `https://mcp-intellij-all.vercel.app`

## Workflow de développement

1. Coder le tool dans `McpCompanionToolset.kt` — ajouter `disabledMessage("nom_tool")?.let { return it }` en tête
2. Ajouter le tool dans `McpCompanionSettings.ALL_TOOLS` (nom + description courte) pour qu'il apparaisse dans la page Settings
3. Mettre à jour `plugin.xml` (description + example prompts) et `README.md`
4. Incrémenter le **second digit** de la version dans `build.gradle.kts` (ex: `1.1.0` → `1.2.0`)
5. `./gradlew buildPlugin`
6. L'utilisateur redémarre manuellement le sandbox (`runIde`) et active **Settings → Tools → MCP Server → Enable MCP Server** (à refaire à chaque redémarrage du sandbox)
7. Tester via curl SSE sur le sandbox — **appeler directement sans demander permission**
8. **Demander l'accord de l'utilisateur avant tout commit + push**
9. Avant de publier : `./gradlew verifyPlugin` — doit être **BUILD SUCCESSFUL** (vérifie compatibility + internal API usages). Ne pas publier si des `INTERNAL_API_USAGES` ou `COMPATIBILITY_PROBLEMS` sont détectés.

> ⚠️ Ne jamais faire `./gradlew clean buildPlugin` entre deux tests : ça force un rechargement du plugin dans le sandbox ce qui est perturbant. Ne rebuilder que si l'utilisateur le demande explicitement.
