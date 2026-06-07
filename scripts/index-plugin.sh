#!/bin/bash
# ItemForge Plugin Index Generator
# Regenerates all .index/ files from Kotlin source using bulk awk pipelines.
#
# Usage: bash scripts/index-plugin.sh

export LC_ALL=en_US.UTF-8
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SRC_DIR="$PROJECT_DIR/src/main/kotlin"
INDEX_DIR="$PROJECT_DIR/.index"
BASE_PKG="me/itemforge"
BASE_PKG_DOT="me.itemforge"

TIMESTAMP=$(date '+%Y-%m-%d %H:%M:%S')
FILE_COUNT=$(find "$SRC_DIR" -name "*.kt" | wc -l | tr -d ' ')
HEADER="Generated: $TIMESTAMP ($FILE_COUNT source files)"

mkdir -p "$INDEX_DIR"
echo "Indexing $FILE_COUNT Kotlin source files..."

filelist() { find "$SRC_DIR" -name "*.kt" -type f | sort; }

# ── 1. CLASS_INDEX.txt ──────────────────────────
echo "  [1/6] CLASS_INDEX.txt"
{
    echo "# $HEADER"
    filelist | awk -v src="$SRC_DIR/" '
    {
        file=$0; rp=file; sub(src,"",rp)
        while((getline l<file)>0){
            if(l~/^[[:space:]]*[*\/]/)continue
            if(l~/\/\//)continue
            if(match(l,/(class|object|interface)[[:space:]]+([A-Za-z_][A-Za-z0-9_]*)/,m))
                if(m[2]!=""&&m[2]!="companion")printf "%s\t%s\n",m[2],rp
        }
        close(file)
    }' | sort -t$'\t' -k1,1 -u
} > "$INDEX_DIR/CLASS_INDEX.txt"
echo "       -> $(grep -c $'\t' "$INDEX_DIR/CLASS_INDEX.txt" || echo 0) classes"

# ── 2. METHOD_INDEX.txt ─────────────────────────
echo "  [2/6] METHOD_INDEX.txt"
{
    echo "# $HEADER"
    filelist | awk -v src="$SRC_DIR/" '
    {
        file=$0; rp=file; sub(src,"",rp)
        n=split(file,p,"/"); cn=p[n]; sub(/\.kt$/,"",cn)
        nr=0
        while((getline l<file)>0){
            nr++
            if(l~/^[[:space:]]*[*\/]/)continue
            if(l~/\/\//)continue
            if(l~/(class|object|interface)[[:space:]]/)continue
            if(l~/[[:space:]]fun[[:space:]]/||l~/^fun[[:space:]]/){
                gsub(/[[:space:]]+/," ",l)
                if(match(l,/fun[[:space:]]+([a-zA-Z_][a-zA-Z0-9_]*)/,m)){
                    nm=m[1]
                    if(nm!="")printf "%s\t%s\t%s:%d\n",nm,cn,rp,nr
                }
            }
        }
        close(file)
    }' | sort -t$'\t' -k1,1
} > "$INDEX_DIR/METHOD_INDEX.txt"
echo "       -> $(grep -c $'\t' "$INDEX_DIR/METHOD_INDEX.txt" || echo 0) methods"

# ── 3. IMPORT_MAP.txt ───────────────────────────
echo "  [3/6] IMPORT_MAP.txt"
{
    echo "# $HEADER"
    filelist | awk '
    {
        file=$0; pkg=""
        while((getline l<file)>0){
            if(l~/^package /){pkg=l; sub(/^package[[:space:]]+/,"",pkg); gsub(/[[:space:]]*$/,"",pkg); break}
        }
        close(file)
        if(pkg=="")next
        n=split(file,p,"/"); cn=p[n]; sub(/\.kt$/,"",cn)
        printf "%s\t%s.%s\n",cn,pkg,cn
    }' | sort -t$'\t' -k1,1
} > "$INDEX_DIR/IMPORT_MAP.txt"
echo "       -> $(grep -c $'\t' "$INDEX_DIR/IMPORT_MAP.txt" || echo 0) imports"

# ── 4. PACKAGE_MAP.md ───────────────────────────
echo "  [4/6] PACKAGE_MAP.md"
{
    echo "# ItemForge Package Map"
    echo "# $HEADER"
    echo ""
    echo "| Package | Files |"
    echo "|---------|-------|"
    filelist | awk -v src="$SRC_DIR/$BASE_PKG/" '
    {
        f=$0; sub(src,"",f); sub(/\/[^\/]*$/,"",f)
        if(f==src)f="(root)"
        print f
    }' | sort | uniq -c | sort -rn | while read -r count pkg; do
        printf '| `%s` | %s |\n' "$pkg" "$count"
    done
} > "$INDEX_DIR/PACKAGE_MAP.md"

# ── 5. API_SURFACE.md ───────────────────────────
echo "  [5/6] API_SURFACE.md"
{
    echo "# ItemForge Plugin API Surface"
    echo "# $HEADER"
    filelist | awk -v src="$SRC_DIR/" '
    {
        file=$0; rp=file; sub(src,"",rp)
        n=split(file,p,"/"); cn=p[n]; sub(/\.kt$/,"",cn)
        sc=0; delete sigs; cdecl=""
        while((getline l<file)>0){
            if(l~/^[[:space:]]*[*\/]/)continue
            # Class/object/interface declarations (not private/internal at top level)
            if(l~/(class|object|interface)[[:space:]]/&&l!~/private[[:space:]]/) {
                if(cdecl==""){
                    cdecl=l; gsub(/^[[:space:]]+/,"",cdecl); sub(/[[:space:]]*\{.*/,"",cdecl)
                }
            }
            # Function declarations (public or internal, not private)
            if((l~/[[:space:]]fun[[:space:]]/||l~/^fun[[:space:]]/)&&l!~/private[[:space:]]/){
                s=l; gsub(/^[[:space:]]+/,"",s); sub(/[[:space:]]*\{.*/,"",s)
                sc++; sigs[sc]=s
            }
        }
        close(file)
        if(sc<1&&cdecl=="")next
        printf "\n## %s\nFile: `%s`\n```kotlin\n",cn,rp
        if(cdecl!="")print cdecl"\n"
        for(i=1;i<=sc;i++)print sigs[i]
        print "```"
    }'
} > "$INDEX_DIR/API_SURFACE.md"
echo "       -> $(wc -c < "$INDEX_DIR/API_SURFACE.md" | tr -d ' ')B"

# ── 6. HYTALE_API_USED.md ──────────────────────
HYTALE_DECOMPILED="$PROJECT_DIR/../APIReference/decompiled-full"
if [ -d "$HYTALE_DECOMPILED/com/hypixel/hytale" ]; then
    echo "  [6/6] HYTALE_API_USED.md"

    hytale_imports=$(mktemp)
    grep -rh "^import com\.hypixel\.hytale\." "$SRC_DIR" 2>/dev/null | \
        sed 's/^import //' | sed 's/[[:space:]]*$//' | sort -u > "$hytale_imports"
    import_count=$(wc -l < "$hytale_imports" | tr -d ' ')

    {
        echo "# Hytale API Surface (Used by ItemForge)"
        echo "# Regenerate with: bash scripts/index-plugin.sh"
        echo "# $HEADER ($import_count Hytale classes imported)"
        echo '#   grep "^## ClassName" HYTALE_API_USED.md'
        echo ""
        echo "---"

        awk -v dd="$HYTALE_DECOMPILED" '
        {
            fqcn=$0
            path=fqcn; gsub(/\./,"/",path)
            filepath=dd"/"path".java"
            n=split(fqcn,parts,"."); classname=parts[n]
            if(n>4)top_pkg=parts[4]; else top_pkg="(root)"
            if(top_pkg!=prev_top){printf "\n# %s\n",top_pkg; prev_top=top_pkg}
            printf "\n## %s\nImport: `%s`\n",classname,fqcn
            printf "```java\n"
            found=0
            while((getline line<filepath)>0){
                if(line~/^[[:space:]]*public[[:space:]]/){
                    found=1
                    if(line~/(class|interface|enum|record)[[:space:]]/){
                        sig=line; gsub(/^[[:space:]]+/,"",sig); sub(/[[:space:]]*\{.*/,"",sig)
                        print sig"\n"
                    } else if(line~/\(/){
                        sig=line; gsub(/^[[:space:]]+/,"",sig); sub(/[[:space:]]*\{.*/,"",sig); sub(/[[:space:]]*;.*/,"",sig)
                        print sig
                    }
                }
            }
            close(filepath)
            if(!found)print "// (no public members or file not found)"
            printf "```\n"
        }' "$hytale_imports"
    } > "$INDEX_DIR/HYTALE_API_USED.md"

    echo "       -> $import_count classes, $(wc -c < "$INDEX_DIR/HYTALE_API_USED.md" | tr -d ' ')B"
    rm -f "$hytale_imports"
else
    echo "  [6/6] HYTALE_API_USED.md — skipped (no decompiled source at $HYTALE_DECOMPILED)"
fi

echo ""
echo "Done. 6 indexes for $FILE_COUNT files in .index/"
