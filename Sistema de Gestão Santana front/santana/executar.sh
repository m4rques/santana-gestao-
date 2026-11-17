#!/usr/bin/env bash
set -euo pipefail

modo="${1:-servidor}"
case "$modo" in
    servidor) entrada=Main ;;
    teste) entrada=Testes ;;
    teste-integracao) entrada=TestesIntegracao ;;
    *)
        printf 'Uso: bash executar.sh [servidor|teste|teste-integracao]\n' >&2
        exit 2
        ;;
esac

if [[ -n "${JAVA_HOME:-}" ]]; then
    java="$JAVA_HOME/bin/java"
    javac="$JAVA_HOME/bin/javac"
else
    java=java
    javac=javac
fi
if ! command -v "$java" >/dev/null || ! command -v "$javac" >/dev/null; then
    printf 'Use um JDK 17 ou superior. Defina JAVA_HOME ou inclua java e javac no PATH.\n' >&2
    exit 1
fi

cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
classes=.build/classes
mkdir -p -- "$classes"
busca=(src)
if [[ "$modo" == servidor ]]; then
    busca+=(-path src/test -prune -o)
fi
fontes=()
while IFS= read -r -d '' fonte; do
    fontes+=("$fonte")
done < <(find "${busca[@]}" -type f -name '*.java' -print0)

"$javac" --release 17 -encoding UTF-8 -d "$classes" "${fontes[@]}"
cp -R -- src/main/resources/. "$classes/"

case "$(uname -s)" in
    CYGWIN*|MINGW*|MSYS*) separador=';' ;;
    *) separador=: ;;
esac
exec "$java" -Dfile.encoding=UTF-8 -cp "${classes}${separador}lib/*" "$entrada"
