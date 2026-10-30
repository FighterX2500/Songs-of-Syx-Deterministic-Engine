import collections, re, subprocess, sys
jfr, rec = sys.argv[1], sys.argv[2]
out = subprocess.run([jfr, "print", "--stack-depth", "96", "--events", "jdk.ExecutionSample", rec], capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
FRAME = re.compile(r"^\s+([\w$.]+)\.([\w$<>]+)\(")
samples=[]; fr=[]; ins=False
for line in out.splitlines():
    s=line.strip()
    if s.startswith("stackTrace = ["): fr=[]; ins=True
    elif ins and s.startswith("]"): ins=False; samples.append(fr)
    elif ins:
        m=FRAME.match(line)
        if m: fr.append(m.group(1).rsplit(".",1)[-1]+"."+m.group(2))
hum=[f for f in samples if "Humanoid.update" in f]
for target in ("SPathFinder.find","SFinderFindable.reserve"):
    c=collections.Counter()
    for f in hum:
        if target in f:
            i=len(f)-1-f[::-1].index(target)
            caller=next((x for x in f[i+1:] if not x.startswith(("SPathFinder","SFinder","AISUB","AIPLAN.","AI.","AIManager"))),"?")
            c[caller]+=1
    print("\ncallers of",target,"(share of citizen samples)")
    for k,n in c.most_common(10): print("  %5.1f%%  %s"%(100.0*n/len(hum),k))
