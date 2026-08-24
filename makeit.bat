@echo off

if "%~1"=="" (
    echo Error: No filename or argument provided.
    echo Usage: %~n0 [filename]
    pause
    exit /b 1
)



set "filename=%~n1"

echo compiliing %1
javac %1

echo making jar %filename%.jar
jar --create --file %filename%.jar --main-class %filename% *.class

del *.class

echo running %filename%.jar
java -jar %filename%.jar


echo Waiting for 5 seconds...
timeout /t 5 /nobreak >nul

