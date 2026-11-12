#!/bin/sh
set -e
mkdir -p out
find src/main/java src/test/java -name '*.java' > sources.txt
javac -source 8 -target 8 -Xlint:-options -d out @sources.txt
java -cp out minidb.DatabaseTests
