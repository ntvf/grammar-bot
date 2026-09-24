#!/bin/bash
set -a
source /opt/grammar/env
set +a
cd /opt/grammar/extracted
exec /opt/java/current/bin/java -Xmx768m -Xms128m -XX:+UseG1GC -XX:+UseStringDeduplication -jar grammar.jar
