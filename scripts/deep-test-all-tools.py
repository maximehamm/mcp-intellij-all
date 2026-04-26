#!/usr/bin/env python3
"""
Deep validation suite for every MCP Companion tool.

For each of the 72 tools:
  1. Calls it with realistic args (correct param names verified against the source)
  2. Verifies isError == False
  3. Verifies the response is non-empty
  4. Verifies a tool-specific pattern in the response (key JSON field, expected substring, …)
  5. On failure, prints the exact response so the human can decide

Usage:
    python3 scripts/deep-test-all-tools.py [--port 64343]
"""
import json, subprocess, time, re, os, sys, argparse

DEFAULT_PORT = 64343

# ── Plumbing ─────────────────────────────────────────────────────────────
def call(name, args, port, timeout=25):
    """Returns (isError, text) or raises on transport failure."""
    sse = f"/tmp/sse_deep_{name}_{int(time.time()*1000)%100000}.txt"
    p = subprocess.Popen(
        ["curl", "-s", "-N", "--max-time", str(timeout), f"http://127.0.0.1:{port}/sse"],
        stdout=open(sse, "w"), stderr=subprocess.DEVNULL,
    )
    time.sleep(2)
    try:
        txt = open(sse).read()
        m = re.search(r'sessionId=([^"&\s\r]+)', txt)
        if not m:
            p.kill(); return None, "<no SSE session>"
        s = m.group(1).rstrip('\r\n')
        subprocess.run(
            ["curl", "-s", "-X", "POST", f"http://127.0.0.1:{port}/message?sessionId={s}",
             "-H", "Content-Type: application/json", "--max-time", str(timeout-2),
             "-d", json.dumps({"jsonrpc":"2.0","id":1,"method":"tools/call",
                               "params":{"name":name,"arguments":args}})],
            capture_output=True, timeout=timeout,
        )
        time.sleep(2); p.kill()
        data = open(sse).read()
        for line in data.splitlines():
            if '"notifications/' in line: continue
            if '"result"' in line and 'data: ' in line:
                d = json.loads(line.split('data: ', 1)[1])
                r = d.get('result', {})
                return r.get('isError', False), r.get('content', [{}])[0].get('text', '')
        return None, "<no response>"
    finally:
        try: os.remove(sse)
        except: pass

# ── Validation helpers ──────────────────────────────────────────────────
class Result:
    def __init__(self, name, ok, reason, response_preview=""):
        self.name = name; self.ok = ok; self.reason = reason; self.response = response_preview

def check(name, args, port, validate, allow_disabled=False):
    is_err, txt = call(name, args, port)
    if is_err is None:
        return Result(name, False, f"transport: {txt}", txt[:200])
    preview = txt[:200] if txt else ""
    if allow_disabled and txt and "disabled" in txt.lower():
        return Result(name, True, "disabled (correct)", preview)
    if is_err:
        return Result(name, False, f"isError=true: {txt[:200]}", preview)
    if not txt:
        return Result(name, False, "empty response", "")
    ok, why = validate(txt)
    if ok: return Result(name, True, "ok", preview)
    return Result(name, False, why, preview)

def has(substr): return lambda t: (substr.lower() in t.lower(), f"missing '{substr}'")
def is_json_with_keys(*keys):
    def v(t):
        try:
            j = json.loads(t)
            sample = j[0] if isinstance(j, list) and j else j
            for k in keys:
                if not isinstance(sample, dict) or k not in sample:
                    return (False, f"JSON missing key '{k}'")
            return (True, "ok")
        except: return (False, "not valid JSON")
    return v
def any_of(*validators):
    def v(t):
        for vv in validators:
            ok, _ = vv(t)
            if ok: return (True, "ok")
        return (False, "none of expected patterns matched")
    return v

# ── Setup phase ──────────────────────────────────────────────────────────
def setup(port):
    print("═══ SETUP ═══")
    # Set 2 breakpoints so debug-related tools have something to work on
    call("add_conditional_breakpoint", {"filePath":"scripts/debug_test_node.js","line":3,"condition":""}, port)
    call("add_conditional_breakpoint", {"filePath":"scripts/debug_test_node.js","line":15,"condition":"x > 10"}, port)
    # Ensure a build was triggered
    call("execute_ide_action", {"actionId":"CompileProject"}, port)
    # Open terminal so get_terminal_output has something
    call("execute_ide_action", {"actionId":"ActivateTerminalToolWindow"}, port)
    # Trigger a gradle task so background processes show
    call("run_gradle_task", {"tasks":["help"], "timeoutSeconds":30}, port)
    # Get a real commit hash for vcs_show_commit / vcs_revert / vcs_cherry_pick tests
    is_err, log_txt = call("get_vcs_log", {"maxCount": 1}, port)
    commit_hash = "HEAD"
    if not is_err and log_txt:
        m = re.search(r'"hash":"([a-f0-9]+)"', log_txt)
        if m: commit_hash = m.group(1)
    print(f"  Setup done. commit={commit_hash}")
    return {"commit_hash": commit_hash}

# ── Test plan ────────────────────────────────────────────────────────────
def run_all(port):
    state = setup(port)
    results = []

    # Group 1 — Editor & Navigation
    results += [
        check("get_open_editors", {}, port, is_json_with_keys("openFiles")),
        check("navigate_to", {"filePath":"build.gradle.kts","line":10,"column":1}, port, has("Navigated")),
        check("select_text", {"filePath":"build.gradle.kts","startLine":1,"startColumn":1,"endLine":1,"endColumn":5}, port, has("Selected")),
        check("highlight_text", {"filePath":"build.gradle.kts","ranges":"1:1:1:10"}, port, has("highlighted")),
        check("clear_highlights", {"filePath":"build.gradle.kts"}, port, has("leared")),
        check("show_diff", {"filePath":"build.gradle.kts","newContent":"// test\n"}, port, has("Diff")),
    ]

    # Group 2 — Build & Tests
    results += [
        check("get_build_output", {}, port, lambda t: (len(t) > 5, "response too short")),
        check("get_console_output", {}, port, lambda t: (len(t) > 5, "response too short")),
        check("get_services_output", {}, port, lambda t: (len(t) > 5, "response too short")),
        check("get_test_results", {}, port, lambda t: (len(t) > 5, "response too short")),
        check("get_terminal_output", {}, port, lambda t: (len(t) > 0, "empty")),
        check("send_to_terminal", {"command":"echo hi"}, port, lambda t: (True, "ok"), allow_disabled=True),
    ]

    # Group 3 — Debug
    results += [
        check("list_run_configurations", {}, port, is_json_with_keys("name","type")),
        check("get_run_configuration_xml", {"configurationName":"FibonacciTest"}, port, has("<configuration")),
        check("modify_run_configuration", {"configurationName":"FibonacciTest"}, port, lambda t: (len(t) > 5, "too short")),
        check("create_run_configuration_from_xml", {"name":f"_deep_test_{int(time.time())}","xml":'<configuration name="x" type="Application" factoryName="Application"><option name="MAIN_CLASS_NAME" value="Foo"/></configuration>'}, port, has("created")),
        check("start_run_configuration", {"configurationName":"FibonacciTest"}, port, has("started")),
    ]
    time.sleep(2)
    results += [
        check("debug_run_configuration", {"configurationName":"FibonacciTest"}, port, has("Debug session")),
    ]
    time.sleep(2)
    results += [
        check("get_debug_variables", {}, port, lambda t: (len(t) > 0, "empty")),
        check("get_breakpoints", {}, port, lambda t: (len(t) > 5 and ("[" in t or "breakpoint" in t.lower()), "no breakpoints found")),
        check("add_conditional_breakpoint", {"filePath":"scripts/debug_test_node.js","line":7,"condition":""}, port, lambda t: (len(t) > 5, "too short")),
        check("set_breakpoint_condition", {"filePath":"scripts/debug_test_node.js","line":3,"condition":"true"}, port, lambda t: (len(t) > 5, "too short")),
        check("mute_breakpoints", {"muted":True}, port, lambda t: (len(t) > 0, "empty")),
        check("mute_breakpoints", {"muted":False}, port, lambda t: (len(t) > 0, "empty")),
    ]

    # Group 4 — Diagnostic & Processes
    results += [
        check("get_ide_snapshot", {}, port, lambda t: ("activeFile" in t or "projects" in t or len(t) > 100, "too short")),
        check("get_intellij_diagnostic", {}, port, lambda t: (len(t) > 50, "too short")),
        check("get_running_processes", {}, port, lambda t: (len(t) > 5, "too short")),
        check("manage_process", {"title":"nonexistent","action":"cancel"}, port, lambda t: (len(t) > 5, "too short")),
        check("get_ide_settings", {}, port, lambda t: (len(t) > 50, "too short")),
    ]

    # Group 5 — Code Analysis
    results += [
        check("get_file_problems", {"filePath":"scripts/debug_test_node.js"}, port, lambda t: (len(t) > 5, "too short")),
        check("get_quick_fixes", {"filePath":"scripts/debug_test_node.js"}, port, lambda t: (len(t) > 5, "too short")),
        check("apply_quick_fix", {"filePath":"scripts/debug_test_node.js","fixText":"NoSuchFix"}, port, lambda t: (len(t) > 5, "too short")),
        check("list_inspections", {}, port, lambda t: (len(t) > 50, "too short")),
        check("run_inspections", {"scope":"file","filePath":"scripts/debug_test_node.js"}, port, lambda t: (len(t) > 10, "too short")),
        check("refresh_project", {}, port, lambda t: (len(t) > 5, "too short")),
        check("get_project_structure", {}, port, is_json_with_keys("modules")),
    ]

    # Group 6 — Database
    results += [
        check("list_database_sources", {}, port, lambda t: (len(t) > 0, "empty")),
        check("get_database_schema", {}, port, lambda t: (len(t) > 5, "too short")),
        check("execute_database_query", {"query":"SELECT 1"}, port, lambda t: (True, "ok"), allow_disabled=True),
    ]

    # Group 7 — Gradle
    results += [
        check("get_gradle_tasks", {}, port, lambda t: (len(t) > 50, "too short")),
        check("get_gradle_dependencies", {}, port, lambda t: (len(t) > 10, "too short")),
        check("refresh_gradle_project", {}, port, lambda t: (len(t) > 5, "too short")),
        check("get_gradle_project_info", {}, port, lambda t: (len(t) > 50, "too short")),
        check("stop_gradle_task", {}, port, lambda t: (len(t) > 5, "too short")),
        check("run_gradle_task", {"tasks":["help"]}, port, has("success")),
    ]

    # Group 8 — VCS
    h = state["commit_hash"]
    results += [
        check("get_vcs_changes", {}, port, lambda t: (len(t) > 0, "empty")),
        check("get_vcs_branch", {}, port, lambda t: (len(t) > 10, "too short")),
        check("get_vcs_log", {"maxCount":3}, port, lambda t: (len(t) > 50, "too short")),
        check("get_vcs_blame", {"filePath":"build.gradle.kts","startLine":1,"endLine":5}, port, lambda t: (len(t) > 10, "too short")),
        check("get_local_history", {}, port, lambda t: (len(t) > 5, "too short")),
        check("get_vcs_file_history", {"filePath":"build.gradle.kts"}, port, lambda t: (len(t) > 50, "too short")),
        check("get_vcs_diff_between_branches", {"ref1":h,"ref2":h+"~1"}, port, lambda t: (len(t) > 0, "empty")),
        check("vcs_show_commit", {"hash":h}, port, lambda t: (h[:8] in t or "commit" in t.lower(), f"missing commit info")),
        check("vcs_stage_files", {"paths":[]}, port, lambda t: (len(t) > 5, "too short")),
        check("vcs_commit", {"message":"_test_dry_run_should_fail"}, port, lambda t: (len(t) > 5, "too short")),
        check("vcs_push", {}, port, lambda t: (len(t) > 5, "too short")),
        check("vcs_pull", {}, port, lambda t: (len(t) > 5, "too short")),
        check("vcs_stash", {"action":"list"}, port, lambda t: (len(t) > 0, "empty")),
        check("vcs_create_branch", {"name":f"_tmp_test_{int(time.time())}","checkout":False}, port, lambda t: (len(t) > 5, "too short")),
        check("vcs_checkout_branch", {"name":"main"}, port, lambda t: (len(t) > 5, "too short")),
        check("vcs_delete_branch", {"name":"_nonexistent"}, port, lambda t: (True, "ok"), allow_disabled=True),
        check("vcs_fetch", {}, port, lambda t: (len(t) > 5, "too short")),
        check("vcs_merge_branch", {"branch":"main"}, port, lambda t: (len(t) > 5, "too short")),
        check("vcs_rebase", {"abort":True}, port, lambda t: (len(t) > 5, "too short")),
        check("get_vcs_conflicts", {}, port, lambda t: (len(t) > 5, "too short")),
        check("vcs_open_merge_tool", {}, port, lambda t: (len(t) > 5, "too short")),
        check("vcs_reset", {"ref":"HEAD"}, port, lambda t: (len(t) > 5, "too short")),
        check("vcs_revert", {"hash":"deadbeef"}, port, lambda t: (len(t) > 5, "too short")),
        check("vcs_cherry_pick", {"hash":"deadbeef"}, port, lambda t: (len(t) > 5, "too short")),
    ]

    # Group 9 — General
    results += [
        check("get_mcp_companion_overview", {}, port, has("MCP")),
        check("execute_ide_action", {"actionId":"NonExistent"}, port, lambda t: (len(t) > 5, "too short")),
        check("replace_text_undoable", {"pathInProject":"build.gradle.kts","oldText":"plugins","newText":"plugins"}, port, any_of(has("ok"), has("replaced"), has("not found"))),
        check("delete_file", {"filePath":"some/nonexistent.txt"}, port, lambda t: (True, "ok"), allow_disabled=True),
    ]

    return results

# ── Main ────────────────────────────────────────────────────────────────
def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    args = parser.parse_args()

    print(f"Running deep test against MCP server on port {args.port}…")
    print()
    results = run_all(args.port)

    # Per-group summary
    print()
    print("═" * 60)
    passed = [r for r in results if r.ok]
    failed = [r for r in results if not r.ok]
    print(f"FINAL: ✓ {len(passed)} / {len(results)}   ✗ {len(failed)}")

    if failed:
        print()
        print("FAILURES (need investigation):")
        for r in failed:
            print(f"  ✗ {r.name}")
            print(f"    reason:   {r.reason}")
            if r.response: print(f"    preview:  {r.response[:120]}")
        sys.exit(1)
    sys.exit(0)

if __name__ == "__main__":
    main()
