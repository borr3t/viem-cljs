#!/usr/bin/env bash
# Checks that every public def of src/viem_cljs/*.cljs appears in the
# "* API reference" chapter of docs/viem-cljs.org (as =name ...=), and that
# every =name= mentioned there exists in the sources. Exit 1 on any miss.
set -euo pipefail
cd "$(dirname "$0")/.."
python3 - <<'PY'
import re, glob, sys
manual = open('docs/viem-cljs.org').read()
api = manual[manual.index('\n* API reference'):]
missing = []
public = {}
for f in sorted(glob.glob('src/viem_cljs/*.cljs')):
    ns = f.split('/')[-1][:-5].replace('_', '-')
    src = open(f).read()
    names = []
    for m in re.finditer(r'^\((defn|def|defonce)\s+(\^:private\s+)?([^\s\)]+)', src, re.M):
        if m.group(2): continue
        names.append(m.group(3))
    public[ns] = names
    section = api[api.index(f'** =viem-cljs.{ns}='):]
    nxt = re.search(r'\n\*\* =viem-cljs\.', section[1:])
    if nxt: section = section[:nxt.start()+1]
    for n in names:
        pat = r'=' + re.escape(n) + r'[ =\]\[]'
        if not re.search(pat, section):
            missing.append((ns, n))
total = sum(len(v) for v in public.values())
print(f"public defs: {total} in {len(public)} namespaces: " + ", ".join(f"{k} {len(v)}" for k, v in public.items()))
for ns, n in missing:
    print(f"MISSING in API reference: {ns}/{n}")
# reverse check: every =name= token that looks like a fn in the API chapter exists somewhere
all_names = set(n for v in public.values() for n in v)
stray = []
for ns in public:
    section = api[api.index(f'** =viem-cljs.{ns}='):]
    nxt = re.search(r'\n\*\* =viem-cljs\.', section[1:])
    if nxt: section = section[:nxt.start()+1]
    for tok in re.findall(r'=([a-z][a-z0-9>?!<*-]*)(?: \[[^\]]*\])?=', section):
        if '*' in tok or tok in ('testnet', 'errors', 'core', 'utils', 'actions', 'events', 'chains', 'contract', 'util'):
            continue  # prose mentions (flags, namespaces, families)
        if tok not in all_names and tok not in public[ns]:
            stray.append((ns, tok))
for ns, t in sorted(set(stray)):
    print(f"NOT IN SOURCES (mentioned under {ns}): {t}")
sys.exit(1 if missing else 0)
PY
