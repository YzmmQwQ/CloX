#!/bin/sh
# Gradle start up script for POSIX compatible shells
# Gradle wrapper 8.4 — standard template

# Attempt to set APP_HOME
# Resolve links: $0 may be a link
PRG="$0"
while [ -h "$PRG" ]; do
  ls=$(ls -ld "$PRG")
  link=$(expr "$ls" : '.*-> \(.*\)$')
  if expr "$link" : '/.*' > /dev/null; then
    PRG="$link"
  else
    PRG=$(dirname "$PRG")/"$link"
  fi
done
SAVED="$(pwd)"
cd "$(dirname "$PRG")" >/dev/null
APP_HOME="$(pwd -P)"
cd "$SAVED" >/dev/null

APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")

# Add default JVM options here
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

# Use the maximum available, or set MAX_FD != -1 to use that value.
MAX_FD=maximum

warn() {
    echo "$*"
}

die() {
    echo
    echo "$*"
    echo
    exit 1
}

# OS specific support (must be 'true' or 'false').
cygwin=false
msys=false
darwin=false
nonstop=false
case "$(uname)" in
  CYGWIN* )    cygwin=true  ;;
  Darwin* )    darwin=true  ;;
  MINGW* )     msys=true    ;;
  NONSTOP* )   nonstop=true ;;
esac

CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

# Determine the Java command to use to start the JVM.
if [ -n "$JAVA_HOME" ]; then
    if [ -x "$JAVA_HOME/jre/sh/java" ]; then
        JAVACMD="$JAVA_HOME/jre/sh/java"
    else
        JAVACMD="$JAVA_HOME/bin/java"
    fi
    if [ ! -x "$JAVACMD" ]; then
        die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME"
    fi
else
    JAVACMD=java
    which java >/dev/null 2>&1 || die "ERROR: JAVA_HOME is not set and no 'java' command found in PATH."
fi

# Increase the maximum file descriptors if we can.
if [ "$cygwin" = false ] && [ "$darwin" = false ] && [ "$nonstop" = false ]; then
    case $MAX_FD in
      max*)
        MAX_FD_LIMIT=$(ulimit -H -n) || warn "Could not query max file descriptor limit"
        case $MAX_FD_LIMIT in
          '' | soft) MAX_FD="$MAX_FD_LIMIT" ;;
          *) MAX_FD="$MAX_FD_LIMIT" ;;
        esac
    esac
    ulimit -n "$MAX_FD" 2>/dev/null || warn "Could not set maximum file descriptor limit to $MAX_FD"
fi

# Collect all arguments for the java command
eval set -- $DEFAULT_JVM_OPTS "$JAVA_OPTS" "$GRADLE_OPTS" \
  "-Dorg.gradle.appname=$APP_BASE_NAME" \
  -classpath "$CLASSPATH" \
  org.gradle.wrapper.GradleWrapperMain "$@"

exec "$JAVACMD" "$@"
