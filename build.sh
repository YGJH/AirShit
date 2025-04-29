#!/bin/bash
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
    javac -encoding UTF-8 -d . *.java
    java -Dfile.encoding=UTF-8 AirShit.Main
fi