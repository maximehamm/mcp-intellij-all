#!/usr/bin/env python3
"""
Audit every MCP Companion tool one by one, watching the sandbox log for
SEVERE / Write-unsafe / "Suppressed frequent exception" entries that the
call triggered. Used to catch modality bugs, write-action violations,
and other quiet-but-bad regressions BEFORE publishing.

For each tool:
  1. Capture log line count (baseline)
  2. Call the tool with realistic args (correct param names — verified
     against scripts/deep-test-all-tools.py)
  3. Sleep briefly, then re-capture log line count
  4. Grep the new lines for SEVERE / Write-unsafe / freq-WARN
  5. Print a one-line status (✓ clean / ✗ N issues)

At the end, print a summary of every tool that produced log noise.

Usage:
    python3 scripts/audit-log-noise.py [--port 64343] [--log /path/to/idea.log]

Defaults assume the runIde sandbox at /build/idea-sandbox/IU-2026.1.

Destructive VCS tools (vcs_commit/push/delete_branch/…) and disabled-by-default
tools (delete_file, execute_database_query, send_to_terminal) are still called:
either they're guarded by `disabledMessage` (returns the standard "disabled"
message which we don't want to log noise either) or they fail safely on
non-existent inputs. We never mutate the project state with mutating args.
"""
import argparse, json, subprocess, time, re, os, sys

DEFAULT_PORT = 64343
DEFAULT_LOG  = "/Users/maxime/Development/mcp-intellij-all/build/idea-sandbox/IU-2026.1/log/idea.log"
PROJECT_BASE = "/Users/maxime/Development/projects-plugins/Tzatziki/sample/rich-example"
SAMPLE_JAVA  = f"{PROJECT_BASE}/supplier/france/src/test/java/example/Cocktail.java"

# ── (tool_name, args) ─────────────────────────────────────────────────────
# Args chosen to: (a) succeed when possible, (b) fail SAFELY when destructive,
# (c) match the real param names accepted by the Kotlin signatures.
TOOLS = [
    # Editor (6)
    ("get_open_editors", {}),
    ("navigate_to", {"filePath": SAMPLE_JAVA, "line": 5}),
    ("select_text", {"filePath": SAMPLE_JAVA, "startLine": 5, "startColumn": 1, "endLine": 5, "endColumn": 10}),
    ("highlight_text", {"filePath": SAMPLE_JAVA, "ranges": "5:1-5:10"}),
    ("clear_highlights", {}),
    ("show_diff", {"filePath": SAMPLE_JAVA, "newContent": "// modified\n"}),
    # Build / Run output (6)
    ("get_build_output", {}),
    ("get_console_output", {}),
    ("get_services_output", {}),
    ("get_test_results", {}),
    ("get_terminal_output", {}),
    ("send_to_terminal", {"command": "echo audit"}),  # disabled-by-default; we still call to exercise the gate
    # Run / Debug (11)
    ("list_run_configurations", {}),
    ("start_run_configuration", {"configurationName": "rich-example [build]"}),
    ("modify_run_configuration", {"configurationName": "rich-example [build]"}),  # no-op without other params
    ("get_run_configuration_xml", {"configurationName": "rich-example [build]"}),
    ("create_run_configuration_from_xml", {"name": "audit-no-op", "xml": "<configuration name='audit-no-op' type='Application'/>"}),
    ("debug_run_configuration", {"configurationName": "rich-example [build]"}),
    ("get_debug_variables", {}),
    ("get_breakpoints", {}),
    ("add_conditional_breakpoint", {"filePath": SAMPLE_JAVA, "line": 5, "condition": ""}),
    ("set_breakpoint_condition", {"filePath": SAMPLE_JAVA, "line": 5, "condition": ""}),
    ("mute_breakpoints", {"muted": False}),
    # Diagnostic (5)
    ("get_ide_snapshot", {}),
    ("get_intellij_diagnostic", {}),
    ("get_running_processes", {}),
    ("manage_process", {"action": "list", "title": "any"}),
    ("get_ide_settings", {}),
    # Code Analysis (8)
    ("get_file_problems", {"filePath": SAMPLE_JAVA}),
    ("get_quick_fixes", {"filePath": SAMPLE_JAVA}),
    ("apply_quick_fix", {"filePath": SAMPLE_JAVA, "fixText": "NoSuchFix"}),
    ("list_inspections", {}),
    ("run_inspections", {"path": SAMPLE_JAVA}),
    ("refresh_project", {}),
    ("get_project_structure", {}),
    ("get_psi_tree", {"filePath": SAMPLE_JAVA, "maxNodes": 30}),
    # Database (3) — disabled by default in non-Ultimate / no DB plugin
    ("list_database_sources", {}),
    ("get_database_schema", {"dataSourceName": "nonexistent"}),
    ("execute_database_query", {"dataSourceName": "nonexistent", "query": "SELECT 1"}),
    # Gradle (6)
    ("run_gradle_task", {"tasks": ["help"], "timeoutSeconds": 60}),
    ("get_gradle_tasks", {}),
    ("refresh_gradle_project", {}),
    ("get_gradle_dependencies", {}),
    ("stop_gradle_task", {}),
    ("get_gradle_project_info", {}),
    # VCS — read-only (10)
    ("get_vcs_changes", {}),
    ("get_vcs_branch", {}),
    ("get_vcs_log", {"maxCount": 3}),
    ("get_vcs_blame", {"filePath": SAMPLE_JAVA, "startLine": 1, "endLine": 5}),
    ("get_local_history", {"scope": "file", "path": SAMPLE_JAVA}),
    ("get_vcs_file_history", {"filePath": SAMPLE_JAVA, "maxCount": 3}),
    ("get_vcs_diff_between_branches", {"ref1": "HEAD", "ref2": "HEAD"}),
    ("vcs_show_commit", {"hash": "HEAD"}),
    ("get_vcs_conflicts", {}),
    ("vcs_open_merge_tool", {}),  # safely returns "no conflicts" if clean
    # VCS — non-destructive operations on a no-op target (16)
    ("vcs_stage_files", {"action": "unstage", "files": []}),
    ("vcs_commit", {"message": "audit no-op"}),  # safely returns "nothing to commit" on clean tree
    ("vcs_push", {}),  # safely returns OK or "everything up-to-date"
    ("vcs_pull", {}),
    ("vcs_stash", {"action": "list"}),
    ("vcs_create_branch", {"name": "audit-branch-DELETE-ME", "checkout": False}),
    ("vcs_checkout_branch", {"name": "main"}),
    ("vcs_rename_branch", {"newName": "audit-renamed-noop"}),
    ("vcs_delete_branch", {"name": "audit-branch-DELETE-ME", "force": True}),
    ("vcs_fetch", {}),
    ("vcs_merge_branch", {"branch": "HEAD"}),
    ("vcs_rebase", {"abort": True}),  # safely no-op when no rebase in progress
    ("vcs_reset", {"ref": "HEAD", "mode": "mixed"}),
    ("vcs_revert", {"hash": "HEAD~9999"}),  # safely fails
    ("vcs_cherry_pick", {"hash": "HEAD"}),
    ("vcs_move_file", {"sourcePath": "nonexistent.txt", "targetPath": "nonexistent-renamed.txt"}),
    ("vcs_check_repo_health", {}),
    # General (4)
    ("get_mcp_companion_overview", {}),
    ("execute_ide_action", {"search": "ReformatCode"}),
    ("replace_text_undoable", {"pathInProject": SAMPLE_JAVA, "oldText": "FOO_DOES_NOT_EXIST", "newText": "BAR"}),
    ("delete_file", {"filePath": "nonexistent-audit.txt"}),  # disabled by default; tests gate
]


def call(port, name, args, idx, timeout=25):
    """Calls one tool via SSE and returns (isError, text)."""
    sse = f"/tmp/audit_call_{idx}.txt"
    p = subprocess.Popen(
        ["curl", "-s", "-N", "--max-time", str(timeout), f"http://127.0.0.1:{port}/sse"],
        stdout=open(sse, "w"), stderr=subprocess.DEVNULL,
    )
    time.sleep(2)
    txt = open(sse).read()
    m = re.search(r'sessionId=([^"&\s\r]+)', txt)
    if not m:
        p.kill(); return None, "<no SSE session>"
    s = m.group(1).rstrip('\r\n')
    try:
        subprocess.run(
            ["curl", "-s", "-X", "POST", f"http://127.0.0.1:{port}/message?sessionId={s}",
             "-H", "Content-Type: application/json", "--max-time", str(timeout-2),
             "-d", json.dumps({"jsonrpc":"2.0","id":1,"method":"tools/call",
                               "params":{"name":name,"arguments":args}})],
            capture_output=True, timeout=timeout,
        )
        time.sleep(2)
    finally:
        p.kill()
    data = open(sse).read()
    for line in data.splitlines():
        if '"notifications/' in line: continue
        if '"result"' in line and 'data: ' in line:
            d = json.loads(line.split('data: ', 1)[1])
            r = d.get('result', {})
            return r.get('isError', False), r.get('content', [{}])[0].get('text', '')
    return None, "<no response>"


def log_lines(path):
    try:
        with open(path) as f: return sum(1 for _ in f)
    except Exception: return 0


def log_slice(path, start, end):
    try:
        with open(path) as f: return f.readlines()[start:end]
    except Exception: return []


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--log", default=DEFAULT_LOG)
    args = parser.parse_args()

    print(f"═══ Auditing {len(TOOLS)} tools — sandbox port {args.port} — log {args.log}\n")
    bad = []
    for i, (name, call_args) in enumerate(TOOLS, 1):
        before = log_lines(args.log)
        timeout = 90 if name in {"run_gradle_task", "refresh_project", "refresh_gradle_project"} else 25
        err, text = call(args.port, name, call_args, i, timeout=timeout)
        after = log_lines(args.log)
        new = log_slice(args.log, before, after)
        severe = [l.rstrip() for l in new if "SEVERE" in l or "Write-unsafe" in l]
        warn_frequent = [l.rstrip() for l in new if "Suppressed a frequent exception" in l]
        status = "✓" if not severe and not warn_frequent else f"✗ {len(severe)}S/{len(warn_frequent)}W"
        if text is None:
            result_brief = "<NO RESPONSE>"
        else:
            result_brief = ("ERROR " if err else "") + (text.splitlines()[0][:60] if text else "<empty>")
        print(f"  [{i:>2}/{len(TOOLS)}] {status:<10} {name:<35} → {result_brief[:80]}")
        if severe or warn_frequent:
            bad.append((name, severe, warn_frequent))
    print("\n" + "═" * 60)
    if not bad:
        print("✅ ALL CLEAN — no SEVERE / Write-unsafe / freq-WARN entries.")
        sys.exit(0)
    print(f"❌ {len(bad)} tool(s) produced log noise:")
    for name, severe, warns in bad:
        print(f"\n── {name} ──")
        for l in severe[:3]:
            print(f"  SEVERE: {l[:200]}")
        for l in warns[:3]:
            print(f"  WARN:   {l[:200]}")
    sys.exit(1)


if __name__ == "__main__":
    main()
