#!/bin/bash
if [[ $1 -eq "push" ]] then
    git add .
    git commit -m "fuck"
    git push

elif [[ "$1" -eq "pull" ]] then
    git reset --hard
    git pull
elif [[ "$1" -eq "test" ]] then
    rm -rf ../AirShitTest
    cp -r asset ../AirShitTest
    cp *.java ../AirShitTest
    javac -d *.java
    javac -d ../AirShitTest ../AirShitTest/*.java
    java AirShit.Main
else 
    javac -d . *.java
    java AirShit.Main
fi