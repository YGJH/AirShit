#!/bin/bash
if [[ $1 == "push" ]] then
    git add .
    git commit -m "fuck"
    git push

elif [[ "$1" == "pull" ]] then
    git reset --hard
    git pull
elif [["$1" == "wraping"]] then
    javac -cp ".;libs/flatlaf-3.4.1.jar" -encoding UTF-8 -d out/production/MyApp *.java ui\*.java
    jar cfm MyApp.jar out/production/MyApp/AirShit/META-INF/MANIFEST.MF -C out/production/MyApp .

else 
    # 修改 javac 命令以包含 classpath
    javac -cp ".:libs/flatlaf-3.4.1.jar" -encoding UTF-8 -d . *.java ui/*.java
    # 修改 java 命令以包含 classpath
    java -cp ".:libs/flatlaf-3.4.1.jar" -Dfile.encoding=UTF-8 AirShit.Main
fi