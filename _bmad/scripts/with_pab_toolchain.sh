#!/bin/sh

set -eu

fail() {
  printf 'PAB toolchain error: %s\n' "$1" >&2
  exit 1
}

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
MAVEN_WRAPPER="$PROJECT_ROOT/backend/mvnw"

JAVA_HOME=${PAB_JAVA_HOME:-}
if [ -z "$JAVA_HOME" ] && [ -n "${PAB_JDK_PREFIX:-}" ]; then
  JAVA_HOME="$PAB_JDK_PREFIX/libexec/openjdk.jdk/Contents/Home"
fi
if [ -z "$JAVA_HOME" ] && command -v java >/dev/null 2>&1; then
  candidate_version=$(java -version 2>&1 | awk -F '"' 'NR == 1 { print $2 }')
  case "$candidate_version" in
    25|25.*)
      JAVA_HOME=$(java -XshowSettings:properties -version 2>&1 \
        | awk -F '= ' '/^[[:space:]]*java.home = / { print $2; exit }')
      ;;
  esac
fi
if [ -z "$JAVA_HOME" ] && [ -x /usr/libexec/java_home ]; then
  JAVA_HOME=$(/usr/libexec/java_home -v 25 2>/dev/null || true)
fi
if [ -z "$JAVA_HOME" ] && command -v brew >/dev/null 2>&1; then
  JDK_PREFIX=$(brew --prefix openjdk@25 2>/dev/null || true)
  if [ -n "$JDK_PREFIX" ]; then
    JAVA_HOME="$JDK_PREFIX/libexec/openjdk.jdk/Contents/Home"
  fi
fi
[ -x "$JAVA_HOME/bin/java" ] || fail "JDK 25 not found; set PAB_JAVA_HOME"

NODE_PREFIX=${PAB_NODE_PREFIX:-}
if [ -z "$NODE_PREFIX" ] && command -v node >/dev/null 2>&1 \
    && [ "$(node --version 2>/dev/null)" = "v24.18.0" ]; then
  NODE_PREFIX=$(CDPATH= cd -- "$(dirname -- "$(command -v node)")/.." && pwd)
fi
if [ -z "$NODE_PREFIX" ] && command -v brew >/dev/null 2>&1; then
  NODE_PREFIX=$(brew --prefix node@24 2>/dev/null || true)
fi
[ -x "$NODE_PREFIX/bin/node" ] || fail "Node 24.18.0 not found; set PAB_NODE_PREFIX"

export JAVA_HOME
PATH="$JAVA_HOME/bin:$NODE_PREFIX/bin:$PATH"
export PATH
CPPFLAGS="-I$JAVA_HOME/include${CPPFLAGS:+ $CPPFLAGS}"
export CPPFLAGS
LDFLAGS="-L$NODE_PREFIX/lib${LDFLAGS:+ $LDFLAGS}"
export LDFLAGS

java_version=$(java -version 2>&1 | awk -F '"' 'NR == 1 { print $2 }')
node_version=$(node --version)
npm_version=$(npm --version)
[ -x "$MAVEN_WRAPPER" ] || fail "project Maven Wrapper is missing or not executable"
maven_version=$(cd "$PROJECT_ROOT/backend" && ./mvnw --version | awk 'NR == 1 { print $3 }')

case "$java_version" in
  25|25.*) ;;
  *) fail "expected JDK 25, found $java_version" ;;
esac

[ "$node_version" = "v24.18.0" ] || fail "expected Node v24.18.0, found $node_version"
[ "$npm_version" = "11.16.0" ] || fail "expected npm 11.16.0, found $npm_version"
[ "$maven_version" = "3.9.16" ] || fail "expected Maven 3.9.16, found $maven_version"

if [ "$#" -eq 0 ]; then
  printf 'JAVA_HOME=%s\n' "$JAVA_HOME"
  printf 'java=%s\n' "$java_version"
  printf 'node=%s\n' "$node_version"
  printf 'npm=%s\n' "$npm_version"
  printf 'maven=%s\n' "$maven_version"
  exit 0
fi

exec "$@"
