Affiche la liste complète de tous nos tools MCP, groupés par catégorie, tels que définis dans le code source du plugin.

Lis le fichier `/Users/maxime/Development/mcp-intellij-all/src/main/kotlin/io/nimbly/mcpcompanion/McpCompanionSettings.kt` pour obtenir les groupes et les noms de tools.

Pour chaque tool, indique aussi :
- ⚠️ si le tool est désactivé par défaut (set `DISABLED_BY_DEFAULT`)
- 🗄️ si le tool nécessite un plugin spécifique (map `TOOL_REQUIRED_PLUGIN`)

Formate la sortie comme un tableau Markdown par groupe, avec les colonnes : `Tool` | `Statut`.

Exemple de statut : `✅ actif`, `⚠️ désactivé par défaut`, `🗄️ nécessite Database plugin`.
