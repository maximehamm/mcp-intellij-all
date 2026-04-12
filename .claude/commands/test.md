Teste un tool MCP dans le sandbox IntelliJ (lancé via `runIde`).

Argument reçu : `$ARGUMENTS`

## Étape 1 — Port du sandbox

Le port MCP SSE du sandbox est **toujours 64343**. Ne pas le chercher, l'utiliser directement.

```
PORT=64343
```

## Étape 2 — Déterminer le tool à appeler et ses arguments

Si `$ARGUMENTS` ressemble à un nom de tool snake_case (ex: `get_open_editors`, `get_vcs_branch`) :
- Utilise ce nom directement.
- Les arguments JSON seront `{}` sauf si l'utilisateur en a fourni (format: `tool_name {"param": "val"}`).

Si `$ARGUMENTS` est un prompt en langage naturel (ex: "liste les fichiers ouverts", "montre les erreurs") :
- Choisis le tool le plus adapté parmi notre liste (lis `/Users/maxime/Development/mcp-intellij-all/src/main/kotlin/io/nimbly/mcpcompanion/McpCompanionSettings.kt` si besoin).
- Détermine les arguments appropriés.
- Indique à l'utilisateur quel tool tu as choisi et pourquoi.

## Étape 3 — Appel SSE

```bash
PORT=<port_trouvé>
TOOL=<nom_du_tool>
ARGS=<json_arguments>  # ex: {} ou {"path": "src/Main.kt"}

curl -s -N --max-time 20 http://127.0.0.1:$PORT/sse > /tmp/sse_test.txt &
sleep 2
SESSION=$(grep -o 'sessionId=[^"& \r]*' /tmp/sse_test.txt | head -1 | cut -d= -f2 | tr -d '\r\n')
curl -s -X POST "http://127.0.0.1:$PORT/message?sessionId=$SESSION" \
  -H "Content-Type: application/json" \
  -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"$TOOL\",\"arguments\":$ARGS}}"
sleep 5
kill %1 2>/dev/null
grep -v '"notifications/tools/list_changed"' /tmp/sse_test.txt | grep '"result"' | tail -3
```

## Étape 4 — Afficher le résultat

Parse la réponse JSON et affiche le contenu de `result.content[0].text` de manière lisible.

Si le tool retourne une erreur, affiche le message d'erreur clairement et suggère des pistes (tool désactivé dans Settings ? sandbox pas démarré ? mauvais port ?).
