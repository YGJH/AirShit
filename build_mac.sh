#!/bin/zsh
if [[ $1 == "push" ]] then
    git add .
    git commit -m "fuck"
    git push

elif [[ "$1" == "pull" ]] then
    git reset --hard
    git pull
elif [[ "$1" == "test" ]] then
    rm -rf ../AirShitTest
    cp -r asset ../AirShitTest
    cp *.java ../AirShitTest
    javac -d *.java
    javac -d ../AirShitTest ../AirShitTest/*.java
    java AirShit.Main
else 
    # 修改 javac 命令以包含 classpath
    javac -cp ".:jars/flatlaf-3.4.1.jar" -encoding UTF-8 -d . *.java ui/*.java
    # 修改 java 命令以包含 classpath
    java -cp ".:jars/flatlaf-3.4.1.jar" -Dfile.encoding=UTF-8 AirShit.Main
fi