#!/usr/bin/env python3
"""Validate candidate pitch dictionaries against the collection's already-pitched units.

Gold = the ~31k {unit;accent} jrp blocks the user already accepted (mined by jidoujisho).
For each gold unit we extract (dict-form expression, dict-form reading, accepted accents).
Each candidate dict is keyed by (expression, reading) -> set of downstep positions.
We report per-dict coverage and agreement, then greedily build the best layered combo.
"""
import sqlite3, os, json, re, glob, collections

ROOT = os.path.expanduser("~/projects/anki-align")
DICTD = os.path.join(ROOT, "research/pitch-pipeline/dicts/d")
COL = os.path.join(ROOT, "collection.anki2")
SEP = "\x1f"

# ---------------------------------------------------------------- jrp parser
class Seg:
    __slots__=("text","reading")
    def __init__(self,text,reading=None):
        self.text=text; self.reading=reading

def read_until(val, idx, stop):
    chars=[]; i=idx
    while i < len(val):
        c=val[i]
        if c in stop: return i,c,"".join(chars)
        if c=="\\":
            i+=1
            if i<len(val): chars.append(val[i])
            else: raise ValueError("backslash at end")
        else: chars.append(c)
        i+=1
    return len(val),None,"".join(chars)

def parse_segment(val,start):
    sep_idx,sep_c,seg_text=read_until(val,start+1,["|","="])
    if sep_c is not None:
        end_idx,end_c,reading=read_until(val,sep_idx+1,["]"])
        if end_c is None: raise ValueError("missing ]")
        if sep_c=="=":   # [surface=dict] : surface okurigana, dict okurigana
            return end_idx+1, ("EQ", seg_text, reading)
        return end_idx+1, ("SEG", seg_text, reading)  # [kanji|yomi]
    raise ValueError("invalid segment")

def parse_unit(val,start):
    # returns (pos, expr_dict, reading_dict, is_yougen, uncertain, accent_str)
    expr_parts=[]; read_parts=[]
    pos=start+1; broke=False
    while pos < len(val):
        pos,last_c,txt=read_until(val,pos,["[",";","}"])
        if txt:
            expr_parts.append(txt); read_parts.append(txt)
        if last_c=="[":
            pos,srv=parse_segment(val,pos)
            kind=srv[0]
            if kind=="SEG":
                _,kanji,yomi=srv
                expr_parts.append(kanji); read_parts.append(yomi)
            else: # EQ surface=dict -> dict-form expression & reading use dict okurigana
                _,surface,dictok=srv
                expr_parts.append(dictok); read_parts.append(dictok)
        elif last_c in (";","}"):
            broke=True; break
    if not broke: raise ValueError("unclosed unit")
    accent_str=""; special_base=None; uncertain=False; is_yougen=False
    if val[pos]==";":
        pos+=1
        if val[pos]=="!": uncertain=True; pos+=1
        if val[pos]=="Y": is_yougen=True; pos+=1
        end_idx,end_c,accent_str=read_until(val,pos,["|","}"])
        if end_c is None: raise ValueError("unclosed")
        if end_c=="|":
            ue,ec,special_base=read_until(val,end_idx+1,["}"])
            if ec is None: raise ValueError("unclosed")
            pos=ue
        else: pos=end_idx
    expr="".join(expr_parts); reading="".join(read_parts)
    if special_base is not None: reading=special_base
    return pos+1, expr, reading, is_yougen, uncertain, accent_str

def parse_jrp(value):
    """Yield (expr, reading, is_yougen, accents:set[int]) for each {..} unit."""
    value=value.replace(" "," ")
    units=[]
    idx=0
    while idx < len(value):
        idx,c,text=read_until(value,idx,["[","{"])
        if c=="{":
            try:
                idx,expr,reading,yg,unc,acc=parse_unit(value,idx)
            except Exception:
                # skip malformed unit; advance past it
                nxt=value.find("}",idx)
                if nxt<0: break
                idx=nxt+1; continue
            accs=set()
            ok=True
            for a in acc.split(","):
                a=a.strip()
                if a=="": ok=False; break
                a=a.split("@")[0]  # take downstep, drop secondary calc
                try: accs.add(int(a))
                except: ok=False; break
            if ok and accs and expr:
                units.append((expr,reading,yg,accs))
        elif c=="[":
            # base-form segment outside unit -> skip
            try: idx,_=parse_segment(value,idx)
            except Exception: idx+=1
        else:
            break
    return units

# ---------------------------------------------------------------- gold extraction
def extract_gold():
    db=sqlite3.connect(COL)
    nt={r[0]:r[1] for r in db.execute("select id,name from notetypes")}
    # token-level multiset and type-level set, keyed by (expr,reading)
    token=collections.Counter()
    gold={}   # (expr,reading) -> (is_yougen, accepted_accents set)
    n_units=0
    for mid,flds in db.execute("select mid,flds from notes"):
        for f in flds.split(SEP):
            if "{" in f and ";" in f:
                for expr,reading,yg,accs in parse_jrp(f):
                    n_units+=1
                    k=(expr,reading)
                    token[k]+=1
                    if k in gold:
                        yg0,a0=gold[k]; a0|=accs; gold[k]=(yg0 or yg, a0)
                    else:
                        gold[k]=(yg,set(accs))
    db.close()
    return gold, token, n_units

# ---------------------------------------------------------------- dict loading
def load_dict(path):
    """Return map (expr,reading)->set[int] and reading-only map reading->set[int]."""
    m=collections.defaultdict(set)
    for bank in glob.glob(os.path.join(path,"term_meta_bank_*.json")):
        with open(bank) as fh: data=json.load(fh)
        for e in data:
            if len(e)<3 or e[1]!="pitch": continue
            term=e[0]; info=e[2]
            reading=info.get("reading",term)
            for p in info.get("pitches",[]):
                pos=p.get("position")
                if isinstance(pos,int):
                    m[(term,reading)].add(pos)
    return m

# ---------------------------------------------------------------- main
def main():
    print("Extracting gold from collection ...")
    gold,token,n_units=extract_gold()
    n_types=len(gold)
    n_tokens=sum(token.values())
    print(f"  parsed units: {n_units}   unique (expr,reading): {n_types}   tokens: {n_tokens}")
    yg_types=sum(1 for _,(y,_) in gold.items() if y)
    print(f"  yougen types: {yg_types} ({100*yg_types/n_types:.1f}%)")

    dirs=sorted([d for d in os.listdir(DICTD) if os.path.isdir(os.path.join(DICTD,d))])
    pitch_dirs=[d for d in dirs if glob.glob(os.path.join(DICTD,d,"term_meta_bank_*.json"))]
    dicts={}
    for d in pitch_dirs:
        dicts[d]=load_dict(os.path.join(DICTD,d))
        print(f"  loaded {d:32s} keys={len(dicts[d])}")

    print("\n=== PER-DICT (type-level / token-weighted) ===")
    print(f"{'dict':34s} {'cover%':>7} {'agree%':>7} {'mism%':>6}   {'tokCov%':>7} {'tokAgr%':>7}")
    # agree% = of covered, share where dict positions intersect gold accepted
    per_found={}
    for d,m in dicts.items():
        cov=agr=mis=0
        tcov=tagr=0
        for k,(yg,accs) in gold.items():
            if k in m:
                cov+=1; tcov+=token[k]
                if m[k] & accs: agr+=1; tagr+=token[k]
                else: mis+=1
        per_found[d]=set(k for k in gold if k in m)
        print(f"{d:34s} {100*cov/n_types:7.1f} {100*agr/cov if cov else 0:7.1f} {100*mis/cov if cov else 0:6.1f}   "
              f"{100*tcov/n_tokens:7.1f} {100*tagr/n_tokens:7.1f}")

    # ---- greedy best combo by incremental TYPE coverage (union of keys present) ----
    print("\n=== GREEDY COMBO (maximise coverage; agreement among covered) ===")
    chosen=[]; covered=set()
    remaining=dict(dicts)
    while remaining:
        best=None; best_gain=-1
        for d,m in remaining.items():
            gain=len(per_found[d]-covered)
            if gain>best_gain: best_gain=gain; best=d
        if best_gain<=0: break
        chosen.append(best); covered|=per_found[best]
        del remaining[best]
        # agreement of the union so far: a key counts as agreeing if ANY chosen dict agrees
        agr=0; tcov=0; tagr=0
        for k,(yg,accs) in gold.items():
            if k in covered:
                tcov+=token[k]
                hit=any((k in dicts[c]) and (dicts[c][k]&accs) for c in chosen)
                if hit: agr+=1; tagr+=token[k]
        print(f"  + {best:34s} cumCover={100*len(covered)/n_types:5.1f}%  "
              f"unionAgree={100*agr/len(covered):5.1f}%  tokCover={100*tcov/n_tokens:5.1f}%")

    # ---- pairwise: best primary, then best single fallback ----
    print("\n=== BEST PRIMARY + SINGLE FALLBACK (top combos by coverage) ===")
    results=[]
    for p in dicts:
        for f in dicts:
            if f==p: continue
            cov=per_found[p]|per_found[f]
            results.append((len(cov),p,f))
    results.sort(reverse=True)
    for n,p,f in results[:6]:
        print(f"  {p:30s} -> {f:30s}  cover={100*n/n_types:.1f}%")

if __name__=="__main__":
    main()
