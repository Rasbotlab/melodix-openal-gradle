@echo off
REM Build script for Melodix OpenAL Patch (Windows)

echo === Melodix OpenAL Patch Builder ===
echo.

REM Check Java
java -version >nul 2>&1
if errorlevel 1 (
    echo ERROR: Java not found. Please install Java 21+.
    exit /b 1
)

REM Step 1: Build
echo [1/4] Building OpenAL audio classes...
call gradlew.bat build
if errorlevel 1 (
    echo ERROR: Build failed.
    exit /b 1
)
echo [OK] Build successful

REM Step 2: Extract original
echo.
echo [2/4] Extracting original JAR...
if not exist "build\original-extracted" mkdir "build\original-extracted"
rd /s /q "build\original-extracted" 2>nul
mkdir "build\original-extracted"
cd "build\original-extracted"
jar xf "../../original/melodix-1.0.0.jar"
cd "../.."

REM Step 3: Replace classes
echo.
echo [3/4] Replacing audio classes...
del /f "build\original-extracted\com\musicplayer\AudioEngine.class" 2>nul
del /f "build\original-extracted\com\musicplayer\AudioEngine$PlaybackSlot.class" 2>nul
del /f "build\original-extracted\com\musicplayer\AudioEngine$DuckStage.class" 2>nul
del /f "build\original-extracted\com\musicplayer\player\FullPcmDecoder.class" 2>nul
del /f "build\original-extracted\com\musicplayer\player\FullPcmDecoder$Result.class" 2>nul
del /f "build\original-extracted\com\musicplayer\player\PcmStream.class" 2>nul

if not exist "build\new-classes" mkdir "build\new-classes"
cd "build\new-classes"
jar xf "../libs/melodix-openal-1.0.0.jar" "com/musicplayer/AudioEngine.class"
jar xf "../libs/melodix-openal-1.0.0.jar" "com/musicplayer/AudioEngine$PlaybackSlot.class"
jar xf "../libs/melodix-openal-1.0.0.jar" "com/musicplayer/AudioEngine$DuckStage.class"
jar xf "../libs/melodix-openal-1.0.0.jar" "com/musicplayer/player/FullPcmDecoder.class"
jar xf "../libs/melodix-openal-1.0.0.jar" "com/musicplayer/player/FullPcmDecoder$Result.class"
jar xf "../libs/melodix-openal-1.0.0.jar" "com/musicplayer/player/PcmStream.class"
jar xf "../libs/melodix-openal-1.0.0.jar" "com/musicplayer/player/OpenALAudioPlayer.class"
cd "../.."

xcopy /e /y /q "build\new-classes\com" "build\original-extracted\com" >nul
echo [OK] Classes replaced

REM Step 4: Repack
echo.
echo [4/4] Repacking JAR...
cd "build\original-extracted"

REM Remove old signatures
del /f "META-INF\*.SF" 2>nul
del /f "META-INF\*.RSA" 2>nul
del /f "META-INF\*.DSA" 2>nul

REM Update fabric.mod.json
echo { > fabric.mod.json
echo   "schemaVersion": 1, >> fabric.mod.json
echo   "id": "musicplayer", >> fabric.mod.json
echo   "version": "1.0.0-openal", >> fabric.mod.json
echo   "name": "Melodix Music Player (OpenAL Patch)", >> fabric.mod.json
echo   "description": "Melodix patched for Android/Pojav compatibility using OpenAL backend.", >> fabric.mod.json
echo   "authors": ["distelbus-svg (original)", "OpenAL Patch"], >> fabric.mod.json
echo   "license": "MIT", >> fabric.mod.json
echo   "environment": "client", >> fabric.mod.json
echo   "icon": "assets/musicplayer/icon.png", >> fabric.mod.json
echo   "entrypoints": { >> fabric.mod.json
echo     "client": [ >> fabric.mod.json
echo       "com.musicplayer.MusicPlayerMod" >> fabric.mod.json
echo     ] >> fabric.mod.json
echo   }, >> fabric.mod.json
echo   "mixins": [ >> fabric.mod.json
echo     "musicplayer.mixins.json" >> fabric.mod.json
echo   ], >> fabric.mod.json
echo   "depends": { >> fabric.mod.json
echo     "fabricloader": ">=0.16.0", >> fabric.mod.json
echo     "minecraft": ">=1.21.2", >> fabric.mod.json
echo     "java": ">=21", >> fabric.mod.json
echo     "fabric-api": "*" >> fabric.mod.json
echo   } >> fabric.mod.json
echo } >> fabric.mod.json

jar cvf "../../melodix-1.0.0-openal.jar" .
cd "../.."

echo.
echo === BUILD COMPLETE ===
echo.
echo Output: melodix-1.0.0-openal.jar
echo.
pause
