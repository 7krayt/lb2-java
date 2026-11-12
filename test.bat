@echo off
if not exist out mkdir out
if exist sources.txt del sources.txt
for /r src\main\java %%f in (*.java) do @echo "%%f" >> sources.txt
for /r src\test\java %%f in (*.java) do @echo "%%f" >> sources.txt
javac -source 8 -target 8 -Xlint:-options -d out @sources.txt
if errorlevel 1 exit /b 1
java -cp out minidb.DatabaseTests
