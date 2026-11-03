#!/bin/zsh
# Start MongoDesk with the required Java version.
export JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null)
if [[ -z "$JAVA_HOME" ]]; then
    print "MongoDesk requires JDK 21. Install JDK 21 and try again."
    read -r "?Press Return to close."
    exit 1
fi
mongodesk_root=${0:A:h:h}
cd "$mongodesk_root" || exit 1
./gradlew --console=plain run
mongodesk_status=$?
if (( mongodesk_status != 0 )); then
    print "MongoDesk could not start. See the error above."
    read -r "?Press Return to close."
fi
exit $mongodesk_status
